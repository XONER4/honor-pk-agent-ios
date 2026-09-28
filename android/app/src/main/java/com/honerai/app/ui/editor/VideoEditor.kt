package com.honerai.app.ui.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.honerai.app.AppContainer
import com.honerai.app.device.AttachmentImporter
import com.honerai.app.media.EditFilter
import com.honerai.app.media.MediaEditingException
import com.honerai.app.media.VideoEditing
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Состояние видеоредактора (порт VideoEditorModel). */
@Stable
@OptIn(UnstableApi::class)
internal class VideoEditorModel(private val context: Context, private val lowEnd: Boolean) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var musicPlayer: ExoPlayer? = null
    var english = false

    var duration by mutableStateOf(0.0); private set
    var trimStart by mutableStateOf(0.0); private set
    var trimEnd by mutableStateOf(0.0); private set
    var currentTime by mutableStateOf(0.0); private set
    var thumbnails by mutableStateOf<List<ImageBitmap>>(emptyList()); private set
    var isPlaying by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var speed by mutableStateOf(1.0); private set
    var filter by mutableStateOf(EditFilter.ORIGINAL); private set
    var originalVolume by mutableStateOf(1f); private set
    var extraVolume by mutableStateOf(0.8f); private set
    var extraAudio by mutableStateOf<File?>(null); private set
    var extraAudioName by mutableStateOf(""); private set
    var isRecording by mutableStateOf(false); private set
    var exporting by mutableStateOf(false); private set
    var progress by mutableStateOf(0.0); private set
    var errorMessage by mutableStateOf<String?>(null)

    private var source: File? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val tempFiles = ArrayList<File>()

    private fun t(russian: String, englishText: String) = if (english) englishText else russian

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) musicPlayer?.pause()
            }
        })
    }

    suspend fun load(file: File) {
        if (source != null) return
        source = file
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        val length = VideoEditing.duration(file)
        duration = length
        trimStart = 0.0
        trimEnd = length
        if (length <= 0) {
            errorMessage = t("Не удалось открыть видео", "Could not open the video")
            return
        }
        val frames = VideoEditing.thumbnails(file, if (lowEnd) 8 else 10, if (lowEnd) 160 else 240)
        thumbnails = withContext(Dispatchers.Default) { frames.map { it.asImageBitmap() } }
    }

    fun release() {
        if (isRecording) stopRecording()
        player.release()
        musicPlayer?.release()
        musicPlayer = null
        tempFiles.forEach { runCatching { it.delete() } }
    }

    // Воспроизведение

    /** Вызывается ~20 раз в секунду: позиция, петля внутри обрезки, конец записи голоса. */
    fun tick() {
        val seconds = player.currentPosition / 1000.0
        currentTime = seconds
        if (isRecording && seconds >= trimEnd - 0.03) {
            stopRecording()
            return
        }
        if (isPlaying && seconds >= trimEnd - 0.03) {
            seek(trimStart)
            restartMusic()
        }
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    fun play() {
        if (currentTime < trimStart || currentTime >= trimEnd - 0.05) seek(trimStart)
        player.play()
        restartMusic()
    }

    fun pause() {
        player.pause()
        musicPlayer?.pause()
    }

    fun seek(seconds: Double) {
        val clamped = seconds.coerceIn(0.0, max(duration, 0.0))
        player.seekTo((clamped * 1000).toLong())
        currentTime = clamped
    }

    private fun restartMusic() {
        val music = musicPlayer ?: return
        val offset = max(0.0, (currentTime - trimStart) / max(speed, 0.25))
        music.seekTo((offset * 1000).toLong())
        music.volume = extraVolume
        music.play()
    }

    private fun applyVolumes() {
        player.volume = if (muted || isRecording) 0f else originalVolume
        musicPlayer?.volume = extraVolume
    }

    fun updateMuted(value: Boolean) { muted = value; applyVolumes() }
    fun updateOriginalVolume(value: Float) { originalVolume = value; applyVolumes() }
    fun updateExtraVolume(value: Float) { extraVolume = value; applyVolumes() }

    fun updateSpeed(value: Double) {
        speed = value
        player.setPlaybackSpeed(value.toFloat())
    }

    fun updateFilter(value: EditFilter) {
        if (value == filter) return
        filter = value
        // Живой предпросмотр фильтра (эффекты Media3 поверх SurfaceView); если устройство не умеет — только при сохранении.
        runCatching { player.setVideoEffects(listOfNotNull(VideoEditing.filterEffect(value))) }
    }

    // Обрезка

    fun setTrimStartValue(value: Double) {
        val upper = max(0.0, trimEnd - MINIMUM_LENGTH)
        trimStart = value.coerceIn(0.0, upper)
        if (isPlaying) pause()
        seek(trimStart)
    }

    fun setTrimEndValue(value: Double) {
        val lower = min(duration, trimStart + MINIMUM_LENGTH)
        trimEnd = value.coerceIn(lower, max(lower, duration))
        if (isPlaying) pause()
        seek(trimEnd)
    }

    val selectionLength: Double get() = max(0.0, trimEnd - trimStart) / max(speed, 0.25)

    // Музыка и голос

    private fun setExtraAudio(file: File, name: String) {
        musicPlayer?.release()
        extraAudio = file
        extraAudioName = name
        musicPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = extraVolume
            prepare()
        }
    }

    fun removeExtraAudio() {
        musicPlayer?.release()
        musicPlayer = null
        extraAudio = null
        extraAudioName = ""
    }

    suspend fun importAudio(uri: Uri) {
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "audio"
                val ext = name.substringAfterLast('.', "m4a").take(5)
                val target = File(context.cacheDir, "audio-" + UUID.randomUUID() + "." + ext)
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                target to name
            }.getOrNull()
        }
        if (copied == null) {
            errorMessage = t("Не удалось открыть аудиофайл", "Could not open the audio file")
            return
        }
        tempFiles.add(copied.first)
        setExtraAudio(copied.first, copied.second)
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        if (isRecording) return
        val file = File(context.cacheDir, "voice-" + UUID.randomUUID() + ".m4a")
        try {
            @Suppress("DEPRECATION")
            val newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setAudioEncodingBitRate(128_000)
            newRecorder.setAudioChannels(1)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            recordingFile = file
            tempFiles.add(file)
            isRecording = true
            musicPlayer?.pause()
            applyVolumes()
            seek(trimStart)
            player.play()
        } catch (error: Exception) {
            file.delete()
            errorMessage = t("Не удалось начать запись", "Could not start recording")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        val ok = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.release() }
        recorder = null
        isRecording = false
        pause()
        applyVolumes()
        val file = recordingFile
        recordingFile = null
        if (ok && file != null && file.length() > 0) {
            setExtraAudio(file, t("Голос", "Voice-over"))
        } else {
            errorMessage = t("Запись слишком короткая", "The recording is too short")
        }
    }

    // Экспорт

    suspend fun export(): File? {
        val file = source ?: return null
        if (exporting || duration <= 0) return null
        if (isRecording) stopRecording()
        pause()
        exporting = true
        progress = 0.0
        return try {
            val lower = min(trimStart, trimEnd)
            val upper = max(trimStart, trimEnd)
            val trimmed = lower > 0.01 || upper < duration - 0.01
            VideoEditing.export(
                context, file, if (trimmed) lower else null, if (trimmed) upper else null, muted, extraAudio,
                extraVolume, originalVolume, speed, filter.takeIf { it != EditFilter.ORIGINAL },
                AttachmentImporter.attachmentsDir(context),
            ) { value -> progress = value }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MediaEditingException) {
            errorMessage = error.text(english)
            null
        } catch (error: Throwable) {
            errorMessage = error.localizedMessage ?: t("Не удалось сохранить видео", "Could not export the video")
            null
        } finally {
            exporting = false
        }
    }

    companion object {
        const val MINIMUM_LENGTH = 0.5
        val speeds = listOf(0.5, 1.0, 1.5, 2.0)
        val filters = listOf(
            EditFilter.ORIGINAL, EditFilter.VIVID, EditFilter.WARM, EditFilter.COOL, EditFilter.MONO, EditFilter.NOIR,
            EditFilter.SEPIA, EditFilter.FADE, EditFilter.CHROME, EditFilter.INSTANT, EditFilter.DRAMATIC,
        )
    }
}

