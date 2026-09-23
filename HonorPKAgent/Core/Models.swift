import Foundation

enum MessageRole: String, Codable, Sendable { case user, assistant }
enum MessageFeedback: String, Codable, Sendable { case like, dislike }
enum AttachmentKind: String, Codable, Sendable { case image, document, text, video }

struct MessageAttachment: Identifiable, Codable, Equatable, Sendable {
    var id: UUID = UUID()
    var name: String
    var kind: AttachmentKind
    var extractedText: String = ""
    var localPath: String? = nil
    var videoFramePaths: [String]? = nil

    /// Resolves files after iOS changes the app's sandbox UUID during an update.
    var resolvedURL: URL? {
        guard let localPath else { return nil }
        return Self.resolve(localPath)
    }

    var resolvedFrameURLs: [URL] { (videoFramePaths ?? []).compactMap(Self.resolve) }
    var allLocalURLs: [URL] { ([resolvedURL].compactMap { $0 } + resolvedFrameURLs) }

    static func resolve(_ localPath: String) -> URL? {
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

struct WebSource: Identifiable, Codable, Equatable, Sendable {
    var id: UUID = UUID()
    var title: String
    var url: URL
    var snippet: String
    var content: String? = nil
    var fetchedAt: Date? = nil
}

struct ChatMessage: Identifiable, Codable, Equatable, Sendable {
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
    var reasoningWasTranslated: Bool? = nil
    /// Как сообщение попало в чат: набрано текстом, наговорено голосом или выбрано из подсказки.
    var inputKind: MessageInputKind = .text
    /// Реакция пользователя или агента эмодзи.
    var reaction: String? = nil

    /// Устойчивый идентификатор для прокрутки к сообщению.
    var anchorID: String { "message-anchor-" + id.uuidString }
}

enum MessageInputKind: String, Codable, Sendable {
    case text, voice, suggestion
}

struct Conversation: Identifiable, Codable, Equatable, Sendable {
    var id: UUID = UUID()
    var title: String = "Новый чат"
    var messages: [ChatMessage] = []
    var pinned: Bool = false
    var updatedAt: Date = Date()
    var createdAt: Date = Date()
    var parentConversationID: UUID? = nil
    var forkedAtMessageID: UUID? = nil
    var archivedAt: Date? = nil
    /// Инструкция только для этого чата (меню «три точки»).
    var systemPrompt: String = ""
    /// Порядок среди закреплённых: закреплённые чаты можно менять местами.
    var pinOrder: Int = 0
}

extension Conversation {
    /// Время последнего сообщения — по нему чаты сортируются в списке.
    var lastMessageAt: Date {
        messages.last?.createdAt ?? updatedAt
    }

    /// «2 минуты назад», «1 час 34 минуты назад», «12 дней назад».
    var relativeTimestamp: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "ru_RU")
        formatter.unitsStyle = .full
        // Для свежих сообщений показываем минуты, а не «только что».
        let interval = Date().timeIntervalSince(lastMessageAt)
        if interval < 45 { return "только что" }
        return formatter.localizedString(for: lastMessageAt, relativeTo: Date())
    }
}

struct HonorMemory: Identifiable, Codable, Equatable, Sendable {
    var id: UUID = UUID()
    var text: String
    var createdAt: Date = Date()
    /// Из какого чата пришёл факт — чтобы понимать источник (и не путать общую память с локальной).
    var sourceChatID: UUID? = nil
    /// Слова для быстрого отбора релевантных фактов без embeddings.
    var keywords: [String] = []
}

/// Статистика использования приложения (раздел «Статистика» в настройках).
struct UsageStatistics: Codable, Equatable, Sendable {
    var sentMessages: Int = 0
    var receivedMessages: Int = 0
    var totalSessionSeconds: Double = 0
    var voiceMessages: Int = 0
    var firstLaunch: Date = Date()

    var formattedTime: String {
        let total = Int(totalSessionSeconds)
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        if hours > 0 { return "\(hours) ч \(minutes) мин" }
        if minutes > 0 { return "\(minutes) мин" }
        return "\(total) сек"
    }
}

struct HistoryArchive: Codable, Sendable {
    var version: Int = 1
    var conversations: [Conversation]
    var selectedConversationID: UUID?
    var draft: String = ""
    var attachments: [MessageAttachment] = []
    var attachmentFiles: [String: Data]? = nil
    var attachmentFileReferences: [String: String]? = nil
    var attachmentFrameFiles: [String: [Data]]? = nil
    var inFlightMessageID: UUID? = nil
    var memories: [HonorMemory]? = nil
    var memoryEnabled: Bool? = nil
}

struct DeepSeekConfiguration: Sendable {
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
    case http(Int, String), searchUnavailable, attachmentUnavailable(String), requestTooLarge, invalidArchive, archiveTooLarge, memoryLimit

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
        case .archiveTooLarge: return "Архив слишком большой: поддерживается до 100 МБ, включая до 64 МБ файлов."
        case .memoryLimit: return "После импорта в памяти будет больше 50 записей. Удалите ненужные записи перед импортом."
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
