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
        return AVSpeechSynthesisVoice.speechVoices().filter { $0.language.lowercased().hasPrefix(code) }.sorted {
            if $0.quality != $1.quality { return $0.quality.rawValue > $1.quality.rawValue }
            if ($0.language == language) != ($1.language == language) { return $0.language == language }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }
    static func preferredVoice(identifier: String = "", language: String = "ru-RU") -> AVSpeechSynthesisVoice? {
        if !identifier.isEmpty, let voice = AVSpeechSynthesisVoice(identifier: identifier),
           voice.language.lowercased().hasPrefix(String(language.prefix(2)).lowercased()) { return voice }
        return availableVoices(language: language).first ?? AVSpeechSynthesisVoice(language: language)
    }
    /// - Parameter rate: множитель скорости чтения (1.0 — обычная). Настраивается в разделе «Голос».
    func speak(_ text: String, voiceIdentifier: String = "", language: String = "ru-RU", rate: Double = 0.94) {
        let spokenText = Self.sanitizedSpeechText(text)
        guard !spokenText.isEmpty else { return }
        clearError(); cancelRecording()
        activeUtterance = nil; synthesizer.stopSpeaking(at: .immediate)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
            try session.setActive(true)
            let utterance = AVSpeechUtterance(string: spokenText)
            utterance.voice = Self.preferredVoice(identifier: voiceIdentifier, language: language)
            let clamped = Float(min(max(rate, 0.35), 1.8))
            utterance.rate = min(AVSpeechUtteranceMaximumSpeechRate,
                                 max(AVSpeechUtteranceMinimumSpeechRate,
                                     AVSpeechUtteranceDefaultSpeechRate * clamped))
            utterance.preUtteranceDelay = 0.04
            activeUtterance = utterance; isSpeaking = true
            synthesizer.speak(utterance)
        } catch { activeUtterance = nil; isSpeaking = false; deactivateAudioIfIdle(); errorMessage = error.localizedDescription }
    }
    func stopSpeaking() {
        activeUtterance = nil; synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false; deactivateAudioIfIdle()
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
