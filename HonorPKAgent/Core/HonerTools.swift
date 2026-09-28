import Foundation
import UIKit

/// Инструменты, которые модель может вызвать сама (пункт 11 ТЗ: function calling).
///
/// Схема такая: приложение отправляет в API список доступных функций,
/// модель в ответе возвращает `tool_calls` вместо обычного текста,
/// приложение выполняет функцию и отправляет результат обратно,
/// после чего модель формулирует итоговый ответ уже с учётом результата.
enum HonerTool: String, CaseIterable {
    case copyToClipboard = "copy_to_clipboard"
    case getCurrentDateTime = "get_current_datetime"
    case getDeviceInfo = "get_device_info"
    case summarizeChat = "summarize_chat_stats"
    /// Расширенные права: ИИ работает с другими чатами пользователя (пункты 7 и 16 ТЗ).
    case listChats = "list_chats"
    case readChat = "read_chat"
    case renameChat = "rename_chat"
    case pinChat = "pin_chat"
    case sendToChat = "send_message_to_chat"
    case saveMemory = "save_memory"
    case setAppSetting = "set_app_setting"
    /// Текущие настройки приложения: модель видит, что включено у пользователя.
    case getAppSettings = "get_app_settings"
    /// Нарисовать картинку по описанию.
    case drawImage = "draw_image"
    /// Найти контакт в телефонной книге (с разрешения пользователя).
    case findContact = "find_contact"
    /// Открыть мини-игру: шахматы, шашки, дурак, «Удача».
    case startGame = "start_game"
    /// Интернет — доступен, только когда включена кнопка «Поиск». Модель сама решает,
    /// нужен ли ей интернет для ответа.
    case webSearch = "web_search"
    case openPage = "open_page"
    case findImages = "find_images"
    case findVideos = "find_videos"
    case screenshotPage = "screenshot_page"
    case getWeather = "get_weather"
    /// Таблицы в чате.
    case createTable = "create_table"
    case updateTable = "update_table"
    case readTable = "read_table"
    /// Память: просмотр, исправление и удаление фактов.
    case listMemory = "list_memory"
    case updateMemory = "update_memory"
    case deleteMemory = "delete_memory"
    /// Массовое чтение сайтов.
    case readManyPages = "read_many_pages"
    /// Интеграции.
    case youtubeSearch = "youtube_search"
    case youtubeVideo = "youtube_video"
    case github = "github"
    case marketplaceSearch = "marketplace_search"
    case vkPage = "vk_page"
    case telegramChannel = "telegram_channel"
    /// Медиа: рассмотреть картинку, расшифровать запись, отредактировать фото.
    case viewImage = "view_image"
    case transcribeMedia = "transcribe_media"
    case editImage = "edit_image"

    /// Инструмент ходит в интернет и выполняется асинхронно.
    var isWeb: Bool {
        switch self {
        case .webSearch, .openPage, .findImages, .findVideos, .screenshotPage, .getWeather: return true
        case .readManyPages, .youtubeSearch, .youtubeVideo, .github, .marketplaceSearch, .vkPage, .telegramChannel: return true
        default: return false
        }
    }

    /// Новые инструменты (ExtraTools.swift).
    var isExtra: Bool {
        switch self {
        case .createTable, .updateTable, .readTable, .listMemory, .updateMemory, .deleteMemory,
             .readManyPages, .youtubeSearch, .youtubeVideo, .github, .marketplaceSearch, .vkPage, .telegramChannel,
             .viewImage, .transcribeMedia, .editImage:
            return true
        default:
            return false
        }
    }

    /// Инструмент выполняется асинхронно (сеть, контакты, медиа).
    var isAsync: Bool {
        isWeb || self == .findContact || self == .viewImage || self == .transcribeMedia || self == .editImage
    }

