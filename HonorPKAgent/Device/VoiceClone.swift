import AVFoundation
import Foundation
import Security
import SwiftUI

/// Защищённое хранилище iPhone (Keychain). Записи в нём переживают даже удаление
/// и повторную установку приложения — поэтому здесь ключи и настройки для восстановления.
enum KeychainStore {
    private static let service = "com.honorpk.agent.keychain"

    static func set(_ value: String, for key: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: key]
        SecItemDelete(query as CFDictionary)
        guard !value.isEmpty else { return }
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: key,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// Клонирование голоса пользователя через Fish Audio: голос создаётся по записи
/// от 20 секунд, поддерживается русский. Сервис облачный: нужен ключ API
/// пользователя (fish.audio → API Keys).
enum FishAudio {
    static let base = URL(string: "https://api.fish.audio")!

    /// Текст для записи образца голоса: разные звуки, интонации и числа.
    static let script = """
    Привет! Меня зовут так, как вы меня слышите, и это мой настоящий голос. \
    Сегодня прекрасный день, чтобы узнать что-то новое. Съешь же ещё этих мягких французских булок да выпей чаю. \
    Я люблю ясные ответы, короткие фразы и понятные объяснения. Сколько будет двадцать пять плюс семнадцать? \
    Сорок два! Отлично, давай продолжим. Широкая электрификация южных губерний даст мощный толчок подъёму сельского хозяйства. \
    Hello! I can speak English too. Спасибо, что слушаете меня.
    """

