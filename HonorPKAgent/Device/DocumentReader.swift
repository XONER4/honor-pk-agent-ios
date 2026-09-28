import Compression
import Foundation
#if canImport(UIKit)
import UIKit
#endif

// Извлечение текста из документов (Office, OpenDocument, EPUB, RTF, HTML, CSV, код).
// Только Foundation + Compression + XMLParser: синхронно, без главного потока.

// MARK: - Ошибки

enum DocumentReaderError: LocalizedError, Equatable, Sendable {
    case invalidArchive
    case legacyOrEncrypted
    case archiveTooLarge
    case unsupportedCompression
    case missingContent
    case unreadableText
    case emptyDocument
    case unsupportedFormat

    var errorDescription: String? {
        switch self {
        case .invalidArchive: return "Не удалось открыть документ: файл повреждён / Could not open the document: the file is damaged."
        case .legacyOrEncrypted: return "Документ защищён паролем или сохранён в старом формате (.doc, .xls, .ppt). Сохраните его как .docx, .xlsx или .pptx / The document is password protected or uses a legacy format."
        case .archiveTooLarge: return "Документ слишком большой после распаковки / The document is too large when unpacked."
        case .unsupportedCompression: return "Документ сжат неподдерживаемым способом / The document uses unsupported compression."
        case .missingContent: return "Не удалось открыть документ: содержимое не найдено / Could not open the document: content not found."
        case .unreadableText: return "Не удалось прочитать текст файла / Could not decode this text file."
        case .emptyDocument: return "В документе не удалось найти текст / No readable text found in this document."
        case .unsupportedFormat: return "Этот тип файла не поддерживается / Unsupported file type."
        }
    }
}

// MARK: - ZIP

/// Минимальный читатель ZIP: stored и deflate, без ZIP64 и шифрования.
enum ZipArchiveReader {
    static let maximumEntries = 2_000
    static let maximumEntryBytes = 60 * 1024 * 1024
    static let maximumTotalBytes = 200 * 1024 * 1024

    struct Entry: Sendable {
        let name: String
        let method: Int
        let flags: Int
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int

        var isEncrypted: Bool { flags & 0x1 != 0 }
        var isDirectory: Bool { name.hasSuffix("/") }
    }

    /// Оглавление архива; `read` считает распакованные байты против общего лимита.
    struct Archive {
        let data: Data
        let entries: [Entry]
        private var byName: [String: Int] = [:]
        private var byLowercasedName: [String: Int] = [:]
        private(set) var totalBytesRead: Int = 0

        init(data: Data) throws {
            self.data = data
            self.entries = try ZipArchiveReader.centralDirectory(in: data)
            for (index, entry) in entries.enumerated() {
                if byName[entry.name] == nil { byName[entry.name] = index }
                let lowered = entry.name.lowercased()
                if byLowercasedName[lowered] == nil { byLowercasedName[lowered] = index }
            }
        }

        var names: [String] { entries.filter { !$0.isDirectory }.map { $0.name } }

        func contains(_ path: String) -> Bool { index(of: path) != nil }

        private func index(of path: String) -> Int? {
            let key = ZipArchiveReader.normalizedPath(path)
            if let exact = byName[key] { return exact }
            return byLowercasedName[key.lowercased()]
        }

        /// Возвращает nil, если файла нет; бросает при повреждении или превышении лимитов.
        mutating func read(_ path: String) throws -> Data? {
            guard let position = index(of: path) else { return nil }
            let entry = entries[position]
            if entry.isDirectory { return nil }
            guard entry.uncompressedSize <= ZipArchiveReader.maximumEntryBytes,
                  totalBytesRead + entry.uncompressedSize <= ZipArchiveReader.maximumTotalBytes else {
                throw DocumentReaderError.archiveTooLarge
            }
            let content = try ZipArchiveReader.extract(entry, from: data)
            totalBytesRead += content.count
            return content
        }
    }

    static func names(in data: Data) throws -> [String] {
        try Archive(data: data).names
    }

    static func read(_ path: String, from data: Data) throws -> Data? {
        var archive = try Archive(data: data)
        return try archive.read(path)
    }

    static func entries(in data: Data) throws -> [String: Data] {
        var archive = try Archive(data: data)
        var result: [String: Data] = [:]
        for name in archive.names {
            if let content = try archive.read(name) { result[name] = content }
        }
        return result
    }

    /// Разбор End Of Central Directory и записей центрального каталога.
    static func centralDirectory(in data: Data) throws -> [Entry] {
        let count = data.count
        guard count >= 22 else { throw DocumentReaderError.invalidArchive }
        var endRecord = -1
        var position = count - 22
        let lowest = max(0, count - 22 - 65_535)
        while position >= lowest {
            if littleEndian32(data, position) == 0x0605_4b50,
               let commentLength = littleEndian16(data, position + 20),
               position + 22 + commentLength <= count {
                endRecord = position
                break
            }
            position -= 1
        }
        guard endRecord >= 0,
              let totalEntries = littleEndian16(data, endRecord + 10),
              let directorySize = littleEndian32(data, endRecord + 12),
              let directoryOffset = littleEndian32(data, endRecord + 16) else {
            throw DocumentReaderError.invalidArchive
        }
        // ZIP64 не поддерживаем
        if totalEntries == 0xFFFF || directorySize == 0xFFFF_FFFF || directoryOffset == 0xFFFF_FFFF {
            throw DocumentReaderError.invalidArchive
        }
        guard totalEntries <= maximumEntries else { throw DocumentReaderError.archiveTooLarge }
        let directoryEnd = directoryOffset + directorySize
        guard directoryEnd <= endRecord else { throw DocumentReaderError.invalidArchive }

        var result: [Entry] = []
        result.reserveCapacity(totalEntries)
        var offset = directoryOffset
        for _ in 0..<totalEntries {
            guard offset + 46 <= directoryEnd,
                  littleEndian32(data, offset) == 0x0201_4b50,
                  let flags = littleEndian16(data, offset + 8),
                  let method = littleEndian16(data, offset + 10),
                  let compressedSize = littleEndian32(data, offset + 20),
                  let uncompressedSize = littleEndian32(data, offset + 24),
                  let nameLength = littleEndian16(data, offset + 28),
                  let extraLength = littleEndian16(data, offset + 30),
                  let commentLength = littleEndian16(data, offset + 32),
                  let localOffset = littleEndian32(data, offset + 42) else {
                throw DocumentReaderError.invalidArchive
            }
            let nameStart = offset + 46
            guard nameStart + nameLength <= directoryEnd else { throw DocumentReaderError.invalidArchive }
            let nameBytes = data.subdata(in: (data.startIndex + nameStart)..<(data.startIndex + nameStart + nameLength))
            let rawName = String(data: nameBytes, encoding: .utf8) ?? String(data: nameBytes, encoding: .isoLatin1) ?? ""
            result.append(Entry(name: normalizedPath(rawName), method: method, flags: flags,
                                compressedSize: compressedSize, uncompressedSize: uncompressedSize,
                                localHeaderOffset: localOffset))
            offset = nameStart + nameLength + extraLength + commentLength
        }
        return result
    }

    /// Данные записи: смещение берём из локального заголовка (его name/extra могут отличаться от центральных).
    static func extract(_ entry: Entry, from data: Data) throws -> Data {
        if entry.isEncrypted { throw DocumentReaderError.legacyOrEncrypted }
        guard entry.uncompressedSize <= maximumEntryBytes else { throw DocumentReaderError.archiveTooLarge }
        let local = entry.localHeaderOffset
        guard littleEndian32(data, local) == 0x0403_4b50,
              let nameLength = littleEndian16(data, local + 26),
              let extraLength = littleEndian16(data, local + 28) else {
            throw DocumentReaderError.invalidArchive
        }
        let start = local + 30 + nameLength + extraLength
        let end = start + entry.compressedSize
        guard start >= 0, start <= end, end <= data.count else { throw DocumentReaderError.invalidArchive }
        let range = (data.startIndex + start)..<(data.startIndex + end)
        switch entry.method {
        case 0:
            guard entry.compressedSize == entry.uncompressedSize else { throw DocumentReaderError.invalidArchive }
            return data.subdata(in: range)
        case 8:
            return try inflate([UInt8](data[range]), expectedSize: entry.uncompressedSize)
        default:
            throw DocumentReaderError.unsupportedCompression
        }
    }

