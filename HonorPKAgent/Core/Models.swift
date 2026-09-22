import Foundation

enum MessageRole: String, Codable { case user, assistant }
enum MessageFeedback: String, Codable { case like, dislike }
enum AttachmentKind: String, Codable { case image, document, text }

struct MessageAttachment: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var name: String
    var kind: AttachmentKind
    var extractedText: String = ""
    var localPath: String? = nil

    /// Resolves files after iOS changes the app's sandbox UUID during an update.
    var resolvedURL: URL? {
        guard let localPath else { return nil }
        let original = URL(fileURLWithPath: localPath)
        if FileManager.default.fileExists(atPath: original.path) { return original }
        for directory in [FileManager.SearchPathDirectory.applicationSupportDirectory, .documentDirectory] {
            if let root = FileManager.default.urls(for: directory, in: .userDomainMask).first,
               let range = localPath.range(of: directory == .documentDirectory ? "/Documents/" : "/Library/Application Support/") {
                let rebased = root.appendingPathComponent(String(localPath[range.upperBound...]))
                if FileManager.default.fileExists(atPath: rebased.path) { return rebased }
            }
        }
        return nil
    }
}

struct WebSource: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var title: String
    var url: URL
    var snippet: String
}

struct ChatMessage: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var role: MessageRole
    var content: String = ""
    var reasoning: String = ""
    var reasoningSeconds: Int = 0
    var createdAt: Date = Date()
    var feedback: MessageFeedback? = nil
    var attachments: [MessageAttachment] = []
    var sources: [WebSource] = []
    var error: String? = nil
    var isInterrupted: Bool = false
}

struct Conversation: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var title: String = "Новый чат"
    var messages: [ChatMessage] = []
    var pinned: Bool = false
    var updatedAt: Date = Date()
    var createdAt: Date = Date()
}

struct HistoryArchive: Codable {
    var version: Int = 1
    var conversations: [Conversation]
    var selectedConversationID: UUID?
    var draft: String = ""
    var attachments: [MessageAttachment] = []
    var attachmentFiles: [String: Data]? = nil
    var inFlightMessageID: UUID? = nil
}

struct DeepSeekConfiguration {
    var apiKey: String
    var baseURL: URL = URL(string: "https://api.deepseek.com")!
    var model: String = "deepseek-flash"

    static var bundled: DeepSeekConfiguration {
        let values: [String: Any]
        if let url = Bundle.main.url(forResource: "DeepSeekConfig", withExtension: "plist"),
           let data = try? Data(contentsOf: url),
           let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] {
            values = plist
        } else { values = [:] }
        let base = (values["BaseURL"] as? String).flatMap(URL.init(string:))
        return DeepSeekConfiguration(
            apiKey: (values["APIKey"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
            baseURL: base ?? URL(string: "https://api.deepseek.com")!,
            model: values["Model"] as? String ?? "deepseek-flash"
        )
    }
}

enum HonorError: LocalizedError {
    case missingAPIKey, invalidResponse, unfinishedResponse, emptyResponse
    case http(Int, String), searchUnavailable, attachmentUnavailable(String), requestTooLarge, invalidArchive

    var errorDescription: String? {
        switch self {
        case .missingAPIKey: return "Добавьте ключ DeepSeek в настройках аккаунта, чтобы начать разговор."
        case .invalidResponse: return "Сервис вернул ответ в неизвестном формате. Попробуйте ещё раз."
        case .unfinishedResponse: return "Соединение прервалось. Часть ответа сохранена — можно повторить запрос."
        case .emptyResponse: return "Сервис завершил запрос без ответа. Попробуйте ещё раз."
        case .searchUnavailable: return "Поиск сейчас недоступен или не нашёл результатов. Повторите запрос или выключите «Поиск»."
        case .attachmentUnavailable(let name): return "Не удалось прочитать вложение «\(name)». Прикрепите файл ещё раз."
        case .requestTooLarge: return "Слишком много вложений в этом разговоре. Начните новый чат или отправьте меньше изображений."
        case .invalidArchive: return "Этот файл не является поддерживаемым архивом Honor."
        case .http(let status, let message):
            switch status {
            case 401, 403: return "DeepSeek не принял API-ключ. Проверьте его в настройках аккаунта."
            case 402: return "На аккаунте DeepSeek недостаточно средств для запроса."
            case 429: return "Лимит запросов DeepSeek достигнут. Попробуйте немного позже."
            case 500...599: return "DeepSeek временно недоступен (\(status)). Попробуйте ещё раз."
            default: return message.isEmpty ? "Не удалось выполнить запрос (\(status))." : "DeepSeek (\(status)): \(message)"
            }
        }
    }
}
