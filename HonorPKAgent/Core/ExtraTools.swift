import AVFoundation
import Foundation
import Speech
import UIKit

// MARK: - Описание новых инструментов для API

/// Схемы новых инструментов: таблицы, память, массовое чтение сайтов,
/// интеграции (YouTube, GitHub, маркетплейсы, ВКонтакте, Telegram),
/// просмотр изображений, расшифровка аудио и видео, редактор фото.
enum ExtraToolSchemas {
    static func schema(for tool: HonerTool) -> [String: Any] {
        switch tool {
        case .createTable:
            return function(tool, "Создаёт таблицу прямо в чате. Пользователь откроет её на весь экран; редактируемую таблицу (editable=true) он может дополнять и править сам, а ты видишь все его правки в разделе «Таблицы этого чата». Используй для учёта, планов, списков, сравнений, трекеров, когда таблицу будут дополнять. Для разового сравнения достаточно Markdown-таблицы в ответе.", [
                "title": ["type": "string", "description": "Название таблицы"],
                "columns": ["type": "array", "items": ["type": "string"], "description": "Названия столбцов"],
                "rows": ["type": "array", "items": ["type": "array", "items": ["type": "string"]], "description": "Строки: массивы значений в порядке столбцов"],
                "editable": ["type": "boolean", "description": "true — пользователь может редактировать; false — только просмотр"]
            ], ["title", "columns"])
        case .updateTable:
            return function(tool, "Меняет таблицу чата по номеру (T1, T2… из раздела «Таблицы этого чата»). Действия: set_cell (row, column, value), add_row (values), delete_row (row), add_column (name, values), delete_column (column), rename_column (column, name), set_title (title), sort (column, descending), replace (columns, rows), set_editable (editable). Номера строк и столбцов — с 1; столбец можно указать названием.", [
                "table": ["type": "string", "description": "Номер таблицы: T1, T2 или 1, 2"],
                "action": ["type": "string", "description": "set_cell, add_row, delete_row, add_column, delete_column, rename_column, set_title, sort, replace, set_editable"],
                "row": ["type": "integer", "description": "Номер строки (с 1)"],
                "column": ["type": "string", "description": "Номер (с 1) или название столбца"],
                "value": ["type": "string", "description": "Новое значение ячейки"],
                "values": ["type": "array", "items": ["type": "string"], "description": "Значения новой строки или столбца"],
                "name": ["type": "string", "description": "Название столбца"],
                "title": ["type": "string", "description": "Новое название таблицы"],
                "columns": ["type": "array", "items": ["type": "string"]],
                "rows": ["type": "array", "items": ["type": "array", "items": ["type": "string"]]],
                "descending": ["type": "boolean"],
                "editable": ["type": "boolean"]
            ], ["table", "action"])
        case .readTable:
            return function(tool, "Возвращает таблицу чата целиком (с последними правками пользователя) по номеру T1, T2…", [
                "table": ["type": "string", "description": "Номер таблицы: T1, T2 или 1, 2"]
            ], ["table"])
        case .listMemory:
            return function(tool, "Показывает сохранённые факты памяти о пользователе с номерами. Можно отфильтровать по словам.", [
                "query": ["type": "string", "description": "Слова для отбора, необязательно"]
            ], [])
        case .updateMemory:
            return function(tool, "Исправляет факт памяти по номеру из list_memory. Используй, когда факт устарел или пользователь его уточнил.", [
                "number": ["type": "integer", "description": "Номер факта из list_memory"],
                "text": ["type": "string", "description": "Новый текст факта"]
            ], ["number", "text"])
        case .deleteMemory:
            return function(tool, "Удаляет факт памяти по номеру из list_memory. Используй, когда пользователь просит забыть что-то или факт неверен.", [
                "number": ["type": "integer", "description": "Номер факта из list_memory"]
            ], ["number"])
        case .readManyPages:
            return function(tool, "Быстро читает много сайтов параллельно (от десятков до тысяч) и возвращает самые подходящие к вопросу выдержки со ссылками. Передай список адресов urls и/или поисковые запросы queries (по каждому соберутся ссылки из выдачи). Для исследований, сравнений цен, обзоров мнений, сбора фактов из многих источников.", [
                "question": ["type": "string", "description": "Что именно нужно найти на страницах"],
                "urls": ["type": "array", "items": ["type": "string"], "description": "Адреса страниц (до 10 000)"],
                "queries": ["type": "array", "items": ["type": "string"], "description": "Поисковые запросы для сбора ссылок (до 20)"],
                "max_pages": ["type": "integer", "description": "Сколько страниц прочитать максимум, по умолчанию 60"],
                "time_limit": ["type": "integer", "description": "Лимит времени в секундах, по умолчанию 60, максимум 300"]
            ], ["question"])
        case .youtubeSearch:
            return function(tool, "Ищет видео на YouTube: название, канал, длительность, просмотры и ссылку. Ссылки вставляй в ответ — приложение покажет видео с кнопкой воспроизведения.", [
                "query": ["type": "string", "description": "Поисковый запрос"],
                "count": ["type": "integer", "description": "Сколько видео, по умолчанию 6"]
            ], ["query"])
        case .youtubeVideo:
            return function(tool, "Открывает видео YouTube по ссылке или id: название, автор, длительность, описание и текст субтитров (что говорят в видео), если они доступны.", [
                "video": ["type": "string", "description": "Ссылка на видео или его id"]
            ], ["video"])
        case .github:
            return function(tool, "Работает с публичным GitHub: search (поиск репозиториев), repo (описание и README), files (список файлов в папке), file (содержимое файла), issues (открытые задачи), releases (релизы), user (репозитории пользователя).", [
                "action": ["type": "string", "description": "search, repo, files, file, issues, releases или user"],
                "query": ["type": "string", "description": "Запрос для search или имя для user"],
                "repo": ["type": "string", "description": "Репозиторий в виде owner/name или ссылка"],
                "path": ["type": "string", "description": "Путь к файлу или папке"]
            ], ["action"])
        case .marketplaceSearch:
            return function(tool, "Ищет товары на маркетплейсах и досках объявлений: wildberries, ozon, avito, yandex_market. Возвращает названия, цены, рейтинг и ссылки.", [
                "store": ["type": "string", "description": "wildberries, ozon, avito или yandex_market"],
                "query": ["type": "string", "description": "Что искать"],
                "count": ["type": "integer", "description": "Сколько товаров, по умолчанию 8"]
            ], ["store", "query"])
        case .vkPage:
            return function(tool, "Читает публичную страницу ВКонтакте: сообщество или профиль (короткое имя или ссылка) — описание и последние записи, если они открыты без входа.", [
                "page": ["type": "string", "description": "Короткое имя (например, durov) или ссылка vk.com/…"]
            ], ["page"])
        case .telegramChannel:
            return function(tool, "Читает последние публикации публичного канала Telegram по имени или ссылке t.me/…", [
                "channel": ["type": "string", "description": "Имя канала (например, durov) или ссылка"]
            ], ["channel"])
        case .viewImage:
            return function(tool, "Рассматривает изображение по ссылке (или фото из этого чата по имени) и подробно описывает, что на нём: объекты, текст, людей, детали. Используй, чтобы увидеть картинку с сайта или ответить на вопрос о ней.", [
                "url": ["type": "string", "description": "Адрес изображения или имя файла из чата"],
                "question": ["type": "string", "description": "Что нужно понять по изображению"]
            ], ["url"])
        case .transcribeMedia:
            return function(tool, "Расшифровывает речь из аудио или видео: голосовые сообщения, подкасты, ролики. Источник — ссылка на файл (mp3, m4a, wav, mp4, mov) или имя файла из этого чата.", [
                "source": ["type": "string", "description": "Ссылка на аудио/видео или имя файла из чата"],
                "language": ["type": "string", "description": "ru или en, по умолчанию ru"]
            ], ["source"])
        case .editImage:
            return function(tool, "Редактирует фото из чата и показывает результат пользователю: remove_background (убрать фон), background_color (value — цвет), background_blur, filter (value: vivid, warm, cool, mono, noir, sepia, fade, chrome, instant, dramatic, vignette, sharpen, blur), adjust (brightness/contrast/saturation через amount), rotate, flip, crop (value: square, portrait4x5, story9x16, landscape16x9), text (text, x, y от 0 до 1, value — цвет), sticker (text — эмодзи), resize (amount — длинная сторона).", [
                "source": ["type": "string", "description": "Имя фото из чата или last — последнее фото"],
                "operations": ["type": "array", "items": ["type": "object"], "description": "Список операций: [{\"type\":\"remove_background\"}, {\"type\":\"text\",\"text\":\"Привет\",\"x\":0.5,\"y\":0.85}]"]
            ], ["operations"])
        default:
            return function(tool, "", [:], [])
        }
    }