    /// Описание для API: имя, назначение и параметры в формате JSON Schema.
    var schema: [String: Any] {
        if isExtra { return ExtraToolSchemas.schema(for: self) }
        switch self {
        case .copyToClipboard:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Кладёт переданный текст в буфер обмена iPhone. Используй, когда пользователь просит скопировать что-то, чтобы вставить в другом приложении.",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "text": ["type": "string", "description": "Текст для копирования"]
                        ],
                        "required": ["text"]
                    ]
                ]
            ]
        case .getCurrentDateTime:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Возвращает точные текущие дату, время, день недели и часовой пояс устройства.",
                    "parameters": ["type": "object", "properties": [:], "required": []]
                ]
            ]
        case .getDeviceInfo:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Возвращает модель iPhone, версию iOS и версию приложения Honer AI.",
                    "parameters": ["type": "object", "properties": [:], "required": []]
                ]
            ]
        case .summarizeChat:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Возвращает статистику текущего чата: сколько сообщений, сколько голосовых, когда начался и когда было последнее.",
                    "parameters": ["type": "object", "properties": [:], "required": []]
                ]
            ]
        case .listChats:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Показывает список всех чатов пользователя: номер, название, число сообщений, дата последнего сообщения, закреплён ли чат. Вызывай, когда пользователь спрашивает про свои чаты, просит найти чат или поработать с другим чатом.",
                    "parameters": ["type": "object", "properties": [:], "required": []]
                ]
            ]
        case .readChat:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Читает содержимое другого чата пользователя по его номеру из list_chats. Возвращает последние сообщения с ролями.",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "number": ["type": "integer", "description": "Номер чата из списка list_chats"],
                            "messages": ["type": "integer", "description": "Сколько последних сообщений вернуть, по умолчанию 40"]
                        ],
                        "required": ["number"]
                    ]
                ]
            ]
        case .renameChat:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Переименовывает чат пользователя по номеру из list_chats.",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "number": ["type": "integer", "description": "Номер чата из списка list_chats"],
                            "title": ["type": "string", "description": "Новое название чата"]
                        ],
                        "required": ["number", "title"]
                    ]
                ]
            ]
        case .pinChat:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Закрепляет или открепляет чат пользователя по номеру из list_chats.",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "number": ["type": "integer", "description": "Номер чата из списка list_chats"],
                            "pinned": ["type": "boolean", "description": "true — закрепить, false — открепить"]
                        ],
                        "required": ["number", "pinned"]
                    ]
                ]
            ]
        case .sendToChat:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Отправляет сообщение в другой чат пользователя по номеру из list_chats. Пиши от себя, коротко и по делу. Пользователь увидит сообщение в том чате.",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "number": ["type": "integer", "description": "Номер чата из списка list_chats"],
                            "text": ["type": "string", "description": "Текст сообщения"]
                        ],
                        "required": ["number", "text"]
                    ]
                ]
            ]
        case .saveMemory:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Сам сохраняет важный факт о пользователе в память Honer AI. Используй, когда узнал устойчивый факт: имя, город, профессию, предпочтения, постоянные требования к ответам.",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "text": ["type": "string", "description": "Факт одной короткой фразой, например «Живёт в Новосибирске»"]
                        ],
                        "required": ["text"]
                    ]
                ]
            ]
        case .getAppSettings:
            return Self.function(rawValue, "Показывает текущие настройки приложения у пользователя: включены ли рассуждение, поиск, озвучивание, уведомления, стикеры, тема, язык, размер шрифта, голос, имя в профиле, число записей памяти. Вызывай, когда пользователь спрашивает про свои настройки или когда от них зависит ответ.", [:], [])
        case .drawImage:
            return Self.function(rawValue, "Рисует картинку по описанию (иллюстрация, арт, логотип, пейзаж, персонаж) и возвращает строку для вставки в ответ. Описание пиши на английском — так качество выше. Вставь возвращённую строку ![…](…) в ответ без изменений.", [
                "prompt": ["type": "string", "description": "Подробное описание картинки на английском: объект, стиль, цвета, композиция"],
                "orientation": ["type": "string", "description": "square, portrait или landscape; по умолчанию square"]
            ], ["prompt"])
        case .startGame:
            return Self.function(rawValue, "Открывает на экране пользователя мини-игру против тебя: chess (шахматы), checkers (русские шашки), durak (дурак подкидной), slots (игровой автомат «Удача»). Вызывай, когда пользователь хочет поиграть.", [
                "game": ["type": "string", "description": "chess, checkers, durak или slots"]
            ], ["game"])
        case .findContact:
            return Self.function(rawValue, "Находит контакт в телефонной книге пользователя по имени: телефоны, почту, организацию, день рождения. Работает, если пользователь разрешил доступ к контактам.", [
                "name": ["type": "string", "description": "Имя или фамилия"]
            ], ["name"])
        case .webSearch:
            return Self.function(rawValue, "Ищет в интернете сразу в нескольких поисковиках (Bing, DuckDuckGo, Brave, Википедия) и читает найденные страницы. Вызывай, когда нужны свежие или точные данные, которых ты не знаешь наверняка: новости, цены, курсы, события, расписания, характеристики, факты о малоизвестном. Для общих знаний, расчётов, кода, советов и болтовни поиск не нужен.", [
                "query": ["type": "string", "description": "Поисковый запрос: только тема, без слов-команд"]
            ], ["query"])
        case .openPage:
            return Self.function(rawValue, "Открывает и читает страницу по ссылке, как браузер Safari: выполняет JavaScript, читает публичные каналы Telegram (t.me/…), страницы ВКонтакте, новости, документацию. Возвращает текст страницы и картинки на ней.", [
                "url": ["type": "string", "description": "Полная ссылка https://…; для Telegram можно t.me/имя_канала"]
            ], ["url"])
        case .findImages:
            return Self.function(rawValue, "Находит в интернете настоящие фотографии и изображения по теме и возвращает строки для вставки в ответ. Вызывай, когда пользователь просит показать фото, картинку, как что-то выглядит.", [
                "query": ["type": "string", "description": "Что должно быть на изображении; лучше на английском"],
                "count": ["type": "integer", "description": "Сколько изображений, 1–6, по умолчанию 3"]
            ], ["query"])
        case .findVideos:
            return Self.function(rawValue, "Находит видео (YouTube и другие) по теме и возвращает строки для вставки в ответ: приложение покажет видео с кнопкой воспроизведения прямо в чате.", [
                "query": ["type": "string", "description": "Тема видео"],
                "count": ["type": "integer", "description": "Сколько видео, 1–4, по умолчанию 2"]
            ], ["query"])
        case .screenshotPage:
            return Self.function(rawValue, "Делает скриншот страницы сайта и возвращает строку для вставки в ответ — пользователь увидит, как выглядит страница.", [
                "url": ["type": "string", "description": "Полная ссылка https://…"]
            ], ["url"])
        case .getWeather:
            return Self.function(rawValue, "Возвращает текущую погоду и прогноз на 7 дней для города (Open-Meteo).", [
                "city": ["type": "string", "description": "Город, например «Клин» или «Москва»"]
            ], ["city"])
        case .setAppSetting:
            return [
                "type": "function",
                "function": [
                    "name": rawValue,
                    "description": "Меняет настройку приложения. Доступно: reasoning (рассуждение), search (поиск), autoRead (озвучивать ответы), fontScale (размер шрифта), notifications (уведомления).",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "name": ["type": "string", "description": "Название настройки: reasoning, search, autoRead, notifications или fontScale"],
                            "value": ["type": "string", "description": "Новое значение: true/false для переключателей, число для размера шрифта"]
                        ],
                        "required": ["name", "value"]
                    ]
                ]
            ]
        }
    }

    static var apiSchemas: [[String: Any]] { allCases.map(\.schema) }

    /// Инструменты для запроса: интернет — только когда включена кнопка «Поиск».
    static func schemas(searchEnabled: Bool) -> [[String: Any]] {
        allCases.filter { (searchEnabled || !$0.isWeb) && Integrations.allows($0) }.map(\.schema)
    }

    private static func function(_ name: String, _ description: String,
                                 _ properties: [String: Any], _ required: [String]) -> [String: Any] {
        [
            "type": "function",
            "function": [
                "name": name,
                "description": description,
                "parameters": ["type": "object", "properties": properties, "required": required]
            ]
        ]
    }
}

