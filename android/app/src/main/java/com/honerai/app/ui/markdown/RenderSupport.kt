package com.honerai.app.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.honerai.app.AppContainer

/** Язык интерфейса как состояние Compose: смена языка сразу перерисовывает карточки. */
@Composable
fun rememberEnglish(): Boolean {
    val context = LocalContext.current
    val settings = remember(context) { AppContainer.get(context).settings }
    val language by settings.language.collectAsState()
    return language == "en"
}

/** Строка на языке приложения (то же, что settings.text(ru, en), но от состояния Compose). */
fun tr(english: Boolean, russian: String, englishText: String): String = if (english) englishText else russian

internal object RenderActions {
    fun copy(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("Honer AI", text))
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Нет приложения для ссылки — просто ничего не делаем.
        }
    }

    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    enum class Haptic { LIGHT, SUCCESS, ERROR, WARNING }

    fun haptic(view: View, kind: Haptic) {
        val constant = when (kind) {
            Haptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            Haptic.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.ERROR -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Haptic.WARNING -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}