    private static func function(_ tool: HonerTool, _ description: String,
                                 _ properties: [String: Any], _ required: [String]) -> [String: Any] {
        [
            "type": "function",
            "function": [
                "name": tool.rawValue,
                "description": description,
                "parameters": ["type": "object", "properties": properties, "required": required]
            ] as [String: Any]
        ]
    }

    /// Шаг для ленты «что делает Honer AI».
    static func step(for call: ToolCallRequest) -> GenerationStep? {
        let arguments = call.parsedArguments
        func argument(_ key: String) -> String { String((ToolArgument.string(arguments[key]) ?? "").prefix(120)) }
        switch HonerTool(rawValue: call.name) {
        case .createTable: return GenerationStep(kind: "table", title: "Создаю таблицу", detail: argument("title"))
        case .updateTable: return GenerationStep(kind: "table", title: "Обновляю таблицу", detail: argument("table"))
        case .readTable: return GenerationStep(kind: "table", title: "Смотрю таблицу", detail: argument("table"))
        case .listMemory: return GenerationStep(kind: "memory", title: "Смотрю память")
        case .updateMemory: return GenerationStep(kind: "memory", title: "Исправляю факт в памяти", detail: argument("text"))
        case .deleteMemory: return GenerationStep(kind: "memory", title: "Удаляю факт из памяти")
        case .readManyPages: return GenerationStep(kind: "read", title: "Читаю много сайтов", detail: argument("question"))
        case .youtubeSearch: return GenerationStep(kind: "videos", title: "Ищу на YouTube", detail: "«\(argument("query"))»", sites: ["youtube.com"])
        case .youtubeVideo: return GenerationStep(kind: "videos", title: "Смотрю видео YouTube", detail: argument("video"), sites: ["youtube.com"])
        case .github: return GenerationStep(kind: "read", title: "Смотрю GitHub", detail: argument("repo").isEmpty ? argument("query") : argument("repo"), sites: ["github.com"])
        case .marketplaceSearch: return GenerationStep(kind: "search", title: "Ищу товары", detail: "\(argument("store")): «\(argument("query"))»")
        case .vkPage: return GenerationStep(kind: "read", title: "Читаю ВКонтакте", detail: argument("page"), sites: ["vk.com"])
        case .telegramChannel: return GenerationStep(kind: "read", title: "Читаю канал Telegram", detail: argument("channel"), sites: ["t.me"])
        case .viewImage: return GenerationStep(kind: "images", title: "Рассматриваю изображение", detail: argument("question"))
        case .transcribeMedia: return GenerationStep(kind: "read", title: "Слушаю и расшифровываю", detail: argument("source"))
        case .editImage: return GenerationStep(kind: "draw", title: "Редактирую фото")
        default: return nil
        }
    }

    static func status(for names: Set<String>) -> String? {
        let map: [(HonerTool, String)] = [
            (.readManyPages, "Читаю сайты…"), (.youtubeSearch, "Ищу на YouTube…"), (.youtubeVideo, "Смотрю видео…"),
            (.github, "Смотрю GitHub…"), (.marketplaceSearch, "Ищу товары…"), (.vkPage, "Читаю ВКонтакте…"),
            (.telegramChannel, "Читаю Telegram…"), (.viewImage, "Рассматриваю изображение…"),
            (.transcribeMedia, "Расшифровываю запись…"), (.editImage, "Редактирую фото…"),
            (.createTable, "Создаю таблицу…"), (.updateTable, "Обновляю таблицу…"), (.readTable, "Смотрю таблицу…"),
            (.listMemory, "Смотрю память…"), (.updateMemory, "Обновляю память…"), (.deleteMemory, "Обновляю память…")
        ]
        return map.first { names.contains($0.0.rawValue) }?.1
    }
}

// MARK: - Таблицы

enum TableEditing {
    static let maximumRows = 2000
    static let maximumColumns = 40

    static func strings(_ value: Any?) -> [String] {
        if let list = value as? [Any] { return list.map { ToolArgument.string($0) ?? "" } }
        if let text = value as? String {
            return text.split(whereSeparator: { $0 == "|" || $0 == ";" || $0 == "," })
                .map { $0.trimmingCharacters(in: .whitespaces) }
        }
        return []
    }

    static func rows(_ value: Any?, columns: [String]) -> [[String]] {
        guard let list = value as? [Any] else { return [] }
        return list.prefix(maximumRows).map { item -> [String] in
            if let dictionary = item as? [String: Any] {
                return columns.map { ToolArgument.string(dictionary[$0]) ?? "" }
            }
            return strings(item)
        }
    }

    static func make(from arguments: [String: Any]) -> ChatTable? {
        var columns = Array(strings(arguments["columns"]).prefix(maximumColumns))
        var rows = rows(arguments["rows"], columns: columns)
        if columns.isEmpty, let first = rows.first {
            columns = first
            rows = Array(rows.dropFirst())
        }
        guard !columns.isEmpty else { return nil }
        let title = (ToolArgument.string(arguments["title"]) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let editable = ToolArgument.bool(arguments["editable"]) ?? true
        return ChatTable(title: title.isEmpty ? "Таблица" : String(title.prefix(120)),
                         columns: columns, rows: rows.map { normalized($0, width: columns.count) },
                         editable: editable)
    }

    static func normalized(_ row: [String], width: Int) -> [String] {
        if row.count == width { return row }
        if row.count > width { return Array(row.prefix(width)) }
        return row + Array(repeating: "", count: width - row.count)
    }

    enum EditError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
    }

    /// Номер столбца по номеру (с 1) или названию.
    static func columnIndex(_ value: Any?, in table: ChatTable) -> Int? {
        if let number = ToolArgument.int(value), number >= 1, number <= table.columns.count { return number - 1 }
        guard let name = ToolArgument.string(value)?.trimmingCharacters(in: .whitespaces).lowercased(), !name.isEmpty else { return nil }
        return table.columns.firstIndex { $0.trimmingCharacters(in: .whitespaces).lowercased() == name }
    }