    /// COMPRESSION_ZLIB у Apple — это «сырой» DEFLATE без заголовка zlib, как в ZIP.
    private static func inflate(_ source: [UInt8], expectedSize: Int) throws -> Data {
        if expectedSize == 0 { return Data() }
        guard !source.isEmpty else { throw DocumentReaderError.invalidArchive }
        var destination = [UInt8](repeating: 0, count: expectedSize)
        let written: Int = source.withUnsafeBufferPointer { (input: UnsafeBufferPointer<UInt8>) -> Int in
            destination.withUnsafeMutableBufferPointer { (output: inout UnsafeMutableBufferPointer<UInt8>) -> Int in
                guard let inputBase = input.baseAddress, let outputBase = output.baseAddress else { return 0 }
                return compression_decode_buffer(outputBase, expectedSize, inputBase, input.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written == expectedSize else { throw DocumentReaderError.invalidArchive }
        return Data(destination)
    }

    /// Единый вид путей: прямые слэши, без "./" и "..".
    static func normalizedPath(_ path: String) -> String {
        let unified = path.replacingOccurrences(of: "\\", with: "/")
        var parts: [Substring] = []
        for component in unified.split(separator: "/", omittingEmptySubsequences: true) {
            if component == "." { continue }
            if component == ".." {
                if !parts.isEmpty { parts.removeLast() }
                continue
            }
            parts.append(component)
        }
        let joined = parts.joined(separator: "/")
        return unified.hasSuffix("/") && !joined.isEmpty ? joined + "/" : joined
    }

    // Побайтовое чтение little-endian: без невыровненного load(as:).
    static func littleEndian16(_ data: Data, _ offset: Int) -> Int? {
        guard offset >= 0, offset + 2 <= data.count else { return nil }
        let base = data.startIndex + offset
        return Int(data[base]) | (Int(data[base + 1]) << 8)
    }

    static func littleEndian32(_ data: Data, _ offset: Int) -> Int? {
        guard offset >= 0, offset + 4 <= data.count else { return nil }
        let base = data.startIndex + offset
        let low = Int(data[base]) | (Int(data[base + 1]) << 8)
        let high = (Int(data[base + 2]) << 16) | (Int(data[base + 3]) << 24)
        return low | high
    }
}

// MARK: - Результат

struct DocumentText: Equatable, Sendable {
    var text: String
    var summary: String
}

// MARK: - DocumentReader

enum DocumentReader {
    static let maximumCharacters = 160_000
    static let maximumTableRows = 2_000
    static let maximumTableColumns = 60
    /// Рабочий бюджет UTF-8 байт при разборе: дальше всё равно обрежем до 160 000 символов.
    static let workingBudget = 700_000
    static let truncationNote = "\n\n… (текст обрезан: документ длиннее 160 000 символов)"

    static let supportedExtensions: Set<String> = [
        "docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm",
        "odt", "ods", "odp", "rtf", "html", "htm", "xhtml", "xml", "epub", "csv", "tsv",
        "txt", "md", "markdown", "json", "log", "text",
        "swift", "py", "js", "jsx", "ts", "tsx", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cc",
        "m", "mm", "cs", "go", "rs", "rb", "php", "sh", "bash", "zsh", "ps1", "sql",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "css", "scss", "less", "vue", "svelte",
        "dart", "lua", "r", "pl", "scala", "gradle", "properties", "tex", "bib", "srt", "vtt",
        "ics", "vcf", "plist", "ipynb"
    ]

    struct CodeLanguage: Sendable {
        let fence: String
        let name: String
    }

    static let codeLanguages: [String: CodeLanguage] = [
        "swift": CodeLanguage(fence: "swift", name: "Swift"),
        "py": CodeLanguage(fence: "python", name: "Python"),
        "js": CodeLanguage(fence: "javascript", name: "JavaScript"),
        "jsx": CodeLanguage(fence: "jsx", name: "JSX"),
        "ts": CodeLanguage(fence: "typescript", name: "TypeScript"),
        "tsx": CodeLanguage(fence: "tsx", name: "TSX"),
        "java": CodeLanguage(fence: "java", name: "Java"),
        "kt": CodeLanguage(fence: "kotlin", name: "Kotlin"),
        "kts": CodeLanguage(fence: "kotlin", name: "Kotlin"),
        "c": CodeLanguage(fence: "c", name: "C"),
        "h": CodeLanguage(fence: "c", name: "C"),
        "cpp": CodeLanguage(fence: "cpp", name: "C++"),
        "hpp": CodeLanguage(fence: "cpp", name: "C++"),
        "cc": CodeLanguage(fence: "cpp", name: "C++"),
        "m": CodeLanguage(fence: "objectivec", name: "Objective-C"),
        "mm": CodeLanguage(fence: "objectivec", name: "Objective-C++"),
        "cs": CodeLanguage(fence: "csharp", name: "C#"),
        "go": CodeLanguage(fence: "go", name: "Go"),
        "rs": CodeLanguage(fence: "rust", name: "Rust"),
        "rb": CodeLanguage(fence: "ruby", name: "Ruby"),
        "php": CodeLanguage(fence: "php", name: "PHP"),
        "sh": CodeLanguage(fence: "bash", name: "Shell"),
        "bash": CodeLanguage(fence: "bash", name: "Bash"),
        "zsh": CodeLanguage(fence: "zsh", name: "Zsh"),
        "ps1": CodeLanguage(fence: "powershell", name: "PowerShell"),
        "sql": CodeLanguage(fence: "sql", name: "SQL"),
        "yaml": CodeLanguage(fence: "yaml", name: "YAML"),
        "yml": CodeLanguage(fence: "yaml", name: "YAML"),
        "toml": CodeLanguage(fence: "toml", name: "TOML"),
        "ini": CodeLanguage(fence: "ini", name: "INI"),
        "cfg": CodeLanguage(fence: "ini", name: "CFG"),
        "conf": CodeLanguage(fence: "ini", name: "CONF"),
        "env": CodeLanguage(fence: "bash", name: "ENV"),
        "css": CodeLanguage(fence: "css", name: "CSS"),
        "scss": CodeLanguage(fence: "scss", name: "SCSS"),
        "less": CodeLanguage(fence: "less", name: "Less"),
        "vue": CodeLanguage(fence: "vue", name: "Vue"),
        "svelte": CodeLanguage(fence: "svelte", name: "Svelte"),
        "dart": CodeLanguage(fence: "dart", name: "Dart"),
        "lua": CodeLanguage(fence: "lua", name: "Lua"),
        "r": CodeLanguage(fence: "r", name: "R"),
        "pl": CodeLanguage(fence: "perl", name: "Perl"),
        "scala": CodeLanguage(fence: "scala", name: "Scala"),
        "gradle": CodeLanguage(fence: "groovy", name: "Gradle"),
        "properties": CodeLanguage(fence: "properties", name: "Properties"),
        "tex": CodeLanguage(fence: "latex", name: "LaTeX"),
        "json": CodeLanguage(fence: "json", name: "JSON"),
        "xml": CodeLanguage(fence: "xml", name: "XML"),
        "plist": CodeLanguage(fence: "xml", name: "Plist")
    ]

    private static let dataExtensions: Set<String> = [
        "json", "xml", "plist", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "properties"
    ]

    static func isSupported(fileExtension: String) -> Bool {
        supportedExtensions.contains(normalizedExtension(fileExtension))
    }

    // MARK: Точка входа

    static func extractText(from data: Data, fileExtension: String) throws -> DocumentText {
        let ext = normalizedExtension(fileExtension)
        guard supportedExtensions.contains(ext) else { throw DocumentReaderError.unsupportedFormat }
        guard !data.isEmpty else { throw DocumentReaderError.emptyDocument }
        let raw: DocumentText
        switch ext {
        case "docx", "docm", "dotx": raw = try readWord(data)
        case "xlsx", "xlsm", "xltx": raw = try readExcel(data)
        case "pptx", "pptm": raw = try readPowerPoint(data)
        case "odt", "ods", "odp": raw = try readOpenDocument(data, fileExtension: ext)
        case "epub": raw = try readEPUB(data)
        case "rtf": raw = try readRTF(data)
        case "html", "htm", "xhtml": raw = try readHTML(data)
        case "csv", "tsv": raw = try readDelimited(data, fileExtension: ext)
        case "ipynb": raw = try readNotebook(data)
        default: raw = try readPlainText(data, fileExtension: ext)
        }
        let text = limited(normalize(raw.text))
        guard !text.isEmpty else { throw DocumentReaderError.emptyDocument }
        return DocumentText(text: text, summary: raw.summary)
    }

    // MARK: Подписи для интерфейса

    static func kind(forExtension fileExtension: String) -> String {
        let ext = normalizedExtension(fileExtension)
        switch ext {
        case "docx", "docm", "dotx": return "Документ Word"
        case "xlsx", "xlsm", "xltx": return "Таблица Excel"
        case "pptx", "pptm": return "Презентация PowerPoint"
        case "odt": return "Документ OpenDocument"
        case "ods": return "Таблица OpenDocument"
        case "odp": return "Презентация OpenDocument"
        case "rtf": return "Документ RTF"
        case "html", "htm", "xhtml": return "Веб-страница"
        case "epub": return "Книга EPUB"
        case "csv": return "Таблица CSV"
        case "tsv": return "Таблица TSV"
        case "ipynb": return "Блокнот Jupyter"
        case "md", "markdown": return "Markdown"
        case "srt", "vtt": return "Субтитры"
        case "ics": return "Календарь"
        case "vcf": return "Контакты"
        case "log": return "Журнал"
        case "pdf": return "Документ PDF"
        default:
            if codeLanguages[ext] != nil { return "Код" }
            if supportedExtensions.contains(ext) { return "Текст" }
            return "Файл"
        }
    }

    static func symbol(forExtension fileExtension: String) -> String {
        let ext = normalizedExtension(fileExtension)
        switch ext {
        case "docx", "docm", "dotx", "odt", "rtf", "pdf": return "doc.richtext"
        case "xlsx", "xlsm", "xltx", "ods", "csv", "tsv": return "tablecells"
        case "pptx", "pptm", "odp": return "rectangle.on.rectangle.angled"
        case "html", "htm", "xhtml": return "globe"
        case "epub": return "book"
        case "ipynb": return "chevron.left.forwardslash.chevron.right"
        case "md", "markdown": return "doc.plaintext"
        case "srt", "vtt": return "captions.bubble"
        case "ics": return "calendar"
        case "vcf": return "person.crop.rectangle"
        case "log": return "doc.text.magnifyingglass"
        case "txt", "text": return "doc.text"
        default:
            if dataExtensions.contains(ext) { return "curlybraces" }
            if codeLanguages[ext] != nil { return "chevron.left.forwardslash.chevron.right" }
            if supportedExtensions.contains(ext) { return "doc.text" }
            return "doc"
        }
    }

    // MARK: Word

    private static func readWord(_ data: Data) throws -> DocumentText {
        var archive = try openPackage(data)
        let documentPath = try mainPart(in: &archive, fallback: "word/document.xml")
        guard let documentXML = try archive.read(documentPath) else { throw DocumentReaderError.missingContent }
        let rels = try relationships(for: documentPath, in: &archive)

        // Стили: styleId → уровень заголовка (русский Word хранит id вида "1", а имя "heading 1")
        var headingLevels: [String: Int] = [:]
        let stylesPath = rels.first(where: { $0.type.hasSuffix("/styles") }).map { resolve($0.target, from: documentPath) } ?? "word/styles.xml"
        if let stylesXML = try archive.read(stylesPath) {
            let styles = WordStylesHandler()
            XMLSupport.parse(stylesXML, with: styles)
            headingLevels = styles.headingLevels()
        }

        let body = OfficeTextHandler()
        body.styleHeadingLevels = headingLevels
        let parsed = XMLSupport.parse(documentXML, with: body)
        if !parsed && !body.truncated && body.output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DocumentReaderError.invalidArchive
        }
        var text = body.output

        // Сноски — в конце документа
        let notes: [(String, String)] = [("/footnotes", "Сноски"), ("/endnotes", "Концевые сноски")]
        for (suffix, title) in notes where !body.truncated {
            guard let relation = rels.first(where: { $0.type.hasSuffix(suffix) }),
                  let xml = try archive.read(resolve(relation.target, from: documentPath)) else { continue }
            let handler = OfficeTextHandler()
            XMLSupport.parse(xml, with: handler)
            let content = handler.output.trimmingCharacters(in: .whitespacesAndNewlines)
            if !content.isEmpty { text += "\n\n---\n\(title):\n" + content }
        }

        var parts: [String] = []
        let properties = try appProperties(in: &archive)
        if let pages = Int(properties["Pages"] ?? ""), pages > 0 {
            parts.append(plural(pages, "страница", "страницы", "страниц"))
        } else {
            parts.append(plural(wordCount(text), "слово", "слова", "слов"))
        }
        if body.tableCount > 0 { parts.append(plural(body.tableCount, "таблица", "таблицы", "таблиц")) }
        return DocumentText(text: text, summary: "Документ Word: " + parts.joined(separator: ", "))
    }

    // MARK: Excel

    private static func readExcel(_ data: Data) throws -> DocumentText {
        var archive = try openPackage(data)
        let workbookPath = try mainPart(in: &archive, fallback: "xl/workbook.xml")
        guard let workbookXML = try archive.read(workbookPath) else { throw DocumentReaderError.missingContent }
        let workbook = WorkbookHandler()
        XMLSupport.parse(workbookXML, with: workbook)
        let rels = try relationships(for: workbookPath, in: &archive)
        var targets: [String: String] = [:]
        for relation in rels { targets[relation.id] = resolve(relation.target, from: workbookPath) }

        var sharedStrings: [String] = []
        let sharedPath = rels.first(where: { $0.type.hasSuffix("/sharedStrings") }).map { resolve($0.target, from: workbookPath) } ?? "xl/sharedStrings.xml"
        if let xml = try archive.read(sharedPath) {
            let handler = SharedStringsHandler()
            XMLSupport.parse(xml, with: handler)
            sharedStrings = handler.strings
        }

        var dateStyles = Set<Int>()
        let stylesPath = rels.first(where: { $0.type.hasSuffix("/styles") }).map { resolve($0.target, from: workbookPath) } ?? "xl/styles.xml"
        if let xml = try archive.read(stylesPath) {
            let handler = ExcelStylesHandler()
            XMLSupport.parse(xml, with: handler)
            dateStyles = handler.dateStyleIndexes
        }

        var sheets: [(name: String, path: String, hidden: Bool)] = []
        for (index, sheet) in workbook.sheets.enumerated() {
            let path = targets[sheet.relationshipID] ?? "xl/worksheets/sheet\(index + 1).xml"
            if path.contains("chartsheets/") || path.contains("dialogsheets/") { continue }
            sheets.append((name: sheet.name, path: path, hidden: sheet.hidden))
        }
        if sheets.isEmpty {
            let paths = archive.names.filter { $0.hasPrefix("xl/worksheets/sheet") && $0.hasSuffix(".xml") }
                .sorted { trailingNumber($0) < trailingNumber($1) }
            for (index, path) in paths.enumerated() { sheets.append((name: "Лист \(index + 1)", path: path, hidden: false)) }
        }

        var sections: [String] = []
        var sheetCount = 0
        var totalRows = 0
        var size = 0
        for sheet in sheets {
            guard let xml = try archive.read(sheet.path) else { continue }
            let handler = SheetHandler(sharedStrings: sharedStrings, dateStyles: dateStyles, date1904: workbook.date1904)
            XMLSupport.parse(xml, with: handler)
            sheetCount += 1
            totalRows += handler.nonEmptyRowCount
            let section = renderSheet(name: sheet.name, hidden: sheet.hidden, handler: handler)
            size += section.utf8.count
            sections.append(section)
            if size > workingBudget { break }
        }
        guard sheetCount > 0 else { throw DocumentReaderError.missingContent }
        let summary = "Таблица Excel: " + plural(sheetCount, "лист", "листа", "листов") + ", " + plural(totalRows, "строка", "строки", "строк")
        return DocumentText(text: sections.joined(separator: "\n\n"), summary: summary)
    }

    fileprivate static func renderSheet(name: String, hidden: Bool, handler: SheetHandler) -> String {
        var section = "## Лист «\(name)»" + (hidden ? " (скрытый)" : "") + "\n\n"
        let rows = handler.rows
        var minColumn = Int.max
        var maxColumn = -1
        for row in rows {
            for column in row.keys {
                minColumn = min(minColumn, column)
                maxColumn = max(maxColumn, column)
            }
        }
        guard maxColumn >= 0 else { return section + "(пустой лист)" }
        let matrix: [[String]] = rows.map { (row: [Int: String]) -> [String] in
            (minColumn...maxColumn).map { (column: Int) -> String in row[column] ?? "" }
        }
        section += markdownTable(matrix)
        if handler.nonEmptyRowCount > rows.count {
            section += "\n… (показаны первые \(rows.count) строк из \(handler.nonEmptyRowCount))"
        }
        if handler.columnsTruncated {
            section += "\n… (показаны первые \(maximumTableColumns) столбцов)"
        }
        return section
    }

    // MARK: PowerPoint

    private static func readPowerPoint(_ data: Data) throws -> DocumentText {
        var archive = try openPackage(data)
        let presentationPath = try mainPart(in: &archive, fallback: "ppt/presentation.xml")
        var slidePaths: [String] = []
        if let xml = try archive.read(presentationPath) {
            let handler = PresentationHandler()
            XMLSupport.parse(xml, with: handler)
            var targets: [String: String] = [:]
            for relation in try relationships(for: presentationPath, in: &archive) {
                targets[relation.id] = resolve(relation.target, from: presentationPath)
            }
            for id in handler.slideRelationshipIDs {
                if let path = targets[id], archive.contains(path) { slidePaths.append(path) }
            }
        }
        // Запасной путь: сортировка по номеру в имени (slide2 раньше slide10)
        if slidePaths.isEmpty {
            let prefix = "ppt/slides/slide"
            slidePaths = archive.names
                .filter { $0.hasPrefix(prefix) && $0.hasSuffix(".xml") && !$0.dropFirst("ppt/slides/".count).contains("/") }
                .sorted { trailingNumber($0) < trailingNumber($1) }
        }
        guard !slidePaths.isEmpty else { throw DocumentReaderError.missingContent }

        var sections: [String] = []
        var size = 0
        for (index, path) in slidePaths.enumerated() {
            guard let xml = try archive.read(path) else { continue }
            let slide = OfficeTextHandler()
            XMLSupport.parse(xml, with: slide)
            var section = "## Слайд \(index + 1)\n" + slide.output.trimmingCharacters(in: .whitespacesAndNewlines)

            let slideRels = try relationships(for: path, in: &archive)
            var notesPath = "ppt/notesSlides/notesSlide\(trailingNumber(path)).xml"
            if let relation = slideRels.first(where: { $0.type.hasSuffix("/notesSlide") }) {
                notesPath = resolve(relation.target, from: path)
            }
            if let notesXML = try archive.read(notesPath) {
                let notes = OfficeTextHandler()
                notes.skipsFields = true
                XMLSupport.parse(notesXML, with: notes)
                let content = notes.output.trimmingCharacters(in: .whitespacesAndNewlines)
                if !content.isEmpty { section += "\n\nЗаметки: " + content }
            }
            size += section.utf8.count
            sections.append(section)
            if size > workingBudget { break }
        }
        let summary = "Презентация PowerPoint: " + plural(slidePaths.count, "слайд", "слайда", "слайдов")
        return DocumentText(text: sections.joined(separator: "\n\n"), summary: summary)
    }

    // MARK: OpenDocument

    private static func readOpenDocument(_ data: Data, fileExtension ext: String) throws -> DocumentText {
        var archive = try openPackage(data)
        if let manifest = try archive.read("META-INF/manifest.xml"),
           manifest.range(of: Data("encryption-data".utf8)) != nil {
            throw DocumentReaderError.legacyOrEncrypted
        }
        guard let xml = try archive.read("content.xml") else { throw DocumentReaderError.missingContent }
        let mode: OpenDocumentHandler.Mode = ext == "ods" ? .spreadsheet : (ext == "odp" ? .presentation : .text)
        let handler = OpenDocumentHandler(mode: mode)
        let parsed = XMLSupport.parse(xml, with: handler)
        if !parsed && !handler.truncated && handler.output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DocumentReaderError.invalidArchive
        }
        let summary: String
        switch mode {
        case .spreadsheet:
            summary = "Таблица OpenDocument: " + plural(handler.sheetCount, "лист", "листа", "листов") + ", " + plural(handler.rowCount, "строка", "строки", "строк")
        case .presentation:
            summary = "Презентация OpenDocument: " + plural(handler.slideCount, "слайд", "слайда", "слайдов")
        case .text:
            summary = "Документ OpenDocument: " + plural(wordCount(handler.output), "слово", "слова", "слов")
        }
        return DocumentText(text: handler.output, summary: summary)
    }

    // MARK: EPUB

    private static func readEPUB(_ data: Data) throws -> DocumentText {
        var archive = try openPackage(data)
        var packagePath = ""
        if let container = try archive.read("META-INF/container.xml") {
            let handler = AttributeCollector(element: "rootfile", attribute: "full-path")
            XMLSupport.parse(container, with: handler)
            packagePath = ZipArchiveReader.normalizedPath(handler.values.first ?? "")
        }
        if packagePath.isEmpty {
            packagePath = archive.names.first(where: { $0.lowercased().hasSuffix(".opf") }) ?? ""
        }

        var chapterPaths: [String] = []
        var title = ""
        if !packagePath.isEmpty, let opf = try archive.read(packagePath) {
            let handler = PackageDocumentHandler()
            XMLSupport.parse(opf, with: handler)
            title = handler.title.trimmingCharacters(in: .whitespacesAndNewlines)
            var seen = Set<String>()
            for idref in handler.spine {
                guard let href = handler.manifest[idref] else { continue }
                let path = resolve(href, from: packagePath)
                if seen.insert(path).inserted { chapterPaths.append(path) }
            }
        }
        if chapterPaths.isEmpty {
            chapterPaths = archive.names.filter {
                let lower = $0.lowercased()
                return lower.hasSuffix(".xhtml") || lower.hasSuffix(".html") || lower.hasSuffix(".htm")
            }.sorted()
        }
        guard !chapterPaths.isEmpty else { throw DocumentReaderError.missingContent }

        var parts: [String] = []
        if !title.isEmpty { parts.append("# " + title) }
        var chapters = 0
        var size = 0
        for path in chapterPaths {
            if size > workingBudget { break }
            guard let content = try archive.read(path), let html = decodeText(content) else { continue }
            let text = plainText(fromHTML: html).trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { continue }
            chapters += 1
            size += text.utf8.count
            parts.append(text)
        }
        guard chapters > 0 else { throw DocumentReaderError.emptyDocument }
        return DocumentText(text: parts.joined(separator: "\n\n"),
                            summary: "Книга EPUB: " + plural(chapters, "глава", "главы", "глав"))
    }

    // MARK: RTF

    private static func readRTF(_ data: Data) throws -> DocumentText {
        #if canImport(UIKit)
        let options: [NSAttributedString.DocumentReadingOptionKey: Any] = [.documentType: NSAttributedString.DocumentType.rtf]
        guard let attributed = try? NSAttributedString(data: data, options: options, documentAttributes: nil) else {
            throw DocumentReaderError.unreadableText
        }
        let text = attributed.string.replacingOccurrences(of: "\u{FFFC}", with: "")
        return DocumentText(text: text, summary: "Документ RTF: " + plural(wordCount(text), "слово", "слова", "слов"))
        #else
        throw DocumentReaderError.unsupportedFormat
        #endif
    }

    // MARK: HTML

    private static func readHTML(_ data: Data) throws -> DocumentText {
        guard let html = decodeText(data) else { throw DocumentReaderError.unreadableText }
        let text = plainText(fromHTML: html)
        return DocumentText(text: text, summary: "Веб-страница: " + plural(wordCount(text), "слово", "слова", "слов"))
    }

    /// Быстрое удаление тегов без NSAttributedString (тот требует главный поток и медленный).
    static func plainText(fromHTML html: String) -> String {
        var scanner = HTMLTextScanner(html)
        return scanner.run()
    }

    static let namedEntities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": "\u{00A0}",
        "mdash": "—", "ndash": "–", "hellip": "…", "laquo": "«", "raquo": "»", "copy": "©",
        "reg": "®", "trade": "™", "euro": "€", "bull": "•", "middot": "·", "rsquo": "’",
        "lsquo": "‘", "rdquo": "”", "ldquo": "“", "bdquo": "„", "shy": "", "times": "×",
        "deg": "°", "minus": "−", "thinsp": " ", "ensp": " ", "emsp": " ", "zwnj": "", "zwj": "",
        "rarr": "→", "larr": "←", "sect": "§", "para": "¶", "plusmn": "±", "frac12": "½",
        "cent": "¢", "pound": "£", "yen": "¥", "numero": "№"
    ]

