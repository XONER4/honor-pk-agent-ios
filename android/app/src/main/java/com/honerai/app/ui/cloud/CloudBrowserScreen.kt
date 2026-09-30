package com.honerai.app.ui.cloud

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.cloud.CloudBrowserStore
import com.honerai.app.cloud.CloudShot
import com.honerai.app.cloud.CloudServices
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.launch

/**
 * Окно облачного браузера в приложении. Пользователь ВХОДИТ в свой аккаунт сам: видит кадр сеанса
 * с сервера, тапает по нему и печатает — это уходит в тот же облачный сеанс. После входа сеанс
 * сохраняется, и нейросеть может действовать в нём (пользователь остаётся в чате).
 */
@Composable
fun CloudBrowserScreen(store: CloudBrowserStore, service: String, onClose: () -> Unit) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val frame by store.frame.collectAsState()
    val busy by store.busy.collectAsState()
    val error by store.error.collectAsState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(service) { if (store.sessionId == null || store.service != service) store.open(service) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть", tint = colors.foreground) }
            Column(Modifier.weight(1f)) {
                Text(CloudServices.label(service), color = colors.foreground, fontSize = 16.sp)
                Text(
                    if (frame?.loggedInHint == true) "Вход выполнен — можно попросить нейросеть" else "Войдите в свой аккаунт",
                    color = colors.secondary, fontSize = 12.sp,
                )
            }
            IconButton(onClick = { scope.launch { store.back() } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = colors.foreground) }
            IconButton(onClick = { scope.launch { if (store.sessionId == null) store.open(service) else store.refresh() } }) { Icon(Icons.Default.Refresh, "Обновить", tint = colors.foreground) }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val f = frame
            val bitmap = remember(f?.image) { decodeFrame(f) }
            if (bitmap != null && f != null) {
                var boxW by remember { mutableStateOf(1) }
                var boxH by remember { mutableStateOf(1) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Экран облачного сеанса",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .onSizeChanged { boxW = it.width.coerceAtLeast(1); boxH = it.height.coerceAtLeast(1) }
                        .pointerInput(f.sessionId) {
                            detectTapGestures { pos ->
                                // Картинка вписана (Fit): учитываем масштаб и поля-«письмо», чтобы тап попадал точно.
                                val scale = minOf(boxW.toFloat() / f.width, boxH.toFloat() / f.height)
                                val offX = (boxW - f.width * scale) / 2f
                                val offY = (boxH - f.height * scale) / 2f
                                val cx = ((pos.x - offX) / scale).toInt()
                                val cy = ((pos.y - offY) / scale).toInt()
                                if (cx in 0..f.width && cy in 0..f.height) scope.launch { store.tap(cx, cy) }
                            }
                        },
                )
            }
            if (busy) CircularProgressIndicator(color = colors.accent)
        }

        error?.let { Text(it, color = HonerTheme.colors.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Введите текст (логин, запрос…) и Enter") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val t = input; input = ""
                    scope.launch { if (t.isNotEmpty()) store.type(t); store.pressEnter() }
                }),
            )
        }
    }
}

private fun decodeFrame(frame: CloudShot?): android.graphics.Bitmap? {
    val data = frame?.image ?: return null
    val base64 = data.substringAfter("base64,", "").ifEmpty { return null }
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

// Оверлей: показывает окно облачного входа поверх всего приложения по запросу CloudManager.
@Composable
fun CloudBrowserOverlay() {
    if (!com.honerai.app.cloud.CloudConfig.isConfigured) return
    val service by com.honerai.app.cloud.CloudManager.cloudLoginRequest.collectAsState()
    val svc = service ?: return
    val store = com.honerai.app.cloud.CloudManager.cloudBrowser
    androidx.activity.compose.BackHandler { com.honerai.app.cloud.CloudManager.cloudLoginRequest.value = null }
    Box(
        Modifier.fillMaxSize()
            .background(HonerTheme.colors.background)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
    ) {
        CloudBrowserScreen(store, svc) { com.honerai.app.cloud.CloudManager.cloudLoginRequest.value = null }
    }
}

// Баннер связи вверху: показывает причину недоступности сервера (например, МТС без VPN) и что делать.
@Composable
fun androidx.compose.foundation.layout.BoxScope.ConnectionBanner() {
    if (!com.honerai.app.cloud.CloudConfig.isConfigured) return
    val diag by com.honerai.app.cloud.CloudManager.connDiagnostic.collectAsState()
    val d = diag ?: return
    val colors = HonerTheme.colors
    Column(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF2A1B1B))
            .padding(14.dp),
    ) {
        // Баннер всегда на тёмной подложке (0xFF2A1B1B) — текст задаём светлым явно, чтобы он читался
        // и в светлой теме приложения (иначе тёмный текст темы сливался бы с тёмной подложкой).
        Text(d.title, color = androidx.compose.ui.graphics.Color(0xFFFF9F0A), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(d.detail, color = androidx.compose.ui.graphics.Color(0xFFECECEF), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        d.fixes.forEach { fix ->
            Text("• $fix", color = androidx.compose.ui.graphics.Color(0xFFB9B9C0), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Text(
            "Повторить",
            color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp)
                .clip(RoundedCornerShape(20.dp)).background(colors.accent)
                .clickable { com.honerai.app.cloud.CloudManager.retryConnection() }.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

