package com.honerai.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.honerai.app.device.ParentalControl
import com.honerai.app.device.SpeechService
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// «Голос» (порт VoiceSettingsPage) и «Мой голос» (порт VoiceClonePage).

/** Голос синтезатора речи Android, пригодный для чтения (установлен, русский или английский). */
internal data class TtsVoiceOption(val id: String, val language: String, val quality: Int, val network: Boolean)

internal fun qualityTitle(quality: Int, english: Boolean): String = when {
    quality >= Voice.QUALITY_VERY_HIGH -> if (english) "Premium" else "Премиум"
    quality >= Voice.QUALITY_HIGH -> if (english) "Enhanced" else "Улучшенный"
    else -> if (english) "Standard" else "Стандартный"
}

/** Голоса синтезатора (TextToSpeech.getVoices) — загружаются один раз на странице. */
@Composable
private fun rememberTtsVoices(): List<TtsVoiceOption>? {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<TtsVoiceOption>?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            voices = if (status != TextToSpeech.SUCCESS || tts == null) emptyList() else runCatching {
                tts.voices.orEmpty()
                    .filter { it.locale.language == "ru" || it.locale.language == "en" }
                    .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    .map { TtsVoiceOption(it.name, it.locale.language, it.quality, it.isNetworkConnectionRequired) }
                    .sortedWith(compareBy<TtsVoiceOption>({ it.language }, { -it.quality }, { it.network }, { it.id }))
            }.getOrDefault(emptyList())
        }
        onDispose { runCatching { engine?.shutdown() } }
    }
    return voices
}