/** Видеоредактор (порт VideoEditorView): обрезка, звук, скорость, фильтр, музыка и голос, сохранение в MP4. */
@Composable
@OptIn(UnstableApi::class)
internal fun VideoEditor(videoFile: File, onSave: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val language by container.settings.language.collectAsState()
    val english = language == "en"
    val t: (String, String) -> String = { ru, en -> if (english) en else ru }
    val scope = rememberCoroutineScope()
    val model = remember(videoFile) { VideoEditorModel(context.applicationContext, container.isLowEndDevice) }
    model.english = english
    val colors = HonerTheme.colors
    val currentOnSave by rememberUpdatedState(onSave)

    LaunchedEffect(model) { model.load(videoFile) }
    LaunchedEffect(model) {
        while (true) {
            model.tick()
            delay(50)
        }
    }
    DisposableEffect(model) { onDispose { model.release() } }
    BackHandler { onCancel() }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { model.importAudio(uri) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startRecording()
        else model.errorMessage = t("Разрешите доступ к микрофону в настройках", "Allow microphone access in Settings")
    }

    fun toggleRecording() {
        when {
            model.isRecording -> model.stopRecording()
            model.hasMicPermission() -> model.startRecording()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun save() {
        scope.launch { model.export()?.let { currentOnSave(it) } }
    }

    Box(Modifier.fillMaxSize().background(colors.background).testTag("editor.video")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            val controlsMax = maxHeight * 0.62f
            Column(Modifier.fillMaxSize()) {
                EditorTopBar(
                    title = t("Видео", "Video"), cancel = t("Отмена", "Cancel"), done = t("Готово", "Done"),
                    doneEnabled = model.duration > 0 && !model.exporting, onCancel = onCancel, onDone = ::save,
                )
                val player: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    this.player = model.player
                                }
                            },
                            modifier = Modifier.fillMaxSize().clickable { model.togglePlay() },
                        )
                    }
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(
                        modifier.background(colors.surface).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PlaybackRow(model, t)
                        TrimTimeline(model, t, Modifier.padding(horizontal = 16.dp))
                        SoundRow(model, t)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(VideoEditorModel.filters, key = { it.raw }) { item ->
                                EditorChip(t(item.title, item.englishTitle), null, model.filter == item,
                                    Modifier.testTag("editor.filter." + item.raw)) { model.updateFilter(item) }
                            }
                        }
                        AudioSection(model, t, onPick = { audioPicker.launch("audio/*") }, onRecord = ::toggleRecording)
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        player(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
                        controls(Modifier.width(400.dp).fillMaxHeight())
                    }
                } else {
                    player(Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp))
                    controls(Modifier.fillMaxWidth().heightIn(max = controlsMax))
                }
            }
        }
        EditorBusyOverlay(if (model.exporting) t("Сохраняю видео…", "Exporting video…") else null, model.progress)
    }

    model.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { model.errorMessage = null },
            confirmButton = { TextButton(onClick = { model.errorMessage = null }) { Text("OK") } },
            title = { Text(t("Ошибка", "Error")) },
            text = { Text(message) },
        )
    }
}

