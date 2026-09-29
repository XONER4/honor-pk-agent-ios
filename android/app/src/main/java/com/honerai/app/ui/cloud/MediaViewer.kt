package com.honerai.app.ui.cloud

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.honerai.app.cloud.CloudAttachment
import com.honerai.app.cloud.CloudConfig
import com.honerai.app.cloud.CloudImages
import com.honerai.app.cloud.CloudManager
import com.honerai.app.cloud.CloudMedia
import com.honerai.app.cloud.CloudUrls
import kotlinx.coroutines.launch
import java.io.File

/** Что открыто в просмотрщике: вложение и его локальная копия (у своих неотправленных). */
data class ViewerItem(val attachment: CloudAttachment, val localPath: String?)

/** Адрес для показа: локальный файл, если есть, иначе файл на сервере. */
fun attachmentModel(attachment: CloudAttachment, localPath: String?): Any? {
    localPath?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
    if (attachment.url.isEmpty()) return null
    return CloudUrls.media(CloudConfig.baseUrl, attachment.url)
}

/** Сохранить вложение в память телефона (до Android 10 — с разрешением на запись). */
@Composable
fun rememberSaver(english: Boolean, onResult: (String) -> Unit): (CloudAttachment, String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ViewerItem?>(null) }
    fun save(item: ViewerItem) {
        scope.launch {
            val result = runCatching { CloudMedia.saveToDevice(context, item.attachment, item.localPath) }
            onResult(result.fold(
                onSuccess = { (if (english) "Saved to " else "Сохранено: ") + it },
                onFailure = { if (english) "Could not save the file" else "Не удалось сохранить файл" },
            ))
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (granted) save(item) else onResult(if (english) "Allow storage access to save files" else "Разрешите доступ к памяти, чтобы сохранять файлы")
    }
    return { attachment, localPath ->
        val item = ViewerItem(attachment, localPath)
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = item
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else save(item)
    }
}

/** Полноэкранный просмотр фото (масштаб пальцами, двойное касание) и видео, кнопка «Сохранить». */
@Composable
fun CloudMediaViewer(item: ViewerItem, english: Boolean, onSave: (CloudAttachment, String?) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val attachment = item.attachment
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }.testTag("cloud.viewer")) {
        if (attachment.kind == "video") {
            VideoPlayer(attachmentModel(attachment, item.localPath), Modifier.fillMaxSize())
        } else {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            AsyncImage(
                model = attachmentModel(attachment, item.localPath),
                imageLoader = CloudImages.loader(context),
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
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
        }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose)
                    .semantics { contentDescription = if (english) "Close" else "Закрыть" }.testTag("cloud.viewer.close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
            Text(attachment.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).clickable { onSave(attachment, item.localPath) }.padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("cloud.viewer.save"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(if (english) "Save" else "Сохранить", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Видео с сервера (с токеном устройства, перемотка через Range) или локальный файл. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(model: Any?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (model == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        return
    }
    val uri = when (model) {
        is File -> Uri.fromFile(model)
        else -> Uri.parse(model.toString())
    }
    val player = remember(uri) {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        CloudManager.authHeader()?.let { http.setDefaultRequestProperties(mapOf("Authorization" to it)) }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}