/// Один вызов инструмента, собранный из потока (аргументы приходят кусками).
struct ToolCallRequest: Equatable, Sendable {
    var id: String
    var name: String
    var arguments: String
    /// Номер вызова в потоке. Именно по нему склеиваются куски аргументов:
    /// id и имя приходят только в первом куске, дальше идёт один index.
    var index: Int? = nil

    /// Разобранные аргументы вызова.
    var parsedArguments: [String: Any] {
        guard let data = arguments.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return object
    }
}

/// Терпимое чтение аргументов инструмента.
///
/// Модель не всегда соблюдает типы из схемы: номер чата приходит то числом, то
/// строкой («3»), флаг — то `true`, то «true» или «да». Раньше строгое `as? Int`
/// отвергало такие вызовы, и модель получала «Не передан номер чата» вместо данных.
enum ToolArgument {
    static func int(_ value: Any?) -> Int? {
        if let number = value as? Int { return number }
        if let number = value as? Double, number.rounded() == number { return Int(number) }
        if let text = value as? String {
            let digits = text.trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "#№. "))
            return Int(digits)
        }
        return nil
    }

    static func bool(_ value: Any?) -> Bool? {
        if let flag = value as? Bool { return flag }
        if let number = value as? Int { return number != 0 }
        if let text = value as? String {
            switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "1", "yes", "да", "вкл", "on": return true
            case "false", "0", "no", "нет", "выкл", "off": return false
            default: return nil
            }
        }
        return nil
    }

    static func string(_ value: Any?) -> String? {
        if let text = value as? String { return text }
        if let flag = value as? Bool { return flag ? "true" : "false" }
        if let number = value as? NSNumber { return number.stringValue }
        return nil
    }
}

