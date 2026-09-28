package com.honerai.app.ui.common

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.honerai.app.AppContainer
import com.honerai.app.ui.theme.HonerTheme

/**
 * Видео в ответе: обложка с кнопкой воспроизведения. По нажатию ролик играет
 * прямо в приложении — YouTube во встроенном плеере, файл видео — плеером Media3,
 * другие сайты (RuTube, VK Видео) — во встроенном браузере.
 */
@Composable
fun VideoCard(url: String, caption: String, modifier: Modifier = Modifier, fontSize: Float = 17f) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val english = AppContainer.get(context).settings.isEnglish
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(colors.surface)
                .border(0.6.dp, colors.divider, shape)
                .clickable { playing = true }
                .semantics { contentDescription = if (caption.isEmpty()) (if (english) "Video" else "Видео") else caption }
                .testTag("message.video"),
            contentAlignment = Alignment.Center,
        ) {
            MediaLinks.videoThumbnail(url)?.let { thumb ->
                AsyncImage(model = thumb, imageLoader = HonerImages.loader(context), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
        }
        if (caption.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.SmartDisplay, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
                Text(caption, fontSize = (fontSize * 0.78f).sp, color = colors.secondary)
            }
        }
    }
    if (playing) VideoPlayerDialog(url = url, title = caption, onClose = { playing = false })
}

/** Плеер видео поверх всего экрана: YouTube, файл или страница с видео. */
@Composable
fun VideoPlayerDialog(url: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val english = AppContainer.get(context).settings.isEnglish
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.testTag("video.close")) {
                    Text(if (english) "Close" else "Закрыть", color = Color.White)
                }
                Text(title.ifEmpty { if (english) "Video" else "Видео" }, color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 16.sp)
                IconButton(onClick = { openUrl(context, url) }, modifier = Modifier.testTag("video.open")) {
                    Icon(Icons.Rounded.OpenInBrowser, if (english) "Open in browser" else "Открыть в браузере", tint = Color.White)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val youTube = MediaLinks.youTubeId(url)
                when {
                    youTube != null -> YouTubePlayer(youTube, Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("video.player"))
                    MediaLinks.isVideoFile(url) -> MediaPlayerView(Uri.parse(url), Modifier.fillMaxSize().testTag("video.player"))
                    else -> WebPageView(url, Modifier.fillMaxSize().testTag("video.player"))
                }
            }
        }
    }
}

/**
 * Встроенный плеер YouTube. Страница загружается с адресом youtube.com как
 * источником: без него YouTube отказывается воспроизводить встроенный ролик.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, modifier: Modifier = Modifier) {
    val html = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
        iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head>
        <body><iframe src="https://www.youtube.com/embed/$videoId?playsinline=1&autoplay=1&rel=0&modestbranding=1"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe></body></html>
    """.trimIndent()
    var view by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                view = this
            }
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            view?.apply { loadUrl("about:blank"); stopLoading(); destroy() }
        }
    }
}

/** Страница с видео (RuTube, VK Видео) во встроенном браузере. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPageView(url: String, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, u: String?) { loading = false }
                    }
                    loadUrl(url)
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) CircularProgressIndicator(color = HonerTheme.colors.accent)
    }
    DisposableEffect(Unit) { onDispose { view?.apply { stopLoading(); destroy() } } }
}

/** Плеер Media3 для видео и аудио: создаётся один раз и освобождается при закрытии. */
@Composable
fun MediaPlayerView(uri: Uri, modifier: Modifier = Modifier, autoPlay: Boolean = true) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = autoPlay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        modifier = modifier,
    )
}