@Composable
private fun PlaybackRow(model: VideoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(colors.raised).clickable { model.togglePlay() }
                .semantics { contentDescription = if (model.isPlaying) t("Пауза", "Pause") else t("Играть", "Play") }
                .testTag("editor.play"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (model.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = colors.foreground)
        }
        Text(EditorGeometry.timeString(model.currentTime) + " / " + EditorGeometry.timeString(model.duration),
            color = colors.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(t("Итог: ", "Result: ") + EditorGeometry.timeString(model.selectionLength), color = colors.secondary, fontSize = 12.sp)
    }
}

@Composable
private fun SoundRow(model: VideoEditorModel, t: (String, String) -> String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorChip(t("Без звука", "Mute"), if (model.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            model.muted, Modifier.testTag("editor.mute")) { model.updateMuted(!model.muted) }
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f).height(36.dp).testTag("editor.speed")) {
            VideoEditorModel.speeds.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = model.speed == value, onClick = { model.updateSpeed(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, VideoEditorModel.speeds.size), icon = {},
                ) {
                    Text(if (value == 1.0) "1×" else value.toString().removeSuffix(".0") + "×", fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AudioSection(model: VideoEditorModel, t: (String, String) -> String, onPick: () -> Unit, onRecord: () -> Unit) {
    val colors = HonerTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EditorChip(t("Добавить музыку/голос", "Add music/voice"), Icons.Filled.MusicNote, false,
                Modifier.testTag("editor.addAudio"), enabled = !model.isRecording, onClick = onPick)
            EditorChip(if (model.isRecording) t("Стоп", "Stop") else t("Записать голос", "Record voice"),
                if (model.isRecording) Icons.Filled.Stop else Icons.Filled.Mic, model.isRecording,
                Modifier.testTag("editor.recordVoice"), onClick = onRecord)
        }
        if (model.extraAudio != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = colors.accent)
                Text(model.extraAudioName, color = colors.foreground, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Cancel, contentDescription = t("Убрать звук", "Remove audio"), tint = colors.secondary,
                    modifier = Modifier.size(24.dp).clickable { model.removeExtraAudio() }.testTag("editor.removeAudio"))
            }
        }
        EditorSliderRow(t("Звук видео", "Video sound"), model.originalVolume, 0f..1f, onChange = { model.updateOriginalVolume(it) },
            enabled = !model.muted)
        if (model.extraAudio != null) {
            EditorSliderRow(t("Музыка/голос", "Music/voice"), model.extraVolume, 0f..1f, onChange = { model.updateExtraVolume(it) })
        }
    }
}