/// Обзор одного чата для списка, который видит модель.
struct ChatOverview: Sendable {
    var number: Int
    /// Устойчивый идентификатор чата. Номер в списке меняется, как только чат
    /// передвинулся наверх, поэтому действия над чатом выполняются по идентификатору:
    /// иначе переименование могло попасть в соседний чат.
    var id: UUID = UUID()
    var title: String
    var messageCount: Int
    var lastMessageAt: Date?
    var pinned: Bool
    var archived: Bool
    var preview: String
}

/// Строка чата, которую модель читает инструментом read_chat.
struct ChatTranscriptLine: Sendable {
    var role: String
    var text: String
}

/// Доступные приложению данные, нужные инструментам.
struct ToolExecutionContext: Sendable {
    var deviceModel: String = "iPhone"
    var systemVersion: String = ""
    var appVersion: String = DeviceModel.appVersion
    var currentDateTime: String = ""
    var messageCount: Int = 0
    var voiceMessageCount: Int = 0
    var chatStartedAt: Date? = nil
    var lastMessageAt: Date? = nil
    /// Все чаты пользователя: нужны инструментам list_chats/read_chat/rename_chat/pin_chat.
    var chats: [ChatOverview] = []
    /// Сообщения выбранного чата для read_chat.
    var transcripts: [Int: [ChatTranscriptLine]] = [:]
    /// Текущие настройки приложения для get_app_settings.
    var settingsSummary: String = ""
    /// Таблицы текущего чата (T1, T2…).
    var tables: [ChatTable] = []
    /// Факты памяти по порядку (номера для list_memory).
    var memoryItems: [MemoryRef] = []
    /// Вложения текущего чата: фото для редактора и просмотра, записи для расшифровки.
    var chatAttachments: [MessageAttachment] = []
    /// Настройки сервиса — для просмотра изображений.
    var configuration: DeepSeekConfiguration? = nil
}

