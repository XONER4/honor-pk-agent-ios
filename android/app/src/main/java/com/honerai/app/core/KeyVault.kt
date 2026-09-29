package com.honerai.app.core

import android.util.Base64
import com.honerai.app.BuildConfig

/**
 * Ключ DeepSeek из сборки: в APK он хранится только замаскированным (XOR со случайной маской),
 * расшифровывается в памяти при первом обращении.
 */
object KeyVault {
    val deepSeekKey: String by lazy { unmask(BuildConfig.DEEPSEEK_KEY_A, BuildConfig.DEEPSEEK_KEY_B) }

    internal fun unmask(masked: String, mask: String): String {
        if (masked.isEmpty() || mask.isEmpty()) return ""
        val a = Base64.decode(masked, Base64.NO_WRAP)
        val b = Base64.decode(mask, Base64.NO_WRAP)
        if (a.size != b.size) return ""
        return String(ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }, Charsets.UTF_8).trim()
    }
}