    /// Имя сущности без "&" и ";": "amp", "#39", "#x1F600".
    static func decodeEntity(_ name: String) -> String? {
        if name.hasPrefix("#") {
            let body = name.dropFirst()
            let value: UInt32?
            if body.hasPrefix("x") || body.hasPrefix("X") {
                value = UInt32(body.dropFirst(), radix: 16)
            } else {
                value = UInt32(body, radix: 10)
            }
            guard let code = value, code > 0, let scalar = Unicode.Scalar(code) else { return nil }
            return String(Character(scalar))
        }
        return namedEntities[name] ?? namedEntities[name.lowercased()]
    }

    // MARK: CSV / TSV

    struct DelimitedTable {
        var rows: [[String]]
        var totalRows: Int
        var columnsTruncated: Bool
    }

    private static func readDelimited(_ data: Data, fileExtension ext: String) throws -> DocumentText {
        guard let text = decodeText(data) else { throw DocumentReaderError.unreadableText }
        let delimiter: Unicode.Scalar = ext == "tsv" ? "\t" : detectDelimiter(text)
        let table = parseDelimited(text, delimiter: delimiter, maximumRows: maximumTableRows, maximumColumns: maximumTableColumns)
        guard !table.rows.isEmpty else { throw DocumentReaderError.emptyDocument }
        var result = markdownTable(table.rows)
        if table.totalRows > table.rows.count {
            result += "\n… (показаны первые \(table.rows.count) строк из \(table.totalRows))"
        }
        if table.columnsTruncated { result += "\n… (показаны первые \(maximumTableColumns) столбцов)" }
        var columns = 0
        for row in table.rows {
            if let last = row.lastIndex(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
                columns = max(columns, last + 1)
            }
        }
        let summary = kind(forExtension: ext) + ": " + plural(table.totalRows, "строка", "строки", "строк") + ", " + plural(columns, "столбец", "столбца", "столбцов")
        return DocumentText(text: result, summary: summary)
    }