/// Что приложение должно сделать по просьбе модели.
enum ToolEffect: Sendable {
    case sendToChat(number: Int, text: String)
    case saveMemory(String)
    case setSetting(name: String, value: String)
    /// Действие над конкретным чатом по устойчивому идентификатору.
    case renameChat(id: UUID, title: String)
    case pinChat(id: UUID, pinned: Bool)
    case sendToChatID(id: UUID, text: String)
    /// Прочитанные страницы: показываются карточкой «Источники ответа».
    case addSources([WebSource])
    /// Открыть мини-игру.
    case openGame(String)
    /// Таблица: новая или изменённая.
    case createTable(ChatTable)
    case replaceTable(ChatTable)
    /// Память: исправить или удалить факт.
    case updateMemory(id: UUID, text: String)
    case deleteMemory(UUID)
    /// Файл (например, отредактированное фото) под ответом.
    case attachFile(MessageAttachment)
}

/// Результат выполнения инструмента, который уходит обратно в модель.
struct ToolCallResult: Sendable {
    let callID: String
    let name: String
    let content: String
    var effect: ToolEffect? = nil
}

@MainActor
enum ToolExecutor {
    /// Выполняет вызов инструмента и возвращает результат для отправки в модель.
    static func execute(_ call: ToolCallRequest, context: ToolExecutionContext) -> ToolCallResult {
        let arguments = call.parsedArguments

        switch HonerTool(rawValue: call.name) {
        case .copyToClipboard:
            let text = (arguments["text"] as? String) ?? ""
            if text.isEmpty {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Не удалось скопировать: текст не передан.")
            }
            UIPasteboard.general.string = text
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Скопировано в буфер обмена: \(text.prefix(200))")

        case .getCurrentDateTime:
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "ru_RU")
            formatter.timeZone = .current
            formatter.dateFormat = "EEEE, d MMMM yyyy, HH:mm:ss"
            let zone = TimeZone.current.identifier
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Сейчас \(formatter.string(from: Date())) (\(zone)).")

        case .getDeviceInfo:
            let system = context.systemVersion.isEmpty ? UIDevice.current.systemVersion : context.systemVersion
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Устройство: \(context.deviceModel). Система: iOS \(system). Приложение: Honer AI \(context.appVersion).")

        case .summarizeChat:
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "ru_RU")
            formatter.dateFormat = "d MMMM, HH:mm"
            var parts = ["Сообщений в чате: \(context.messageCount)",
                         "Из них голосовых: \(context.voiceMessageCount)"]
            if let started = context.chatStartedAt {
                parts.append("Начат: \(formatter.string(from: started))")
            }
            if let last = context.lastMessageAt {
                parts.append("Последнее сообщение: \(formatter.string(from: last))")
            }
            return ToolCallResult(callID: call.id, name: call.name, content: parts.joined(separator: ". ") + ".")

