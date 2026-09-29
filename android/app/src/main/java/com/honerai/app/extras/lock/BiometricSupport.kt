package com.honerai.app.extras.lock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Какой биометрией разблокировать — от этого зависит подпись в настройках. */
enum class BiometricKind { FINGERPRINT, FACE, GENERIC }

/** Что умеет телефон: ответы BiometricManager и аппаратные датчики. */
data class BiometricCapability(
    val strongStatus: Int,
    val weakStatus: Int,
    val hasFingerprint: Boolean,
    val hasFace: Boolean,
    val hasIris: Boolean = false,
)

/**
 * Итог выбора: [available] — можно включить; [needsEnrollment] — датчик есть,
 * но в системе ничего не настроено (показываем подсказку); [authenticators] — для BiometricPrompt.
 */
data class BiometricOption(
    val available: Boolean,
    val needsEnrollment: Boolean,
    val authenticators: Int,
    val kind: BiometricKind,
)

object BiometricChoice {
    /** Чистая функция: по возможностям телефона — предлагать ли биометрию и как её назвать. */
    fun select(capability: BiometricCapability): BiometricOption {
        val kind = kind(capability)
        return when {
            capability.strongStatus == BiometricManager.BIOMETRIC_SUCCESS -> BiometricOption(true, false, BIOMETRIC_STRONG, kind)
            capability.weakStatus == BiometricManager.BIOMETRIC_SUCCESS -> BiometricOption(true, false, BIOMETRIC_WEAK, kind)
            capability.strongStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
                capability.weakStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricOption(false, true, BIOMETRIC_WEAK, kind)
            else -> BiometricOption(false, false, 0, kind)
        }
    }

    /** Один датчик — называем его; несколько или неизвестно — «Биометрия». */
    fun kind(capability: BiometricCapability): BiometricKind {
        val sensors = listOf(capability.hasFingerprint, capability.hasFace, capability.hasIris).count { it }
        return when {
            sensors != 1 -> BiometricKind.GENERIC
            capability.hasFingerprint -> BiometricKind.FINGERPRINT
            capability.hasFace -> BiometricKind.FACE
            else -> BiometricKind.GENERIC
        }
    }

    fun label(kind: BiometricKind, english: Boolean): String = when (kind) {
        BiometricKind.FINGERPRINT -> if (english) "Fingerprint" else "Отпечаток пальца"
        BiometricKind.FACE -> if (english) "Face unlock" else "Распознавание лица"
        BiometricKind.GENERIC -> if (english) "Biometrics" else "Биометрия"
    }
}

/** Опрос телефона и показ системного окна биометрии. */
object BiometricSupport {
    fun capability(context: Context): BiometricCapability {
        val manager = BiometricManager.from(context)
        val pm = context.packageManager
        fun feature(name: String) = runCatching { pm.hasSystemFeature(name) }.getOrDefault(false)
        return BiometricCapability(
            strongStatus = runCatching { manager.canAuthenticate(BIOMETRIC_STRONG) }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
            weakStatus = runCatching { manager.canAuthenticate(BIOMETRIC_WEAK) }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
            hasFingerprint = feature(PackageManager.FEATURE_FINGERPRINT),
            hasFace = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && feature(PackageManager.FEATURE_FACE),
            hasIris = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && feature(PackageManager.FEATURE_IRIS),
        )
    }

    fun option(context: Context): BiometricOption = BiometricChoice.select(capability(context))

    /** Системные настройки, где добавляют отпечаток или лицо. */
    @Suppress("DEPRECATION")
    fun enrollIntent(): Intent = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_WEAK)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Intent(Settings.ACTION_FINGERPRINT_ENROLL)
        else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Системное окно биометрии. [onResult]: true — узнали; false — отмена, «Ввести PIN» или ошибка.
     * Неудачная попытка (чужой палец) окно не закрывает — система сама даёт повторить.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negative: String,
        authenticators: Int,
        onResult: (Boolean) -> Unit,
    ): BiometricPrompt? = runCatching {
        var delivered = false
        fun deliver(value: Boolean) { if (!delivered) { delivered = true; onResult(value) } }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = deliver(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = deliver(false)
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (!subtitle.isNullOrEmpty()) setSubtitle(subtitle) }
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(if (authenticators == 0) BIOMETRIC_WEAK else authenticators)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
        prompt
    }.onFailure { onResult(false) }.getOrNull()
}