    static func apply(_ arguments: [String: Any], to source: ChatTable) throws -> ChatTable {
        var table = source
        let action = (ToolArgument.string(arguments["action"]) ?? "").lowercased()
        func row() throws -> Int {
            guard let number = ToolArgument.int(arguments["row"]), number >= 1, number <= table.rows.count else {
                throw EditError.message("Нет строки с таким номером: в таблице \(table.rows.count) строк.")
            }
            return number - 1
        }
        func column() throws -> Int {
            guard let index = columnIndex(arguments["column"], in: table) else {
                throw EditError.message("Нет такого столбца. Столбцы: \(table.columns.joined(separator: ", ")).")
            }
            return index
        }
        switch action {
        case "set_cell", "set", "update_cell":
            let r = try row(), c = try column()
            table.rows[r][c] = ToolArgument.string(arguments["value"]) ?? ""
        case "add_row", "append_row":
            guard table.rows.count < maximumRows else { throw EditError.message("В таблице уже \(maximumRows) строк.") }
            var values = strings(arguments["values"])
            // Модель иногда передаёт первым значением номер строки из столбца «№».
            if values.count == table.columns.count + 1, Int(values[0].trimmingCharacters(in: .whitespaces)) != nil {
                values.removeFirst()
            }
            if values.isEmpty, let dictionary = arguments["values"] as? [String: Any] {
                values = table.columns.map { ToolArgument.string(dictionary[$0]) ?? "" }
            }
            table.rows.append(normalized(values, width: table.columns.count))
        case "delete_row", "remove_row":
            table.rows.remove(at: try row())
        case "add_column":
            guard table.columns.count < maximumColumns else { throw EditError.message("Столбцов не больше \(maximumColumns).") }
            let name = (ToolArgument.string(arguments["name"]) ?? "Столбец \(table.columns.count + 1)")
            let values = strings(arguments["values"])
            table.columns.append(name)
            for index in table.rows.indices {
                table.rows[index].append(index < values.count ? values[index] : "")
            }
        case "delete_column", "remove_column":
            guard table.columns.count > 1 else { throw EditError.message("Нельзя удалить единственный столбец.") }
            let c = try column()
            table.columns.remove(at: c)
            for index in table.rows.indices where c < table.rows[index].count { table.rows[index].remove(at: c) }
        case "rename_column":
            let c = try column()
            let name = (ToolArgument.string(arguments["name"]) ?? "").trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { throw EditError.message("Не передано новое название столбца.") }
            table.columns[c] = name
        case "set_title", "rename":
            let title = (ToolArgument.string(arguments["title"]) ?? ToolArgument.string(arguments["name"]) ?? "")
                .trimmingCharacters(in: .whitespaces)
            guard !title.isEmpty else { throw EditError.message("Не передано название.") }
            table.title = String(title.prefix(120))
        case "sort":
            let c = try column()
            let descending = ToolArgument.bool(arguments["descending"]) ?? false
            table.rows.sort { lhs, rhs in
                let left = c < lhs.count ? lhs[c] : "", right = c < rhs.count ? rhs[c] : ""
                if let a = Double(left.replacingOccurrences(of: ",", with: ".").filter { !$0.isWhitespace }),
                   let b = Double(right.replacingOccurrences(of: ",", with: ".").filter { !$0.isWhitespace }) {
                    return descending ? a > b : a < b
                }
                let order = left.localizedStandardCompare(right)
                return descending ? order == .orderedDescending : order == .orderedAscending
            }
        case "replace", "replace_all":
            let defaults: [String: Any] = ["title": table.title, "editable": table.editable]
            let merged: [String: Any] = arguments.merging(defaults) { current, _ in current }
            guard let replacement = make(from: merged) else {
                throw EditError.message("Для replace нужны columns и rows.")
            }
            table.columns = replacement.columns
            table.rows = replacement.rows
        case "set_editable":
            table.editable = ToolArgument.bool(arguments["editable"]) ?? true
        default:
            throw EditError.message("Неизвестное действие «\(action)». Доступно: set_cell, add_row, delete_row, add_column, delete_column, rename_column, set_title, sort, replace, set_editable.")
        }
        table.updatedAt = Date()
        return table
    }

    static func markdown(_ table: ChatTable, limitRows: Int = 300) -> String {
        func clean(_ value: String) -> String {
            value.replacingOccurrences(of: "|", with: "\\|").replacingOccurrences(of: "\n", with: " ")
        }
        var lines: [String] = []
        lines.append("| № | " + table.columns.map(clean).joined(separator: " | ") + " |")
        lines.append("|---|" + table.columns.map { _ in "---" }.joined(separator: "|") + "|")
        for (index, row) in table.rows.prefix(limitRows).enumerated() {
            lines.append("| \(index + 1) | " + normalized(row, width: table.columns.count).map(clean).joined(separator: " | ") + " |")
        }
        if table.rows.count > limitRows { lines.append("… ещё \(table.rows.count - limitRows) строк") }
        return lines.joined(separator: "\n")
    }

    static func csv(_ table: ChatTable) -> String {
        func field(_ value: String) -> String {
            guard value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == ";" }) else { return value }
            return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        var lines = [table.columns.map(field).joined(separator: ",")]
        for row in table.rows { lines.append(normalized(row, width: table.columns.count).map(field).joined(separator: ",")) }
        return lines.joined(separator: "\n")
    }

    /// Номер таблицы из «T2», «2», «t2».
    static func number(_ value: Any?) -> Int? {
        guard let text = ToolArgument.string(value)?.trimmingCharacters(in: .whitespaces).lowercased() else { return nil }
        let digits = text.filter(\.isNumber)
        return Int(digits)
    }

    /// Раздел системной инструкции: таблицы чата с актуальным содержимым.
    static func promptBlock(_ tables: [ChatTable]) -> String {
        guard !tables.isEmpty else { return "" }
        var budget = 12_000
        var parts: [String] = []
        for (index, table) in tables.enumerated() {
            let kind = table.editable ? "редактируемая" : "только просмотр"
            let edited = table.editedByUser ? ", пользователь вносил правки" : ""
            var block = "T\(index + 1). «\(table.title)» — \(kind), \(table.rows.count) строк\(edited)"
            if budget > 0 {
                let body = markdown(table, limitRows: 60)
                block += "\n" + String(body.prefix(budget))
                budget -= body.count
            }
            parts.append(block)
        }
        return "\n\n## Таблицы этого чата\nЭто актуальное содержимое таблиц, включая правки пользователя. Первый столбец «№» — номер строки для update_table, это не данные: в values его не передавай. Меняй таблицы инструментом update_table, читай целиком — read_table.\n" + parts.joined(separator: "\n\n")
    }
}

// MARK: - Выполнение

struct MemoryRef: Sendable {
    var id: UUID
    var text: String
}

/// Новые инструменты. Синхронные (таблицы, память) выполняются сразу,
/// сетевые и тяжёлые — асинхронно, с ходом выполнения для ленты шагов.
struct ExtraToolExecutor {
    var client: WebSearchClient = WebSearchClient()
    var context: ToolExecutionContext

    typealias ProgressHandler = WebToolExecutor.ProgressHandler

    private func reply(_ call: ToolCallRequest, _ text: String, effect: ToolEffect? = nil) -> ToolCallResult {
        ToolCallResult(callID: call.id, name: call.name, content: text, effect: effect)
    }

    // MARK: Таблицы и память (без сети)

    func executeLocal(_ call: ToolCallRequest) -> ToolCallResult {
        let arguments = call.parsedArguments
        switch HonerTool(rawValue: call.name) {
        case .createTable:
            guard let table = TableEditing.make(from: arguments) else {
                return reply(call, "Не удалось создать таблицу: нужны названия столбцов (columns).")
            }
            let number = context.tables.count + 1
            return reply(call, "Таблица T\(number) «\(table.title)» создана и показана пользователю в чате (\(table.rows.count) строк, \(table.editable ? "можно редактировать" : "только просмотр")). Не повторяй её содержимое в ответе целиком — коротко опиши, что в ней.",
                         effect: .createTable(table))
        case .updateTable, .readTable:
            guard let number = TableEditing.number(arguments["table"]), number >= 1, number <= context.tables.count else {
                return reply(call, context.tables.isEmpty ? "В этом чате пока нет таблиц. Создай её инструментом create_table."
                                                          : "Нет такой таблицы. Есть: " + context.tables.indices.map { "T\($0 + 1)" }.joined(separator: ", "))
            }
            let table = context.tables[number - 1]
            if HonerTool(rawValue: call.name) == .readTable {
                return reply(call, "Таблица T\(number) «\(table.title)»:\n" + TableEditing.markdown(table))
            }
            do {
                let updated = try TableEditing.apply(arguments, to: table)
                return reply(call, "Таблица T\(number) обновлена. Сейчас в ней \(updated.rows.count) строк.",
                             effect: .replaceTable(updated))
            } catch {
                return reply(call, error.localizedDescription)
            }
        case .listMemory:
            guard !context.memoryItems.isEmpty else { return reply(call, "Память пуста.") }
            let query = (ToolArgument.string(arguments["query"]) ?? "").lowercased()
            let words = ChatStore.keywords(in: query)
            var lines: [String] = []
            for (index, item) in context.memoryItems.enumerated() {
                let lower = item.text.lowercased()
                if !words.isEmpty && !words.contains(where: { lower.contains($0) }) { continue }
                lines.append("\(index + 1). \(item.text)")
                if lines.count >= 200 { break }
            }
            return reply(call, lines.isEmpty ? "Ничего не нашлось по запросу." : "Факты памяти:\n" + lines.joined(separator: "\n"))
        case .updateMemory, .deleteMemory:
            guard let number = ToolArgument.int(arguments["number"]), number >= 1, number <= context.memoryItems.count else {
                return reply(call, "Нет факта с таким номером. Сначала вызови list_memory.")
            }
            let item = context.memoryItems[number - 1]
            if HonerTool(rawValue: call.name) == .deleteMemory {
                return reply(call, "Факт удалён из памяти: «\(item.text)».", effect: .deleteMemory(item.id))
            }
            let text = (ToolArgument.string(arguments["text"]) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return reply(call, "Не передан новый текст факта.") }
            return reply(call, "Факт обновлён: «\(text)».", effect: .updateMemory(id: item.id, text: text))
        default:
            return reply(call, "Неизвестный инструмент.")
        }
    }