        case .startGame:
            guard let kind = GameKind.from(ToolArgument.string(call.parsedArguments["game"]) ?? "") else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Доступны игры: шахматы, шашки, дурак, «Удача». Спроси, во что сыграть.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Игра «\(kind.title)» открыта на экране пользователя. Коротко и весело пожелай удачи.",
                                  effect: .openGame(kind.rawValue))

        case .getAppSettings:
            let summary = context.settingsSummary.isEmpty ? "Настройки сейчас недоступны." : context.settingsSummary
            return ToolCallResult(callID: call.id, name: call.name, content: "Настройки пользователя в приложении Honer AI:\n" + summary)

        case .drawImage:
            let prompt = ToolArgument.string(call.parsedArguments["prompt"])?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !prompt.isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Не передано описание картинки.")
            }
            let orientation = ToolArgument.string(call.parsedArguments["orientation"]) ?? "square"
            guard let url = MediaLinks.drawing(prompt: prompt, orientation: orientation) else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Не удалось составить ссылку на рисунок.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Картинка готова. Вставь в ответ ровно эту строку, без изменений:\n![\(MediaLinks.caption(prompt))](\(url.absoluteString))")

        case .none, .listChats, .readChat, .renameChat, .pinChat, .sendToChat, .saveMemory, .setAppSetting,
             .webSearch, .openPage, .findImages, .findVideos, .screenshotPage, .getWeather, .findContact,
             .createTable, .updateTable, .readTable, .listMemory, .updateMemory, .deleteMemory,
             .readManyPages, .youtubeSearch, .youtubeVideo, .github, .marketplaceSearch, .vkPage, .telegramChannel,
             .viewImage, .transcribeMedia, .editImage:
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Инструмент «\(call.name)» доступен только в расширенном режиме.")
        }
    }

    /// Расширенные инструменты: работа с другими чатами, памятью и настройками.
    /// Возвращают результат для модели и, если нужно, эффект для приложения.
    static func executeExtended(_ call: ToolCallRequest, context: ToolExecutionContext) -> ToolCallResult {
        let arguments = call.parsedArguments
        switch HonerTool(rawValue: call.name) {
        case .listChats:
            guard !context.chats.isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "У пользователя пока только этот чат.")
            }
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "ru_RU")
            formatter.dateFormat = "d MMMM, HH:mm"
            let lines = context.chats.map { chat -> String in
                var parts = ["\(chat.number). «\(chat.title)»",
                             "сообщений: \(chat.messageCount)"]
                if let last = chat.lastMessageAt { parts.append("последнее: \(formatter.string(from: last))") }
                if chat.pinned { parts.append("закреплён") }
                if chat.archived { parts.append("в архиве") }
                if !chat.preview.isEmpty { parts.append("о чём: \(chat.preview)") }
                return parts.joined(separator: ", ")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Чаты пользователя (всего \(context.chats.count)):\n" + lines.joined(separator: "\n")
                                  + "\n\nЧтобы прочитать переписку чата, вызови read_chat с его номером.")

        case .readChat:
            guard let number = ToolArgument.int(arguments["number"]) else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Не передан номер чата.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }),
                  let transcript = context.transcripts[number] else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            let limit = max(1, min(ToolArgument.int(arguments["messages"]) ?? 40, 120))
            let slice = transcript.suffix(limit)
            guard !slice.isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Чат «\(chat.title)» пуст.")
            }
            let body = slice.map { line in
                let who = line.role == "user" ? "Пользователь" : "Honer AI"
                return "\(who): \(line.text)"
            }.joined(separator: "\n")
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Чат «\(chat.title)», последние \(slice.count) сообщений:\n\(body)")

        case .renameChat:
            guard let number = ToolArgument.int(arguments["number"]),
                  let title = ToolArgument.string(arguments["title"]),
                  !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны номер чата и новое название.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Чат «\(chat.title)» переименован в «\(title)».",
                                  effect: .renameChat(id: chat.id, title: title))

        case .pinChat:
            guard let number = ToolArgument.int(arguments["number"]),
                  let pinned = ToolArgument.bool(arguments["pinned"]) else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны номер чата и признак закрепления.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Чат «\(chat.title)» \(pinned ? "закреплён" : "откреплён").",
                                  effect: .pinChat(id: chat.id, pinned: pinned))

        case .sendToChat:
            guard let number = ToolArgument.int(arguments["number"]),
                  let text = ToolArgument.string(arguments["text"]),
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны номер чата и текст сообщения.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Сообщение отправлено в чат «\(chat.title)»: \(text.prefix(200))",
                                  effect: .sendToChatID(id: chat.id, text: text))

        case .saveMemory:
            guard let text = arguments["text"] as? String,
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Не передан текст для памяти.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Записано в память: \(text)",
                                  effect: .saveMemory(text))

        case .setAppSetting:
            guard let name = ToolArgument.string(arguments["name"]),
                  let value = ToolArgument.string(arguments["value"]) else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны название настройки и значение.")
            }
            let allowed = ["reasoning", "search", "autoread", "notifications", "fontscale"]
            guard allowed.contains(name.lowercased()) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Настройка «\(name)» недоступна. Доступны: reasoning, search, autoRead, notifications, fontScale.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Настройка \(name) переключена в \(value).",
                                  effect: .setSetting(name: name, value: value))

        default:
            return execute(call, context: context)
        }
    }
}