    /// Для CSV: сравниваем ';', ',' и табуляцию в первой строке.
    static func detectDelimiter(_ text: String) -> Unicode.Scalar {
        var semicolons = 0
        var commas = 0
        var tabs = 0
        for scalar in text.unicodeScalars {
            if scalar == "\n" || scalar == "\r" { break }
            if scalar == ";" { semicolons += 1 } else if scalar == "," { commas += 1 } else if scalar == "\t" { tabs += 1 }
        }
        if tabs > semicolons && tabs > commas { return "\t" }
        return semicolons > commas ? ";" : ","
    }

    /// Разбор с кавычками: "" внутри кавычек, переводы строк внутри кавычек.
    static func parseDelimited(_ text: String, delimiter: Unicode.Scalar, maximumRows: Int, maximumColumns: Int) -> DelimitedTable {
        let scalars = Array(text.unicodeScalars)
        let count = scalars.count
        var rows: [[String]] = []
        var total = 0
        var columnsTruncated = false
        var row: [String] = []
        var field = String.UnicodeScalarView()
        var inQuotes = false
        var index = 0

        func commit(_ values: [String]) {
            guard values.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) else { return }
            total += 1
            guard rows.count < maximumRows else { return }
            if values.count > maximumColumns {
                if values[maximumColumns...].contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
                    columnsTruncated = true
                }
                rows.append(Array(values.prefix(maximumColumns)))
            } else {
                rows.append(values)
            }
        }

        if count > 0 && scalars[0] == "\u{FEFF}" { index = 1 }
        while index < count {
            let scalar = scalars[index]
            if inQuotes {
                if scalar == "\"" {
                    if index + 1 < count && scalars[index + 1] == "\"" {
                        field.append("\"")
                        index += 2
                        continue
                    }
                    inQuotes = false
                    index += 1
                    continue
                }
                field.append(scalar)
                index += 1
                continue
            }
            if scalar == "\"" && field.isEmpty {
                inQuotes = true
                index += 1
                continue
            }
            if scalar == delimiter {
                row.append(String(field))
                field = String.UnicodeScalarView()
                index += 1
                continue
            }
            if scalar == "\n" || scalar == "\r" {
                row.append(String(field))
                field = String.UnicodeScalarView()
                commit(row)
                row = []
                if scalar == "\r" && index + 1 < count && scalars[index + 1] == "\n" { index += 2 } else { index += 1 }
                continue
            }
            field.append(scalar)
            index += 1
        }
        if !field.isEmpty || !row.isEmpty {
            row.append(String(field))
            commit(row)
        }
        return DelimitedTable(rows: rows, totalRows: total, columnsTruncated: columnsTruncated)
    }

    // MARK: Jupyter

    private static func readNotebook(_ data: Data) throws -> DocumentText {
        let object: Any
        do { object = try JSONSerialization.jsonObject(with: data, options: []) } catch { throw DocumentReaderError.unreadableText }
        guard let root = object as? [String: Any], let cells = root["cells"] as? [[String: Any]] else {
            throw DocumentReaderError.unreadableText
        }
        let metadata = root["metadata"] as? [String: Any]
        let kernel = metadata?["kernelspec"] as? [String: Any]
        let info = metadata?["language_info"] as? [String: Any]
        let language = ((kernel?["language"] as? String) ?? (info?["name"] as? String) ?? "python").lowercased()

        var parts: [String] = []
        for cell in cells {
            let type = cell["cell_type"] as? String ?? ""
            let source = notebookText(cell["source"]).trimmingCharacters(in: .newlines)
            if type == "code" {
                var outputs: [String] = []
                if let items = cell["outputs"] as? [[String: Any]] {
                    for item in items {
                        var text = notebookText(item["text"])
                        if text.isEmpty, let bundle = item["data"] as? [String: Any] { text = notebookText(bundle["text/plain"]) }
                        if text.isEmpty, let name = item["ename"] as? String {
                            text = name + ": " + ((item["evalue"] as? String) ?? "")
                        }
                        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
                        if text.count > 2_000 { text = String(text.prefix(2_000)) + "…" }
                        if !text.isEmpty { outputs.append(text) }
                    }
                }
                if source.isEmpty && outputs.isEmpty { continue }
                var block = "```\(language)\n\(source)\n```"
                if !outputs.isEmpty { block += "\nВывод:\n```\n" + outputs.joined(separator: "\n") + "\n```" }
                parts.append(block)
            } else if !source.isEmpty {
                parts.append(source)
            }
        }
        return DocumentText(text: parts.joined(separator: "\n\n"),
                            summary: "Блокнот Jupyter: " + plural(cells.count, "ячейка", "ячейки", "ячеек"))
    }

    private static func notebookText(_ value: Any?) -> String {
        if let text = value as? String { return text }
        if let lines = value as? [String] { return lines.joined() }
        return ""
    }

    // MARK: Текст и код

    private static func readPlainText(_ data: Data, fileExtension ext: String) throws -> DocumentText {
        var payload = data
        // Бинарный plist переводим в XML
        if ext == "plist", data.starts(with: Array("bplist".utf8)),
           let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil),
           let xml = try? PropertyListSerialization.data(fromPropertyList: object, format: .xml, options: 0) {
            payload = xml
        }
        guard let text = decodeText(payload) else { throw DocumentReaderError.unreadableText }
        let lines = lineCount(text)
        if let language = codeLanguages[ext] {
            let fence = text.contains("```") ? "````" : "```"
            var body = text
            while let last = body.unicodeScalars.last, last == "\n" || last == "\r" { body.unicodeScalars.removeLast() }
            return DocumentText(text: fence + language.fence + "\n" + body + "\n" + fence,
                                summary: "Код \(language.name): " + plural(lines, "строка", "строки", "строк"))
        }
        return DocumentText(text: text, summary: kind(forExtension: ext) + ": " + plural(lines, "строка", "строки", "строк"))
    }

    /// UTF-8 → UTF-16 (BOM или явные нули) → Windows-1251 → ISO Latin 1. Бинарные данные — nil.
    static func decodeText(_ data: Data) -> String? {
        if data.isEmpty { return "" }
        let head = [UInt8](data.prefix(4))
        var decoded: String?
        if head.count >= 3 && head[0] == 0xEF && head[1] == 0xBB && head[2] == 0xBF {
            decoded = String(data: data.dropFirst(3), encoding: .utf8)
        } else if head.count >= 2 && ((head[0] == 0xFF && head[1] == 0xFE) || (head[0] == 0xFE && head[1] == 0xFF)) {
            decoded = String(data: data, encoding: .utf16)
        } else if let encoding = guessedUTF16(data) {
            decoded = String(data: data, encoding: encoding)
        } else {
            if data.prefix(8_192).contains(0) { return nil }
            decoded = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .windowsCP1251)
                ?? String(data: data, encoding: .isoLatin1)
        }
        guard var text = decoded else { return nil }
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        return text
    }

    private static func guessedUTF16(_ data: Data) -> String.Encoding? {
        let sample = [UInt8](data.prefix(512))
        let pairs = sample.count / 2
        guard pairs >= 2 else { return nil }
        var evenZeros = 0
        var oddZeros = 0
        for (index, byte) in sample.enumerated() where byte == 0 {
            if index % 2 == 0 { evenZeros += 1 } else { oddZeros += 1 }
        }
        if oddZeros * 10 >= pairs * 4 && evenZeros * 10 < pairs { return .utf16LittleEndian }
        if evenZeros * 10 >= pairs * 4 && oddZeros * 10 < pairs { return .utf16BigEndian }
        return nil
    }

    // MARK: Общие помощники

    static func normalizedExtension(_ fileExtension: String) -> String {
        fileExtension.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ". "))
    }

    /// Переводы строк → "\n", не больше одной пустой строки подряд, обрезка краёв.
    static func normalize(_ text: String) -> String {
        let unified = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var lines: [Substring] = []
        var blankRun = 0
        for line in unified.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.allSatisfy({ $0 == " " || $0 == "\t" || $0 == "\u{00A0}" }) {
                blankRun += 1
                if blankRun > 1 { continue }
                lines.append("")
            } else {
                blankRun = 0
                lines.append(line)
            }
        }
        return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func limited(_ text: String) -> String {
        guard text.count > maximumCharacters else { return text }
        let keep = maximumCharacters - truncationNote.count
        var head = String(text.prefix(keep))
        while let last = head.last, last.isWhitespace { head.removeLast() }
        return head + truncationNote
    }

    /// Markdown-таблица: первая строка — заголовок; пустые строки и хвостовые пустые столбцы убираются.
    static func markdownTable(_ rows: [[String]]) -> String {
        let cleaned: [[String]] = rows.map { (row: [String]) -> [String] in row.map { markdownCell($0) } }
        var width = 0
        for row in cleaned {
            if let last = row.lastIndex(where: { !$0.isEmpty }) { width = max(width, last + 1) }
        }
        guard width > 0 else { return "" }
        var lines: [String] = []
        for row in cleaned where row.contains(where: { !$0.isEmpty }) {
            var cells = Array(row.prefix(width))
            while cells.count < width { cells.append("") }
            lines.append("| " + cells.joined(separator: " | ") + " |")
            if lines.count == 1 { lines.append("|" + String(repeating: "---|", count: width)) }
        }
        return lines.joined(separator: "\n")
    }

    static func markdownCell(_ value: String) -> String {
        var text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        text = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        text = text.replacingOccurrences(of: "|", with: "\\|")
        text = text.replacingOccurrences(of: "\n", with: "<br>")
        text = text.replacingOccurrences(of: "\t", with: " ")
        return text
    }

    /// Вложенная таблица внутри ячейки: строки через перевод строки, ячейки через "; ".
    static func flattenedTable(_ rows: [[String]]) -> String {
        rows.map { (row: [String]) -> String in
            row.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.joined(separator: "; ")
        }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// "Heading 2", "heading2", "Заголовок 3", "Title" → уровень заголовка.
    static func headingLevel(styleName: String) -> Int? {
        let compact = styleName.lowercased().replacingOccurrences(of: " ", with: "")
        if compact == "title" || compact == "название" || compact == "заголовок" || compact == "heading" { return 1 }
        for prefix in ["heading", "заголовок"] where compact.hasPrefix(prefix) {
            let rest = compact.dropFirst(prefix.count)
            let digits = rest.prefix(while: { $0.isASCII && $0.isNumber })
            if !digits.isEmpty, digits.count == rest.count, let number = Int(String(digits)), number >= 1 {
                return min(number, 6)
            }
        }
        return nil
    }

    /// "B12" → 1, "AA3" → 26.
    static func columnIndex(fromReference reference: String) -> Int? {
        var column = 0
        var letters = 0
        for scalar in reference.unicodeScalars {
            let value = scalar.value
            if value == 36 { continue } // "$"
            if value >= 65 && value <= 90 {
                column = column * 26 + Int(value - 64)
            } else if value >= 97 && value <= 122 {
                column = column * 26 + Int(value - 96)
            } else {
                break
            }
            letters += 1
            if letters > 4 { return nil }
        }
        return letters == 0 ? nil : column - 1
    }

    /// Убирает двоичный «шум» вида 0.30000000000000004 (до 15 значащих цифр, как показывает Excel).
    static func formatNumber(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard trimmed.count > 15, trimmed.contains("."), !trimmed.contains("e"), !trimmed.contains("E"),
              let number = Double(trimmed), number.isFinite else { return trimmed }
        return String(format: "%.15g", number)
    }

    static func isDateFormat(id: Int, code: String?) -> Bool {
        if let code = code, !code.isEmpty {
            var cleaned = ""
            var inQuotes = false
            var inBrackets = false
            var escapeNext = false
            for character in code {
                if escapeNext { escapeNext = false; continue }
                if inQuotes { if character == "\"" { inQuotes = false }; continue }
                if inBrackets { if character == "]" { inBrackets = false }; continue }
                switch character {
                case "\"": inQuotes = true
                case "[": inBrackets = true
                case "\\", "_", "*": escapeNext = true
                default: cleaned.append(character)
                }
            }
            let lower = cleaned.lowercased()
            return lower.contains("y") || lower.contains("d") || lower.contains("h")
        }
        return (14...22).contains(id) || (27...36).contains(id) || (45...47).contains(id) || (50...58).contains(id)
    }

    /// Серийный номер Excel → "2024-05-01", "2024-05-01 13:30" или "13:30".
    static func excelDate(_ serial: Double, date1904: Bool) -> String? {
        guard serial.isFinite, serial >= 0, serial < 2_958_466 else { return nil }
        let epoch: Double = date1904 ? -2_082_844_800 : -2_209_161_600
        let totalSeconds = (serial * 86_400).rounded()
        var calendar = Calendar(identifier: .gregorian)
        if let utc = TimeZone(secondsFromGMT: 0) { calendar.timeZone = utc }
        let date = Date(timeIntervalSince1970: epoch + totalSeconds)
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let day = pad(parts.year ?? 0, 4) + "-" + pad(parts.month ?? 0, 2) + "-" + pad(parts.day ?? 0, 2)
        let second = parts.second ?? 0
        let time = pad(parts.hour ?? 0, 2) + ":" + pad(parts.minute ?? 0, 2) + (second > 0 ? ":" + pad(second, 2) : "")
        if serial < 1 { return time }
        let secondsOfDay = Int(totalSeconds.truncatingRemainder(dividingBy: 86_400))
        return secondsOfDay == 0 ? day : day + " " + time
    }

    private static func pad(_ value: Int, _ width: Int) -> String {
        let text = String(value)
        return String(repeating: "0", count: max(0, width - text.count)) + text
    }

    static func plural(_ count: Int, _ one: String, _ few: String, _ many: String) -> String {
        let mod10 = count % 10
        let mod100 = count % 100
        let word: String
        if mod10 == 1 && mod100 != 11 {
            word = one
        } else if (2...4).contains(mod10) && !(12...14).contains(mod100) {
            word = few
        } else {
            word = many
        }
        return "\(count) \(word)"
    }

    static func wordCount(_ text: String) -> Int {
        let letters = CharacterSet.alphanumerics
        var count = 0
        var inWord = false
        for scalar in text.unicodeScalars {
            let isWord = letters.contains(scalar)
            if isWord && !inWord { count += 1 }
            inWord = isWord
        }
        return count
    }

    static func lineCount(_ text: String) -> Int {
        guard !text.isEmpty else { return 0 }
        var lines = 1
        for scalar in text.unicodeScalars where scalar == "\n" { lines += 1 }
        if text.unicodeScalars.last == "\n" { lines -= 1 }
        return max(lines, 1)
    }

    /// "ppt/slides/slide12.xml" → 12.
    static func trailingNumber(_ path: String) -> Int {
        let file = (path as NSString).lastPathComponent
        let base = (file as NSString).deletingPathExtension
        var digits = ""
        for character in base.reversed() {
            guard character.isASCII && character.isNumber else { break }
            digits.insert(character, at: digits.startIndex)
        }
        return Int(digits) ?? 0
    }

    // MARK: OPC-пакеты (docx/xlsx/pptx)

    private static func openPackage(_ data: Data) throws -> ZipArchiveReader.Archive {
        // OLE-контейнер: старый .doc/.xls/.ppt или зашифрованный OOXML
        let compoundSignature: [UInt8] = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]
        if data.count >= 8 && data.prefix(8).elementsEqual(compoundSignature) {
            throw DocumentReaderError.legacyOrEncrypted
        }
        return try ZipArchiveReader.Archive(data: data)
    }

    private static func mainPart(in archive: inout ZipArchiveReader.Archive, fallback: String) throws -> String {
        let rels = try relationships(for: "", in: &archive)
        if let office = rels.first(where: { $0.type.hasSuffix("/officeDocument") }) {
            let path = resolve(office.target, from: "")
            if archive.contains(path) { return path }
        }
        return fallback
    }

    fileprivate static func relationships(for part: String, in archive: inout ZipArchiveReader.Archive) throws -> [PackageRelationship] {
        guard let xml = try archive.read(relationshipsPath(for: part)) else { return [] }
        let handler = RelationshipsHandler()
        XMLSupport.parse(xml, with: handler)
        return handler.relationships.filter { !$0.isExternal }
    }

    private static func appProperties(in archive: inout ZipArchiveReader.Archive) throws -> [String: String] {
        guard let xml = try archive.read("docProps/app.xml") else { return [:] }
        let handler = ElementTextCollector(names: ["Pages", "Slides", "Words"])
        XMLSupport.parse(xml, with: handler)
        return handler.values
    }

    /// "xl/workbook.xml" → "xl/_rels/workbook.xml.rels"; "" → "_rels/.rels".
    static func relationshipsPath(for part: String) -> String {
        guard !part.isEmpty else { return "_rels/.rels" }
        guard let slash = part.lastIndex(of: "/") else { return "_rels/" + part + ".rels" }
        let directory = String(part[..<slash])
        let file = String(part[part.index(after: slash)...])
        return directory + "/_rels/" + file + ".rels"
    }

    /// Цель связи относительно папки части; "/..." — от корня пакета.
    static func resolve(_ target: String, from part: String) -> String {
        var clean = target
        if let hash = clean.firstIndex(of: "#") { clean = String(clean[..<hash]) }
        clean = clean.removingPercentEncoding ?? clean
        if clean.hasPrefix("/") { return ZipArchiveReader.normalizedPath(clean) }
        var directory = ""
        if let slash = part.lastIndex(of: "/") { directory = String(part[...slash]) }
        return ZipArchiveReader.normalizedPath(directory + clean)
    }
}