@Composable
internal fun VoiceSettingsPage(onBack: () -> Unit, push: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    val colors = HonerTheme.colors
    val gender by settings.voiceGender.collectAsState()
    val rate by settings.voiceRate.collectAsState()
    val voiceId by settings.voiceIdentifier.collectAsState()
    val speech = remember { SpeechService(context) }
    val speaking by speech.isSpeaking.collectAsState()
    val speechError by speech.errorMessage.collectAsState()
    val voices = rememberTtsVoices()
    val clonedId by VoiceCloneStore.clonedVoiceIdFlow.collectAsState()
    val useCloned by VoiceCloneStore.useClonedVoiceFlow.collectAsState()
    var rateSlider by remember { mutableFloatStateOf(rate.toFloat()) }
    val english = settings.isEnglish
    fun t(ru: String, en: String) = settings.text(ru, en)
    LaunchedEffect(Unit) { VoiceCloneStore.load(context) }
    DisposableEffect(Unit) { onDispose { speech.stopSpeaking(); speech.release() } }

    fun activeLine(language: String): String {
        val list = voices ?: return "…"
        val chosen = list.firstOrNull { it.id == voiceId && it.language == language }
        val voice = chosen ?: list.filter { it.language == language }.maxWithOrNull(compareBy({ it.quality }, { !it.network }))
            ?: return t("нет голоса", "no voice")
        val prefix = if (chosen == null) t("автоматически: ", "automatic: ") else ""
        return prefix + voice.id + " · " + qualityTitle(voice.quality, english)
    }
    val needsBetterVoice = voices != null && voices.none { it.language == "ru" && it.quality >= Voice.QUALITY_HIGH }

    SettingsPageScaffold(t("Голос", "Voice"), "settings.page.voice", onBack) {
        item(key = "voice") {
            SettingsGroup(
                t("Голос озвучки", "Speaking voice"),
                footer = t("Русский текст читает русский голос, английские слова — английский голос того же пола.",
                    "Russian text is read by a Russian voice, English words by an English voice of the same gender."),
            ) {
                // «Послушать голос» — первой строкой, чтобы сразу услышать выбор.
                SettingsButtonRow(
                    if (speaking) t("Остановить", "Stop") else t("Послушать голос", "Preview voice"),
                    if (speaking) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, tag = "voice.preview",
                ) {
                    if (speaking) speech.stopSpeaking()
                    else speech.speak(
                        t("Привет! Я Honer AI, твой личный помощник. Сегодня 25 °C, и я могу читать по-английски: Hello, how are you today?",
                            "Hi! I'm Honer AI, your personal assistant. It's 25 °C today, and I can read Russian too: Привет, как дела?"),
                        gender, rate, voiceId,
                    )
                }
                SettingsDivider(16)
                SegmentedPicker(
                    listOf("male" to t("Мужской", "Male"), "female" to t("Женский", "Female")), gender,
                    { value ->
                        // Смена пола сбрасывает ручной выбор голоса: дальше голос подбирается сам.
                        speech.stopSpeaking()
                        settings.setVoiceGender(value)
                        settings.setVoiceIdentifier("")
                    },
                    tag = "voice.gender",
                )
                SettingsDivider(16)
                SettingsValueRow(t("Русский", "Russian"), activeLine("ru"), "voice.active.ru")
                SettingsDivider(16)
                SettingsValueRow(t("Английский", "English"), activeLine("en"), "voice.active.en")
            }
        }
        item(key = "own") {
            SettingsGroup(
                t("Свой голос", "Your own voice"),
                footer = t("«Мой голос» клонирует ваш голос для русского и английского (сервис Fish Audio, нужен ключ).",
                    "“My voice” clones your voice for Russian and English (Fish Audio key required)."),
            ) {
                SettingsRow(
                    Icons.Outlined.RecordVoiceOver, t("Мой голос", "My voice"),
                    when {
                        clonedId.isEmpty() -> t("не создан", "not created")
                        useCloned -> t("включён", "on")
                        else -> t("выключен", "off")
                    },
                    tag = "voice.clone.link",
                ) { push(SettingsPage.VOICE_CLONE) }
            }
        }
        if (needsBetterVoice) {
            item(key = "download") {
                SettingsGroup(
                    footer = t(
                        "Для живого, не «роботного» звучания скачайте голос высокого качества: настройки Android → Система → Язык и ввод → Синтез речи → Установить голосовые данные → Русский и English. После загрузки голос выберется сам.",
                        "For natural speech download a high-quality voice: Android settings → System → Languages & input → Text-to-speech → Install voice data → Russian and English.",
                    ),
                ) {
                    SettingsButtonRow(t("Скачать голоса", "Download voices"), Icons.Outlined.Download, tag = "voice.download.hint") {
                        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(install) }.onFailure {
                            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }
        }
        item(key = "reading") {
            SettingsGroup(t("Голос для чтения", "Reading voice")) {
                SettingsCheckRow(t("Лучший доступный", "Best available"), voiceId.isEmpty(), tag = "voice.option.system") {
                    speech.stopSpeaking(); settings.setVoiceIdentifier("")
                }
                when {
                    voices == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
                    }
                    else -> voices.filter { it.language == "ru" }.forEach { voice ->
                        SettingsDivider(16)
                        SettingsCheckRow(
                            voice.id, voiceId == voice.id, tag = "voice.option.${voice.id}",
                            subtitle = qualityTitle(voice.quality, english) + " · " + if (voice.network) t("через интернет", "online") else t("без интернета", "offline"),
                        ) { speech.stopSpeaking(); settings.setVoiceIdentifier(voice.id) }
                    }
                }
            }
        }
        item(key = "rate") {
            SettingsGroup(t("Скорость чтения", "Reading speed")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Скорость", "Speed"), color = colors.foreground, fontSize = 16.sp)
                    Slider(
                        value = rateSlider,
                        onValueChange = { rateSlider = it; settings.setVoiceRate((it * 20).roundToInt() / 20.0) },
                        valueRange = 0.5f..1.6f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = colors.accent, inactiveTrackColor = colors.divider),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp).testTag("voice.rate"),
                    )
                    Text("%.2f×".format(Locale.US, rate), color = colors.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                }
            }
        }
        item(key = "notes") {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Автоматическое чтение включается кнопкой динамика вверху чата.", "Turn automatic reading on or off with the speaker button at the top of the chat."), color = colors.foreground, fontSize = 15.sp)
                    Text(
                        t("Голоса берутся из синтезатора речи Android. Новые голоса появятся здесь после загрузки в настройках синтеза речи.",
                            "Voices come from the Android text-to-speech engine. New voices appear here after you download them in text-to-speech settings."),
                        color = colors.secondary, fontSize = 13.sp,
                    )
                }
            }
        }
        speechError?.let { error -> item(key = "error") { SettingsFootnote(error) } }
    }
}

