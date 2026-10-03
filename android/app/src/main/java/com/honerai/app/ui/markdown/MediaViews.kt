package com.honerai.app.ui.markdown

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.honerai.app.ui.theme.HonerTheme

/**
 * Картинка из ответа: из кэша Coil (не мигает при печати), в аккуратной рамке,
 * с увеличением на весь экран по нажатию (порт CachedRemoteImage).
 */
@Composable
internal fun RemoteImage(
    url: String,
    caption: String,
    fontSize: Float,
    english: Boolean,
    maxHeight: Int = 320,
    allowsFullScreen: Boolean = true,
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    // Счётчик перезагрузок: при ошибке (релей/сеть/403) нажатие создаёт НОВЫЙ запрос и Coil
    // грузит заново — раньше состояние Error держалось до перезапуска приложения (баг «картинка
    // не грузится до перезапуска»).
    var reloadKey by remember(url) { mutableStateOf(0) }
    val request = remember(url, reloadKey) {
        // Размер задан явно: без него Coil ждёт первой отрисовки, а пока идёт загрузка,
        // картинка не рисуется. 1600 пикселей хватает и для полноэкранного просмотра.
        ImageRequest.Builder(context).data(url).size(1600).crossfade(true)
            .setParameter("reload", reloadKey) // меняет ключ кэша → принудительная перезагрузка
            .build()
    }
    // Общий загрузчик с браузерными заголовками — иначе часть сайтов отдаёт 403.
    val painter = rememberAsyncImagePainter(request, imageLoader = com.honerai.app.ui.common.HonerImages.loader(context))
    val state = painter.state
    var fullScreen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("message.image")
            .semantics {
                contentDescription = if (caption.isEmpty()) tr(english, "Изображение в ответе", "Image in the answer")
                else tr(english, "Изображение: ", "Image: ") + caption
            },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (state) {
            is AsyncImagePainter.State.Success -> Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier
                    .heightIn(max = maxHeight.dp)
                    .clip(shape)
                    .border(0.6.dp, colors.divider, shape)
                    .clickable(enabled = allowsFullScreen) { fullScreen = true },
            )
            is AsyncImagePainter.State.Error -> Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { reloadKey++ } // нажатие — повторить загрузку без перезапуска приложения
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Outlined.BrokenImage, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                Text(
                    tr(english, "Не удалось загрузить · нажмите, чтобы повторить", "Couldn't load · tap to retry"),
                    fontSize = (fontSize * 0.85f).sp, color = colors.secondary,
                )
            }
            else -> Box(
                Modifier.fillMaxWidth().height(140.dp).clip(shape).background(colors.surface).border(0.6.dp, colors.divider, shape),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
        if (caption.isNotEmpty()) {
            Text(caption, fontSize = (fontSize * 0.78f).sp, color = colors.secondary)
        }
    }
    if (fullScreen) ImageViewerDialog(url, english) { fullScreen = false }
}

/** Картинка на весь экран: щипок — масштаб, двойное нажатие — приблизить/вернуть. */
@Composable
internal fun ImageViewerDialog(url: String, english: Boolean, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            )
            Box(
                Modifier
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onDismiss)
                    .testTag("message.image.close")
                    .semantics { contentDescription = tr(english, "Закрыть изображение", "Close image") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Видео в ответе: обложка с кнопкой воспроизведения (порт VideoCardView).
 * По нажатию ролик играет в приложении: YouTube — встроенным плеером, файл — ExoPlayer.
 */
@Composable
internal fun VideoCard(url: String, caption: String, fontSize: Float, english: Boolean) {
    val colors = HonerTheme.colors
    var playing by remember { mutableStateOf(false) }
    val thumbnail = remember(url) { VideoLinks.thumbnail(url) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(colors.surface)
                .border(0.6.dp, colors.divider, shape)
                .clickable { playing = true }
                .testTag("message.video")
                .semantics {
                    contentDescription = if (caption.isEmpty()) tr(english, "Видео", "Video") else tr(english, "Видео: ", "Video: ") + caption
                },
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.OndemandVideo, null, tint = colors.secondary.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
            }
            Box(Modifier.size(58.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayCircle, null, tint = Color.White, modifier = Modifier.size(58.dp))
            }
        }
        if (caption.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.OndemandVideo, null, tint = colors.secondary, modifier = Modifier.size(15.dp))
                Text(caption, fontSize = (fontSize * 0.78f).sp, color = colors.secondary)
            }
        }
    }
    if (playing) VideoPlayerDialog(url, caption, english) { playing = false }
}

/** Плеер видео на весь экран: YouTube и страницы видео — во встроенном браузере, файлы — ExoPlayer. */
@Composable
internal fun VideoPlayerDialog(url: String, title: String, english: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().testTag("video.player")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(english, "Закрыть", "Close"),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(10.dp).testTag("video.close"),
                )
                Text(
                    title.ifEmpty { tr(english, "Видео", "Video") },
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { RenderActions.open(context, url) }.testTag("video.open")
                        .semantics { contentDescription = tr(english, "Открыть в браузере", "Open in browser") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.OpenInBrowser, null, tint = Color.White)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val youTube = remember(url) { VideoLinks.youTubeId(url) }
                when {
                    youTube != null -> Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { YouTubePlayer(youTube) }
                    VideoLinks.isVideoFile(url) -> FileVideoPlayer(url)
                    else -> WebVideo(url)
                }
            }
            Spacer(Modifier.height(8.dp).width(1.dp))
        }
    }
}

@Composable
private fun FileVideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Встроенный плеер YouTube: страница с адресом youtube.com как источником, иначе ролик не играет. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubePlayer(videoId: String) {
    val html = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
        iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head>
        <body><iframe src="https://www.youtube.com/embed/$videoId?playsinline=1&autoplay=1&rel=0"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe></body></html>
    """.trimIndent()
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebVideo(url: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}
