package com.honerai.app.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Данные устройства в облаке. Токен — в зашифрованном хранилище (ключ в Android Keystore);
 * остальное (installId, отметки прочтения, блокировка) — в обычных настройках приложения.
 */
class CloudCredentials(context: Context) {
    private val app = context.applicationContext
    private val plain: SharedPreferences = app.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy { openSecure() }

    /**
     * UUID установки: создаётся один раз; повторная регистрация с ним — то же устройство.
     * Настройки попадают в резервную копию, поэтому UUID привязан к ANDROID_ID: восстановленная
     * на другом телефоне копия не выдаёт себя за старое устройство, а регистрируется заново.
     */
    val installId: String
        get() = synchronized(this) {
            val saved = plain.getString(KEY_INSTALL, null)
            val owner = plain.getString(KEY_INSTALL_OWNER, null)
            val current = androidId()
            if (saved != null && (owner == null || owner == current)) {
                if (owner == null) plain.edit().putString(KEY_INSTALL_OWNER, current).apply()
                return saved
            }
            // Новая установка или копия с другого телефона: новый UUID и новая сессия.
            val fresh = UUID.randomUUID().toString()
            plain.edit().clear().putString(KEY_INSTALL, fresh).putString(KEY_INSTALL_OWNER, current).apply()
            runCatching { secure.edit().clear().commit() }
            fresh
        }

    private fun androidId(): String = runCatching {
        android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()

    var token: String?
        get() = runCatching { secure.getString(KEY_TOKEN, null) }.getOrNull()?.takeIf { it.isNotBlank() }
        set(value) { runCatching { secure.edit().putString(KEY_TOKEN, value).commit() } }

    var deviceId: String?
        get() = plain.getString("deviceId", null)
        set(value) = plain.edit().putString("deviceId", value).apply()
    var userId: String?
        get() = plain.getString("userId", null)
        set(value) = plain.edit().putString("userId", value).apply()
    var adminChatId: String?
        get() = plain.getString("adminChatId", null)
        set(value) = plain.edit().putString("adminChatId", value).apply()

    /** Блокировка: null — нет; строка (может быть пустой) — причина. Хранится, чтобы экран был и без сети. */
    var blockReason: String?
        get() = if (plain.getBoolean("blocked", false)) plain.getString("blockReason", "").orEmpty() else null
        set(value) = plain.edit().putBoolean("blocked", value != null).putString("blockReason", value.orEmpty()).apply()

    /** Время последнего прочитанного сообщения администратора (мс). */
    var lastReadMs: Long
        get() = plain.getLong("lastReadMs", 0L)
        set(value) = plain.edit().putLong("lastReadMs", value).apply()
    /** Время последнего сообщения, о котором уже было уведомление (мс). */
    var lastNotifiedMs: Long
        get() = plain.getLong("lastNotifiedMs", 0L)
        set(value) = plain.edit().putLong("lastNotifiedMs", value).apply()
    /** Показано ли разовое уведомление «Вам написал администратор Honer AI». */
    var firstContactShown: Boolean
        get() = plain.getBoolean("firstContactShown", false)
        set(value) = plain.edit().putBoolean("firstContactShown", value).apply()
    /** Прочитана ли плашка «Это официальный чат…». */
    var infoBannerDismissed: Boolean
        get() = plain.getBoolean("infoBannerDismissed", false)
        set(value) = plain.edit().putBoolean("infoBannerDismissed", value).apply()
    /** Отметка ?after= для /v1/notifications. */
    var notificationsAfter: String?
        get() = plain.getString("notificationsAfter", null)
        set(value) = plain.edit().putString("notificationsAfter", value).apply()
    var pushToken: String?
        get() = plain.getString("pushToken", null)
        set(value) = plain.edit().putString("pushToken", value).apply()
    /** Отправленный на сервер отпечаток профиля — чтобы не слать PATCH без изменений. */
    var profileFingerprint: String?
        get() = plain.getString("profileFingerprint", null)
        set(value) = plain.edit().putString("profileFingerprint", value).apply()

    fun clearSession() {
        token = null
        plain.edit().remove("profileFingerprint").apply()
    }

    /**
     * Зашифрованные настройки. На части телефонов Keystore сбоит (после восстановления из копии) —
     * тогда файл пересоздаётся; если и это не помогло — обычные настройки приложения (лучше, чем без облака).
     */
    private fun openSecure(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(app, SECURE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
        return runCatching { create() }.recoverCatching {
            app.deleteSharedPreferences(SECURE)
            create()
        }.getOrElse { app.getSharedPreferences("$SECURE.fallback", Context.MODE_PRIVATE) }
    }

    companion object {
        private const val PLAIN = "honer.cloud"
        private const val SECURE = "honer.cloud.secure"
        private const val KEY_INSTALL = "installId"
        private const val KEY_INSTALL_OWNER = "installOwner"
        private const val KEY_TOKEN = "deviceToken"
    }
}
