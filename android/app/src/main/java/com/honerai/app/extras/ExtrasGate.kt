package com.honerai.app.extras

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import com.honerai.app.R
import com.honerai.app.core.AppSettings
import com.honerai.app.extras.device.DeviceHost
import com.honerai.app.extras.device.DeviceToolsHostEffect
import com.honerai.app.extras.license.LicenseAcceptanceScreen
import com.honerai.app.extras.license.LicenseAgreement
import com.honerai.app.extras.lock.AppLock
import com.honerai.app.extras.lock.AppLockScreen
import com.honerai.app.ui.theme.HonerTheme

/**
 * Обёртка корня интерфейса (дополнения): сначала лицензионное соглашение, затем
 * приложение под экраном блокировки; здесь же хост инструментов устройства.
 */
@Composable
fun ExtrasGate(activity: FragmentActivity, settings: AppSettings, content: @Composable () -> Unit) {
    remember { AppLock.init(activity); DeviceHost.init(activity); true }
    val acceptedAt by settings.licenseAcceptedAt.collectAsState()
    val acceptedVersion by settings.licenseVersion.collectAsState()
    val locked by AppLock.locked.collectAsState()
    val hideInRecents by AppLock.hideInRecents.collectAsState()
    val lockMode by AppLock.mode.collectAsState()
    val language by settings.language.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // «Скрывать в недавних»: Android 13+ — только снимок в списке недавних; раньше — FLAG_SECURE.
    val hide = hideInRecents && lockMode != com.honerai.app.extras.lock.LockMode.OFF
    LaunchedEffect(hide) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // Под замком клавиатура и фокус ввода закрываются — набранное не видно.
    LaunchedEffect(locked) {
        if (locked) { focus.clearFocus(force = true); keyboard?.hide() }
    }

    DeviceToolsHostEffect(activity)

    if (LicenseAgreement.needsAcceptance(acceptedVersion, acceptedAt)) {
        LicenseAcceptanceScreen(settings, onExit = { activity.finish() })
        return
    }
    Box(Modifier.fillMaxSize()) {
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
        if (locked) {
            // Заслонка в основном окне: закрывает чат с первого кадра после возвращения.
            Box(
                Modifier.fillMaxSize().background(HonerTheme.colors.background)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.honer_logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
            }
            // Сам экран блокировки — отдельным окном поверх всего, в том числе поверх открытых
            // диалогов приложения (просмотр фото, подтверждения), которые лежат выше основного окна.
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false, dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                ),
            ) {
                AppLockScreen(activity, english = language == "en")
            }
        }
    }
}