/** Лента обрезки: кадры, затемнение вне выбора, жёлтая рамка, две ручки и бегунок. */
@Composable
private fun TrimTimeline(model: VideoEditorModel, t: (String, String) -> String, modifier: Modifier) {
    val colors = HonerTheme.colors
    val density = LocalDensity.current
    val handle = 16.dp
    val height = 56.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val widthPx = constraints.maxWidth.toFloat()
        val handlePx = with(density) { handle.toPx() }
        val track = max(1f, widthPx - handlePx * 2)
        fun x(time: Double): Float =
            if (model.duration <= 0) handlePx else handlePx + (time / model.duration).toFloat().coerceIn(0f, 1f) * track
        fun time(px: Float): Double = ((px - handlePx) / track).coerceIn(0f, 1f) * model.duration

        // Кадры и перемотка касанием.
        Row(
            Modifier.offset(x = handle, y = 4.dp).width(with(density) { track.toDp() }).height(height - 8.dp)
                .clip(RoundedCornerShape(6.dp)).background(colors.raised)
                .pointerInput(model.duration, track) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun scrub(px: Float) {
                            if (model.isPlaying) model.pause()
                            model.seek(time(px + handlePx).coerceIn(model.trimStart, model.trimEnd))
                        }
                        scrub(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            scrub(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            val count = max(1, model.thumbnails.size)
            for (frame in model.thumbnails) {
                Image(frame, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(with(density) { (track / count).toDp() }).fillMaxHeight())
            }
        }
        // Затемнение, рамка и бегунок.
        Canvas(Modifier.fillMaxSize()) {
            val startX = x(model.trimStart)
            val endX = x(model.trimEnd)
            val top = 4.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(handlePx, top), Size(max(0f, startX - handlePx), bottom - top))
            drawRect(dim, Offset(endX, top), Size(max(0f, handlePx + track - endX), bottom - top))
            drawRoundRect(Color(0xFFFFD60A), Offset(startX - handlePx, 1.5.dp.toPx()),
                Size(endX - startX + handlePx * 2, size.height - 3.dp.toPx()), CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
            val playhead = x(model.currentTime)
            drawRoundRect(Color.White, Offset(playhead - 1.dp.toPx(), 2.dp.toPx()), Size(2.dp.toPx(), size.height - 4.dp.toPx()),
                CornerRadius(1.dp.toPx()))
        }
        // Ручки.
        TrimHandle(
            "‹", Modifier.offset { IntOffset((x(model.trimStart) - handlePx).roundToInt(), 0) }.width(handle).fillMaxHeight()
                .semantics { contentDescription = t("Начало", "Start") + " " + EditorGeometry.timeString(model.trimStart) }
                .testTag("editor.trim.start")
                .pointerInput(model.duration, track) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        model.setTrimStartValue(time(x(model.trimStart) + drag))
                    }
                },
        )
        TrimHandle(
            "›", Modifier.offset { IntOffset(x(model.trimEnd).roundToInt(), 0) }.width(handle).fillMaxHeight()
                .semantics { contentDescription = t("Конец", "End") + " " + EditorGeometry.timeString(model.trimEnd) }
                .testTag("editor.trim.end")
                .pointerInput(model.duration, track) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        model.setTrimEndValue(time(x(model.trimEnd) + drag))
                    }
                },
        )
    }
}

@Composable
private fun TrimHandle(symbol: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFFFD60A)), contentAlignment = Alignment.Center) {
        Text(symbol, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}