// MARK: - XML: общие помощники

fileprivate enum XMLSupport {
    static func localName(_ qualified: String) -> String {
        guard let colon = qualified.lastIndex(of: ":") else { return qualified }
        return String(qualified[qualified.index(after: colon)...])
    }

    static func attribute(_ attributes: [String: String], _ name: String) -> String? {
        if let direct = attributes[name] { return direct }
        for (key, value) in attributes where key.contains(":") && !key.hasPrefix("xmlns") && localName(key) == name {
            return value
        }
        return nil
    }

    /// Именно r:id (у p:sldId есть ещё обычный числовой id).
    static func relationshipID(_ attributes: [String: String]) -> String? {
        for (key, value) in attributes where key.contains(":") && !key.hasPrefix("xmlns") && localName(key) == "id" {
            return value
        }
        return nil
    }

    @discardableResult
    static func parse(_ data: Data, with handler: XMLElementHandler) -> Bool {
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        parser.shouldReportNamespacePrefixes = false
        parser.shouldResolveExternalEntities = false
        parser.delegate = handler
        return parser.parse()
    }
}

/// База для обработчиков: имена элементов без префикса пространства имён.
fileprivate class XMLElementHandler: NSObject, XMLParserDelegate {
    func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {}
    func end(_ name: String, _ parser: XMLParser) {}
    func text(_ string: String) {}

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String]) {
        start(XMLSupport.localName(elementName), attributeDict, parser)
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        end(XMLSupport.localName(elementName), parser)
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        text(string)
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        if let string = String(data: CDATABlock, encoding: .utf8) { text(string) }
    }
}

