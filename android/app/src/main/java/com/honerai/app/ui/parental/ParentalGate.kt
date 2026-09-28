package com.honerai.app.ui.parental

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.device.ParentalBlockKind
import com.honerai.app.device.ParentalControl
import com.honerai.app.ui.settings.BackupService
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.rememberReduceMotion

// Экран блокировки родительского контроля (порт ParentalLockScreen / parentalGate() из iOS).

/**
 * Экран блокировки родительского контроля (лимит времени, тихие часы) поверх приложения.
 * Заодно подключает учёт времени (только пока приложение на экране) и автокопию при сворачивании.
 */
@Composable
fun ParentalGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember {
        ParentalControl.init(context)
        BackupService.init(context)
        true
    }
    val snap by ParentalControl.state.collectAsState()
    val reduce = rememberReduceMotion()
    val blocked = snap.blockKind
    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = blocked != null,
            enter = if (reduce) fadeIn(tween(150)) else fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 1.04f),
            exit = fadeOut(tween(if (reduce) 150 else 350)),
        ) {
            // Держим последнюю причину, пока экран исчезает.
            var kind by remember { mutableStateOf(blocked ?: ParentalBlockKind.DAILY_LIMIT) }
            if (blocked != null) kind = blocked
            ParentalLockScreen(kind)
        }
    }
    // Назад не пропускаем к чату, пока экран блокировки на месте.
    BackHandler(enabled = blocked != null) {}
}

/** Полноэкранная заглушка поверх чата, когда закончился лимит или идут тихие часы. */
@Composable
private fun ParentalLockScreen(kind: ParentalBlockKind) {
    val settings = appSettings()
    val reduce = rememberReduceMotion()
    var askingPin by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(if (appeared || reduce) 1f else 0f, spring(0.6f, 120f), label = "lock.appear")
    val quiet = kind == ParentalBlockKind.QUIET_HOURS
    val backdrop = if (quiet) Brush.verticalGradient(listOf(Color(0xFF12173D), Color(0xFF332466)))
    else Brush.verticalGradient(listOf(Color(0xFFF2804D), Color(0xFF9E408C)))
    val reason = ParentalControl.blockReasonText(settings.isEnglish).orEmpty()

    Box(
        Modifier.fillMaxSize().background(backdrop)
            // Касания не должны доходить до чата под экраном.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .testTag("parental.lockscreen"),
        contentAlignment = Alignment.Center,
    ) {
        // Прокрутка на маленьких экранах; min-высота даёт распорным Spacer место для центрирования.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = 28.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.heightIn(min = 20.dp).weight(1f))
            Box(Modifier.size(190.dp).scale(0.6f + 0.4f * progress), contentAlignment = Alignment.Center) {
                Box(Modifier.size(190.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f * progress)))
                Box(Modifier.size(136.dp).scale(0.7f + 0.3f * progress).clip(CircleShape).background(Color.White.copy(alpha = 0.12f * progress)))
                Icon(
                    if (quiet) Icons.Filled.Bedtime else Icons.Filled.HourglassBottom, null, tint = Color.White.copy(alpha = progress),
                    modifier = Modifier.size(64.dp).rotate(-25f * (1 - progress)),
                )
            }
            Spacer(Modifier.size(22.dp))
            Text(
                if (quiet) settings.text("Время отдыхать", "Time to rest") else settings.text("На сегодня всё", "That's all for today"),
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(14.dp))
            Text(
                reason, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp).testTag("parental.lockscreen.reason"),
            )
            Spacer(Modifier.heightIn(min = 20.dp).weight(1f))
            Row(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable(role = Role.Button) { askingPin = true }
                    .padding(vertical = 15.dp).testTag("parental.lockscreen.unlock"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.LockOpen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(settings.text("Родитель: ввести PIN", "Parent: enter PIN"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        }
    }
    if (askingPin) {
        ParentalPinPromptDialog(
            settings.text("Открыть до конца дня", "Unlock for today"),
            settings.text("Введите PIN родителя.", "Enter the parent PIN."),
            Icons.Outlined.LockOpen, { askingPin = false },
        ) { ParentalControl.unlockForToday(it) }
    }
}