    // MARK: Сеть и медиа

    func execute(_ call: ToolCallRequest, progress: ProgressHandler? = nil) async -> ToolCallResult {
        let arguments = call.parsedArguments
        func text(_ key: String) -> String {
            (ToolArgument.string(arguments[key]) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        switch HonerTool(rawValue: call.name) {
        case .readManyPages:
            return await BulkPageReader(client: client).run(call: call, progress: progress)
        case .youtubeSearch:
            return await IntegrationClient(client: client).youtubeSearch(call: call, query: text("query"),
                                                                         count: ToolArgument.int(arguments["count"]) ?? 6)
        case .youtubeVideo:
            return await IntegrationClient(client: client).youtubeVideo(call: call, reference: text("video"))
        case .github:
            return await IntegrationClient(client: client).github(call: call, action: text("action").lowercased(),
                                                                  query: text("query"), repo: text("repo"), path: text("path"))
        case .marketplaceSearch:
            return await IntegrationClient(client: client).marketplace(call: call, store: text("store").lowercased(),
                                                                       query: text("query"),
                                                                       count: ToolArgument.int(arguments["count"]) ?? 8,
                                                                       progress: progress)
        case .vkPage:
            return await IntegrationClient(client: client).vk(call: call, page: text("page"))
        case .telegramChannel:
            return await IntegrationClient(client: client).telegram(call: call, channel: text("channel"))
        case .viewImage:
            return await MediaInsight(context: context, client: client).viewImage(call: call, reference: text("url"),
                                                                                  question: text("question"))
        case .transcribeMedia:
            return await MediaInsight(context: context, client: client).transcribe(call: call, reference: text("source"),
                                                                                   language: text("language"), progress: progress)
        case .editImage:
            return await MediaInsight(context: context, client: client).editImage(call: call, source: text("source"),
                                                                                  operations: arguments["operations"] ?? arguments)
        default:
            return executeLocal(call)
        }
    }
}

// MARK: - Массовое чтение сайтов

/// Читает много страниц параллельно и отбирает подходящие к вопросу выдержки.
struct BulkPageReader {
    var client: WebSearchClient

    static let concurrency = 24
    static let pageTimeout: TimeInterval = 8
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = BulkPageReader.pageTimeout
        configuration.timeoutIntervalForResource = BulkPageReader.pageTimeout + 4
        configuration.httpMaximumConnectionsPerHost = 4
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }()

    struct Page: Sendable {
        var url: URL
        var title: String
        var passages: [(score: Int, text: String)]
    }

    func run(call: ToolCallRequest, progress: WebToolExecutor.ProgressHandler?) async -> ToolCallResult {
        let arguments = call.parsedArguments
        let question = (ToolArgument.string(arguments["question"]) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let maxPages = min(10_000, max(1, ToolArgument.int(arguments["max_pages"]) ?? 60))
        let timeLimit = Double(min(300, max(10, ToolArgument.int(arguments["time_limit"]) ?? 60)))
        let started = Date()

        // 1. Адреса: переданные и собранные из поисковой выдачи.
        var urls: [URL] = TableEditing.strings(arguments["urls"]).compactMap { WebToolExecutor.url(from: $0) }
        let queries = Array(TableEditing.strings(arguments["queries"]).filter { !$0.isEmpty }.prefix(20))
        if urls.isEmpty && queries.isEmpty && !question.isEmpty {
            urls += await gather(queries: [question], progress: progress)
        } else if !queries.isEmpty {
            urls += await gather(queries: queries, progress: progress)
        }
        var seen = Set<String>()
        urls = urls.filter { WebPageText.isPublicWebURL($0) && seen.insert($0.absoluteString).inserted }
        let targets = Array(urls.prefix(maxPages))
        guard !targets.isEmpty else {
            return ToolCallResult(callID: call.id, name: call.name, content: "Не нашлось ни одного адреса для чтения. Передай urls или queries.")
        }

        // 2. Параллельное чтение с ограничением числа потоков и общего времени.
        let words = Set(ChatStore.keywords(in: question))
        let deadline = started.addingTimeInterval(timeLimit)
        var pages: [Page] = []
        var failed = 0
        var done = 0
        var lastReport = Date.distantPast
        await withTaskGroup(of: Page?.self) { group in
            var nextIndex = 0
            while nextIndex < min(Self.concurrency, targets.count) {
                let url = targets[nextIndex]
                nextIndex += 1
                group.addTask { await Self.read(url, words: words) }
            }
            while let page = await group.next() {
                done += 1
                if let page { pages.append(page) } else { failed += 1 }
                if Date().timeIntervalSince(lastReport) > 0.25 || done == targets.count {
                    lastReport = Date()
                    let hosts = pages.suffix(4).compactMap { $0.url.host }
                    progress?("Прочитано \(done) из \(targets.count)", hosts)
                }
                if Date() >= deadline {
                    group.cancelAll()
                    break
                }
                if nextIndex < targets.count {
                    let url = targets[nextIndex]
                    nextIndex += 1
                    group.addTask { await Self.read(url, words: words) }
                }
            }
        }

        // 3. Лучшие выдержки со всех страниц в пределах объёма.
        var ranked: [(score: Int, text: String, page: Int)] = []
        for (index, page) in pages.enumerated() {
            for passage in page.passages { ranked.append((passage.score, passage.text, index)) }
        }
        ranked.sort { $0.score > $1.score }
        var budget = 28_000
        var used: [Int: [String]] = [:]
        var bestScore: [Int: Int] = [:]
        for item in ranked where budget > 0 {
            if used[item.page, default: []].count >= 3 { continue }
            used[item.page, default: []].append(item.text)
            if bestScore[item.page] == nil { bestScore[item.page] = item.score }
            budget -= item.text.count
        }
        let order = used.keys.sorted { (bestScore[$0] ?? 0) > (bestScore[$1] ?? 0) }
        var lines: [String] = []
        var sources: [WebSource] = []
        for (number, pageIndex) in order.enumerated() {
            let page = pages[pageIndex]
            lines.append("[\(number + 1)] \(page.title) — \(page.url.absoluteString)")
            for passage in used[pageIndex] ?? [] { lines.append("   > " + passage) }
            if sources.count < 25 {
                sources.append(WebSource(title: page.title, url: page.url,
                                         snippet: String((used[pageIndex]?.first ?? "").prefix(280)),
                                         content: (used[pageIndex] ?? []).joined(separator: "\n"), fetchedAt: Date()))
            }
        }
        let seconds = Int(Date().timeIntervalSince(started).rounded())
        let skipped = max(0, targets.count - done)
        var header = "Прочитано страниц: \(pages.count) из \(targets.count) (не открылись: \(failed)"
        if skipped > 0 { header += ", не успел за лимит времени: \(skipped)" }
        header += ") за \(seconds) с."
        if urls.count > targets.count { header += " Всего адресов было \(urls.count), прочитаны первые \(targets.count) (max_pages)." }
        let body = lines.isEmpty ? "Подходящих к вопросу выдержек не нашлось." : "Самые подходящие выдержки (номер источника — для ссылок [N](URL)):\n" + lines.joined(separator: "\n")
        return ToolCallResult(callID: call.id, name: call.name, content: header + "\n" + body,
                              effect: sources.isEmpty ? nil : .addSources(sources))
    }

    private func gather(queries: [String], progress: WebToolExecutor.ProgressHandler?) async -> [URL] {
        var result: [URL] = []
        await withTaskGroup(of: [URL].self) { group in
            for query in queries {
                group.addTask {
                    async let bing = client.bingResults(query)
                    async let duck = client.duckDuckGoResults(query)
                    async let brave = client.braveResults(query)
                    let all = await bing + duck + brave
                    return all.map(\.url)
                }
            }
            for await urls in group {
                result += urls
                progress?("Собрано ссылок: \(result.count)", Array(urls.compactMap { $0.host }.prefix(4)))
            }
        }
        return result
    }

    /// Одна страница: быстрый запрос, текст и лучшие абзацы.
    static func read(_ url: URL, words: Set<String>) async -> Page? {
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        request.setValue("ru-RU,ru;q=0.9,en;q=0.6", forHTTPHeaderField: "Accept-Language")
        guard let (data, response) = try? await session.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        let type = (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        guard type.isEmpty || type.contains("html") || type.contains("text") || type.contains("json") || type.contains("xml") else { return nil }
        let html = String(decoding: data.prefix(2_500_000), as: UTF8.self)
        let text = type.contains("html") || html.contains("<html") || html.contains("<body") ? WebPageText.extract(html) : html
        guard text.count >= 80, !WebPageText.looksLikeChallenge(text) else { return nil }
        let title = WebPageText.title(html) ?? (url.host ?? url.absoluteString)
        return Page(url: http.url ?? url, title: title, passages: passages(in: text, words: words))
    }

    /// Абзацы страницы с оценкой по совпадению слов вопроса.
    static func passages(in text: String, words: Set<String>) -> [(score: Int, text: String)] {
        var chunks: [String] = []
        var current = ""
        for line in text.components(separatedBy: "\n") where line.count >= 30 {
            if current.count + line.count > 600, !current.isEmpty {
                chunks.append(current)
                current = ""
            }
            current += (current.isEmpty ? "" : " ") + line
            if chunks.count > 400 { break }
        }
        if !current.isEmpty { chunks.append(current) }
        var scored: [(score: Int, text: String)] = []
        for chunk in chunks {
            let lower = chunk.lowercased()
            var score = 0
            for word in words where lower.contains(word) { score += 3 }
            if lower.range(of: "\\d", options: .regularExpression) != nil { score += 1 }
            if words.isEmpty { score = 1 }
            if score > 0 { scored.append((score, String(chunk.prefix(700)))) }
        }
        return Array(scored.sorted { $0.score > $1.score }.prefix(3))
    }
}

// MARK: - Интеграции

struct IntegrationClient {
    var client: WebSearchClient
    static let desktopAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    private func reply(_ call: ToolCallRequest, _ text: String, sources: [WebSource] = []) -> ToolCallResult {
        ToolCallResult(callID: call.id, name: call.name, content: text, effect: sources.isEmpty ? nil : .addSources(sources))
    }

    func get(_ url: URL, accept: String? = nil, agent: String = IntegrationClient.desktopAgent,
             timeout: TimeInterval = 12) async -> (data: Data, status: Int)? {
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.setValue(agent, forHTTPHeaderField: "User-Agent")
        request.setValue("ru-RU,ru;q=0.9,en;q=0.6", forHTTPHeaderField: "Accept-Language")
        if let accept { request.setValue(accept, forHTTPHeaderField: "Accept") }
        guard let (data, response) = try? await BulkPageReader.session.data(for: request),
              let http = response as? HTTPURLResponse else { return nil }
        return (data, http.statusCode)
    }

    func json(_ url: URL, accept: String = "application/json") async -> Any? {
        guard let result = await get(url, accept: accept), (200..<300).contains(result.status) else { return nil }
        return try? JSONSerialization.jsonObject(with: result.data)
    }

    // MARK: YouTube

    /// JSON, встроенный в страницу YouTube: `var ytInitialData = {...};`
    static func embeddedJSON(named name: String, in html: String) -> Any? {
        let markers = ["var \(name) = ", "\(name) = ", "window[\"\(name)\"] = "]
        for marker in markers {
            guard let start = html.range(of: marker) else { continue }
            let tail = html[start.upperBound...]
            guard tail.first == "{" else { continue }
            // Ищем конец объекта по балансу скобок с учётом строк.
            var depth = 0
            var inString = false
            var escaped = false
            var end: String.Index?
            for index in tail.indices {
                let character = tail[index]
                if inString {
                    if escaped { escaped = false } else if character == "\\" { escaped = true } else if character == "\"" { inString = false }
                    continue
                }
                if character == "\"" { inString = true } else if character == "{" { depth += 1 } else if character == "}" {
                    depth -= 1
                    if depth == 0 { end = index; break }
                }
            }
            guard let end else { continue }
            let text = String(tail[tail.startIndex...end])
            if let data = text.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data) { return object }
        }
        return nil
    }

    static func collect(_ key: String, in object: Any, limit: Int, into result: inout [[String: Any]]) {
        guard result.count < limit else { return }
        if let dictionary = object as? [String: Any] {
            if let found = dictionary[key] as? [String: Any] { result.append(found) }
            for (name, value) in dictionary where name != key { collect(key, in: value, limit: limit, into: &result) }
        } else if let list = object as? [Any] {
            for value in list { collect(key, in: value, limit: limit, into: &result) }
        }
    }

    static func runsText(_ value: Any?) -> String {
        if let dictionary = value as? [String: Any] {
            if let simple = dictionary["simpleText"] as? String { return simple }
            if let runs = dictionary["runs"] as? [[String: Any]] { return runs.compactMap { $0["text"] as? String }.joined() }
        }
        return ""
    }

    func youtubeSearch(call: ToolCallRequest, query: String, count: Int) async -> ToolCallResult {
        guard !query.isEmpty else { return reply(call, "Не передан запрос.") }
        let videos = await youTubeVideos(query, limit: count)
        if videos.isEmpty {
            // Запасной путь: поиск видео через поисковики.
            let found = await client.videoResults(query + " youtube", count: count)
            guard !found.isEmpty else { return reply(call, "YouTube сейчас не ответил, и видео не нашлись. Скажи об этом пользователю.") }
            let lines = found.map { "• [\($0.title)](\($0.url.absoluteString))" }
            return reply(call, "Видео по запросу «\(query)»:\n" + lines.joined(separator: "\n") + "\nВставь ссылки в ответ — приложение покажет видео с кнопкой воспроизведения.",
                         sources: found.map { WebSource(title: $0.title, url: $0.url, snippet: "") })
        }
        var lines: [String] = []
        var sources: [WebSource] = []
        for video in videos.prefix(count) {
            let link = "https://www.youtube.com/watch?v=\(video.id)"
            let details = [video.channel, video.length, video.views, video.published].filter { !$0.isEmpty }.joined(separator: " · ")
            lines.append("• [\(video.title)](\(link)) — \(details)")
            if let url = URL(string: link) { sources.append(WebSource(title: video.title, url: url, snippet: details)) }
        }
        return reply(call, "YouTube по запросу «\(query)»:\n" + lines.joined(separator: "\n")
                     + "\nВставь нужные ссылки в ответ отдельными строками — приложение покажет видео с кнопкой воспроизведения.",
                     sources: sources)
    }

    struct YouTubeVideo {
        var id: String
        var title: String
        var channel: String
        var length: String
        var views: String
        var published: String
    }

    /// Ролики со страницы поиска YouTube.
    func youTubeVideos(_ query: String, limit: Int) async -> [YouTubeVideo] {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        guard let url = URL(string: "https://www.youtube.com/results?search_query=\(encoded)&hl=ru&gl=RU"),
              let page = await get(url), page.status == 200,
              let data = Self.embeddedJSON(named: "ytInitialData", in: String(decoding: page.data, as: UTF8.self)) else { return [] }
        var renderers: [[String: Any]] = []
        Self.collect("videoRenderer", in: data, limit: max(1, min(limit, 20)), into: &renderers)
        return renderers.compactMap { item -> YouTubeVideo? in
            guard let id = item["videoId"] as? String else { return nil }
            return YouTubeVideo(id: id, title: Self.runsText(item["title"]), channel: Self.runsText(item["ownerText"]),
                                length: Self.runsText(item["lengthText"]), views: Self.runsText(item["viewCountText"]),
                                published: Self.runsText(item["publishedTimeText"]))
        }
    }

    func youtubeVideo(call: ToolCallRequest, reference: String) async -> ToolCallResult {
        var id = reference
        if let url = URL(string: reference), let parsed = MediaLinks.youTubeID(url) { id = parsed }
        id = id.trimmingCharacters(in: .whitespaces)
        guard id.count >= 6, let url = URL(string: "https://www.youtube.com/watch?v=\(id)&hl=ru") else {
            return reply(call, "Не удалось понять, какое это видео. Нужна ссылка на YouTube.")
        }
        guard let page = await get(url), page.status == 200 else {
            return reply(call, "YouTube не открылся. Скажи пользователю, что видео сейчас недоступно.")
        }
        let html = String(decoding: page.data, as: UTF8.self)
        guard let player = Self.embeddedJSON(named: "ytInitialPlayerResponse", in: html) as? [String: Any],
              let details = player["videoDetails"] as? [String: Any] else {
            return reply(call, "Не удалось прочитать данные видео.")
        }
        let title = details["title"] as? String ?? "Видео"
        let author = details["author"] as? String ?? ""
        let seconds = Int(details["lengthSeconds"] as? String ?? "") ?? 0
        let views = details["viewCount"] as? String ?? ""
        let description = String((details["shortDescription"] as? String ?? "").prefix(3000))
        var text = "Видео: \(title)\nКанал: \(author)\nДлительность: \(seconds / 60) мин \(seconds % 60) с\nПросмотров: \(views)\nСсылка: https://www.youtube.com/watch?v=\(id)\n\nОписание:\n\(description)"
        let transcript = await captions(player)
        if transcript.isEmpty {
            text += "\n\nСубтитры недоступны: о содержании суди по названию и описанию и честно скажи об этом."
        } else {
            text += "\n\nСубтитры (что говорят в видео):\n" + String(transcript.prefix(24_000))
        }
        let source = URL(string: "https://www.youtube.com/watch?v=\(id)").map { WebSource(title: title, url: $0, snippet: author) }
        return reply(call, text, sources: source.map { [$0] } ?? [])
    }

    /// Текст субтитров: русские, затем английские, затем любые.
    private func captions(_ player: [String: Any]) async -> String {
        guard let captions = player["captions"] as? [String: Any],
              let renderer = captions["playerCaptionsTracklistRenderer"] as? [String: Any],
              let tracks = renderer["captionTracks"] as? [[String: Any]], !tracks.isEmpty else { return "" }
        let ordered = tracks.sorted { lhs, rhs in
            func rank(_ track: [String: Any]) -> Int {
                let code = (track["languageCode"] as? String ?? "").lowercased()
                return code.hasPrefix("ru") ? 0 : (code.hasPrefix("en") ? 1 : 2)
            }
            return rank(lhs) < rank(rhs)
        }
        for track in ordered.prefix(2) {
            guard let base = track["baseUrl"] as? String, let url = URL(string: base) else { continue }
            guard let result = await get(url), result.status == 200, !result.data.isEmpty else { continue }
            let xml = String(decoding: result.data, as: UTF8.self)
            let text = WebPageText.decodeEntities(
                xml.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            ).replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if text.count > 40 { return text }
        }
        return ""
    }

    // MARK: GitHub

    static func repoPath(_ raw: String) -> String? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: value), let host = url.host, host.contains("github.com") {
            value = url.path
        }
        let parts = value.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        guard parts.count >= 2 else { return nil }
        return parts[0] + "/" + parts[1].replacingOccurrences(of: ".git", with: "")
    }

