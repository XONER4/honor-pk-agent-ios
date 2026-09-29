package com.honerai.admin.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.honerai.admin.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Вошедший администратор. */
data class AdminSession(val token: String, val email: String, val name: String)

/**
 * Токен администратора и адрес сервера. Токен — в EncryptedSharedPreferences (ключ в Android Keystore).
 * Если Keystore на телефоне сломан, файл пересоздаётся; в крайнем случае токен живёт только в памяти.
 */
class SessionStore(context: Context) {
    private val app = context.applicationContext
    private val secure: SharedPreferences? = openSecure()
    private val plain: SharedPreferences = app.getSharedPreferences("admin.settings", Context.MODE_PRIVATE)
    private var memoryToken: AdminSession? = null

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<AdminSession?> get() = _session

    private val _serverUrl = MutableStateFlow(normalizeUrl(plain.getString(KEY_URL, null) ?: BuildConfig.HONER_CLOUD_URL))
    val serverUrl: StateFlow<String> get() = _serverUrl

    /** Причина последнего выхода (истёк токен) — показывается на экране входа. */
    private val _logoutReason = MutableStateFlow<String?>(null)
    val logoutReason: StateFlow<String?> get() = _logoutReason

    val buildServerUrl: String get() = normalizeUrl(BuildConfig.HONER_CLOUD_URL)

    fun setServerUrl(url: String) {
        val clean = normalizeUrl(url)
        plain.edit().putString(KEY_URL, clean).apply()
        _serverUrl.value = clean
    }

    fun save(session: AdminSession) {
        val prefs = secure
        if (prefs != null) {
            prefs.edit().putString(KEY_TOKEN, session.token).putString(KEY_EMAIL, session.email)
                .putString(KEY_NAME, session.name).apply()
        } else {
            memoryToken = session
        }
        _logoutReason.value = null
        _session.value = session
    }

    fun logout(reason: String? = null) {
        secure?.edit()?.clear()?.apply()
        memoryToken = null
        _logoutReason.value = reason
        _session.value = null
    }

    private fun readSession(): AdminSession? {
        val prefs = secure ?: return memoryToken
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return AdminSession(token, prefs.getString(KEY_EMAIL, "").orEmpty(), prefs.getString(KEY_NAME, "").orEmpty())
    }

    private fun openSecure(): SharedPreferences? {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                app, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (first: Exception) {
            Log.w(TAG, "encrypted prefs broken, recreating", first)
            runCatching {
                app.deleteSharedPreferences(FILE)
                File(app.filesDir.parentFile, "shared_prefs/$FILE.xml").delete()
                create()
            }.onFailure { Log.w(TAG, "encrypted prefs unavailable, token kept in memory", it) }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "SessionStore"
        private const val FILE = "admin.session.secure"
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "name"
        private const val KEY_URL = "serverUrl"

        /** «honer.up.railway.app/» → «https://honer.up.railway.app». */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            if (url.endsWith("/v1")) url = url.removeSuffix("/v1")
            return url
        }
    }
}

/** Настройки интерфейса: язык (русский по умолчанию). */
class AdminSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("admin.settings", Context.MODE_PRIVATE)
    private val _english = MutableStateFlow(prefs.getBoolean("english", false))
    val english: StateFlow<Boolean> get() = _english

    fun setEnglish(value: Boolean) {
        prefs.edit().putBoolean("english", value).apply()
        _english.value = value
    }

    fun text(ru: String, en: String): String = if (_english.value) en else ru
}
