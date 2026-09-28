package com.honerai.app.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Голосовой ввод (SpeechRecognizer) и чтение вслух (TextToSpeech) — порт SpeechService.swift.
 *
 * Ввод: частичные результаты сразу попадают в [transcript]; пока палец держит кнопку, распознавание
 * после паузы в речи перезапускается и текст накапливается (как длинная диктовка на iPhone).
 * [stopRecording] останавливает микрофон и ждёт финальный результат (не дольше 2 секунд).
 *
 * Озвучка: русские куски читает русский голос, английские — английский того же пола;
 * [speak] прерывает текущее чтение, [append] дочитывает следом (чтение во время печати ответа).
 * Методы вызываются с главного потока (экран); работа с распознавателем всегда идёт на нём.
 */
class SpeechService(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _transcript = MutableStateFlow("")
    /** Распознанный текст по мере речи. */
    val transcript: StateFlow<String> = _transcript.asStateFlow()
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    /** Громкость голоса 0…1 для анимации кнопки микрофона. */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private val _isFinalizing = MutableStateFlow(false)
    /** Микрофон уже выключен, ждём последние слова. */
    val isFinalizingRecording: StateFlow<Boolean> = _isFinalizing.asStateFlow()
    private val _needsPermissionSettings = MutableStateFlow(false)
    /** Разрешение отклонено — экран может предложить открыть настройки приложения. */
    val needsPermissionSettings: StateFlow<Boolean> = _needsPermissionSettings.asStateFlow()
    private val _voicesReady = MutableStateFlow(false)
    /** Движок синтеза готов — список голосов можно показывать. */
    val voicesReady: StateFlow<Boolean> = _voicesReady.asStateFlow()

    // MARK: Распознавание

    private var recognizer: SpeechRecognizer? = null
    private var recognitionLanguage = "ru-RU"
    private var session = 0
    private var committed = ""
    private var holding = false
    private var restarts = 0
    private var preferOffline = false
    private var retriedOnline = false
    private var lastLevelUpdate = 0L
    private var finishWaiter: CompletableDeferred<String>? = null
    private var watchdog: Runnable? = null
    private var finishTimeout: Runnable? = null

    /** Начать запись (язык: "ru-RU", "en-US"). Разрешение на микрофон запрашивает экран. */
    fun startRecording(language: String) = onMain { startInternal(language) }

    private fun startInternal(language: String) {
        if (_isRecording.value || _isFinalizing.value) return
        cancelRecordingInternal()
        stopSpeaking()
        clearError()
        val russian = !language.startsWith("en")
        recognitionLanguage = language.ifBlank { "ru-RU" }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _needsPermissionSettings.value = true
            _errorMessage.value = if (russian) "Разрешите доступ к микрофону в настройках Android → Приложения → Honer AI."
            else "Allow microphone access in Android Settings → Apps → Honer AI."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _errorMessage.value = if (russian) "На телефоне нет службы распознавания речи. Установите или включите приложение Google."
            else "No speech recognition service on this phone. Install or enable the Google app."
            return
        }
        session += 1
        committed = ""
        restarts = 0
        retriedOnline = false
        holding = true
        _transcript.value = ""
        // Без интернета просим распознавание на устройстве; с интернетом — как решит служба.
        preferOffline = !isOnline(appContext)
        if (!createAndListen(session)) return
        _isRecording.value = true
        // Запись не может длиться вечно: если событие «отпустил палец» потерялось — предел 90 секунд.
        val token = session
        watchdog = Runnable { if (session == token && _isRecording.value) beginFinish() }.also { main.postDelayed(it, 90_000) }
    }

    private fun createAndListen(token: Int): Boolean {
        destroyRecognizer()
        val created = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (e: Exception) {
            null
        }
        if (created == null) {
            _errorMessage.value = text("Распознавание речи сейчас недоступно.", "Speech recognition is unavailable.")
            return false
        }
        recognizer = created
        created.setRecognitionListener(Listener(token))
        return try {
            created.startListening(recognitionIntent())
            true
        } catch (e: Exception) {
            destroyRecognizer()
            _errorMessage.value = e.localizedMessage ?: text("Микрофон недоступен.", "Microphone unavailable.")
            false
        }
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguage)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        // Диктовка: служба Google дольше ждёт паузы и сама ставит знаки препинания.
        putExtra("android.speech.extra.DICTATION_MODE", true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
    }

    private inner class Listener(private val token: Int) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (token != session || !_isRecording.value) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelUpdate < 70) return
            lastLevelUpdate = now
            _audioLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (token != session) return
            val text = firstResult(partialResults) ?: return
            if (text.isNotBlank()) _transcript.value = join(committed, text)
        }

        override fun onResults(results: Bundle?) {
            if (token != session) return
            val text = firstResult(results)
            if (!text.isNullOrBlank()) {
                committed = join(committed, text)
                _transcript.value = committed
            }
            if (holding && finishWaiter == null && restarts < 30) {
                // Пользователь ещё держит кнопку — слушаем дальше, текст копится.
                restarts += 1
                if (!createAndListen(token)) complete(token)
            } else {
                complete(token)
            }
        }

        override fun onError(error: Int) {
            if (token != session) return
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (finishWaiter != null) { complete(token); return }
            if (holding && quiet && restarts < 30) {
                restarts += 1
                if (!createAndListen(token)) complete(token)
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && restarts < 3) {
                restarts += 1
                main.postDelayed({ if (token == session && holding) { if (!createAndListen(token)) complete(token) } }, 200)
                return
            }
            val languageMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            if (preferOffline && !retriedOnline && (languageMissing || error == SpeechRecognizer.ERROR_SERVER)) {
                // Пакета языка на устройстве нет — пробуем обычное распознавание.
                retriedOnline = true
                preferOffline = false
                if (!createAndListen(token)) complete(token)
                return
            }
            if (!quiet) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) _needsPermissionSettings.value = true
                _errorMessage.value = errorText(error, languageMissing)
            }
            complete(token)
        }
    }

    private fun errorText(code: Int, languageMissing: Boolean): String = when {
        code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            text("Разрешите доступ к микрофону в настройках Android → Приложения → Honer AI.", "Allow microphone access in Android Settings → Apps → Honer AI.")
        code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || code == SpeechRecognizer.ERROR_SERVER ->
            text("Распознавание речи сейчас недоступно. Проверьте подключение и попробуйте снова.", "Speech recognition is unavailable. Check your connection and try again.")
        code == SpeechRecognizer.ERROR_AUDIO -> text("Микрофон занят другим приложением.", "The microphone is busy in another app.")
        languageMissing -> text("Этот язык распознавания не установлен на телефоне. Скачайте его в настройках голосового ввода Google.",
            "This recognition language isn't installed. Download it in Google voice typing settings.")
        code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> text("Распознавание речи занято. Попробуйте ещё раз.", "Speech recognition is busy. Try again.")
        else -> text("Не удалось распознать речь. Попробуйте ещё раз.", "Could not recognize speech. Try again.")
    }

    private fun text(russian: String, english: String) = if (recognitionLanguage.startsWith("en")) english else russian

    /** Закончить запись и вернуть распознанный текст (ждёт финальный результат, не дольше 2 секунд). */
    suspend fun stopRecording(): String = withContext(Dispatchers.Main.immediate) {
        if (!_isRecording.value && finishWaiter == null) return@withContext _transcript.value
        val waiter = finishWaiter ?: beginFinish()
        try {
            waiter.await()
        } catch (e: CancellationException) {
            cancelRecordingInternal()
            throw e
        }
    }

    private fun beginFinish(): CompletableDeferred<String> {
        finishWaiter?.let { return it }
        val waiter = CompletableDeferred<String>()
        finishWaiter = waiter
        holding = false
        _isFinalizing.value = true
        _isRecording.value = false
        _audioLevel.value = 0f
        val token = session
        try { recognizer?.stopListening() } catch (_: Exception) {}
        finishTimeout = Runnable { complete(token) }.also { main.postDelayed(it, 2_000) }
        return waiter
    }

    private fun complete(token: Int) {
        if (token != session) return
        session += 1
        holding = false
        clearTimers()
        destroyRecognizer()
        _isRecording.value = false
        _isFinalizing.value = false
        _audioLevel.value = 0f
        val text = _transcript.value
        finishWaiter?.complete(text)
        finishWaiter = null
    }

    fun cancelRecording() = onMain { cancelRecordingInternal() }

    private fun cancelRecordingInternal() {
        session += 1
        holding = false
        clearTimers()
        try { recognizer?.cancel() } catch (_: Exception) {}
        destroyRecognizer()
        _isRecording.value = false
        _isFinalizing.value = false
        _audioLevel.value = 0f
        _transcript.value = ""
        committed = ""
        finishWaiter?.complete("")
        finishWaiter = null
    }

    private fun clearTimers() {
        watchdog?.let { main.removeCallbacks(it) }; watchdog = null
        finishTimeout?.let { main.removeCallbacks(it) }; finishTimeout = null
    }

    private fun destroyRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        try { current.destroy() } catch (_: Exception) {}
    }

    fun clearError() {
        _errorMessage.value = null
        _needsPermissionSettings.value = false
    }

    // MARK: Озвучка

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var triedGoogleEngine = false
    private val pendingActions = ArrayList<(TextToSpeech) -> Unit>()
    @Volatile private var lastUtteranceId: String? = null
    private var utteranceCounter = 0
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private val choiceCache = HashMap<String, VoiceCatalog.Choice?>()

    /** Прочитать текст целиком (прерывает текущее чтение). gender: "male"/"female". */
    fun speak(text: String, gender: String, rate: Double, voiceId: String = "") {
        val spoken = SpeechText.spokenForm(SpeechText.sanitizedSpeechText(text))
        if (spoken.isEmpty()) return
        onMain {
            clearError()
            if (_isRecording.value || _isFinalizing.value) cancelRecordingInternal()
            withTts { engine ->
                lastUtteranceId = null
                try { engine.stop() } catch (_: Exception) {}
                enqueue(engine, spoken, gender, rate, voiceId, flush = true)
            }
        }
    }

    /** Дочитать следующий кусок, не прерывая звучащий (чтение во время печати ответа). */
    fun append(text: String, gender: String, rate: Double, voiceId: String = "") {
        val spoken = SpeechText.spokenForm(SpeechText.sanitizedSpeechText(text))
        if (spoken.isEmpty()) return
        onMain {
            if (!_isSpeaking.value) clearError()
            withTts { engine -> enqueue(engine, spoken, gender, rate, voiceId, flush = false) }
        }
    }

    fun stopSpeaking() = onMain {
        lastUtteranceId = null
        pendingActions.clear()
        try { tts?.stop() } catch (_: Exception) {}
        _isSpeaking.value = false
        abandonFocus()
    }

    /** Голоса для языка ("ru-RU"/"en-US") — для экрана выбора голоса; пусто, пока движок не готов. */
    fun availableVoices(language: String = "ru-RU"): List<VoiceOption> {
        val engine = tts
        if (engine == null) { onMain { ensureTts() }; return emptyList() }
        if (!ttsReady) return emptyList()
        val code = language.take(2).lowercase()
        return installedVoices(engine)
            .filter { it.locale.language.lowercase().startsWith(code) }
            .map { voice ->
                VoiceOption(
                    id = voice.name,
                    name = voice.name,
                    language = voice.locale.toLanguageTag(),
                    gender = VoiceCatalog.gender(voice.name, voice.features ?: emptySet()).raw,
                    quality = voice.quality,
                    needsNetwork = voice.isNetworkConnectionRequired,
                )
            }
            .sortedWith(compareByDescending<VoiceOption> { it.quality }.thenBy { it.needsNetwork }.thenBy { it.name })
    }

    /** Открыть установку голосов движка (докачать русские/английские голоса). */
    fun voiceDataInstallIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun withTts(action: (TextToSpeech) -> Unit) {
        val engine = ensureTts()
        if (ttsFailed) {
            _errorMessage.value = ttsUnavailableText()
            return
        }
        if (engine != null && ttsReady) action(engine) else pendingActions.add(action)
    }

    private fun ttsUnavailableText(): String =
        if (Locale.getDefault().language == "en") "Text-to-speech is unavailable on this phone. Install Speech Services by Google."
        else "Синтез речи на этом телефоне недоступен. Установите «Синтезатор речи Google»."

    private fun ensureTts(engineName: String? = null): TextToSpeech? {
        tts?.let { return it }
        ttsReady = false
        ttsFailed = false
        val created = try {
            val listener = TextToSpeech.OnInitListener { status -> main.post { onTtsInit(status) } }
            if (engineName != null) TextToSpeech(appContext, listener, engineName) else TextToSpeech(appContext, listener)
        } catch (e: Exception) {
            ttsFailed = true
            null
        }
        tts = created
        return created
    }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true
            pendingActions.clear()
            engine.shutdown()
            tts = null
            _errorMessage.value = ttsUnavailableText()
            return
        }
        // Движок по умолчанию без русского (например, старый Pico), а Google установлен — берём Google.
        val google = "com.google.android.tts"
        val russian = runCatching { engine.isLanguageAvailable(Locale("ru", "RU")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (russian < TextToSpeech.LANG_AVAILABLE && !triedGoogleEngine && engine.defaultEngine != google &&
            runCatching { engine.engines.any { it.name == google } }.getOrDefault(false)) {
            triedGoogleEngine = true
            engine.shutdown()
            tts = null
            ensureTts(google)
            return
        }
        ttsReady = true
        choiceCache.clear()
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        _voicesReady.value = true
        val actions = pendingActions.toList()
        pendingActions.clear()
        actions.forEach { it(engine) }
    }

    private fun finished(utteranceId: String?) {
        main.post {
            if (utteranceId != null && utteranceId == lastUtteranceId) {
                lastUtteranceId = null
                _isSpeaking.value = false
                abandonFocus()
            }
        }
    }

    private fun enqueue(engine: TextToSpeech, spoken: String, gender: String, rate: Double, voiceId: String, flush: Boolean) {
        requestFocus()
        val wanted = VoiceCatalog.genderFromRaw(gender)
        val speed = rate.coerceIn(0.35, 1.8).toFloat()
        val maxLength = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000).coerceIn(500, 4000) - 100
        var first = flush
        var last: String? = null
        for (segment in SpeechText.languageSegments(spoken)) {
            val language = if (segment.english) "en" else "ru"
            val choice = choose(engine, language, wanted, if (segment.english) "" else voiceId)
                ?: if (segment.english) choose(engine, "ru", wanted, voiceId) else null
            applyVoice(engine, choice, if (segment.english) Locale.US else Locale("ru", "RU"))
            engine.setSpeechRate(speed)
            engine.setPitch(choice?.pitch ?: 1f)
            for (chunk in SpeechText.chunks(segment.text, maxLength)) {
                utteranceCounter += 1
                val id = "honer-$utteranceCounter"
                val params = Bundle()
                val result = engine.speak(chunk, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
                first = false
                if (result == TextToSpeech.SUCCESS) last = id
            }
        }
        if (last != null) {
            lastUtteranceId = last
            _isSpeaking.value = true
        } else if (!_isSpeaking.value) {
            abandonFocus()
        }
    }

    private fun applyVoice(engine: TextToSpeech, choice: VoiceCatalog.Choice?, fallback: Locale) {
        val voice = choice?.let { c -> installedVoices(engine).firstOrNull { it.name == c.id } }
        val applied = voice != null && runCatching { engine.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
        if (!applied) runCatching { engine.setLanguage(fallback) }
    }

    private fun choose(engine: TextToSpeech, language: String, gender: VoiceCatalog.Gender, voiceId: String): VoiceCatalog.Choice? {
        val online = isOnline(appContext)
        val key = "$language|$gender|$voiceId|$online"
        if (choiceCache.containsKey(key)) return choiceCache[key]
        val voices = installedVoices(engine)
        val chosen = voices.firstOrNull { voiceId.isNotEmpty() && it.name == voiceId && it.locale.language.startsWith(language) }
            ?.let { VoiceCatalog.Choice(it.name, 1f) }
            ?: VoiceCatalog.choose(
                voices.map { voice ->
                    VoiceCatalog.Candidate(
                        id = voice.name,
                        language = voice.locale.language,
                        country = voice.locale.country,
                        quality = voice.quality,
                        needsNetwork = voice.isNetworkConnectionRequired,
                        gender = VoiceCatalog.gender(voice.name, voice.features ?: emptySet()),
                    )
                },
                language, gender, online,
            )
        choiceCache[key] = chosen
        return chosen
    }

    private fun installedVoices(engine: TextToSpeech): List<Voice> {
        val all = runCatching { engine.voices }.getOrNull() ?: return emptyList()
        return all.filter { voice ->
            val features = voice.features ?: emptySet()
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features
        }
    }

    // MARK: Аудиофокус: другие звуки приглушаются, пока читается ответ

    private val audioManager: AudioManager? get() = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post { stopSpeaking() }
        }
    }

    private fun requestFocus() {
        if (hasFocus) return
        val manager = audioManager ?: return
        hasFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
    }

    fun release() = onMain {
        cancelRecordingInternal()
        lastUtteranceId = null
        pendingActions.clear()
        tts?.let { engine ->
            try { engine.stop() } catch (_: Exception) {}
            try { engine.shutdown() } catch (_: Exception) {}
        }
        tts = null
        ttsReady = false
        _voicesReady.value = false
        _isSpeaking.value = false
        abandonFocus()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        /** Проза без эмодзи, разметки, ссылок и сносок. */
        fun sanitizedSpeechText(input: String): String = SpeechText.sanitizedSpeechText(input)
        /** Знаки и сокращения словами, паузы в конце строк. */
        fun spokenForm(input: String): String = SpeechText.spokenForm(input)
        fun languageSegments(text: String): List<SpeechSegment> = SpeechText.languageSegments(text)
        /** Длина готового к чтению куска в [rest] или null — ждать продолжения (для чтения во время печати). */
        fun speakableEnd(rest: String, final: Boolean): Int? = SpeechText.speakableEnd(rest, final)

        private fun join(first: String, second: String): String = when {
            first.isBlank() -> second.trim()
            second.isBlank() -> first.trim()
            else -> first.trim() + " " + second.trim()
        }

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        internal fun isOnline(context: Context): Boolean {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val network = manager.activeNetwork ?: return false
                    val caps = manager.getNetworkCapabilities(network) ?: return false
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } else {
                    @Suppress("DEPRECATION")
                    manager.activeNetworkInfo?.isConnected == true
                }
            } catch (_: Exception) {
                true
            }
        }
    }
}