    func github(call: ToolCallRequest, action: String, query: String, repo: String, path: String) async -> ToolCallResult {
        let api = "https://api.github.com"
        func encoded(_ text: String) -> String { text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? text }
        switch action {
        case "search", "search_repos":
            guard !query.isEmpty, let url = URL(string: "\(api)/search/repositories?q=\(encoded(query))&per_page=8"),
                  let object = await json(url, accept: "application/vnd.github+json") as? [String: Any],
                  let items = object["items"] as? [[String: Any]] else {
                return reply(call, "GitHub не ответил или ничего не нашёл (у GitHub лимит 60 запросов в час без входа).")
            }
            var sources: [WebSource] = []
            let lines = items.map { item -> String in
                let name = item["full_name"] as? String ?? ""
                let stars = item["stargazers_count"] as? Int ?? 0
                let language = item["language"] as? String ?? ""
                let about = item["description"] as? String ?? ""
                let link = item["html_url"] as? String ?? ""
                if let url = URL(string: link) { sources.append(WebSource(title: name, url: url, snippet: about)) }
                return "• [\(name)](\(link)) ★\(stars) \(language) — \(about)"
            }
            return reply(call, "Репозитории GitHub по запросу «\(query)»:\n" + lines.joined(separator: "\n"), sources: sources)
        case "user":
            let name = query.isEmpty ? repo : query
            guard !name.isEmpty, let url = URL(string: "\(api)/users/\(encoded(name))/repos?sort=updated&per_page=15"),
                  let items = await json(url, accept: "application/vnd.github+json") as? [[String: Any]] else {
                return reply(call, "Пользователь GitHub не найден или GitHub не ответил.")
            }
            let lines = items.map { "• \($0["name"] as? String ?? "") ★\($0["stargazers_count"] as? Int ?? 0) — \($0["description"] as? String ?? "")" }
            return reply(call, "Репозитории \(name):\n" + lines.joined(separator: "\n"))
        default:
            break
        }
        guard let full = Self.repoPath(repo.isEmpty ? query : repo) else {
            return reply(call, "Укажи репозиторий в виде owner/name.")
        }
        switch action {
        case "repo", "readme", "info":
            guard let url = URL(string: "\(api)/repos/\(full)"),
                  let info = await json(url, accept: "application/vnd.github+json") as? [String: Any] else {
                return reply(call, "Репозиторий \(full) не найден или GitHub не ответил.")
            }
            var text = "Репозиторий \(full)\nОписание: \(info["description"] as? String ?? "")\nЗвёзд: \(info["stargazers_count"] as? Int ?? 0), форков: \(info["forks_count"] as? Int ?? 0), открытых задач: \(info["open_issues_count"] as? Int ?? 0)\nЯзык: \(info["language"] as? String ?? "")\nОбновлён: \(info["pushed_at"] as? String ?? "")\nСсылка: https://github.com/\(full)"
            if let readmeURL = URL(string: "\(api)/repos/\(full)/readme"),
               let readme = await get(readmeURL, accept: "application/vnd.github.raw"), readme.status == 200 {
                text += "\n\nREADME:\n" + String(String(decoding: readme.data, as: UTF8.self).prefix(20_000))
            }
            let source = URL(string: "https://github.com/\(full)").map { WebSource(title: full, url: $0, snippet: info["description"] as? String ?? "") }
            return reply(call, text, sources: source.map { [$0] } ?? [])
        case "files", "list", "tree":
            let folder = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let url = URL(string: "\(api)/repos/\(full)/contents/\(folder)"),
                  let items = await json(url, accept: "application/vnd.github+json") as? [[String: Any]] else {
                return reply(call, "Папка не найдена.")
            }
            let lines = items.map { "\(($0["type"] as? String) == "dir" ? "📁" : "📄") \($0["path"] as? String ?? "")" }
            return reply(call, "Файлы \(full)/\(folder):\n" + lines.joined(separator: "\n"))
        case "file", "read":
            let file = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !file.isEmpty, let url = URL(string: "https://raw.githubusercontent.com/\(full)/HEAD/\(file)"),
                  let result = await get(url), result.status == 200 else {
                return reply(call, "Файл не найден. Посмотри список файлов действием files.")
            }
            let content = String(decoding: result.data.prefix(300_000), as: UTF8.self)
            let language = (file as NSString).pathExtension
            return reply(call, "Файл \(full)/\(file):\n```\(language)\n\(String(content.prefix(40_000)))\n```")
        case "issues":
            guard let url = URL(string: "\(api)/repos/\(full)/issues?state=open&per_page=15"),
                  let items = await json(url, accept: "application/vnd.github+json") as? [[String: Any]] else {
                return reply(call, "Не удалось получить задачи.")
            }
            let lines = items.map { "• #\($0["number"] as? Int ?? 0) \($0["title"] as? String ?? "") — \(($0["user"] as? [String: Any])?["login"] as? String ?? "")" }
            return reply(call, "Открытые задачи \(full):\n" + (lines.isEmpty ? "нет" : lines.joined(separator: "\n")))
        case "releases":
            guard let url = URL(string: "\(api)/repos/\(full)/releases?per_page=6"),
                  let items = await json(url, accept: "application/vnd.github+json") as? [[String: Any]] else {
                return reply(call, "Не удалось получить релизы.")
            }
            let lines = items.map { "• \($0["tag_name"] as? String ?? "") \($0["name"] as? String ?? "") (\($0["published_at"] as? String ?? ""))\n  \(String(($0["body"] as? String ?? "").prefix(600)))" }
            return reply(call, "Релизы \(full):\n" + (lines.isEmpty ? "нет" : lines.joined(separator: "\n")))
        default:
            return reply(call, "Неизвестное действие. Доступно: search, repo, files, file, issues, releases, user.")
        }
    }

