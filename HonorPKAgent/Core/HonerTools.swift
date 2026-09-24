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

    /// Описание для API: имя, назначение и параметры в формате JSON Schema.
    var schema: [String: Any] {
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

/// Обзор одного чата для списка, который видит модель.
struct ChatOverview: Sendable {
    var number: Int
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
    var appVersion: String = "10.25"
    var currentDateTime: String = ""
    var messageCount: Int = 0
    var voiceMessageCount: Int = 0
    var chatStartedAt: Date? = nil
    var lastMessageAt: Date? = nil
    /// Все чаты пользователя: нужны инструментам list_chats/read_chat/rename_chat/pin_chat.
    var chats: [ChatOverview] = []
    /// Сообщения выбранного чата для read_chat.
    var transcripts: [Int: [ChatTranscriptLine]] = [:]
}

/// Что приложение должно сделать по просьбе модели.
enum ToolEffect: Sendable {
    case sendToChat(number: Int, text: String)
    case saveMemory(String)
    case setSetting(name: String, value: String)
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

        case .none, .listChats, .readChat, .renameChat, .pinChat, .sendToChat, .saveMemory, .setAppSetting:
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
                                  content: "Чаты пользователя (всего \(context.chats.count)):\n" + lines.joined(separator: "\n"))

        case .readChat:
            guard let number = arguments["number"] as? Int else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Не передан номер чата.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }),
                  let transcript = context.transcripts[number] else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            let limit = max(1, min((arguments["messages"] as? Int) ?? 40, 120))
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
            guard let number = arguments["number"] as? Int,
                  let title = arguments["title"] as? String,
                  !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны номер чата и новое название.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Чат «\(chat.title)» переименован в «\(title)».",
                                  effect: .setSetting(name: "rename_chat:\(number)", value: title))

        case .pinChat:
            guard let number = arguments["number"] as? Int,
                  let pinned = arguments["pinned"] as? Bool else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны номер чата и признак закрепления.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Чат «\(chat.title)» \(pinned ? "закреплён" : "откреплён").",
                                  effect: .setSetting(name: "pin_chat:\(number)", value: pinned ? "true" : "false"))

        case .sendToChat:
            guard let number = arguments["number"] as? Int,
                  let text = arguments["text"] as? String,
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Нужны номер чата и текст сообщения.")
            }
            guard let chat = context.chats.first(where: { $0.number == number }) else {
                return ToolCallResult(callID: call.id, name: call.name,
                                      content: "Чат с номером \(number) не найден. Сначала вызови list_chats.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Сообщение отправлено в чат «\(chat.title)»: \(text.prefix(200))",
                                  effect: .sendToChat(number: number, text: text))

        case .saveMemory:
            guard let text = arguments["text"] as? String,
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ToolCallResult(callID: call.id, name: call.name, content: "Не передан текст для памяти.")
            }
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Записано в память: \(text)",
                                  effect: .saveMemory(text))

        case .setAppSetting:
            guard let name = arguments["name"] as? String,
                  let value = arguments["value"] as? String else {
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