// MARK: - OPC: связи и служебные части

fileprivate struct PackageRelationship {
    let id: String
    let type: String
    let target: String
    let isExternal: Bool
}

fileprivate final class RelationshipsHandler: XMLElementHandler {
    private(set) var relationships: [PackageRelationship] = []

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        guard name == "Relationship" else { return }
        relationships.append(PackageRelationship(
            id: attributes["Id"] ?? "",
            type: attributes["Type"] ?? "",
            target: attributes["Target"] ?? "",
            isExternal: (attributes["TargetMode"] ?? "").lowercased() == "external"))
    }
}

fileprivate final class ElementTextCollector: XMLElementHandler {
    let names: Set<String>
    private(set) var values: [String: String] = [:]
    private var current: String?
    private var buffer = ""

    init(names: Set<String>) {
        self.names = names
        super.init()
    }

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        if current == nil, names.contains(name), values[name] == nil {
            current = name
            buffer = ""
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        if let active = current, active == name {
            values[active] = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            current = nil
        }
    }

    override func text(_ string: String) {
        if current != nil { buffer += string }
    }
}

fileprivate final class AttributeCollector: XMLElementHandler {
    let element: String
    let attributeName: String
    private(set) var values: [String] = []

    init(element: String, attribute: String) {
        self.element = element
        self.attributeName = attribute
        super.init()
    }

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        if name == element, let value = XMLSupport.attribute(attributes, attributeName) { values.append(value) }
    }
}

// MARK: - Word / PowerPoint: текст, заголовки, списки, таблицы

fileprivate final class WordStylesHandler: XMLElementHandler {
    private struct Style {
        var name = ""
        var basedOn = ""
        var outline: Int?
    }

    private var styles: [String: Style] = [:]
    private var currentID: String?
    private var current = Style()

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        switch name {
        case "style":
            currentID = XMLSupport.attribute(attributes, "styleId")
            current = Style()
        case "name":
            if currentID != nil { current.name = XMLSupport.attribute(attributes, "val") ?? "" }
        case "basedOn":
            if currentID != nil { current.basedOn = XMLSupport.attribute(attributes, "val") ?? "" }
        case "outlineLvl":
            if currentID != nil, let level = Int(XMLSupport.attribute(attributes, "val") ?? "") { current.outline = level }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        if name == "style" {
            if let id = currentID { styles[id] = current }
            currentID = nil
        }
    }

    /// Уровни заголовков с учётом наследования basedOn.
    func headingLevels() -> [String: Int] {
        var result: [String: Int] = [:]
        for id in styles.keys {
            var cursor = id
            var depth = 0
            while depth < 8, let style = styles[cursor] {
                if let level = DocumentReader.headingLevel(styleName: style.name) {
                    result[id] = level
                    break
                }
                if let outline = style.outline, outline >= 0, outline < 9 {
                    result[id] = min(outline + 1, 6)
                    break
                }
                if style.basedOn.isEmpty { break }
                cursor = style.basedOn
                depth += 1
            }
        }
        return result
    }
}

/// Общий разбор WordprocessingML и DrawingML: у обоих p/t/tbl/tr/tc/br по локальным именам.
fileprivate final class OfficeTextHandler: XMLElementHandler {
    private struct Paragraph {
        var text = ""
        var styleID = ""
        var outline: Int?
        var isList = false
        var listLevel = 0
    }

    private struct Table {
        var rows: [[String]] = []
        var row: [String] = []
        var cell = ""
        var span = 1
        var inCell = false
        var paragraphDepth = 0
    }

    // Fallback дублирует mc:Choice; *Change — старые версии свойств при рецензировании
    private static let skippedElements: Set<String> = [
        "Fallback", "pPrChange", "rPrChange", "tblPrChange", "trPrChange", "tcPrChange", "sectPrChange", "moveFrom"
    ]

    var styleHeadingLevels: [String: Int] = [:]
    var skipsFields = false
    private(set) var output = ""
    private(set) var tableCount = 0
    private(set) var truncated = false
    private var paragraphs: [Paragraph] = []
    private var tables: [Table] = []
    private var inText = false
    private var tabStopDepth = 0
    private var skipDepth = 0

    private func isSkipped(_ name: String) -> Bool {
        OfficeTextHandler.skippedElements.contains(name) || (skipsFields && name == "fld")
    }

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        if isSkipped(name) {
            skipDepth += 1
            return
        }
        guard skipDepth == 0 else { return }
        switch name {
        case "p":
            paragraphs.append(Paragraph())
        case "t":
            inText = true
        case "tab", "ptab":
            if tabStopDepth == 0 { append("\t") }
        case "tabs", "tabLst":
            tabStopDepth += 1
        case "br", "cr":
            append("\n")
        case "noBreakHyphen":
            append("-")
        case "pStyle":
            if let index = paragraphs.indices.last {
                paragraphs[index].styleID = XMLSupport.attribute(attributes, "val") ?? ""
            }
        case "outlineLvl":
            if let index = paragraphs.indices.last, let level = Int(XMLSupport.attribute(attributes, "val") ?? "") {
                paragraphs[index].outline = level
            }
        case "numPr":
            if let index = paragraphs.indices.last { paragraphs[index].isList = true }
        case "ilvl":
            if let index = paragraphs.indices.last, let level = Int(XMLSupport.attribute(attributes, "val") ?? "") {
                paragraphs[index].listLevel = min(max(level, 0), 8)
            }
        case "numId":
            // numId = 0 означает «без нумерации»
            if let index = paragraphs.indices.last, XMLSupport.attribute(attributes, "val") == "0" {
                paragraphs[index].isList = false
            }
        case "tbl":
            var table = Table()
            table.paragraphDepth = paragraphs.count
            tables.append(table)
            tableCount += 1
        case "tr":
            if let index = tables.indices.last { tables[index].row = [] }
        case "tc":
            if let index = tables.indices.last {
                tables[index].cell = ""
                tables[index].span = 1
                tables[index].inCell = true
            }
        case "gridSpan":
            if let index = tables.indices.last, let span = Int(XMLSupport.attribute(attributes, "val") ?? "") {
                tables[index].span = min(max(span, 1), DocumentReader.maximumTableColumns)
            }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        if isSkipped(name) {
            skipDepth = max(0, skipDepth - 1)
            return
        }
        guard skipDepth == 0 else { return }
        switch name {
        case "t":
            inText = false
        case "tabs", "tabLst":
            tabStopDepth = max(0, tabStopDepth - 1)
        case "p":
            finishParagraph(parser)
        case "tc":
            if let index = tables.indices.last {
                let cell = tables[index].cell
                tables[index].row.append(cell)
                let extra = tables[index].span - 1
                if extra > 0 {
                    for _ in 0..<extra { tables[index].row.append("") }
                }
                tables[index].cell = ""
                tables[index].span = 1
                tables[index].inCell = false
            }
        case "tr":
            if let index = tables.indices.last {
                let row = tables[index].row
                tables[index].rows.append(row)
                tables[index].row = []
            }
        case "tbl":
            finishTable()
        default:
            break
        }
    }

    override func text(_ string: String) {
        if inText && skipDepth == 0 { append(string) }
    }

    private func append(_ string: String) {
        guard let index = paragraphs.indices.last else { return }
        paragraphs[index].text += string
    }

    private func finishParagraph(_ parser: XMLParser) {
        guard let paragraph = paragraphs.popLast() else { return }
        let text = paragraph.text.trimmingCharacters(in: .whitespaces)
        if let index = tables.indices.last, tables[index].inCell, tables[index].paragraphDepth == paragraphs.count {
            if !text.isEmpty {
                let joiner = tables[index].cell.isEmpty ? "" : "\n"
                tables[index].cell += joiner + text
            }
            return
        }
        // Абзац внутри надписи (text box) — дописываем к внешнему
        if let parent = paragraphs.indices.last {
            if !text.isEmpty {
                let joiner = paragraphs[parent].text.isEmpty ? "" : "\n"
                paragraphs[parent].text += joiner + text
            }
            return
        }
        guard !text.isEmpty else {
            output += "\n"
            return
        }
        if let level = headingLevel(for: paragraph) {
            output += "\n" + String(repeating: "#", count: level) + " " + text + "\n"
        } else if paragraph.isList {
            output += String(repeating: "  ", count: paragraph.listLevel) + "- " + text + "\n"
        } else {
            output += text + "\n"
        }
        if output.utf8.count > DocumentReader.workingBudget {
            truncated = true
            parser.abortParsing()
        }
    }

    private func headingLevel(for paragraph: Paragraph) -> Int? {
        if !paragraph.styleID.isEmpty {
            if let level = styleHeadingLevels[paragraph.styleID] { return level }
            if let level = DocumentReader.headingLevel(styleName: paragraph.styleID) { return level }
        }
        if let outline = paragraph.outline, outline >= 0, outline < 9 { return min(outline + 1, 6) }
        return nil
    }

    private func finishTable() {
        guard var table = tables.popLast() else { return }
        if !table.row.isEmpty {
            table.rows.append(table.row)
            table.row = []
        }
        if let parent = tables.indices.last, tables[parent].inCell, tables[parent].paragraphDepth == paragraphs.count {
            let flat = DocumentReader.flattenedTable(table.rows)
            if !flat.isEmpty {
                let joiner = tables[parent].cell.isEmpty ? "" : "\n"
                tables[parent].cell += joiner + flat
            }
            return
        }
        if let paragraph = paragraphs.indices.last {
            let flat = DocumentReader.flattenedTable(table.rows)
            if !flat.isEmpty {
                let joiner = paragraphs[paragraph].text.isEmpty ? "" : "\n"
                paragraphs[paragraph].text += joiner + flat
            }
            return
        }
        let markdown = DocumentReader.markdownTable(table.rows)
        if !markdown.isEmpty { output += "\n" + markdown + "\n\n" }
    }
}

// MARK: - Excel: книга, общие строки, стили, листы

fileprivate struct WorkbookSheet {
    let name: String
    let relationshipID: String
    let hidden: Bool
}

fileprivate final class WorkbookHandler: XMLElementHandler {
    private(set) var sheets: [WorkbookSheet] = []
    private(set) var date1904 = false

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        switch name {
        case "sheet":
            let state = XMLSupport.attribute(attributes, "state") ?? ""
            sheets.append(WorkbookSheet(name: XMLSupport.attribute(attributes, "name") ?? "Лист \(sheets.count + 1)",
                                        relationshipID: XMLSupport.relationshipID(attributes) ?? "",
                                        hidden: state == "hidden" || state == "veryHidden"))
        case "workbookPr":
            let flag = (XMLSupport.attribute(attributes, "date1904") ?? "").lowercased()
            date1904 = flag == "1" || flag == "true"
        default:
            break
        }
    }
}