    // MARK: Маркетплейсы

    func marketplace(call: ToolCallRequest, store: String, query: String, count: Int,
                     progress: WebToolExecutor.ProgressHandler?) async -> ToolCallResult {
        guard !query.isEmpty else { return reply(call, "Не передан запрос.") }
        let limit = max(1, min(count, 20))
        if store.contains("wild") || store == "wb" {
            progress?("Wildberries", ["wildberries.ru"])
            let products = await wildberries(query, limit: limit)
            if !products.isEmpty {
                return reply(call, "Wildberries — «\(query)»:\n" + products.map(\.line).joined(separator: "\n"),
                             sources: products.map(\.source))
            }
            return await siteSearch(call: call, site: "wildberries.ru", name: "Wildberries", query: query, limit: limit)
        }
        if store.contains("ozon") {
            return await siteSearch(call: call, site: "ozon.ru", name: "Ozon", query: query, limit: limit,
                                    direct: URL(string: "https://www.ozon.ru/search/?text=\(query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query)"))
        }
        if store.contains("avito") || store.contains("авито") {
            return await siteSearch(call: call, site: "avito.ru", name: "Авито", query: query, limit: limit,
                                    direct: URL(string: "https://www.avito.ru/rossiya?q=\(query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query)"))
        }
        if store.contains("yandex") || store.contains("market") {
            return await siteSearch(call: call, site: "market.yandex.ru", name: "Яндекс Маркет", query: query, limit: limit)
        }
        return reply(call, "Неизвестный магазин. Доступно: wildberries, ozon, avito, yandex_market.")
    }

