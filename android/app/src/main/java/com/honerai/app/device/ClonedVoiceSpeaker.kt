package com.honerai.app.device

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.honerai.app.ui.settings.FishAudio
import com.honerai.app.ui.settings.VoiceCloneStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Правила чтения своим (клонированным) голосом — чистый Kotlin, проверяется JVM-тестами.
 */
object ClonedVoicePlan {
    /** Клон включён, только если есть согласие/готовый голос (useCloned) и ключ Fish Audio. */
    fun shouldUseCloned(useCloned: Boolean, clonedId: String, apiKey: String): Boolean =
        useCloned && clonedId.isNotBlank() && apiKey.isNotBlank()

    /**
     * Текст режется на короткие куски: первый звучит быстро, следующие готовятся, пока играет текущий.
     * Границы — по предложениям и словам (как chunks у синтезатора), пустые куски отбрасываются.
     */
    fun synthChunks(spoken: String, max: Int = 260): List<String> =
        if (spoken.isBlank()) emptyList()
        else SpeechText.chunks(spoken, max.coerceAtLeast(80)).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Чтение вслух голосом пользователя (клон Fish Audio) — порт speakCloned из SpeechService.swift.
 *
 * Текст режется на короткие куски; каждый синтезируется в MP3 и по очереди играет через ExoPlayer:
 * звук начинается быстро и продолжается, пока готовится следующий кусок. Ошибка сети/сервиса —
 * молча дочитываем этот и оставшиеся куски голосом устройства ([fallback]) и показываем текст ошибки.
 *
 * Встраивается в [SpeechService]: speak/append перенаправляются сюда, когда включён «Мой голос».
 * Все методы вызываются с главного потока.
 */
class ClonedVoiceSpeaker(
    context: Context,
    private val onSpeaking: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val fallback: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var channel: Channel<String>? = null
    private var consumer: Job? = null
    private var token = 0
    private var fileCounter = 0
    private val files = ArrayList<File>()

    /** Включён ли «Мой голос» (есть готовый голос, согласие и ключ). */
    fun enabled(): Boolean = ClonedVoicePlan.shouldUseCloned(
        VoiceCloneStore.useClonedVoice(appContext),
        VoiceCloneStore.clonedVoiceId(appContext),
        VoiceCloneStore.apiKey(appContext),
    )

    /**
     * Прочитать текст своим голосом. [flush] — прервать текущее чтение (кнопка «Прослушать»),
     * иначе дочитать следом (чтение во время печати ответа). Возвращает false, если клон выключен —
     * тогда вызывающий читает голосом устройства, как обычно.
     */
    fun speak(spoken: String, flush: Boolean): Boolean {
        if (!enabled()) return false
        val voiceId = VoiceCloneStore.clonedVoiceId(appContext)
        val key = VoiceCloneStore.apiKey(appContext)
        val parts = ClonedVoicePlan.synthChunks(spoken)
        if (parts.isEmpty()) return true
        onMain {
            if (flush) resetInternal()
            val ch = ensureConsumer(voiceId, key)
            onSpeaking(true)
            parts.forEach { ch.trySend(it) }
        }
        return true
    }

    fun stop() = onMain {
        resetInternal()
        onSpeaking(false)
    }

    fun release() = onMain {
        resetInternal()
        player?.let { runCatching { it.release() } }
        player = null
        scope.cancel()
    }

    private fun ensureConsumer(voiceId: String, key: String): Channel<String> {
        channel?.let { return it }
        val ch = Channel<String>(Channel.UNLIMITED)
        channel = ch
        val myToken = token
        consumer = scope.launch {
            try {
                for (part in ch) {
                    if (token != myToken) break
                    val file = try {
                        withContext(Dispatchers.IO) {
                            val bytes = FishAudio.synthesize(part, voiceId, key)
                            writeTemp(bytes, myToken)
                        }
                    } catch (e: Exception) {
                        if (token != myToken) break
                        onError(FishAudio.describe(e, Locale.getDefault().language == "en"))
                        // Дочитываем текущий и оставшиеся куски голосом устройства, чтобы ответ не молчал.
                        fallback(part)
                        while (true) { val more = ch.tryReceive().getOrNull() ?: break; fallback(more) }
                        onSpeaking(false)
                        break
                    }
                    if (token != myToken) break
                    addAndPlay(file)
                }
            } finally {
                if (channel === ch) { channel = null; consumer = null }
            }
        }
        return ch
    }

    private fun writeTemp(bytes: ByteArray, tk: Int): File {
        val dir = File(appContext.cacheDir, "cloned-voice").apply { mkdirs() }
        val file = File(dir, "seg-$tk-${fileCounter++}.mp3")
        file.writeBytes(bytes)
        synchronized(files) { files.add(file) }
        return file
    }

    private fun addAndPlay(file: File) {
        val p = ensurePlayer()
        val index = p.mediaItemCount
        p.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.playWhenReady = true
        when (p.playbackState) {
            Player.STATE_IDLE -> p.prepare()
            // Плеер уже доиграл прежние куски — переходим к только что добавленному.
            Player.STATE_ENDED -> p.seekTo(index, 0L)
            else -> {}
        }
        onSpeaking(true)
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(appContext).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onSpeaking(false)
            }
            override fun onPlayerError(error: PlaybackException) {
                onError(error.localizedMessage ?: error.toString())
                onSpeaking(false)
            }
        })
        player = p
        return p
    }

    private fun resetInternal() {
        token += 1
        consumer?.cancel(); consumer = null
        channel?.close(); channel = null
        player?.let { runCatching { it.stop(); it.clearMediaItems() } }
        synchronized(files) {
            files.forEach { runCatching { it.delete() } }
            files.clear()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
