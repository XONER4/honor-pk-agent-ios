package com.honerai.app.ui.markdown

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.honerai.app.core.CaptionLanguage
import com.honerai.app.core.CaptionSelection
import com.honerai.app.core.CaptionTranslator
import com.honerai.app.core.MediaKinds
import com.honerai.app.device.DownloadState
import com.honerai.app.device.MediaDownloads
import com.honerai.app.device.MediaKind
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay

// media: карточки медиа в ответе — картинка, видео, аудио с кнопками «Скачать» и «Поделиться»,
// подпись на языке интерфейса.

/** Медиа из строки `![подпись](ссылка)`: вид определяется по ссылке. */
@Composable
internal fun WebMediaCard(url: String, caption: String, fontSize: Float, english: Boolean) {
    val shown = rememberLocalizedCaption(caption, url, english)
    val audio = remember(url) { MediaKinds.isAudio(url) }
    val video = remember(url) { !audio && VideoLinks.isVideo(url) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            audio -> AudioCard(url, shown, fontSize, english)
            video -> VideoCard(url, shown, fontSize, english)
            else -> RemoteImage(url, shown, fontSize, english)
        }
        val kind = when {
            audio -> MediaKind.AUDIO
            video && VideoLinks.isVideoFile(url) -> MediaKind.VIDEO
            video -> null // страница ролика (YouTube, VK): скачать нельзя, можно открыть и поделиться ссылкой
            else -> MediaKind.IMAGE
        }
        MediaActionRow(url, shown, kind, english)
    }
}

/**
 * Подпись на языке интерфейса: английская подпись переводится одним общим запросом;
 * пока перевода нет или он не удался — показывается имя собственное как есть или название сайта.
 */
@Composable
internal fun rememberLocalizedCaption(caption: String, url: String, english: Boolean): String {
    val language = if (english) "en" else "ru"
    val needs = remember(caption, language) { CaptionLanguage.needsTranslation(caption, language) }
    if (!needs) return caption
    LaunchedEffect(caption) { CaptionTranslator.enqueue(caption) }
    val translations by CaptionTranslator.translations.collectAsState()
    return remember(caption, url, translations) {
        CaptionSelection.choose(caption, url, language, translations[caption.trim()])
    }
}

/** «Скачать» (с ходом загрузки) и «Поделиться»; у страниц видео — «Открыть». */
@Composable
private fun MediaActionRow(url: String, caption: String, kind: MediaKind?, english: Boolean) {
    val context = LocalContext.current
    val colors = HonerTheme.colors
    val states by MediaDownloads.states.collectAsState()
    val state = states[url]
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && kind != null) MediaDownloads.download(context, url, caption, kind)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kind != null) {
            when (state) {
                is DownloadState.Running -> Row(
                    Modifier.heightIn(min = 32.dp).padding(horizontal = 8.dp).semantics {
                        contentDescription = tr(english, "Скачивается", "Downloading")
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val progress = state.progress
                    if (progress == null) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else CircularProgressIndicator(progress = { progress }, color = colors.accent, strokeWidth = 2.dp, trackColor = colors.divider, modifier = Modifier.size(16.dp))
                    Text(progress?.let { "${(it * 100).toInt()}%" } ?: tr(english, "Скачиваю…", "Downloading…"),
                        fontSize = 12.sp, color = colors.secondary)
                }
                is DownloadState.Done -> ActionChip(Icons.Rounded.CheckCircle, tr(english, "Сохранено", "Saved"), "media.saved", colors.accent) {
                    // Нажатие открывает сохранённый файл в галерее или плеере.
                    if (state.uri != android.net.Uri.EMPTY) runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(state.uri, context.contentResolver.getType(state.uri))
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                is DownloadState.Failed -> ActionChip(Icons.Rounded.ErrorOutline, tr(english, "Повторить", "Retry"), "media.retry") {
                    MediaDownloads.download(context, url, caption, kind)
                }
                null -> ActionChip(Icons.Rounded.Download, tr(english, "Скачать", "Download"), "media.download") {
                    if (MediaDownloads.needsStoragePermission(context)) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else MediaDownloads.download(context, url, caption, kind)
                }
            }
        } else {
            ActionChip(Icons.AutoMirrored.Rounded.OpenInNew, tr(english, "Открыть", "Open"), "media.open") { RenderActions.open(context, url) }
        }
        ActionChip(Icons.Rounded.Share, tr(english, "Поделиться", "Share"), "media.share") {
            MediaDownloads.share(context, url, caption, kind)
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, title: String, tag: String, tint: Color? = null, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(0.6.dp, colors.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = tint ?: colors.secondary, modifier = Modifier.size(15.dp))
        Text(title, fontSize = 12.sp, color = tint ?: colors.secondary, fontWeight = FontWeight.Medium)
    }
}

/** Аудио прямо в чате: плей/пауза, ползунок, время. Плеер создаётся по первому нажатию. */
@Composable
internal fun AudioCard(url: String, caption: String, fontSize: Float, english: Boolean) {
    val context = LocalContext.current
    val colors = HonerTheme.colors
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    DisposableEffect(url) { onDispose { player?.release(); player = null } }
    LaunchedEffect(player, playing) {
        val active = player ?: return@LaunchedEffect
        while (playing) {
            if (!dragging) position = active.currentPosition
            duration = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: duration
            delay(250)
        }
    }
    fun toggle() {
        val current = player ?: ExoPlayer.Builder(context).build().also { created ->
            // Фокус звука: другой трек или видео ставятся на паузу сами.
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            created.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) duration = created.duration.takeIf { it > 0 } ?: duration
                    if (state == Player.STATE_ENDED) { created.seekTo(0); created.pause(); position = 0 }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true; playing = false; buffering = false }
            })
            created.setMediaItem(MediaItem.fromUri(url))
            created.prepare()
            player = created
        }
        failed = false
        if (current.isPlaying) current.pause() else current.play()
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(0.6.dp, colors.divider, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("message.audio")
            .semantics { contentDescription = tr(english, "Аудио: ", "Audio: ") + caption },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.accent).clickable { toggle() }.testTag("audio.play")
                    .semantics { contentDescription = if (playing) tr(english, "Пауза", "Pause") else tr(english, "Играть", "Play") },
                contentAlignment = Alignment.Center,
            ) {
                if (buffering && playing) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.MusicNote, null, tint = colors.secondary, modifier = Modifier.size(14.dp))
                    Text(caption.ifEmpty { tr(english, "Аудиозапись", "Audio") }, fontSize = (fontSize * 0.82f).sp, color = colors.foreground,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (failed) tr(english, "Не удалось воспроизвести", "Couldn't play")
                    else "${clock(if (dragging) (dragValue * duration).toLong() else position)} / ${if (duration > 0) clock(duration) else "–:––"}",
                    fontSize = 11.sp, color = colors.secondary,
                )
            }
        }
        Slider(
            value = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                player?.let { p -> if (duration > 0) p.seekTo((dragValue * duration).toLong()) }
                position = (dragValue * duration).toLong()
                dragging = false
            },
            enabled = player != null && duration > 0,
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.divider),
            modifier = Modifier.fillMaxWidth().heightIn(max = 28.dp).testTag("audio.progress"),
        )
    }
}

private fun clock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, (seconds % 3600) / 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
