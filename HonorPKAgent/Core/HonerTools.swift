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

/// Результат выполнения инструмента, который уходит обратно в модель.
struct ToolCallResult: Sendable {
    let callID: String
    let name: String
    let content: String
}

/// Доступные приложению данные, нужные инструментам.
struct ToolExecutionContext: Sendable {
    var deviceModel: String = "iPhone"
    var systemVersion: String = ""
    var appVersion: String = "10.8"
    var currentDateTime: String = ""
    var messageCount: Int = 0
    var voiceMessageCount: Int = 0
    var chatStartedAt: Date? = nil
    var lastMessageAt: Date? = nil
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

        case .none:
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Инструмент «\(call.name)» недоступен.")
        }
    }
}
