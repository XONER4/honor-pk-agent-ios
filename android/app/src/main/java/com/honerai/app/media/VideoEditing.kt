package com.honerai.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** Монтаж видео (порт VideoEditing.swift) на Media3 Transformer: обрезка, скорость, звук, музыка/голос, фильтр. */
@OptIn(UnstableApi::class)
object VideoEditing {
    val unreadable get() = MediaEditingException("Не удалось открыть видео", "Could not open the video")
    val tooShort get() = MediaEditingException("Фрагмент слишком короткий", "The selected clip is too short")
    val cancelled get() = MediaEditingException("Сохранение видео отменено", "Video export was cancelled")

    /** Длительность в секундах (0, если прочитать не удалось). */
    suspend fun duration(file: File): Double = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            max(0.0, ms / 1000.0)
        } catch (_: Exception) {
            0.0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Кадры для ленты обрезки (уменьшенные, чтобы не тратить память). */
    suspend fun thumbnails(file: File, count: Int, maxSide: Int): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val result = ArrayList<Bitmap>()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (ms <= 0 || count <= 0) return@withContext emptyList()
            for (index in 0 until count) {
                val timeUs = (ms * 1000.0 * (index + 0.5) / count).toLong()
                val frame = runCatching {
                    if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { ImageOps.resize(it, maxSide) }
                    }
                }.getOrNull()
                when {
                    frame != null -> result.add(frame)
                    result.isNotEmpty() -> result.add(result.last())
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        result
    }

    /** Фильтр для видео (цветовая часть фильтров фото); null — без фильтра. */
    fun filterEffect(filter: EditFilter?): Effect? {
        val matrix = filter?.let { ColorMatrices.forFilter(it) } ?: return null
        val rgb = ColorMatrices.toRgbMatrix(matrix)
        return RgbMatrix { _, _ -> rgb }
    }

    /** Громкость звуковой дорожки (моно и стерео). */
    private fun volumeProcessor(volume: Float): AudioProcessor {
        val level = volume.coerceIn(0f, 1f)
        return ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(level))
            putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(level))
        }
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /**
     * Собрать и сохранить отредактированное видео (.mp4) в [outputDir].
     * trim — секунды исходника; speed 0.25…4; громкости 0…1. Музыка/голос зацикливается по длине ролика
     * и смешивается со звуком видео (вторая звуковая последовательность Composition).
     */
    suspend fun export(
        context: Context, source: File, trimStart: Double?, trimEnd: Double?, muteOriginal: Boolean,
        extraAudio: File?, extraAudioVolume: Float, originalVolume: Float, speed: Double, filter: EditFilter?,
        outputDir: File, onProgress: (Double) -> Unit,
    ): File {
        val total = duration(source)
        if (total <= 0) throw unreadable
        val start = (trimStart ?: 0.0).coerceIn(0.0, total)
        val end = (trimEnd ?: total).coerceIn(start, total)
        if (end - start < 0.1) throw tooShort
        outputDir.mkdirs()
        val output = File(outputDir, UUID.randomUUID().toString() + ".mp4")

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs((start * 1000).toLong())
            .apply { if (end < total - 0.01) setEndPositionMs((end * 1000).toLong()) }
            .build()
        val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(clipping).build()
        val audioProcessors = ArrayList<AudioProcessor>()
        val videoEffects = ArrayList<Effect>()
        val clampedSpeed = speed.coerceIn(0.25, 4.0).toFloat()
        if (kotlin.math.abs(clampedSpeed - 1f) > 0.001f) {
            // Скорость меняется согласованно у видео и у его звука.
            val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(clampedSpeed))
            if (!muteOriginal) audioProcessors.add(pair.first)
            videoEffects.add(pair.second)
        }
        if (!muteOriginal && originalVolume < 0.99f) audioProcessors.add(volumeProcessor(originalVolume))
        filterEffect(filter)?.let { videoEffects.add(it) }
        val videoItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muteOriginal)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
        val sequences = arrayListOf(EditedMediaItemSequence.Builder(listOf(videoItem)).build())
        if (extraAudio != null) {
            val music = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(extraAudio)))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumeProcessor(extraAudioVolume)), emptyList()))
                .build()
            sequences.add(EditedMediaItemSequence.Builder(listOf(music)).setIsLooping(true).build())
        }
        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(extraAudio != null)
            .build()

        return withContext(Dispatchers.Main) {
            coroutineScope {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()
                val poller = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100.0)
                        delay(150)
                    }
                }
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) {
                                    val reason = exportException.localizedMessage ?: exportException.errorCodeName
                                    continuation.resumeWithException(MediaEditingException(
                                        "Не удалось сохранить видео: $reason", "Could not export the video: $reason",
                                    ))
                                }
                            }
                        })
                        // Transformer живёт на главном потоке — отмена тоже отправляется туда.
                        continuation.invokeOnCancellation {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { transformer.cancel() } }
                        }
                        transformer.start(composition, output.absolutePath)
                    }
                    onProgress(1.0)
                    output
                } catch (error: Throwable) {
                    output.delete()
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    throw error as? MediaEditingException ?: MediaEditingException(
                        "Не удалось сохранить видео: ${error.localizedMessage}", "Could not export the video: ${error.localizedMessage}",
                    )
                } finally {
                    poller.cancel()
                }
            }
        }
    }
}
