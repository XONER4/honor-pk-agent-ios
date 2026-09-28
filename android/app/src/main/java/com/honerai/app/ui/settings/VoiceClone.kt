package com.honerai.app.ui.settings

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.honerai.app.data.HonerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// «Мой голос» (порт VoiceClone.swift): ключ Fish Audio, запись образца, создание голоса.

/**
 * Настройки своего голоса. Отдельное хранилище, потому что в общих настройках этих полей нет.
 * Озвучка может читать [clonedVoiceId]/[useClonedVoice]/[apiKey], чтобы говорить голосом пользователя.
 */
object VoiceCloneStore {
    private const val PREFS = "honer.voiceclone"
    private val _clonedVoiceId = MutableStateFlow("")
    private val _useClonedVoice = MutableStateFlow(false)
    @Volatile private var loaded = false
    val clonedVoiceIdFlow: StateFlow<String> = _clonedVoiceId.asStateFlow()
    val useClonedVoiceFlow: StateFlow<Boolean> = _useClonedVoice.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        _clonedVoiceId.value = p.getString("clonedVoiceID", "").orEmpty()
        _useClonedVoice.value = p.getBoolean("useClonedVoice", false)
        loaded = true
    }

    fun apiKey(context: Context): String = prefs(context).getString("fishAudioKey", "").orEmpty()
    fun setApiKey(context: Context, value: String) { prefs(context).edit().putString("fishAudioKey", value.trim()).apply() }
    fun clonedVoiceId(context: Context): String { load(context); return _clonedVoiceId.value }
    fun useClonedVoice(context: Context): Boolean { load(context); return _useClonedVoice.value && _clonedVoiceId.value.isNotEmpty() }

    fun setClonedVoice(context: Context, id: String, use: Boolean) {
        prefs(context).edit().putString("clonedVoiceID", id).putBoolean("useClonedVoice", use).apply()
        _clonedVoiceId.value = id
        _useClonedVoice.value = use
    }

    fun setUseClonedVoice(context: Context, use: Boolean) {
        prefs(context).edit().putBoolean("useClonedVoice", use).apply()
        _useClonedVoice.value = use
    }

    fun sampleFile(context: Context): File = File(context.filesDir, "voice-sample.m4a")
}

/** Клиент Fish Audio: голос создаётся по записи от 20 секунд, поддерживается русский. */
object FishAudio {
    private const val BASE = "https://api.fish.audio"

    /** Текст для записи образца голоса: разные звуки, интонации и числа. */
    const val SCRIPT = "Привет! Меня зовут так, как вы меня слышите, и это мой настоящий голос. " +
        "Сегодня прекрасный день, чтобы узнать что-то новое. Съешь же ещё этих мягких французских булок да выпей чаю. " +
        "Я люблю ясные ответы, короткие фразы и понятные объяснения. Сколько будет двадцать пять плюс семнадцать? " +
        "Сорок два! Отлично, давай продолжим. Широкая электрификация южных губерний даст мощный толчок подъёму сельского хозяйства. " +
        "Hello! I can speak English too. Спасибо, что слушаете меня."

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()
    }

    class ServiceException(val status: Int, val body: String) : IOException("HTTP $status")

    /** Понятный текст ошибки сервиса. */
    fun describe(error: Throwable, english: Boolean): String = when {
        error is ServiceException && (error.status == 401 || error.status == 403) ->
            if (english) "The Fish Audio key didn't work. Check it on the voice page." else "Ключ Fish Audio не подошёл. Проверьте его в настройках голоса."
        error is ServiceException && error.status == 402 ->
            if (english) "Your Fish Audio balance is empty." else "На счёте Fish Audio закончились средства."
        error is ServiceException ->
            (if (english) "The voice service returned error ${error.status}. " else "Сервис голоса ответил ошибкой ${error.status}. ") + error.body.take(300)
        else -> error.localizedMessage ?: error.toString()
    }

    /** Создать голос по записи. Возвращает идентификатор голоса. */
    suspend fun createVoice(apiKey: String, sample: File, transcript: String, title: String): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("type", "tts").addFormDataPart("title", title).addFormDataPart("train_mode", "fast")
            .addFormDataPart("visibility", "private").addFormDataPart("texts", transcript)
            .addFormDataPart("enhance_audio_quality", "true")
            .addFormDataPart("voices", "sample.m4a", sample.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder().url("$BASE/model").header("Authorization", "Bearer $apiKey").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ServiceException(response.code, text)
            val id = runCatching { HonerJson.parseToJsonElement(text).jsonObject["_id"]?.jsonPrimitive?.content }.getOrNull()
            if (id.isNullOrEmpty()) throw ServiceException(response.code, text)
            id
        }
    }

    /** Озвучить текст голосом пользователя. Возвращает звук в MP3. */
    suspend fun synthesize(text: String, voiceId: String, apiKey: String): ByteArray = withContext(Dispatchers.IO) {
        val json = buildJsonObject {
            put("text", text); put("reference_id", voiceId); put("format", "mp3"); put("normalize", true)
        }.toString()
        val request = Request.Builder().url("$BASE/v1/tts").header("Authorization", "Bearer $apiKey").header("model", "s2-pro")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful || bytes.size <= 256) throw ServiceException(response.code, String(bytes.take(300).toByteArray()))
            bytes
        }
    }

    suspend fun deleteVoice(voiceId: String, apiKey: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("$BASE/model/$voiceId").header("Authorization", "Bearer $apiKey").delete().build()
                client.newCall(request).execute().close()
            }
        }
    }
}

/** Запись образца голоса в AAC (m4a), как на iPhone. */
internal class VoiceSampleRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    val isRecording: Boolean get() = recorder != null

    /** Начать запись. Возвращает текст ошибки или null. */
    @Suppress("DEPRECATION")
    fun start(): String? {
        stop()
        val file = VoiceCloneStore.sampleFile(context)
        file.delete()
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioChannels(1)
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
            recorder = r
            startedAt = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            recorder = null
            e.localizedMessage ?: e.toString()
        }
    }

    val seconds: Double get() = if (recorder != null) (System.currentTimeMillis() - startedAt) / 1000.0 else 0.0

    /** Громкость 0…1 для индикатора. */
    val level: Double
        get() {
            val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            if (amplitude <= 0) return 0.0
            val db = 20 * kotlin.math.log10(amplitude / 32767.0)
            return ((db + 50) / 50).coerceIn(0.0, 1.0)
        }

    /** Остановить; возвращает длину записи в секундах (0 — записи нет). */
    fun stop(): Double {
        val r = recorder ?: return 0.0
        val length = seconds
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok) { VoiceCloneStore.sampleFile(context).delete(); return 0.0 }
        return length
    }
}