fileprivate final class SharedStringsHandler: XMLElementHandler {
    private(set) var strings: [String] = []
    private var current = ""
    private var inItem = false
    private var inText = false
    private var phoneticDepth = 0

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        switch name {
        case "si":
            current = ""
            inItem = true
        case "rPh":
            phoneticDepth += 1
        case "t":
            if inItem && phoneticDepth == 0 { inText = true }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        switch name {
        case "si":
            strings.append(current)
            inItem = false
        case "rPh":
            phoneticDepth = max(0, phoneticDepth - 1)
        case "t":
            inText = false
        default:
            break
        }
    }

    override func text(_ string: String) {
        if inText { current += string }
    }
}

fileprivate final class ExcelStylesHandler: XMLElementHandler {
    private var formats: [Int: String] = [:]
    private var cellFormats: [Int] = []
    private var inCellFormats = false

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        switch name {
        case "numFmt":
            if let id = Int(XMLSupport.attribute(attributes, "numFmtId") ?? "") {
                formats[id] = XMLSupport.attribute(attributes, "formatCode") ?? ""
            }
        case "cellXfs":
            inCellFormats = true
        case "xf":
            if inCellFormats { cellFormats.append(Int(XMLSupport.attribute(attributes, "numFmtId") ?? "") ?? 0) }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        if name == "cellXfs" { inCellFormats = false }
    }

    /// Индексы стилей ячеек (атрибут s), означающих дату/время.
    var dateStyleIndexes: Set<Int> {
        var result = Set<Int>()
        for (index, formatID) in cellFormats.enumerated() where DocumentReader.isDateFormat(id: formatID, code: formats[formatID]) {
            result.insert(index)
        }
        return result
    }
}

fileprivate final class SheetHandler: XMLElementHandler {
    let sharedStrings: [String]
    let dateStyles: Set<Int>
    let date1904: Bool
    /// Только непустые строки, не больше maximumTableRows.
    private(set) var rows: [[Int: String]] = []
    private(set) var nonEmptyRowCount = 0
    private(set) var columnsTruncated = false
    private var currentRow: [Int: String] = [:]
    private var nextColumn = 0
    private var cellColumn = 0
    private var cellType = "n"
    private var cellStyle = 0
    private var value = ""
    private var inlineText = ""
    private var inValue = false
    private var inInlineString = false
    private var inInlineText = false
    private var phoneticDepth = 0

    init(sharedStrings: [String], dateStyles: Set<Int>, date1904: Bool) {
        self.sharedStrings = sharedStrings
        self.dateStyles = dateStyles
        self.date1904 = date1904
        super.init()
    }

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        switch name {
        case "row":
            currentRow = [:]
            nextColumn = 0
        case "c":
            if let reference = attributes["r"], let column = DocumentReader.columnIndex(fromReference: reference) {
                cellColumn = column
            } else {
                cellColumn = nextColumn
            }
            cellType = attributes["t"] ?? "n"
            cellStyle = Int(attributes["s"] ?? "") ?? 0
            value = ""
            inlineText = ""
        case "v":
            inValue = true
        case "is":
            inInlineString = true
        case "rPh":
            phoneticDepth += 1
        case "t":
            if inInlineString && phoneticDepth == 0 { inInlineText = true }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        switch name {
        case "v":
            inValue = false
        case "t":
            inInlineText = false
        case "is":
            inInlineString = false
        case "rPh":
            phoneticDepth = max(0, phoneticDepth - 1)
        case "c":
            let display = cellDisplay().trimmingCharacters(in: .whitespacesAndNewlines)
            if !display.isEmpty {
                if cellColumn < DocumentReader.maximumTableColumns {
                    currentRow[cellColumn] = display
                } else {
                    columnsTruncated = true
                }
            }
            nextColumn = cellColumn + 1
        case "row":
            if !currentRow.isEmpty {
                nonEmptyRowCount += 1
                if rows.count < DocumentReader.maximumTableRows { rows.append(currentRow) }
            }
            currentRow = [:]
        default:
            break
        }
    }

    override func text(_ string: String) {
        if inValue {
            value += string
        } else if inInlineText {
            inlineText += string
        }
    }

    private func cellDisplay() -> String {
        let raw = value.trimmingCharacters(in: .whitespacesAndNewlines)
        switch cellType {
        case "s":
            if let index = Int(raw), index >= 0, index < sharedStrings.count { return sharedStrings[index] }
            return ""
        case "inlineStr":
            return inlineText
        case "b":
            if raw.isEmpty { return "" }
            return raw == "1" ? "TRUE" : "FALSE"
        case "str", "e", "d":
            return value
        default:
            if raw.isEmpty { return inlineText }
            if dateStyles.contains(cellStyle), let serial = Double(raw),
               let date = DocumentReader.excelDate(serial, date1904: date1904) {
                return date
            }
            return DocumentReader.formatNumber(raw)
        }
    }
}

// MARK: - PowerPoint: порядок слайдов

fileprivate final class PresentationHandler: XMLElementHandler {
    private(set) var slideRelationshipIDs: [String] = []

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        if name == "sldId", let id = XMLSupport.relationshipID(attributes) { slideRelationshipIDs.append(id) }
    }
}

// MARK: - OpenDocument

fileprivate final class OpenDocumentHandler: XMLElementHandler {
    enum Mode {
        case text
        case spreadsheet
        case presentation
    }

    private struct Paragraph {
        var text = ""
        var headingLevel = 0
        var listLevel = -1
    }

    private struct Table {
        var name = ""
        var rows: [[String]] = []
        var row: [String] = []
        var rowRepeat = 1
        var cell = ""
        var cellRepeat = 1
        var cellValue = ""
        var inCell = false
        var totalRows = 0
        var columnsTruncated = false
        var paragraphDepth = 0
    }

    // Заметки докладчика, комментарии и история правок не нужны
    private static let skippedElements: Set<String> = ["notes", "annotation", "tracked-changes"]

    let mode: Mode
    private(set) var output = ""
    private(set) var sheetCount = 0
    private(set) var slideCount = 0
    private(set) var rowCount = 0
    private(set) var truncated = false
    private var paragraphs: [Paragraph] = []
    private var tables: [Table] = []
    private var listDepth = 0
    private var pendingListItem = false
    private var skipDepth = 0

    init(mode: Mode) {
        self.mode = mode
        super.init()
    }

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        if OpenDocumentHandler.skippedElements.contains(name) {
            skipDepth += 1
            return
        }
        guard skipDepth == 0 else { return }
        switch name {
        case "p", "h":
            var paragraph = Paragraph()
            if name == "h" {
                let level = Int(XMLSupport.attribute(attributes, "outline-level") ?? "") ?? 1
                paragraph.headingLevel = min(max(level, 1), 6)
            }
            if pendingListItem {
                paragraph.listLevel = min(max(listDepth - 1, 0), 8)
                pendingListItem = false
            }
            paragraphs.append(paragraph)
        case "list":
            listDepth += 1
        case "list-item":
            pendingListItem = true
        case "list-header":
            pendingListItem = false
        case "s":
            let count = min(max(Int(XMLSupport.attribute(attributes, "c") ?? "") ?? 1, 1), 100)
            append(String(repeating: " ", count: count))
        case "tab":
            append("\t")
        case "line-break":
            append("\n")
        case "page":
            if mode == .presentation {
                slideCount += 1
                output += "\n## Слайд \(slideCount)\n"
            }
        case "table":
            var table = Table()
            table.name = XMLSupport.attribute(attributes, "name") ?? ""
            table.paragraphDepth = paragraphs.count
            tables.append(table)
        case "table-row":
            if let index = tables.indices.last {
                tables[index].row = []
                let repeated = Int(XMLSupport.attribute(attributes, "number-rows-repeated") ?? "") ?? 1
                tables[index].rowRepeat = min(max(repeated, 1), 100)
            }
        case "table-cell", "covered-table-cell":
            if let index = tables.indices.last {
                tables[index].cell = ""
                let repeated = Int(XMLSupport.attribute(attributes, "number-columns-repeated") ?? "") ?? 1
                tables[index].cellRepeat = min(max(repeated, 1), DocumentReader.maximumTableColumns)
                tables[index].cellValue = XMLSupport.attribute(attributes, "value")
                    ?? XMLSupport.attribute(attributes, "date-value")
                    ?? XMLSupport.attribute(attributes, "time-value")
                    ?? XMLSupport.attribute(attributes, "boolean-value")
                    ?? ""
                tables[index].inCell = true
            }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        if OpenDocumentHandler.skippedElements.contains(name) {
            skipDepth = max(0, skipDepth - 1)
            return
        }
        guard skipDepth == 0 else { return }
        switch name {
        case "p", "h":
            finishParagraph(parser)
        case "list":
            listDepth = max(0, listDepth - 1)
        case "table-cell", "covered-table-cell":
            finishCell()
        case "table-row":
            finishRow()
        case "table":
            finishTable()
        default:
            break
        }
    }

    override func text(_ string: String) {
        if skipDepth == 0 { append(string) }
    }

    private func append(_ string: String) {
        guard let index = paragraphs.indices.last else { return }
        paragraphs[index].text += string
    }

    private func finishParagraph(_ parser: XMLParser) {
        guard let paragraph = paragraphs.popLast() else { return }
        let text = paragraph.text.trimmingCharacters(in: .whitespaces)
        if let index = tables.indices.last, tables[index].inCell, tables[index].paragraphDepth == paragraphs.count {
            if !text.isEmpty {
                let joiner = tables[index].cell.isEmpty ? "" : "\n"
                tables[index].cell += joiner + text
            }
            return
        }
        // Сноска или подпись внутри абзаца
        if let parent = paragraphs.indices.last {
            if !text.isEmpty { paragraphs[parent].text += " [" + text + "]" }
            return
        }
        guard !text.isEmpty else {
            output += "\n"
            return
        }
        if paragraph.headingLevel > 0 {
            output += "\n" + String(repeating: "#", count: paragraph.headingLevel) + " " + text + "\n"
        } else if paragraph.listLevel >= 0 {
            output += String(repeating: "  ", count: paragraph.listLevel) + "- " + text + "\n"
        } else {
            output += text + "\n"
        }
        if output.utf8.count > DocumentReader.workingBudget {
            truncated = true
            parser.abortParsing()
        }
    }

    private func finishCell() {
        guard let index = tables.indices.last, tables[index].inCell else { return }
        var content = tables[index].cell.trimmingCharacters(in: .whitespacesAndNewlines)
        if content.isEmpty { content = tables[index].cellValue }
        let room = DocumentReader.maximumTableColumns - tables[index].row.count
        if room > 0 {
            let copies = min(tables[index].cellRepeat, room)
            for _ in 0..<copies { tables[index].row.append(content) }
        } else if !content.isEmpty {
            tables[index].columnsTruncated = true
        }
        tables[index].cell = ""
        tables[index].inCell = false
    }

    private func finishRow() {
        guard let index = tables.indices.last else { return }
        let row = tables[index].row
        tables[index].row = []
        guard row.contains(where: { !$0.isEmpty }) else { return }
        for _ in 0..<tables[index].rowRepeat {
            tables[index].totalRows += 1
            if tables[index].rows.count < DocumentReader.maximumTableRows { tables[index].rows.append(row) }
        }
    }

    private func finishTable() {
        guard let table = tables.popLast() else { return }
        if let parent = tables.indices.last, tables[parent].inCell, tables[parent].paragraphDepth == paragraphs.count {
            let flat = DocumentReader.flattenedTable(table.rows)
            if !flat.isEmpty {
                let joiner = tables[parent].cell.isEmpty ? "" : "\n"
                tables[parent].cell += joiner + flat
            }
            return
        }
        if let paragraph = paragraphs.indices.last {
            let flat = DocumentReader.flattenedTable(table.rows)
            if !flat.isEmpty { paragraphs[paragraph].text += " [" + flat + "]" }
            return
        }
        let markdown = DocumentReader.markdownTable(table.rows)
        if mode == .spreadsheet {
            sheetCount += 1
            rowCount += table.totalRows
            output += "\n## Лист «\(table.name)»\n\n" + (markdown.isEmpty ? "(пустой лист)" : markdown)
            if table.totalRows > table.rows.count {
                output += "\n… (показаны первые \(table.rows.count) строк из \(table.totalRows))"
            }
            if table.columnsTruncated {
                output += "\n… (показаны первые \(DocumentReader.maximumTableColumns) столбцов)"
            }
            output += "\n\n"
        } else if !markdown.isEmpty {
            output += "\n" + markdown + "\n\n"
        }
    }
}

