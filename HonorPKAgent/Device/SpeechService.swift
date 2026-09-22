import AVFoundation
import Combine
import Speech

@MainActor
final class SpeechService: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var isSpeaking = false
    @Published private(set) var transcript = ""
    @Published var errorMessage: String?

    private let engine = AVAudioEngine()
    private let synthesizer = AVSpeechSynthesizer()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var tapInstalled = false
    private var recordingID = UUID()
    private var preparingRecording = false
    private var interruptionObserver: NSObjectProtocol?

    override init() {
        super.init()
        synthesizer.delegate = self
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.stopRecording()
                self?.stopSpeaking()
            }
        }
    }

    deinit {
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
    }

    func startRecording(language: String = "ru-RU") async {
        guard !isRecording && !preparingRecording else { return }
        preparingRecording = true
        defer { preparingRecording = false }
        errorMessage = nil
        stopSpeaking()
        let token = UUID()
        recordingID = token
        let authorized = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
        guard recordingID == token else { return }
        guard authorized else {
            errorMessage = language.hasPrefix("ru")
                ? "Разрешите распознавание речи в настройках iPhone → Honor PK Agent."
                : "Allow speech recognition in iPhone Settings → Honor PK Agent."
            return
        }
        let microphoneAllowed = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { allowed in
                continuation.resume(returning: allowed)
            }
        }
        guard recordingID == token else { return }
        guard microphoneAllowed else {
            errorMessage = language.hasPrefix("ru")
                ? "Разрешите доступ к микрофону в настройках iPhone → Honor PK Agent."
                : "Allow microphone access in iPhone Settings → Honor PK Agent."
            return
        }
        guard let speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: language)), speechRecognizer.isAvailable else {
            errorMessage = language.hasPrefix("ru")
                ? "Распознавание речи сейчас недоступно. Проверьте подключение и попробуйте снова."
                : "Speech recognition is unavailable. Check your connection and try again."
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker, .allowBluetooth])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.taskHint = .dictation
            recognizer = speechRecognizer
            recognitionRequest = request
            transcript = ""
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                throw SpeechFailure.noInput
            }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            isRecording = true
            recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, error in
                Task { @MainActor in
                    guard let self, self.recordingID == token else { return }
                    if let result { self.transcript = result.bestTranscription.formattedString }
                    if result?.isFinal == true {
                        self.stopRecording()
                    } else if let error {
                        if self.isRecording { self.errorMessage = error.localizedDescription }
                        self.stopRecording()
                    }
                }
            }
        } catch {
            stopRecording()
            errorMessage = error.localizedDescription
        }
    }

    func stopRecording() {
        recordingID = UUID()
        engine.stop()
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        recognizer = nil
        isRecording = false
        if !synthesizer.isSpeaking {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func speak(_ text: String, voiceIdentifier: String = "", language: String = "ru-RU") {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        stopRecording()
        synthesizer.stopSpeaking(at: .immediate)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
            try session.setActive(true)
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = voiceIdentifier.isEmpty ? AVSpeechSynthesisVoice(language: language) : AVSpeechSynthesisVoice(identifier: voiceIdentifier)
            if utterance.voice == nil { utterance.voice = AVSpeechSynthesisVoice(language: language) }
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate
            isSpeaking = true
            synthesizer.speak(utterance)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopSpeaking() {
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
        if !isRecording {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private enum SpeechFailure: LocalizedError {
        case noInput
        var errorDescription: String? { "Микрофон недоступен / Microphone unavailable." }
    }
}

extension SpeechService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, !self.synthesizer.isSpeaking else { return }
            self.isSpeaking = false
            if !self.isRecording {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, !self.synthesizer.isSpeaking else { return }
            self.isSpeaking = false
        }
    }
}
