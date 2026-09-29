package com.honerai.admin.ui.chat

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.AttachmentRef
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.tr

/** Фото на весь экран: щипок — масштаб, двойное касание — приблизить/вернуть. */
@Composable
fun ImageViewerDialog(container: AdminContainer, ref: AttachmentRef, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = ref.resolvedUrl(container),
                imageLoader = container.imageLoader,
                contentDescription = ref.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
            CloseButton(onClose, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Видео на весь экран — плеер Media3 с токеном администратора. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(container: AdminContainer, ref: AttachmentRef, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        buildAuthorizedPlayer(context, container).apply {
            setMediaItem(MediaItem.fromUri(ref.resolvedUrl(container)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        container.audio.pause()
        onDispose { player.release() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            )
            CloseButton(onClose, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier) {
    Box(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).size(44.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Close, tr("Закрыть", "Close"), tint = Color.White) }
}

/**
 * Голосовое / аудио в пузыре: кнопка, «волна» с прогрессом (касание — перемотка) и время.
 * Волна детерминирована по id — одинакова при каждом показе и не требует разбора файла.
 */
@Composable
fun VoicePlayer(container: AdminContainer, ref: AttachmentRef, tint: Color, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    val playback by container.audio.state.collectAsStateWithLifecycle()
    val active = playback.id == ref.id
    val duration = if (active && playback.durationMs > 0) playback.durationMs else (ref.durationMs ?: 0)
    val progress = if (active && duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bars = remember(ref.id) {
        val rnd = java.util.Random(ref.id.hashCode().toLong())
        FloatArray(32) { 0.25f + rnd.nextFloat() * 0.75f }
    }
    Column(modifier.width(236.dp)) {
        if (ref.kind == AttachmentKinds.AUDIO && ref.name.isNotBlank()) {
            Text(ref.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.foreground, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(tint)
                    .clickable { container.audio.toggle(ref.id, ref.resolvedUrl(container), ref.durationMs) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (active && playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (active && playback.playing) tr("Пауза", "Pause") else tr("Слушать", "Play"),
                    tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier.fillMaxWidth().height(26.dp).pointerInput(ref.id) {
                        detectTapGestures { pos ->
                            val fraction = pos.x / size.width
                            if (!active) container.audio.toggle(ref.id, ref.resolvedUrl(container), ref.durationMs)
                            container.audio.seek(ref.id, fraction)
                        }
                    },
                ) {
                    val gap = 2.dp.toPx()
                    val w = (size.width - gap * (bars.size - 1)) / bars.size
                    bars.forEachIndexed { i, v ->
                        val h = size.height * v
                        val x = i * (w + gap)
                        val played = (i + 0.5f) / bars.size <= progress
                        drawRoundRect(
                            color = if (played) tint else colors.secondary.copy(alpha = 0.45f),
                            topLeft = Offset(x, (size.height - h) / 2),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(w / 2, w / 2),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (active && (playback.playing || playback.positionMs > 0)) PresenceText.mmss(playback.positionMs)
                        else PresenceText.mmss(duration),
                        fontSize = 12.sp, color = colors.secondary,
                    )
                    if (ref.kind == AttachmentKinds.VOICE) {
                        Text(tr("голосовое", "voice"), fontSize = 12.sp, color = colors.secondary)
                    }
                }
            }
        }
    }
}