@Composable
internal fun VoiceClonePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val parental by ParentalControl.state.collectAsState()
    val clonedId by VoiceCloneStore.clonedVoiceIdFlow.collectAsState()
    val useCloned by VoiceCloneStore.useClonedVoiceFlow.collectAsState()
    var apiKey by remember { mutableStateOf(VoiceCloneStore.apiKey(context)) }
    val recorder = remember { VoiceSampleRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableDoubleStateOf(0.0) }
    var level by remember { mutableDoubleStateOf(0.0) }
    var hasSample by remember { mutableStateOf(VoiceCloneStore.sampleFile(context).isFile) }
    var consent by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val english = settings.isEnglish
    fun t(ru: String, en: String) = settings.text(ru, en)
    LaunchedEffect(Unit) { VoiceCloneStore.load(context) }

    fun releasePlayer() { player?.runCatching { stop(); release() }; player = null }
    fun stopPlayer() { releasePlayer(); previewing = false }
    fun play(file: File, onDone: () -> Unit = {}) {
        releasePlayer()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { onDone(); stopPlayer() }
                prepare()
                start()
            }
        }.onFailure { message = it.localizedMessage; stopPlayer() }
    }
    fun stopRecording() {
        if (!recording) return
        seconds = recorder.stop()
        recording = false
        level = 0.0
        hasSample = seconds >= 1 && VoiceCloneStore.sampleFile(context).isFile
    }
    DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.stop(); stopPlayer() } }
    // Секундомер и индикатор громкости во время записи; через 2 минуты запись останавливается сама.
    LaunchedEffect(recording) {
        while (recording) {
            seconds = recorder.seconds
            level = recorder.level
            if (seconds >= 120) stopRecording()
            delay(100)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            stopPlayer()
            recorder.start()?.let { message = it } ?: run { recording = true; hasSample = false; seconds = 0.0 }
        } else message = t("Разрешите доступ к микрофону: Настройки → Разрешения.", "Allow microphone access: Settings → Permissions.")
    }

    SettingsPageScaffold(t("Мой голос", "My voice"), "settings.page.clone", onBack) {
        if (!parental.rules.canCloneVoice) {
            item(key = "blocked") {
                SettingsGroup {
                    Text(
                        t("Клонирование голоса выключено родителем.", "Voice cloning is turned off by a parent."),
                        color = colors.secondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp).testTag("clone.blocked"),
                    )
                }
            }
            return@SettingsPageScaffold
        }
        item(key = "key") {
            SettingsGroup(t("1. Ключ сервиса", "1. Service key")) {
                Text(
                    t("Honer AI сможет читать ответы вашим собственным голосом — по-русски и по-английски. Голос создаёт сервис Fish Audio по записи 30–60 секунд. Нужен ваш ключ Fish Audio: зарегистрируйтесь на fish.audio → API Keys → создайте ключ.",
                        "Honer AI can read answers in your own voice. Fish Audio creates it from a 30–60 second recording; you need your Fish Audio API key."),
                    color = colors.foreground, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
                SettingsDivider(16)
                Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (apiKey.isEmpty()) Text(t("Ключ Fish Audio", "Fish Audio API key"), color = colors.secondary, fontSize = 16.sp)
                    BasicTextField(
                        value = apiKey, onValueChange = { apiKey = it.trim(); VoiceCloneStore.setApiKey(context, it) }, singleLine = true,
                        textStyle = TextStyle(color = colors.foreground, fontSize = 16.sp), cursorBrush = SolidColor(colors.accent),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("clone.key"),
                    )
                }
                SettingsDivider(16)
                SettingsButtonRow(t("Открыть fish.audio", "Open fish.audio"), Icons.AutoMirrored.Outlined.OpenInNew) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fish.audio")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        item(key = "record") {
            SettingsGroup(
                t("2. Прочитайте текст вслух (30–60 секунд)", "2. Read the text aloud (30–60 s)"),
                footer = t("Говорите в тихом месте, спокойно и чётко, как обычно разговариваете.", "Speak in a quiet place, calmly and clearly."),
            ) {
                Text(FishAudio.SCRIPT, color = colors.foreground, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp).testTag("clone.script"))
                SettingsDivider(16)
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.clip(CircleShape).background(if (recording) DestructiveRed else colors.accent)
                            .clickableRow {
                                if (recording) stopRecording()
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    stopPlayer()
                                    recorder.start()?.let { message = it } ?: run { recording = true; hasSample = false; seconds = 0.0 }
                                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("clone.record"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        androidx.compose.material3.Icon(if (recording) Icons.Outlined.StopCircle else Icons.Outlined.FiberManualRecord, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text(if (recording) t("Стоп", "Stop") else t("Записать", "Record"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        t("${seconds.toInt()} с", "${seconds.toInt()} s"), fontFamily = FontFamily.Monospace, fontSize = 16.sp,
                        color = if (seconds >= 20) SuccessGreen else colors.secondary,
                    )
                    if (recording) Box(Modifier.width((60 * level + 4).dp).height(6.dp).clip(CircleShape).background(colors.accent))
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    if (hasSample && !recording) {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.PlayCircle, contentDescription = t("Прослушать запись", "Play recording"), tint = colors.accent,
                            modifier = Modifier.size(34.dp).clip(CircleShape).clickableRow { play(VoiceCloneStore.sampleFile(context)) },
                        )
                    }
                }
            }
        }
        item(key = "create") {
            SettingsGroup(t("3. Создание голоса", "3. Create the voice")) {
                SettingsToggleRow(t("Это мой голос, и я согласен(а) на его клонирование", "This is my own voice and I consent to cloning it"), consent, { consent = it }, tag = "clone.consent")
                SettingsDivider(16)
                SettingsButtonRow(
                    if (clonedId.isEmpty()) t("Создать мой голос", "Create my voice") else t("Пересоздать мой голос", "Recreate my voice"),
                    enabled = !working && consent && hasSample && seconds >= 20 && apiKey.isNotEmpty(), tag = "clone.create",
                    trailing = if (working) ({ CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp) }) else null,
                ) {
                    working = true
                    message = null
                    scope.launch {
                        val key = apiKey.trim()
                        val old = clonedId
                        val name = settings.displayName.value.ifEmpty { "мой голос" }
                        runCatching { FishAudio.createVoice(key, VoiceCloneStore.sampleFile(context), FishAudio.SCRIPT, "Honer AI — $name") }
                            .onSuccess { id ->
                                VoiceCloneStore.setClonedVoice(context, id, true)
                                if (old.isNotEmpty()) FishAudio.deleteVoice(old, key)
                                message = t("Готово! Теперь ответы будут звучать вашим голосом.", "Done! Answers will now use your voice.")
                            }
                            .onFailure { message = FishAudio.describe(it, english) }
                        working = false
                    }
                }
            }
        }
        if (clonedId.isNotEmpty()) {
            item(key = "ready") {
                SettingsGroup(t("Мой голос готов", "My voice is ready")) {
                    SettingsToggleRow(t("Читать ответы моим голосом", "Read answers in my voice"), useCloned, { VoiceCloneStore.setUseClonedVoice(context, it) }, tag = "clone.use")
                    SettingsDivider(16)
                    SettingsButtonRow(
                        if (previewing) t("Остановить", "Stop") else t("Послушать мой голос", "Listen to my voice"), tag = "clone.preview",
                        trailing = if (previewing && player == null) ({ CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp) }) else null,
                    ) {
                        if (previewing) { stopPlayer(); return@SettingsButtonRow }
                        previewing = true
                        scope.launch {
                            runCatching {
                                val audio = FishAudio.synthesize("Привет! Теперь я говорю твоим голосом. Hello, this is my voice.", clonedId, apiKey.trim())
                                withContext(Dispatchers.IO) { File(context.cacheDir, "cloned-preview.mp3").apply { writeBytes(audio) } }
                            }.onSuccess { file -> if (previewing) play(file) }
                                .onFailure { previewing = false; message = FishAudio.describe(it, english) }
                        }
                    }
                    SettingsDivider(16)
                    SettingsButtonRow(t("Удалить мой голос", "Delete my voice"), destructive = true) {
                        val id = clonedId
                        val key = apiKey.trim()
                        stopPlayer()
                        VoiceCloneStore.setClonedVoice(context, "", false)
                        scope.launch { FishAudio.deleteVoice(id, key) }
                    }
                }
            }
        }
        message?.let { text ->
            item(key = "message") {
                SettingsGroup { Text(text, color = colors.foreground, fontSize = 14.sp, modifier = Modifier.padding(16.dp).testTag("clone.message")) }
            }
        }
    }
}