    struct Product { var line: String; var source: WebSource }

    /// Wildberries отдаёт поиск открытым JSON. Версии адреса меняются — пробуем несколько.
    func wildberries(_ query: String, limit: Int) async -> [Product] {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        for version in ["v13", "v9", "v7", "v5", "v4"] {
            guard let url = URL(string: "https://search.wb.ru/exactmatch/ru/common/\(version)/search?ab_testing=false&appType=1&curr=rub&dest=-1257786&query=\(encoded)&resultset=catalog&sort=popular&spp=30"),
                  let object = await json(url) as? [String: Any] else { continue }
            let data = object["data"] as? [String: Any]
            let products = (data?["products"] as? [[String: Any]]) ?? (object["products"] as? [[String: Any]]) ?? []
            guard !products.isEmpty else { continue }
            return products.prefix(limit).compactMap { item -> Product? in
                guard let id = (item["id"] as? Int) ?? Int(item["id"] as? String ?? "") else { return nil }
                let name = item["name"] as? String ?? "Товар"
                let brand = item["brand"] as? String ?? ""
                var price = 0
                if let sale = item["salePriceU"] as? Int { price = sale / 100 }
                if price == 0, let sizes = item["sizes"] as? [[String: Any]],
                   let first = sizes.first?["price"] as? [String: Any] {
                    price = ((first["product"] as? Int) ?? (first["total"] as? Int) ?? 0) / 100
                }
                let rating = (item["reviewRating"] as? Double) ?? (item["rating"] as? Double) ?? 0
                let feedbacks = (item["feedbacks"] as? Int) ?? 0
                let link = "https://www.wildberries.ru/catalog/\(id)/detail.aspx"
                guard let url = URL(string: link) else { return nil }
                let priceText = price > 0 ? "\(price) ₽" : "цена на сайте"
                let line = "• [\(brand.isEmpty ? "" : brand + " — ")\(name)](\(link)) — \(priceText), ★\(String(format: "%.1f", rating)) (\(feedbacks) отзывов)"
                return Product(line: line, source: WebSource(title: name, url: url, snippet: priceText))
            }
        }
        return []
    }

    /// Магазины с защитой от роботов: пробуем страницу поиска движком Safari,
    /// а при отказе — поиск по сайту через поисковики.
    func siteSearch(call: ToolCallRequest, site: String, name: String, query: String, limit: Int,
                    direct: URL? = nil) async -> ToolCallResult {
        if let direct, let rendered = await WebPageRenderer.render(direct, timeout: 14),
           rendered.text.count > 400, !WebPageText.looksLikeChallenge(rendered.text) {
            let links = WebPageText.resultLinks(rendered.html, excludingHostsContaining: [])
                .filter { ($0.url.host ?? "").contains(site.replacingOccurrences(of: "market.", with: "")) }
            let text = String(rendered.text.prefix(9000))
            let lines = links.prefix(limit).map { "• [\($0.title)](\($0.url.absoluteString))" }
            return reply(call, "\(name) — страница поиска «\(query)» (цены и названия — в тексте ниже):\n\(text)\n\nСсылки на товары:\n" + lines.joined(separator: "\n"),
                         sources: Array(links.prefix(limit)))
        }
        let found = (try? await client.search("\(query) site:\(site)")) ?? []
        let relevant = found.filter { ($0.url.host ?? "").contains(site.replacingOccurrences(of: "market.", with: "")) || site.hasPrefix("market") }
        guard !relevant.isEmpty else {
            return reply(call, "\(name) не отдал результаты без входа (защита от роботов), и поиск по сайту ничего не дал. Предложи пользователю открыть поиск на сайте самому: https://\(site)")
        }
        let lines = relevant.prefix(limit).map { "• [\($0.title)](\($0.url.absoluteString)) — \(String($0.snippet.prefix(200)))" }
        return reply(call, "\(name) — «\(query)» (через поиск по сайту; актуальные цены уточняй по ссылке):\n" + lines.joined(separator: "\n"),
                     sources: Array(relevant.prefix(limit)))
    }

    // MARK: ВКонтакте и Telegram

    func vk(call: ToolCallRequest, page: String) async -> ToolCallResult {
        var name = page.trimmingCharacters(in: .whitespaces)
        if let url = URL(string: name), let host = url.host, host.contains("vk.com") || host.contains("vk.ru") {
            name = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        }
        name = name.replacingOccurrences(of: "@", with: "")
        guard !name.isEmpty, let url = URL(string: "https://m.vk.com/\(name)") else { return reply(call, "Не передано имя страницы.") }
        guard let source = await client.readPage(url), let content = source.content, content.count > 100 else {
            return reply(call, "Страница ВКонтакте не открылась без входа или закрыта настройками приватности.")
        }
        return reply(call, "ВКонтакте — \(source.title):\n" + String(content.prefix(12_000)), sources: [source])
    }

    func telegram(call: ToolCallRequest, channel: String) async -> ToolCallResult {
        var name = channel.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "@", with: "")
        if let url = URL(string: name), let host = url.host, host.contains("t.me") {
            name = url.pathComponents.filter { $0 != "/" && $0 != "s" }.first ?? ""
        }
        guard !name.isEmpty, let url = URL(string: "https://t.me/s/\(name)") else { return reply(call, "Не передано имя канала.") }
        guard let source = await client.readPage(url), let content = source.content, content.count > 100 else {
            return reply(call, "Канал не открылся: он закрытый или не существует.")
        }
        return reply(call, "Telegram-канал @\(name) — последние публикации:\n" + String(content.prefix(12_000)), sources: [source])
    }
}

// MARK: - Изображения, звук и редактор

struct MediaInsight {
    var context: ToolExecutionContext
    var client: WebSearchClient

    private func reply(_ call: ToolCallRequest, _ text: String, effect: ToolEffect? = nil) -> ToolCallResult {
        ToolCallResult(callID: call.id, name: call.name, content: text, effect: effect)
    }

    /// Вложение из чата по имени или «last».
    private func attachment(named name: String, kinds: [AttachmentKind]) -> MessageAttachment? {
        let candidates = context.chatAttachments.filter { kinds.contains($0.kind) }
        let lower = name.lowercased().trimmingCharacters(in: .whitespaces)
        if lower.isEmpty || lower == "last" || lower == "последнее" || lower == "последнее фото" { return candidates.last }
        return candidates.last { $0.name.lowercased() == lower }
            ?? candidates.last { $0.name.lowercased().contains(lower) || lower.contains($0.name.lowercased()) }
    }