    /// Тело запроса multipart/form-data для создания голоса.
    static func multipartBody(boundary: String, fields: [(String, String)], fileField: String,
                              fileName: String, mimeType: String, fileData: Data) -> Data {
        var body = Data()
        for (name, value) in fields {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
        }
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(fileField)\"; filename=\"\(fileName)\"\r\nContent-Type: \(mimeType)\r\n\r\n".utf8))
        body.append(fileData)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return body
    }

    /// Создать голос по записи. Возвращает идентификатор голоса.
    static func createVoice(apiKey: String, sample: URL, transcript: String, title: String) async throws -> String {
        let audio = try Data(contentsOf: sample)
        let boundary = "HonerBoundary\(UUID().uuidString)"
        var request = URLRequest(url: base.appendingPathComponent("model"))
        request.httpMethod = "POST"
        request.timeoutInterval = 180
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = multipartBody(boundary: boundary,
                                         fields: [("type", "tts"), ("title", title), ("train_mode", "fast"),
                                                  ("visibility", "private"), ("texts", transcript),
                                                  ("enhance_audio_quality", "true")],
                                         fileField: "voices", fileName: "sample.m4a", mimeType: "audio/mp4", fileData: audio)
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["_id"] as? String, !id.isEmpty else {
            throw VoiceCloneError.service(status: status, message: String(data: data.prefix(300), encoding: .utf8) ?? "")
        }
        return id
    }

    /// Озвучить текст голосом пользователя. Возвращает звук в MP3.
    static func synthesize(text: String, voiceID: String, apiKey: String) async throws -> Data {
        var request = URLRequest(url: base.appendingPathComponent("v1/tts"))
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("s2-pro", forHTTPHeaderField: "model")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["text": text, "reference_id": voiceID,
                                                                      "format": "mp3", "normalize": true])
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status), data.count > 256 else {
            throw VoiceCloneError.service(status: status, message: String(data: data.prefix(300), encoding: .utf8) ?? "")
        }
        return data
    }

    static func deleteVoice(voiceID: String, apiKey: String) async {
        var request = URLRequest(url: base.appendingPathComponent("model/\(voiceID)"))
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        _ = try? await URLSession.shared.data(for: request)
    }

    /// Длинный текст режется на куски по предложениям: первый кусок звучит быстрее,
    /// пока следующий уже готовится.
    static func chunks(_ text: String, limit: Int = 380) -> [String] {
        var result: [String] = []
        var current = ""
        let sentences = text.replacingOccurrences(of: "\n", with: " \n ")
            .components(separatedBy: CharacterSet(charactersIn: ".!?…\n"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        for sentence in sentences {
            let piece = sentence.hasSuffix(".") ? sentence : sentence + "."
            if current.count + piece.count + 1 > limit, !current.isEmpty {
                result.append(current)
                current = ""
            }
            if piece.count > limit {
                var remainder = piece
                while remainder.count > limit {
                    let cut = remainder.index(remainder.startIndex, offsetBy: limit)
                    let space = remainder[..<cut].lastIndex(of: " ") ?? cut
                    result.append(String(remainder[..<space]))
                    remainder = String(remainder[space...]).trimmingCharacters(in: .whitespaces)
                }
                current = remainder
            } else {
                current += current.isEmpty ? piece : " " + piece
            }
        }
        if !current.isEmpty { result.append(current) }
        return result
    }
}

enum VoiceCloneError: LocalizedError {
    case service(status: Int, message: String)
    case tooShort

    var errorDescription: String? {
        switch self {
        case .service(let status, let message):
            if status == 401 || status == 403 { return "Ключ Fish Audio не подошёл. Проверьте его в настройках голоса." }
            if status == 402 { return "На счёте Fish Audio закончились средства." }
            return "Сервис голоса ответил ошибкой \(status). \(message)"
        case .tooShort: return "Запись слишком короткая: нужно хотя бы 20 секунд."
        }
    }
}

/// Запись образца голоса.
@MainActor
final class VoiceSampleRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published private(set) var isRecording = false
    @Published private(set) var seconds: Double = 0
    @Published private(set) var level: Double = 0
    @Published private(set) var sampleURL: URL?
    private var recorder: AVAudioRecorder?
    private var timer: Timer?

    static var storedSampleURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPK", isDirectory: true).appendingPathComponent("voice-sample.m4a")
    }

    func start() async -> String? {
        let allowed = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard allowed else { return "Разрешите доступ к микрофону: Настройки → Разрешения." }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            let url = Self.storedSampleURL
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: url)
            let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                           AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue]
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            recorder.delegate = self
            guard recorder.record() else { return "Не удалось начать запись." }
            self.recorder = recorder
            sampleURL = nil
            seconds = 0
            isRecording = true
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    private func tick() {
        guard let recorder, isRecording else { return }
        recorder.updateMeters()
        seconds = recorder.currentTime
        level = max(0, min(1, Double(recorder.averagePower(forChannel: 0) + 50) / 50))
        if seconds >= 120 { stop() }
    }

    func stop() {
        timer?.invalidate(); timer = nil
        recorder?.stop()
        isRecording = false
        level = 0
        if seconds >= 1 { sampleURL = Self.storedSampleURL }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// Страница «Мой голос»: запись, создание и включение своего голоса.
struct VoiceClonePage: View {
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var recorder = VoiceSampleRecorder()
    @StateObject private var speech = SpeechService()
    @State private var apiKey = KeychainStore.get("fishAudioKey") ?? ""
    @State private var consent = false
    @State private var working = false
    @State private var message: String?
    @State private var player: AVAudioPlayer?

    var body: some View {
        Form {
            Section {
                Text(settings.text("Honer AI сможет читать ответы вашим собственным голосом — по-русски и по-английски. Голос создаёт сервис Fish Audio по записи 30–60 секунд. Нужен ваш ключ Fish Audio: зарегистрируйтесь на fish.audio → API Keys → создайте ключ.",
                                   "Honer AI can read answers in your own voice. Fish Audio creates it from a 30–60 second recording; you need your Fish Audio API key."))
                    .font(.footnote)
                SecureField(settings.text("Ключ Fish Audio", "Fish Audio API key"), text: $apiKey)
                    .textContentType(.password)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: apiKey) { KeychainStore.set($0.trimmingCharacters(in: .whitespacesAndNewlines), for: "fishAudioKey") }
                    .accessibilityIdentifier("clone.key")
                Link(settings.text("Открыть fish.audio", "Open fish.audio"), destination: URL(string: "https://fish.audio")!)
            } header: { Text(settings.text("1. Ключ сервиса", "1. Service key")) }

            Section {
                Text(FishAudio.script)
                    .font(.system(size: 16))
                    .padding(.vertical, 4)
                    .accessibilityIdentifier("clone.script")
                HStack(spacing: 14) {
                    Button {
                        if recorder.isRecording { recorder.stop() } else {
                            Task { if let error = await recorder.start() { message = error } }
                        }
                    } label: {
                        Label(recorder.isRecording ? settings.text("Стоп", "Stop") : settings.text("Записать", "Record"),
                              systemImage: recorder.isRecording ? "stop.circle.fill" : "record.circle")
                            .font(.headline)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(recorder.isRecording ? .red : HonorTheme.accent)
                    .accessibilityIdentifier("clone.record")
                    Text(String(format: "%.0f с", recorder.seconds))
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(recorder.seconds >= 20 ? .green : HonorTheme.secondary)
                    if recorder.isRecording {
                        Capsule().fill(HonorTheme.accent).frame(width: 60 * recorder.level + 4, height: 6)
                            .animation(.easeOut(duration: 0.1), value: recorder.level)
                    }
                    Spacer(minLength: 0)
                    if recorder.sampleURL != nil && !recorder.isRecording {
                        Button { playSample() } label: { Image(systemName: "play.circle").font(.title2) }
                            .accessibilityLabel(settings.text("Прослушать запись", "Play recording"))
                    }
                }
            } header: {
                Text(settings.text("2. Прочитайте текст вслух (30–60 секунд)", "2. Read the text aloud (30–60 s)"))
            } footer: {
                Text(settings.text("Говорите в тихом месте, спокойно и чётко, как обычно разговариваете.",
                                   "Speak in a quiet place, calmly and clearly."))
            }

            Section {
                Toggle(settings.text("Это мой голос, и я согласен(а) на его клонирование", "This is my own voice and I consent to cloning it"), isOn: $consent)
                    .accessibilityIdentifier("clone.consent")
                Button {
                    Task { await createVoice() }
                } label: {
                    HStack {
                        if working { ProgressView().padding(.trailing, 6) }
                        Text(settings.clonedVoiceID.isEmpty ? settings.text("Создать мой голос", "Create my voice")
                                                            : settings.text("Пересоздать мой голос", "Recreate my voice"))
                    }
                }
                .disabled(working || !consent || recorder.sampleURL == nil || recorder.seconds < 20 || apiKey.isEmpty)
                .accessibilityIdentifier("clone.create")
            } header: { Text(settings.text("3. Создание голоса", "3. Create the voice")) }

            if !settings.clonedVoiceID.isEmpty {
                Section {
                    Toggle(settings.text("Читать ответы моим голосом", "Read answers in my voice"), isOn: $settings.useClonedVoice)
                        .accessibilityIdentifier("clone.use")
                    Button(speech.isSpeaking ? settings.text("Остановить", "Stop") : settings.text("Послушать мой голос", "Listen to my voice")) {
                        if speech.isSpeaking { speech.stopSpeaking() } else {
                            speech.speakCloned("Привет! Теперь я говорю твоим голосом. Hello, this is my voice.",
                                               voiceID: settings.clonedVoiceID, apiKey: apiKey)
                        }
                    }
                    .accessibilityIdentifier("clone.preview")
                    Button(settings.text("Удалить мой голос", "Delete my voice"), role: .destructive) {
                        let id = settings.clonedVoiceID
                        let key = apiKey
                        settings.clonedVoiceID = ""
                        settings.useClonedVoice = false
                        Task { await FishAudio.deleteVoice(voiceID: id, apiKey: key) }
                    }
                } header: { Text(settings.text("Мой голос готов", "My voice is ready")) }
            }
            if let message {
                Section { Text(message).font(.footnote).accessibilityIdentifier("clone.message") }
            }
        }
        .navigationTitle(settings.text("Мой голос", "My voice"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.clone")
        .onDisappear { recorder.stop(); speech.stopSpeaking(); player?.stop() }
    }

    private func playSample() {
        guard let url = recorder.sampleURL else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        player = try? AVAudioPlayer(contentsOf: url)
        player?.play()
    }

    private func createVoice() async {
        guard let sample = recorder.sampleURL else { return }
        working = true
        message = nil
        defer { working = false }
        do {
            let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let old = settings.clonedVoiceID
            let id = try await FishAudio.createVoice(apiKey: key, sample: sample, transcript: FishAudio.script,
                                                    title: "Honer AI — \(settings.displayName.isEmpty ? "мой голос" : settings.displayName)")
            settings.clonedVoiceID = id
            settings.useClonedVoice = true
            if !old.isEmpty { await FishAudio.deleteVoice(voiceID: old, apiKey: key) }
            message = settings.text("Готово! Теперь ответы будут звучать вашим голосом.", "Done! Answers will now use your voice.")
        } catch {
            message = error.localizedDescription
        }
    }
}
