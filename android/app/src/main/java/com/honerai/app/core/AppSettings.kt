package com.honerai.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class AppAppearance(val raw: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }
enum class AppLanguage(val raw: String) { RUSSIAN("ru"), ENGLISH("en") }

/**
 * Настройки приложения. Ключи те же, что на iPhone (honor.*), чтобы снимок настроек
 * из резервной копии применялся одинаково на обеих платформах.
 * Каждое поле — StateFlow: экраны Compose подписываются через collectAsState().
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("honer.settings", Context.MODE_PRIVATE)

    private fun string(key: String, default: String) = MutableStateFlow(prefs.getString(key, default) ?: default)
    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))
    private fun double(key: String, default: Double) =
        MutableStateFlow(java.lang.Double.longBitsToDouble(prefs.getLong(key, java.lang.Double.doubleToLongBits(default))))
    private fun int(key: String, default: Int) = MutableStateFlow(prefs.getInt(key, default))

    private val _appearance = string("honor.appearance", "dark")
    private val _language = string("honor.language", "ru")
    private val _fontScale = double("honor.fontScale", 1.0)
    private val _voiceIdentifier = string("honor.voiceIdentifier", "")
    private val _speechLanguage = string("honor.speechLanguage", "ru-RU")
    private val _voiceGender = string("honor.voiceGender", "male")
    private val _autoRead = bool("honor.autoRead", false)
    private val _displayName = string("honor.displayName", "")
    private val _completedOnboarding = bool("honer.onboarding.completed", false)
    private val _voiceRate = double("honor.voiceRate", 0.95)
    private val _autoDeleteDays = int("honor.autoDeleteDays", 0)
    private val _notificationsEnabled = bool("honor.notificationsEnabled", true)
    private val _crossChatMemoryEnabled = bool("honor.crossChatMemoryEnabled", true)
    private val _stickersEnabled = bool("honor.stickersEnabled", true)
    private val _profilePhotoPath = string("honor.profilePhotoPath", "")
    private val _birthday = string("honer.birthday", "")
    private val _autoUpdate = bool("honer.autoUpdate", true)
    private val _reduceMotion = bool("honer.reduceMotion", false)
    // agent: главный переключатель функции «Действия в приложениях» (по умолчанию выключен).
    private val _agentEnabled = bool("honor.agentEnabled", false)
    // extras: принятие лицензионного соглашения (время в мс и версия текста).
    private val _licenseAcceptedAt = MutableStateFlow(prefs.getLong("honor.licenseAcceptedAt", 0L))
    private val _licenseVersion = int("honor.licenseVersion", 0)

    val appearance: StateFlow<String> = _appearance.asStateFlow()
    val language: StateFlow<String> = _language.asStateFlow()
    val fontScale: StateFlow<Double> = _fontScale.asStateFlow()
    val voiceIdentifier: StateFlow<String> = _voiceIdentifier.asStateFlow()
    val speechLanguage: StateFlow<String> = _speechLanguage.asStateFlow()
    val voiceGender: StateFlow<String> = _voiceGender.asStateFlow()
    val autoRead: StateFlow<Boolean> = _autoRead.asStateFlow()
    val displayName: StateFlow<String> = _displayName.asStateFlow()
    val completedOnboarding: StateFlow<Boolean> = _completedOnboarding.asStateFlow()
    val voiceRate: StateFlow<Double> = _voiceRate.asStateFlow()
    val autoDeleteDays: StateFlow<Int> = _autoDeleteDays.asStateFlow()
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()
    val crossChatMemoryEnabled: StateFlow<Boolean> = _crossChatMemoryEnabled.asStateFlow()
    val stickersEnabled: StateFlow<Boolean> = _stickersEnabled.asStateFlow()
    val profilePhotoPath: StateFlow<String> = _profilePhotoPath.asStateFlow()
    val birthday: StateFlow<String> = _birthday.asStateFlow()
    /** Автообновление приложения в фоне. */
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()
    /** Меньше анимаций — для слабых телефонов (включается само на устройствах с малой памятью). */
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()
    // agent: включена ли функция «Действия в приложениях (агент)».
    val agentEnabled: StateFlow<Boolean> = _agentEnabled.asStateFlow()
    // extras: когда (epoch ms, 0 — ещё нет) и какую версию соглашения принял пользователь — для отправки на сервер.
    val licenseAcceptedAt: StateFlow<Long> = _licenseAcceptedAt.asStateFlow()
    val licenseVersion: StateFlow<Int> = _licenseVersion.asStateFlow()

    /** Когда создан аккаунт (первый запуск). */
    val accountCreatedAt: Instant

    init {
        val key = "honer.accountCreatedAt"
        val saved = prefs.getLong(key, 0L)
        accountCreatedAt = if (saved > 0) Instant.ofEpochMilli(saved) else Instant.now().also {
            prefs.edit().putLong(key, it.toEpochMilli()).apply()
        }
        // agent: зеркалим переключатель в статический флаг для системной инструкции и проверки доступа.
        com.honerai.app.core.agent.AgentAvailability.set(_agentEnabled.value)
    }

    val isEnglish: Boolean get() = _language.value == "en"

    /** Строка на языке приложения. */
    fun text(russian: String, english: String): String = if (isEnglish) english else russian

    fun setAppearance(value: String) = put("honor.appearance", value, _appearance)
    fun setLanguage(value: String) = put("honor.language", if (value == "en") "en" else "ru", _language)
    fun setFontScale(value: Double) = putDouble("honor.fontScale", value.coerceIn(0.85, 1.4), _fontScale)
    fun setVoiceIdentifier(value: String) = put("honor.voiceIdentifier", value, _voiceIdentifier)
    fun setSpeechLanguage(value: String) = put("honor.speechLanguage", value, _speechLanguage)
    fun setVoiceGender(value: String) = put("honor.voiceGender", if (value == "female") "female" else "male", _voiceGender)
    fun setAutoRead(value: Boolean) = putBool("honor.autoRead", value, _autoRead)
    fun setDisplayName(value: String) = put("honor.displayName", value.take(60), _displayName)
    fun setCompletedOnboarding(value: Boolean) = putBool("honer.onboarding.completed", value, _completedOnboarding)
    fun setVoiceRate(value: Double) = putDouble("honor.voiceRate", value.coerceIn(0.4, 1.8), _voiceRate)
    fun setAutoDeleteDays(value: Int) { prefs.edit().putInt("honor.autoDeleteDays", value).apply(); _autoDeleteDays.value = value }
    fun setNotificationsEnabled(value: Boolean) = putBool("honor.notificationsEnabled", value, _notificationsEnabled)
    fun setCrossChatMemoryEnabled(value: Boolean) = putBool("honor.crossChatMemoryEnabled", value, _crossChatMemoryEnabled)
    fun setStickersEnabled(value: Boolean) = putBool("honor.stickersEnabled", value, _stickersEnabled)
    fun setProfilePhotoPath(value: String) = put("honor.profilePhotoPath", value, _profilePhotoPath)
    fun setBirthday(value: String) = put("honer.birthday", value, _birthday)
    fun setAutoUpdate(value: Boolean) = putBool("honer.autoUpdate", value, _autoUpdate)
    fun setReduceMotion(value: Boolean) = putBool("honer.reduceMotion", value, _reduceMotion)
    fun setAgentEnabled(value: Boolean) { // agent
        putBool("honor.agentEnabled", value, _agentEnabled)
        com.honerai.app.core.agent.AgentAvailability.set(value)
    }
    // extras: пользователь принял соглашение версии [version].
    fun setLicenseAccepted(version: Int, acceptedAt: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("honor.licenseAcceptedAt", acceptedAt).putInt("honor.licenseVersion", version).apply()
        _licenseAcceptedAt.value = acceptedAt
        _licenseVersion.value = version
    }

    /** Снимок настроек для резервной копии (ключи как на iOS). */
    fun exportSnapshot(): Map<String, String> = mapOf(
        "appearance" to _appearance.value, "language" to _language.value,
        "fontScale" to _fontScale.value.toString(), "voiceIdentifier" to _voiceIdentifier.value,
        "speechLanguage" to _speechLanguage.value, "voiceGender" to _voiceGender.value,
        "autoRead" to _autoRead.value.toString(), "displayName" to _displayName.value,
        "voiceRate" to _voiceRate.value.toString(), "autoDeleteDays" to _autoDeleteDays.value.toString(),
        "notificationsEnabled" to _notificationsEnabled.value.toString(),
        "crossChatMemoryEnabled" to _crossChatMemoryEnabled.value.toString(),
        "stickersEnabled" to _stickersEnabled.value.toString(), "birthday" to _birthday.value,
    )

    fun applySnapshot(values: Map<String, String>) {
        values["appearance"]?.let { if (it in setOf("system", "light", "dark")) setAppearance(it) }
        values["language"]?.let { setLanguage(it) }
        values["fontScale"]?.toDoubleOrNull()?.let { setFontScale(it) }
        values["voiceIdentifier"]?.let { setVoiceIdentifier(it) }
        values["speechLanguage"]?.takeIf { it.isNotEmpty() }?.let { setSpeechLanguage(it) }
        values["voiceGender"]?.let { setVoiceGender(it) }
        values["autoRead"]?.toBooleanStrictOrNull()?.let { setAutoRead(it) }
        values["displayName"]?.takeIf { it.isNotEmpty() }?.let { setDisplayName(it) }
        values["voiceRate"]?.toDoubleOrNull()?.let { setVoiceRate(it) }
        values["autoDeleteDays"]?.toIntOrNull()?.let { setAutoDeleteDays(it) }
        values["notificationsEnabled"]?.toBooleanStrictOrNull()?.let { setNotificationsEnabled(it) }
        values["crossChatMemoryEnabled"]?.toBooleanStrictOrNull()?.let { setCrossChatMemoryEnabled(it) }
        values["stickersEnabled"]?.toBooleanStrictOrNull()?.let { setStickersEnabled(it) }
        values["birthday"]?.takeIf { it.isNotEmpty() }?.let { setBirthday(it) }
    }

    private fun put(key: String, value: String, flow: MutableStateFlow<String>) {
        prefs.edit().putString(key, value).apply(); flow.value = value
    }
    private fun putBool(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, value).apply(); flow.value = value
    }
    private fun putDouble(key: String, value: Double, flow: MutableStateFlow<Double>) {
        prefs.edit().putLong(key, java.lang.Double.doubleToLongBits(value)).apply(); flow.value = value
    }
}
