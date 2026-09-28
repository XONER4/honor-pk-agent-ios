import AVFoundation
import Combine
import Speech
import UIKit

@MainActor
final class SpeechService: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var isPreparingRecording = false
    @Published private(set) var isFinalizingRecording = false
    @Published private(set) var isSpeaking = false
    @Published private(set) var transcript = ""
    @Published private(set) var audioLevel: Double = 0
    @Published var errorMessage: String?
    @Published private(set) var needsPermissionSettings = false
    private let engine = AVAudioEngine()
    private let synthesizer = AVSpeechSynthesizer()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var tapInstalled = false
    private var recordingID = UUID()
    private var lastLevelUpdate = Date.distantPast
    private var finishTimeout: Task<Void, Never>?
    /// Предел длительности одной записи: защита от потерянного события «отпустил палец».
    private var recordingWatchdog: Task<Void, Never>?
    private var finishWaiters: [CheckedContinuation<String, Never>] = []
    private var activeUtterance: AVSpeechUtterance?
    private var interruptionObserver: NSObjectProtocol?
    /// Свой голос: воспроизведение фрагментов из облака.
    private var clonePlayer: AVAudioPlayer?
    private var cloneTask: Task<Void, Never>?
    private var cloneToken = UUID()
    private var playContinuation: CheckedContinuation<Void, Never>?
    private var backgroundObserver: NSObjectProtocol?

    override init() {
        super.init()
        synthesizer.delegate = self
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let interruptionType = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            Task { @MainActor [weak self] in
                switch interruptionType {
                case .began:
                    self?.cancelRecording()
                    self?.stopSpeaking()
                case .ended:
                    // Прерывание закончилось: состояние речи и финализации могло остаться
                    // «занятым», и новая запись больше не запускалась.
                    self?.cancelRecording()
                    self?.isSpeaking = false
                    self?.activeUtterance = nil
                    self?.deactivateAudioIfIdle()
                @unknown default:
                    break
                }
            }
        }
        backgroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.cancelRecording(); self?.stopSpeaking() }
        }
    }
    deinit {
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
    }

    func startRecording(language: String = "ru-RU") async {
        guard !isRecording && !isPreparingRecording && !isFinalizingRecording else { return }
        cancelRecording()
        stopSpeaking()
        clearError()
        isPreparingRecording = true
        let token = UUID()
        recordingID = token
        // Снимаем флаг подготовки ВСЕГДА. Раньше он снимался только если токен не сменился,
        // поэтому после отмены во время запроса разрешений флаг залипал навсегда и
        // `guard` выше блокировал любую следующую запись — «Подключаю микрофон…» навсегда.
        defer { isPreparingRecording = false }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-UITestVoice") {
            isPreparingRecording = false
            isRecording = true
            audioLevel = 0.45
            transcript = "Проверка голосового ввода"
            return
        }
        #endif
        // Разрешения запрашиваем с таймаутом: системный диалог может не ответить
        // (прерван, отклонён системой), и тогда запись не начнётся никогда.
        guard await Self.requestSpeechAuthorization(timeout: 12) else {
            guard recordingID == token else { return }
            needsPermissionSettings = true
            errorMessage = language.hasPrefix("ru") ? "Разрешите распознавание речи в настройках iPhone → Honer AI." : "Allow speech recognition in iPhone Settings → Honer AI."
            return
        }
        guard recordingID == token else { return }
        let microphoneAllowed = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard recordingID == token else { return }
        guard microphoneAllowed else {
            needsPermissionSettings = true
            errorMessage = language.hasPrefix("ru") ? "Разрешите доступ к микрофону в настройках iPhone → Honer AI." : "Allow microphone access in iPhone Settings → Honer AI."
            return
        }
        guard let speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: language)), speechRecognizer.isAvailable else {
            errorMessage = language.hasPrefix("ru") ? "Распознавание речи сейчас недоступно. Проверьте подключение и попробуйте снова." : "Speech recognition is unavailable. Check your connection and try again."
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.taskHint = .dictation
            // Знаки препинания в распознанном тексте: сообщение читается как написанное.
            request.addsPunctuation = true
            recognizer = speechRecognizer
            recognitionRequest = request
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { throw SpeechFailure.noInput }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                // Буферы не должны уходить в уже отменённый запрос: иначе распознавание
                // молчит, а состояние записи остаётся включённым.
                guard let self, self.isRecording, self.recordingID == token else { return }
                request.append(buffer)
                guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return }
                var sum: Float = 0
                for index in 0..<Int(buffer.frameLength) { sum += samples[index] * samples[index] }
                let rms = sqrt(Double(sum) / Double(buffer.frameLength))
                let level = min(1, max(0, (20 * log10(max(rms, 0.00001)) + 55) / 55))
                Task { @MainActor in
                    // self здесь уже развёрнут guard-ом выше (weak self + guard let self).
                    guard self.recordingID == token, self.isRecording,
                          Date().timeIntervalSince(self.lastLevelUpdate) >= 0.07 else { return }
                    self.lastLevelUpdate = Date()
                    self.audioLevel = level
                }
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            isPreparingRecording = false
            isRecording = true
            // Запись не может длиться вечно: если пользователь отпустил палец, а событие
            // потерялось, приложение выходило из записи только вручную. Теперь есть предел.
            recordingWatchdog?.cancel()
            recordingWatchdog = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 90_000_000_000)
                guard !Task.isCancelled, let self, self.recordingID == token else { return }
                self.recognitionRequest?.endAudio()
                self.completeRecording(token: token)
            }
            recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, error in
                Task { @MainActor in
                    // Раньше здесь стояла проверка токена, и терминальный колбэк
                    // отбрасывался после stopCapture/finish: состояние оставалось «в записи».
                    guard let self else { return }
                    if let result { self.transcript = result.bestTranscription.formattedString }
                    if result?.isFinal == true {
                        self.completeRecording(token: self.recordingID)
                    } else if let error {
                        if !self.isFinalizingRecording { self.errorMessage = error.localizedDescription }
                        self.completeRecording(token: self.recordingID)
                    }
                }
            }
        } catch {
            // При ошибке снимаем состояние безусловно: токен мог уже смениться.
            stopCapture()
            recordingWatchdog?.cancel(); recordingWatchdog = nil
            finishTimeout?.cancel(); finishTimeout = nil
            recognitionRequest?.endAudio(); recognitionTask?.cancel()
            recognitionTask = nil; recognitionRequest = nil; recognizer = nil
            isPreparingRecording = false; isFinalizingRecording = false
            resolveWaiters(with: transcript)
            deactivateAudioIfIdle()
            errorMessage = error.localizedDescription
        }
    }

    /// Запрос разрешения на распознавание с таймаутом.
    private static func requestSpeechAuthorization(timeout: TimeInterval) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await withCheckedContinuation { continuation in
                    SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return false
            }
            let result = await group.next() ?? false
            group.cancelAll()
            return result
        }
    }

    /// Stop the microphone immediately, then await final words for no longer than 1.2 seconds.
    func finishRecording() async -> String {
        if isPreparingRecording { cancelRecording(); return "" }
        guard isRecording || isFinalizingRecording else { return transcript }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-UITestVoice") {
            let text = transcript
            completeRecording(token: recordingID)
            return text
        }
        #endif
        let token = recordingID
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                finishWaiters.append(continuation)
                guard !isFinalizingRecording else { return }
                isFinalizingRecording = true
                stopCapture()
                recognitionRequest?.endAudio()
                finishTimeout = Task { @MainActor [weak self] in
                    try? await Task.sleep(nanoseconds: 1_200_000_000)
                    guard !Task.isCancelled else { return }
                    self?.completeRecording(token: token)
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                guard self?.recordingID == token else { return }
                self?.cancelRecording()
            }
        }
    }
    /// Compatibility for tap controls. Hold release should await finishRecording().
    func stopRecording() { Task { @MainActor in _ = await finishRecording() } }
    /// Swipe cancellation and navigation invalidate every pending recognition callback.
    func cancelRecording() {
        recordingID = UUID()
        // Сначала гасим запись и задачу распознавания, потом снимаем движок:
        // иначе буферы успевали уйти в отменённый запрос.
        recognitionRequest?.endAudio(); recognitionTask?.cancel()
        stopCapture()
        recordingWatchdog?.cancel(); recordingWatchdog = nil
        finishTimeout?.cancel(); finishTimeout = nil
        recognitionTask = nil; recognitionRequest = nil; recognizer = nil
        isPreparingRecording = false; isFinalizingRecording = false
        transcript = ""
        resolveWaiters(with: "")
        deactivateAudioIfIdle()
    }
    private func stopCapture() {
        engine.stop()
        if tapInstalled { engine.inputNode.removeTap(onBus: 0); tapInstalled = false }
        isRecording = false; audioLevel = 0
    }
    private func completeRecording(token: UUID) {
        guard recordingID == token else { return }
        recordingID = UUID()
        recordingWatchdog?.cancel(); recordingWatchdog = nil
        stopCapture()
        finishTimeout?.cancel(); finishTimeout = nil
        recognitionTask?.cancel(); recognitionTask = nil; recognitionRequest = nil; recognizer = nil
        isPreparingRecording = false; isFinalizingRecording = false
        resolveWaiters(with: transcript)
        deactivateAudioIfIdle()
    }
    private func resolveWaiters(with text: String) {
        let waiters = finishWaiters
        finishWaiters.removeAll()
        for waiter in waiters { waiter.resume(returning: text) }
    }
    private func deactivateAudioIfIdle() {
        if activeUtterance == nil && !isRecording && !isPreparingRecording {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    static func availableVoices(language: String = "ru-RU") -> [AVSpeechSynthesisVoice] {
        let code = String(language.prefix(2)).lowercased()
        return AVSpeechSynthesisVoice.speechVoices().filter { $0.language.lowercased().hasPrefix(code) && VoiceCatalog.isUsable($0) }.sorted {
            if $0.quality != $1.quality { return $0.quality.rawValue > $1.quality.rawValue }
            if ($0.language == language) != ($1.language == language) { return $0.language == language }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }
    static func preferredVoice(identifier: String = "", language: String = "ru-RU",
                               gender: VoiceCatalog.Gender? = nil) -> AVSpeechSynthesisVoice? {
        if !identifier.isEmpty, let voice = AVSpeechSynthesisVoice(identifier: identifier),
           voice.language.lowercased().hasPrefix(String(language.prefix(2)).lowercased()) { return voice }
        if let gender, let voice = VoiceCatalog.bestVoice(language: language, gender: gender) { return voice }
        return availableVoices(language: language).first ?? AVSpeechSynthesisVoice(language: language)
    }

    /// Читает текст вслух. Русские части читает русский голос, английские —
    /// английский того же пола: раньше один русский голос читал и английские слова,
    /// и половина слов звучала неправильно.
    /// - Parameter rate: множитель скорости чтения (1.0 — обычная). Настраивается в разделе «Голос».
    func speak(_ text: String, voiceIdentifier: String = "", gender: VoiceCatalog.Gender = .male,
               language: String = "ru-RU", rate: Double = 0.95) {
        let spokenText = Self.spokenForm(Self.sanitizedSpeechText(text))
        guard !spokenText.isEmpty else { return }
        clearError(); cancelRecording()
        activeUtterance = nil; synthesizer.stopSpeaking(at: .immediate)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
            try session.setActive(true)
            let russian = Self.preferredVoice(identifier: voiceIdentifier, language: language, gender: gender)
            let english = (UserDefaults.standard.bool(forKey: "honor.personalVoiceEnglish") ? Self.personalVoice() : nil)
                ?? VoiceCatalog.bestVoice(language: "en", gender: gender)
            let clamped = Float(min(max(rate, 0.35), 1.8))
            let speed = min(AVSpeechUtteranceMaximumSpeechRate,
                            max(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * clamped))
            var last: AVSpeechUtterance?
            for segment in Self.languageSegments(spokenText) {
                let utterance = AVSpeechUtterance(string: segment.text)
                utterance.voice = segment.english ? (english ?? russian) : russian
                utterance.rate = speed
                utterance.pitchMultiplier = 1.0
                utterance.preUtteranceDelay = last == nil ? 0.04 : 0
                synthesizer.speak(utterance)
                last = utterance
            }
            activeUtterance = last; isSpeaking = last != nil
        } catch { activeUtterance = nil; isSpeaking = false; deactivateAudioIfIdle(); errorMessage = error.localizedDescription }
    }

    /// Текст делится на куски по языку: латиница — английский голос, остальное — русский.
    /// Числа и знаки остаются в текущем куске.
    nonisolated static func languageSegments(_ text: String) -> [(text: String, english: Bool)] {
        guard let expression = try? NSRegularExpression(pattern: "\\S+\\s*") else { return [(text, false)] }
        var segments: [(text: String, english: Bool)] = []
        var current = ""
        var currentEnglish: Bool?
        for match in expression.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let range = Range(match.range, in: text) else { continue }
            let token = String(text[range])
            let hasCyrillic = token.unicodeScalars.contains { (0x0400...0x04FF).contains(Int($0.value)) }
            let hasLatin = token.unicodeScalars.contains { ($0.value >= 65 && $0.value <= 90) || ($0.value >= 97 && $0.value <= 122) }
            let kind: Bool? = hasCyrillic ? false : (hasLatin ? true : nil)
            if let kind, let active = currentEnglish, kind != active, !current.isEmpty {
                segments.append((current, active))
                current = ""
            }
            if let kind { currentEnglish = kind }
            current += token
        }
        if !current.isEmpty { segments.append((current, currentEnglish ?? false)) }
        return segments.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// Текст для чтения по-русски: знаки и сокращения произносятся словами,
    /// строки заканчиваются паузой.
    nonisolated static func spokenForm(_ input: String) -> String {
        var text = input
        func replace(_ pattern: String, _ replacement: String) {
            guard let expression = try? NSRegularExpression(pattern: pattern) else { return }
            text = expression.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: replacement)
        }
        replace("\\s?°\\s?[CС]\\b", " градусов Цельсия")
        replace("\\s?°", " градусов")
        replace("\\s?%", " процентов")
        replace("\\s?₽", " рублей")
        replace("\\s?€", " евро")
        replace("\\$\\s?(\\d[\\d\\s.,]*\\d|\\d)", "$1 долларов")
        replace("\\s?\\$", " долларов")
        replace("(?i)\\bкм/ч\\b", "километров в час")
        replace("(?i)\\bм/с\\b", "метров в секунду")
        replace("(?i)\\bт\\.\\s?е\\.", "то есть")
        replace("(?i)\\bт\\.\\s?д\\.", "так далее")
        replace("(?i)\\bт\\.\\s?п\\.", "тому подобное")
        replace("(?i)\\bнапр\\.", "например")
        replace("(?i)\\bтыс\\.", "тысяч")
        replace("(?i)\\bмлн\\b\\.?", "миллионов")
        replace("(?i)\\bмлрд\\b\\.?", "миллиардов")
        replace("(\\d)\\s?[–—]\\s?(\\d)", "$1 до $2")
        replace("\\s?×\\s?", " умножить на ")
        replace("\\s?≈\\s?", " примерно ")
        replace("\\s?±\\s?", " плюс-минус ")
        replace("\\s?(→|⇒)\\s?", ", ")
        replace("\\s=\\s", " равно ")
        // Пауза в конце каждой строки: заголовки и пункты списка не сливаются.
        text = text.components(separatedBy: "\n").map { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard let last = trimmed.last, !".!?…:;,".contains(last) else { return trimmed }
            return trimmed + "."
        }.joined(separator: "\n")
        replace("[ \\t]{2,}", " ")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    func stopSpeaking() {
        activeUtterance = nil; synthesizer.stopSpeaking(at: .immediate)
        cloneToken = UUID()
        cloneTask?.cancel(); cloneTask = nil
        clonePlayer?.stop(); clonePlayer = nil
        playContinuation?.resume(); playContinuation = nil
        isSpeaking = false; deactivateAudioIfIdle()
    }

    /// «Личный голос» Apple (iOS 17+), если пользователь разрешил его использовать.
    static func personalVoice() -> AVSpeechSynthesisVoice? {
        guard #available(iOS 17.0, *) else { return nil }
        return AVSpeechSynthesisVoice.speechVoices().first { $0.voiceTraits.contains(.isPersonalVoice) }
    }

    /// Запросить доступ к «Личному голосу» Apple.
    static func requestPersonalVoice() async -> Bool {
        guard #available(iOS 17.0, *) else { return false }
        let status = await withCheckedContinuation { continuation in
            AVSpeechSynthesizer.requestPersonalVoiceAuthorization { continuation.resume(returning: $0) }
        }
        return status == .authorized
    }

    /// Прочитать текст своим (клонированным) голосом. Длинный текст звучит частями:
    /// следующая часть готовится, пока играет текущая.
    func speakCloned(_ text: String, voiceID: String, apiKey: String, fallbackGender: VoiceCatalog.Gender = .male,
                     rate: Double = 0.95) {
        let parts = FishAudio.chunks(Self.spokenForm(Self.sanitizedSpeechText(text)))
        stopSpeaking()
        clearError(); cancelRecording()
        guard !parts.isEmpty, !voiceID.isEmpty, !apiKey.isEmpty else { return }
        let token = UUID()
        cloneToken = token
        isSpeaking = true
        cloneTask = Task { @MainActor [weak self] in
            var next: Task<Data, Error>? = Task { try await FishAudio.synthesize(text: parts[0], voiceID: voiceID, apiKey: apiKey) }
            for index in parts.indices {
                guard let self, self.cloneToken == token, let current = next else { return }
                let data: Data
                do {
                    data = try await current.value
                } catch {
                    guard self.cloneToken == token else { return }
                    self.isSpeaking = false
                    if index == 0 {
                        // Свой голос сейчас недоступен — читаем обычным, чтобы ответ не остался без звука.
                        self.errorMessage = error.localizedDescription
                        self.speak(text, gender: fallbackGender, rate: rate)
                    }
                    return
                }
                next = index + 1 < parts.count
                    ? Task { try await FishAudio.synthesize(text: parts[index + 1], voiceID: voiceID, apiKey: apiKey) }
                    : nil
                guard self.cloneToken == token else { return }
                await self.play(data, token: token)
            }
            guard let self, self.cloneToken == token else { return }
            self.isSpeaking = false
            self.clonePlayer = nil
            self.deactivateAudioIfIdle()
        }
    }

    private func play(_ data: Data, token: UUID) async {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
            try session.setActive(true)
            let player = try AVAudioPlayer(data: data)
            player.delegate = self
            clonePlayer = player
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                playContinuation = continuation
                if !player.play() { playContinuation?.resume(); playContinuation = nil }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
    func clearError() { errorMessage = nil; needsPermissionSettings = false }

    /// Speak prose, omitting decorative emoji, Markdown controls, URLs and citation markers.
    nonisolated static func sanitizedSpeechText(_ input: String) -> String {
        var text = input
        func replace(_ pattern: String, _ replacement: String = " ") {
            guard let expression = try? NSRegularExpression(pattern: pattern) else { return }
            text = expression.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: replacement)
        }
        replace(#"(?s)```.*?```|~~~.*?~~~"#)
        replace(#"\uE200[^\uE201]*\uE201"#)
        replace(#"\[\s*\d+(?:\s*[,–\-]\s*\d+)*\s*\]\([^\)]+\)"#)
        replace(#"!?\[([^\]]*)\]\([^\)]+\)"#, "$1")
        replace(#"\[\s*\d+(?:\s*[,–\-]\s*\d+)*\s*\]"#)
        replace(#"(?i)\b(?:https?://|www\.)[^\s<>]+"#)
        replace(#"(?m)^\s{0,3}(?:#{1,6}\s*|>\s*|[-+*]\s+)"#, "")
        replace(#"(?m)^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)*\|?\s*$"#, "")
        replace(#"(\*\*|__)(.*?)\1"#, "$2")
        replace(#"(?<!\w)[*_]([^*_\n]+)[*_](?!\w)"#, "$1")
        replace(#"~~(.*?)~~"#, "$1")
        replace(#"`([^`]+)`"#, "$1")
        replace(#"\\([\\`*_{}\[\]()#+.!>\-])"#, "$1")
        text = text.map { character in
            let emoji = character.unicodeScalars.contains {
                $0.properties.isEmojiPresentation || $0.value == 0xFE0F || $0.value == 0x20E3 || ($0.properties.isEmoji && $0.value > 0x238C)
            }
            return emoji ? " " : String(character)
        }.joined()
        replace(#"\s*\|\s*"#, ", ")
        replace(#"[ \t]{2,}"#, " ")
        replace(#"[ \t]*\n[ \t]*"#, "\n")
        replace(#"\n{3,}"#, "\n\n")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private enum SpeechFailure: LocalizedError {
        case noInput
        var errorDescription: String? { "Микрофон недоступен / Microphone unavailable." }
    }
}

extension SpeechService: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self, self.clonePlayer === player else { return }
            self.playContinuation?.resume()
            self.playContinuation = nil
        }
    }
}

extension SpeechService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.activeUtterance === utterance else { return }
            self.activeUtterance = nil; self.isSpeaking = false; self.deactivateAudioIfIdle()
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.activeUtterance === utterance else { return }
            self.activeUtterance = nil; self.isSpeaking = false; self.deactivateAudioIfIdle()
        }
    }
}