    /// Скачать файл во временную папку.
    private func download(_ url: URL, maximumBytes: Int) async -> URL? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue(IntegrationClient.desktopAgent, forHTTPHeaderField: "User-Agent")
        guard let (file, response) = try? await URLSession.shared.download(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0, size <= maximumBytes else { return nil }
        let name = url.lastPathComponent.isEmpty ? "media" : url.lastPathComponent
        let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + name)
        try? FileManager.default.moveItem(at: file, to: target)
        return target
    }

    func viewImage(call: ToolCallRequest, reference: String, question: String) async -> ToolCallResult {
        guard let configuration = context.configuration else { return reply(call, "Просмотр изображений сейчас недоступен.") }
        var fileURL: URL?
        var temporary = false
        if let local = attachment(named: reference, kinds: [.image]), let url = local.resolvedURL {
            fileURL = url
        } else if let url = WebToolExecutor.url(from: reference) {
            fileURL = await download(url, maximumBytes: 15_000_000)
            temporary = true
        }
        guard let source = fileURL, let image = UIImage(contentsOfFile: source.path) else {
            return reply(call, "Не удалось открыть изображение. Проверь ссылку.")
        }
        defer { if temporary { try? FileManager.default.removeItem(at: source) } }
        let scaled = Self.scaled(image, maxSide: 1600)
        guard let jpeg = scaled.jpegData(compressionQuality: 0.85) else { return reply(call, "Не удалось подготовить изображение.") }
        let prepared = FileManager.default.temporaryDirectory.appendingPathComponent("view-\(UUID().uuidString).jpg")
        do { try jpeg.write(to: prepared) } catch { return reply(call, "Не удалось подготовить изображение.") }
        defer { try? FileManager.default.removeItem(at: prepared) }
        var message = ChatMessage(role: .user)
        message.content = (question.isEmpty ? "Подробно опиши изображение: что и кто на нём, текст на нём, детали, цвета, настроение." : question)
            + " Отвечай по существу, по-русски, опираясь только на то, что видно."
        message.attachments = [MessageAttachment(name: "image.jpg", kind: .image, localPath: prepared.path)]
        do {
            let answer = try await DeepSeekClient(configuration: configuration)
                .complete(messages: [message], thinking: false, systemInstruction: "", searchContext: "")
            let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return reply(call, "Изображение открыто, но описать его не удалось.") }
            return reply(call, "Что на изображении:\n" + trimmed)
        } catch {
            return reply(call, "Не удалось рассмотреть изображение: \(error.localizedDescription)")
        }
    }

    static func scaled(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let side = max(image.size.width, image.size.height)
        guard side > maxSide, side > 0 else { return image }
        let ratio = maxSide / side
        let size = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    func transcribe(call: ToolCallRequest, reference: String, language: String,
                    progress: WebToolExecutor.ProgressHandler?) async -> ToolCallResult {
        var fileURL: URL?
        var temporary = false
        if let local = attachment(named: reference, kinds: [.audio, .video]), let url = local.resolvedURL {
            fileURL = url
        } else if let url = WebToolExecutor.url(from: reference) {
            progress?("Скачиваю запись", [url.host ?? ""])
            fileURL = await download(url, maximumBytes: 120_000_000)
            temporary = true
        }
        guard let source = fileURL else { return reply(call, "Не удалось получить запись. Нужна прямая ссылка на аудио/видео или файл из чата.") }
        defer { if temporary { try? FileManager.default.removeItem(at: source) } }
        let locale = language.lowercased().hasPrefix("en") ? "en-US" : "ru-RU"
        do {
            let text = try await MediaTranscriber.transcribe(source, locale: locale, maximumSeconds: 1800) { done, total in
                progress?("Расшифровано \(Int(done)) из \(Int(total)) с", [])
            }
            guard !text.isEmpty else { return reply(call, "Речь в записи не распознана (тишина, музыка или другой язык).") }
            return reply(call, "Расшифровка записи:\n" + String(text.prefix(40_000)))
        } catch {
            return reply(call, "Расшифровать не удалось: \(error.localizedDescription)")
        }
    }

    func editImage(call: ToolCallRequest, source: String, operations: Any?) async -> ToolCallResult {
        guard let original = attachment(named: source, kinds: [.image]), let url = original.resolvedURL,
              let image = ImageEditing.load(url) else {
            return reply(call, "В этом чате нет такого фото. Попроси пользователя прислать фото.")
        }
        let parsed = ImageEditing.parseOperations(operations)
        guard !parsed.isEmpty else { return reply(call, "Не переданы операции (operations).") }
        do {
            let edited = try await ImageEditing.apply(parsed, to: image)
            let saved = try ImageEditing.save(edited, preferPNG: ImageEditing.hasTransparency(edited))
            let name = (original.name as NSString).deletingPathExtension + " (изменено)." + saved.pathExtension
            let attachment = MessageAttachment(name: name, kind: .image, localPath: saved.path)
            return reply(call, "Готово: отредактированное фото «\(name)» показано пользователю под ответом. Коротко опиши, что изменено.",
                         effect: .attachFile(attachment))
        } catch {
            return reply(call, "Не удалось отредактировать фото: \(error.localizedDescription)")
        }
    }
}

// MARK: - Расшифровка речи

/// Расшифровка аудио и звуковой дорожки видео на устройстве (Speech).
/// Длинные записи режутся на части по 50 секунд: так распознавание надёжнее.
enum MediaTranscriber {
    enum TranscriberError: LocalizedError {
        case notAllowed, unavailable, noAudio
        var errorDescription: String? {
            switch self {
            case .notAllowed: return "Нет разрешения на распознавание речи — разрешите его в настройках iPhone."
            case .unavailable: return "Распознавание речи для этого языка сейчас недоступно."
            case .noAudio: return "В файле нет звуковой дорожки."
            }
        }
    }

    static func authorize() async -> Bool {
        let status = SFSpeechRecognizer.authorizationStatus()
        if status == .authorized { return true }
        if status == .denied || status == .restricted { return false }
        return await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
        }
    }

    static func transcribe(_ url: URL, locale: String, maximumSeconds: Double,
                           progress: (@Sendable (Double, Double) -> Void)? = nil) async throws -> String {
        guard await authorize() else { throw TranscriberError.notAllowed }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: locale)), recognizer.isAvailable else {
            throw TranscriberError.unavailable
        }
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard !tracks.isEmpty else { throw TranscriberError.noAudio }
        let duration = min(try await asset.load(.duration).seconds, maximumSeconds)
        guard duration.isFinite, duration > 0 else { throw TranscriberError.noAudio }
        let chunk = 50.0
        var parts: [String] = []
        var start = 0.0
        while start < duration {
            try Task.checkCancellation()
            let length = min(chunk, duration - start)
            let piece = try await exportAudio(asset, start: start, length: length)
            defer { try? FileManager.default.removeItem(at: piece) }
            let text = (try? await recognize(piece, recognizer: recognizer)) ?? ""
            if !text.isEmpty {
                let minutes = Int(start) / 60, seconds = Int(start) % 60
                parts.append(String(format: "[%02d:%02d] ", minutes, seconds) + text)
            }
            start += length
            progress?(start, duration)
        }
        return parts.joined(separator: "\n")
    }

    private static func exportAudio(_ asset: AVURLAsset, start: Double, length: Double) async throws -> URL {
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw TranscriberError.noAudio
        }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("speech-\(UUID().uuidString).m4a")
        session.outputURL = output
        session.outputFileType = .m4a
        session.timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 600),
                                        duration: CMTime(seconds: length, preferredTimescale: 600))
        await session.export()
        guard session.status == .completed else { throw session.error ?? TranscriberError.noAudio }
        return output
    }

    private static func recognize(_ url: URL, recognizer: SFSpeechRecognizer) async throws -> String {
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.shouldReportPartialResults = false
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        request.addsPunctuation = true
        return try await withCheckedThrowingContinuation { continuation in
            var finished = false
            _ = recognizer.recognitionTask(with: request) { result, error in
                guard !finished else { return }
                if let result, result.isFinal {
                    finished = true
                    continuation.resume(returning: result.bestTranscription.formattedString)
                } else if let error {
                    finished = true
                    // «Речь не найдена» — это пустой фрагмент, а не сбой.
                    let code = (error as NSError).code
                    if code == 1110 || code == 203 { continuation.resume(returning: "") } else { continuation.resume(throwing: error) }
                }
            }
        }
    }
}
