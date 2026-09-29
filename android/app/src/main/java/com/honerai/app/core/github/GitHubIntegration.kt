package com.honerai.app.core.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.honerai.app.core.KeyValueStore

// github: хранение персонального токена GitHub. Сам токен лежит только здесь,
// в зашифрованном хранилище (ключ в Android Keystore), и никогда не логируется.

/**
 * Хранилище токена GitHub поверх [KeyValueStore]. Токен — единственный секрет; логин аккаунта
 * кэшируется отдельно, чтобы показывать его в настройках без обращения к сети.
 * Для тестов подаётся [com.honerai.app.core.MemoryKeyValueStore]; на Android — [SecurePrefsStore].
 */
class GitHubTokenStore(private val store: KeyValueStore) {
    var token: String?
        get() = store.getString(KEY_TOKEN)?.takeIf { it.isNotBlank() }
        set(value) = store.putString(KEY_TOKEN, value?.trim()?.takeIf { it.isNotBlank() })

    /** Логин подключённого аккаунта (GET /user), сохранённый для отображения. */
    var login: String?
        get() = store.getString(KEY_LOGIN)?.takeIf { it.isNotBlank() }
        set(value) = store.putString(KEY_LOGIN, value?.takeIf { it.isNotBlank() })

    val isConnected: Boolean get() = token != null

    /** Полное отключение: стираем токен и кэш логина. */
    fun disconnect() {
        store.putString(KEY_TOKEN, null)
        store.putString(KEY_LOGIN, null)
    }

    companion object {
        private const val KEY_TOKEN = "github.token"
        private const val KEY_LOGIN = "github.login"
    }
}

/** [KeyValueStore] поверх EncryptedSharedPreferences (как токен облака в CloudCredentials). */
class SecurePrefsStore(context: Context, private val name: String) : KeyValueStore {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy { open() }

    override fun getString(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    override fun putString(key: String, value: String?) {
        runCatching {
            val editor = prefs.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            editor.commit()
        }
    }

    /** На части телефонов Keystore сбоит после восстановления копии — тогда файл пересоздаём. */
    private fun open(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(app, name, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
        return runCatching { create() }.recoverCatching {
            app.deleteSharedPreferences(name)
            create()
        }.getOrElse { app.getSharedPreferences("$name.fallback", Context.MODE_PRIVATE) }
    }
}

/** Точка входа на Android: токен-хранилище, привязанное к зашифрованным настройкам. */
object GitHubIntegration {
    private const val SECURE = "honer.github.secure"

    @Volatile private var cached: GitHubTokenStore? = null

    fun tokenStore(context: Context): GitHubTokenStore = cached ?: synchronized(this) {
        cached ?: GitHubTokenStore(SecurePrefsStore(context, SECURE)).also { cached = it }
    }
}