// MARK: - EPUB: OPF

fileprivate final class PackageDocumentHandler: XMLElementHandler {
    private(set) var manifest: [String: String] = [:]
    private(set) var spine: [String] = []
    private(set) var title = ""
    private var inTitle = false
    private var titleDone = false

    override func start(_ name: String, _ attributes: [String: String], _ parser: XMLParser) {
        switch name {
        case "item":
            if let id = attributes["id"], let href = attributes["href"] { manifest[id] = href }
        case "itemref":
            if let idref = attributes["idref"] { spine.append(idref) }
        case "title":
            if !titleDone { inTitle = true }
        default:
            break
        }
    }

    override func end(_ name: String, _ parser: XMLParser) {
        if name == "title" && inTitle {
            inTitle = false
            titleDone = true
        }
    }

    override func text(_ string: String) {
        if inTitle { title += string }
    }
}

// MARK: - HTML → текст

/// Однопроходный сканер по Unicode-скалярам: теги → переводы строк, script/style выбрасываются.
fileprivate struct HTMLTextScanner {
    private static let blockTags: Set<String> = [
        "p", "div", "section", "article", "header", "footer", "blockquote", "ul", "ol", "table", "tr",
        "dd", "dt", "dl", "figure", "figcaption", "nav", "aside", "main", "form", "hr", "title",
        "address", "fieldset", "details", "summary", "caption", "thead", "tbody", "tfoot"
    ]
    private static let skippedTags: Set<String> = ["script", "style", "noscript", "template", "svg", "math", "iframe", "object"]

    private let scalars: [Unicode.Scalar]
    private var index = 0
    private var output = String.UnicodeScalarView()
    private var lastWasSpace = true
    private var preformatted = 0

    init(_ html: String) {
        scalars = Array(html.unicodeScalars)
    }

    mutating func run() -> String {
        while index < scalars.count {
            let scalar = scalars[index]
            if scalar == "<" {
                handleTag()
            } else if scalar == "&" {
                handleEntity()
            } else {
                emit(scalar)
                index += 1
            }
        }
        let text = String(output)
        return text.split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .joined(separator: "\n")
    }

    private mutating func emit(_ scalar: Unicode.Scalar) {
        let isSpace = scalar == " " || scalar == "\t" || scalar == "\n" || scalar == "\r" || scalar == "\u{0C}" || scalar == "\u{00A0}"
        if isSpace {
            if preformatted > 0 && scalar != "\u{00A0}" {
                output.append(scalar)
                lastWasSpace = true
            } else if !lastWasSpace {
                output.append(" ")
                lastWasSpace = true
            }
            return
        }
        output.append(scalar)
        lastWasSpace = false
    }

    /// Не больше одного перевода строки подряд; в начале текста — без него.
    private mutating func newline(_ prefix: String = "") {
        if let last = output.last, last != "\n" { output.append("\n") }
        output.append(contentsOf: prefix.unicodeScalars)
        lastWasSpace = true
    }

    private mutating func handleTag() {
        let start = index
        if matches("<!--", at: start) {
            if let end = find("-->", from: start + 4) { index = end + 3 } else { index = scalars.count }
            return
        }
        if matches("<![CDATA[", at: start) {
            let end = find("]]>", from: start + 9) ?? scalars.count
            var cursor = start + 9
            while cursor < end {
                emit(scalars[cursor])
                cursor += 1
            }
            index = min(end + 3, scalars.count)
            return
        }
        var cursor = start + 1
        var closing = false
        if cursor < scalars.count && scalars[cursor] == "/" {
            closing = true
            cursor += 1
        }
        guard cursor < scalars.count else {
            emit("<")
            index = start + 1
            return
        }
        let first = scalars[cursor]
        if first == "!" || first == "?" {
            index = tagEnd(from: cursor)
            return
        }
        guard isASCIILetter(first) else {
            if closing {
                index = tagEnd(from: cursor)
            } else {
                emit("<")
                index = start + 1
            }
            return
        }
        var name = ""
        while cursor < scalars.count && isNameScalar(scalars[cursor]) {
            name.unicodeScalars.append(lowercased(scalars[cursor]))
            cursor += 1
        }
        if let colon = name.lastIndex(of: ":") { name = String(name[name.index(after: colon)...]) }
        let end = tagEnd(from: cursor)
        let selfClosing = end >= 2 && end - 2 >= start && scalars[end - 1] == ">" && scalars[end - 2] == "/"
        index = end
        if closing {
            closeTag(name)
        } else {
            openTag(name, selfClosing: selfClosing)
        }
    }

    private mutating func openTag(_ name: String, selfClosing: Bool) {
        if HTMLTextScanner.skippedTags.contains(name) {
            if !selfClosing { index = skipPast(closingTag: name) }
            return
        }
        if let level = headingLevel(name) {
            newline(String(repeating: "#", count: level) + " ")
            return
        }
        switch name {
        case "br":
            newline()
        case "li":
            newline("- ")
        case "pre":
            preformatted += 1
            newline()
        default:
            if HTMLTextScanner.blockTags.contains(name) { newline() }
        }
    }

    private mutating func closeTag(_ name: String) {
        if headingLevel(name) != nil {
            newline()
            return
        }
        switch name {
        case "pre":
            preformatted = max(0, preformatted - 1)
            newline()
        case "td", "th":
            output.append("\t")
            lastWasSpace = true
        case "li":
            newline()
        default:
            if HTMLTextScanner.blockTags.contains(name) { newline() }
        }
    }

    private mutating func handleEntity() {
        var cursor = index + 1
        var name = ""
        while cursor < scalars.count && cursor - index <= 32 {
            let scalar = scalars[cursor]
            if scalar == ";" { break }
            guard isASCIILetter(scalar) || isASCIIDigit(scalar) || scalar == "#" else { break }
            name.unicodeScalars.append(scalar)
            cursor += 1
        }
        if cursor < scalars.count, scalars[cursor] == ";", !name.isEmpty, let decoded = DocumentReader.decodeEntity(name) {
            for scalar in decoded.unicodeScalars { emit(scalar) }
            index = cursor + 1
        } else {
            emit("&")
            index += 1
        }
    }

    private func headingLevel(_ name: String) -> Int? {
        guard name.count == 2, name.hasPrefix("h"), let level = Int(String(name.dropFirst())), (1...6).contains(level) else { return nil }
        return level
    }

    /// Позиция сразу после ">" с учётом кавычек в атрибутах.
    private func tagEnd(from position: Int) -> Int {
        var cursor = position
        var quote: Unicode.Scalar?
        let limit = min(scalars.count, position + 8_192)
        while cursor < limit {
            let scalar = scalars[cursor]
            if let open = quote {
                if scalar == open { quote = nil }
            } else if scalar == "\"" || scalar == "'" {
                quote = scalar
            } else if scalar == ">" {
                return cursor + 1
            }
            cursor += 1
        }
        // Незакрытая кавычка: первый ">" без учёта кавычек
        cursor = position
        while cursor < scalars.count {
            if scalars[cursor] == ">" { return cursor + 1 }
            cursor += 1
        }
        return scalars.count
    }

    private func skipPast(closingTag name: String) -> Int {
        let target = Array(name.unicodeScalars)
        var cursor = index
        while cursor + 1 < scalars.count {
            if scalars[cursor] == "<" && scalars[cursor + 1] == "/" {
                var matched = true
                for (offset, expected) in target.enumerated() {
                    let position = cursor + 2 + offset
                    if position >= scalars.count || lowercased(scalars[position]) != expected {
                        matched = false
                        break
                    }
                }
                if matched { return tagEnd(from: cursor + 2 + target.count) }
            }
            cursor += 1
        }
        return scalars.count
    }

    private func matches(_ pattern: String, at position: Int) -> Bool {
        var cursor = position
        for expected in pattern.unicodeScalars {
            guard cursor < scalars.count, lowercased(scalars[cursor]) == lowercased(expected) else { return false }
            cursor += 1
        }
        return true
    }

    private func find(_ pattern: String, from position: Int) -> Int? {
        let target = Array(pattern.unicodeScalars)
        guard !target.isEmpty else { return nil }
        var cursor = max(position, 0)
        while cursor + target.count <= scalars.count {
            var matched = true
            for offset in 0..<target.count where scalars[cursor + offset] != target[offset] {
                matched = false
                break
            }
            if matched { return cursor }
            cursor += 1
        }
        return nil
    }

    private func isASCIILetter(_ scalar: Unicode.Scalar) -> Bool {
        (scalar.value >= 65 && scalar.value <= 90) || (scalar.value >= 97 && scalar.value <= 122)
    }

    private func isASCIIDigit(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value >= 48 && scalar.value <= 57
    }

    private func isNameScalar(_ scalar: Unicode.Scalar) -> Bool {
        isASCIILetter(scalar) || isASCIIDigit(scalar) || scalar == "-" || scalar == ":" || scalar == "_"
    }

    private func lowercased(_ scalar: Unicode.Scalar) -> Unicode.Scalar {
        guard scalar.value >= 65 && scalar.value <= 90 else { return scalar }
        return Unicode.Scalar(scalar.value + 32) ?? scalar
    }
}
