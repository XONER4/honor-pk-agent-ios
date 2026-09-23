import SwiftUI
import UIKit

/// Запрос на экспорт содержимого файлом (таблица в Numbers и подобное).
struct ExportRequest: Identifiable {
    let id = UUID()
    let content: String
}

/// Системный лист «Поделиться» с файлом: оттуда таблицу можно открыть в Numbers.
struct DocumentExportSheet: UIViewControllerRepresentable {
    let csv: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let name = "Honer-таблица-\(Int(Date().timeIntervalSince1970)).csv"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? csv.data(using: .utf8)?.write(to: url, options: .atomic)
        return UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Запрос на плавный переход к конкретному сообщению (линии навигации справа).
struct ScrollRequest: Equatable {
    let id: UUID
    let messageID: UUID
}
/// Плавный посимвольный вывод ответа и рассуждений.
///
/// Раньше текст обновлялся рывками: движок присылает токены пачками, а вью
/// перерисовывалась только на 22 обновлениях в секунду — получались «прыжки».
/// Здесь видимая часть текста растёт по кадрам (до 60 в секунду) с адаптивной
/// скоростью: медленно на старте, быстрее, если буфер сильно отстал.
struct StreamText<Content: View>: View {
    let target: String
    let streaming: Bool
    /// Минимальная скорость показа, символов в секунду.
    var baseRate: Double = 55
    /// Отставание, после которого начинаем догонять буфер.
    var comfortableLag: Int = 240
    @ViewBuilder let content: (String) -> Content

    @State private var revealed: String = ""
    @State private var lastTick: Date = .distantPast
    @State private var lastTarget: String = ""

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: isSettled)) { context in
            content(revealed)
                .onChange(of: context.date) { now in advance(to: now) }
        }
        .onAppear { syncTarget() }
        .onChange(of: target) { _ in syncTarget() }
        .onChange(of: streaming) { _ in syncTarget() }
    }

    /// Всё показано и поток закончился — таймер можно останавливать.
    private var isSettled: Bool { !streaming && revealed.count >= target.count }

    private func syncTarget() {
        if target != lastTarget { lastTarget = target }
        // Поток завершился: мгновенно показываем финальный текст без «дописывания».
        if !streaming && revealed != target {
            revealed = target
            lastTick = .distantPast
        }
        if revealed.count > target.count { revealed = target }
    }

    private func advance(to now: Date) {
        guard streaming || revealed.count < target.count else { return }
        if lastTick == .distantPast { lastTick = now; return }
        let elapsed = min(now.timeIntervalSince(lastTick), 0.25)
        lastTick = now
        guard elapsed > 0 else { return }

        let remaining = target.count - revealed.count
        guard remaining > 0 else { return }

        // Адаптивная скорость: догоняем буфер, но не даём тексту «прыгать».
        let rate: Double
        if remaining > comfortableLag * 3 {
            rate = baseRate * 12
        } else if remaining > comfortableLag {
            rate = baseRate * 4
        } else {
            rate = baseRate
        }

        let step = max(1, Int((rate * elapsed).rounded()))
        let take = min(step, remaining)
        let end = target.index(target.startIndex, offsetBy: take)
        // Не разрываем графемы (эмодзи, составные символы).
        var slice = target[target.startIndex..<end]
        if slice.unicodeScalars.last.map({ isCombining($0) }) == true, take > 1 {
            slice = target[target.startIndex..<target.index(end, offsetBy: -1)]
        }
        revealed = String(slice)
    }

    private func isCombining(_ scalar: Unicode.Scalar) -> Bool {
        (0x0300...0x036F).contains(Int(scalar.value)) || scalar.properties.isJoinControl
    }
}

// MARK: - Блочный разбор Markdown

/// Раньше весь ответ разбирался как «только строчный» Markdown: заголовки и таблицы
/// схлопывались в один абзац, поэтому таблицы выглядели текстом с палками, а
/// разделение на разделы пропадало. Здесь текст разбирается на блоки: заголовки,
/// списки, цитаты, код, таблицы с выравниванием и разделители.
enum MarkdownBlockKind: Equatable {
    case heading(level: Int)
    case paragraph
    case bullets([String], ordered: Bool)
    case checklist([ChecklistItem])
    case quote
    case code(language: String)
    case copyBlock
    case card(style: String, title: String)
    case ask([QuickQuestion])
    /// Формула отдельным блоком: $$ … $$
    case mathBlock(String)
    /// Диаграмма Mermaid: ```mermaid
    case diagram(String)
    case table(headers: [String], alignments: [TableAlignment], rows: [[String]])
    case divider
}

/// Вопрос с вариантами ответов, который агент задаёт пользователю (пункт 33).
struct QuickQuestion: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let options: [String]
    /// Разрешить свой вариант ответа.
    let allowsCustom: Bool

    static func == (lhs: QuickQuestion, rhs: QuickQuestion) -> Bool {
        lhs.text == rhs.text && lhs.options == rhs.options && lhs.allowsCustom == rhs.allowsCustom
    }
}

struct ChecklistItem: Equatable {
    let done: Bool
    let text: String
}

enum TableAlignment: Equatable { case leading, center, trailing }

struct MarkdownBlockModel: Identifiable, Equatable {
    let id: Int
    let kind: MarkdownBlockKind
    let text: String
}

enum MarkdownBlockParser {
    static func parse(_ source: String) -> [MarkdownBlockModel] {
        var blocks: [MarkdownBlockModel] = []
        let lines = source.components(separatedBy: .newlines)
        var index = 0
        var buffer: [String] = []

        func flushParagraph() {
            let joined = buffer.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            buffer.removeAll()
            guard !joined.isEmpty else { return }
            blocks.append(MarkdownBlockModel(id: blocks.count, kind: .paragraph, text: joined))
        }

        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            // Блок кода ```
            if trimmed.hasPrefix("```") {
                flushParagraph()
                let language = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                var codeLines: [String] = []
                index += 1
                while index < lines.count,
                      !lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                    codeLines.append(lines[index])
                    index += 1
                }
                if index < lines.count { index += 1 }   // закрывающий ```
                let blockBody = codeLines.joined(separator: "\n")
                // Кастомные блоки из ТЗ: ```copy — фрагмент с кнопкой копирования,
                // ```card:info|warn|success|error — цветная карточка.
                let marker = language.lowercased()
                if marker == "copy" {
                    blocks.append(MarkdownBlockModel(id: blocks.count, kind: .copyBlock, text: blockBody))
                } else if marker == "mermaid" {
                    // Диаграмма рисуется нативно, без веб-вью (пункт 10 ТЗ).
                    blocks.append(MarkdownBlockModel(id: blocks.count, kind: .diagram(blockBody), text: blockBody))
                } else if marker == "ask" || marker == "questions" {
                    blocks.append(MarkdownBlockModel(id: blocks.count,
                                                     kind: .ask(Self.parseQuestions(blockBody)),
                                                     text: blockBody))
                } else if marker.hasPrefix("card") {
                    let style = marker.contains(":")
                        ? String(marker.split(separator: ":").last ?? "info")
                        : "info"
                    blocks.append(MarkdownBlockModel(id: blocks.count,
                                                     kind: .card(style: style, title: ""), text: blockBody))
                } else {
                    blocks.append(MarkdownBlockModel(id: blocks.count, kind: .code(language: language), text: blockBody))
                }
                continue
            }

            // Блок формулы $$ … $$
            if trimmed == "$$" || trimmed.hasPrefix("$$") {
                flushParagraph()
                var body: [String] = []
                let afterOpen = String(trimmed.dropFirst(2))
                if afterOpen.hasSuffix("$$"), afterOpen.count > 2 {
                    body.append(String(afterOpen.dropLast(2)))
                    index += 1
                } else {
                    if !afterOpen.isEmpty { body.append(afterOpen) }
                    index += 1
                    while index < lines.count {
                        let line = lines[index]
                        if line.contains("$$") {
                            let head = line.components(separatedBy: "$$").first ?? ""
                            if !head.trimmingCharacters(in: .whitespaces).isEmpty { body.append(head) }
                            index += 1
                            break
                        }
                        body.append(line)
                        index += 1
                    }
                }
                let expression = body.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
                if !expression.isEmpty {
                    blocks.append(MarkdownBlockModel(id: blocks.count, kind: .mathBlock(expression), text: expression))
                }
                continue
            }

            // Горизонтальный разделитель
            if isDivider(trimmed) && !isSetextUnderline(lines: lines, index: index) {
                flushParagraph()
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .divider, text: ""))
                index += 1
                continue
            }

            // Setext-заголовок: строка текста, подчёркнутая ===== (H1) или ----- (H2)
            if isSetextUnderline(lines: lines, index: index), !buffer.isEmpty {
                let title = buffer.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
                buffer.removeAll()
                let level = trimmed.hasPrefix("=") ? 1 : 2
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .heading(level: level), text: title))
                index += 1
                continue
            }

            // Заголовок ATX
            if let heading = headingLevel(trimmed) {
                flushParagraph()
                let title = String(trimmed.dropFirst(heading)).trimmingCharacters(in: .whitespaces)
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .heading(level: heading), text: title))
                index += 1
                continue
            }

            // Таблица GFM
            if trimmed.hasPrefix("|"), index + 1 < lines.count,
               let alignments = alignmentRow(lines[index + 1]) {
                flushParagraph()
                let headers = splitRow(trimmed)
                var rows: [[String]] = []
                index += 2
                while index < lines.count {
                    let row = lines[index].trimmingCharacters(in: .whitespaces)
                    guard row.hasPrefix("|") else { break }
                    let cells = splitRow(row)
                    if !cells.isEmpty { rows.append(cells) }
                    index += 1
                }
                blocks.append(MarkdownBlockModel(id: blocks.count,
                                                 kind: .table(headers: headers, alignments: alignments, rows: rows),
                                                 text: ""))
                continue
            }

            // Чек-лист
            if isChecklistItem(trimmed) {
                flushParagraph()
                var items: [ChecklistItem] = []
                while index < lines.count, isChecklistItem(lines[index].trimmingCharacters(in: .whitespaces)) {
                    let item = lines[index].trimmingCharacters(in: .whitespaces)
                    let done = item.lowercased().hasPrefix("- [x]") || item.lowercased().hasPrefix("* [x]")
                    let text = String(item.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                    items.append(ChecklistItem(done: done, text: text))
                    index += 1
                }
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .checklist(items), text: ""))
                continue
            }

            // Маркированный или нумерованный список
            if let first = listItem(trimmed) {
                flushParagraph()
                var items: [String] = []
                let ordered = first.ordered
                while index < lines.count, let item = listItem(lines[index].trimmingCharacters(in: .whitespaces)),
                      item.ordered == ordered {
                    items.append(item.text)
                    index += 1
                }
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .bullets(items, ordered: ordered), text: ""))
                continue
            }

            // Цитата
            if trimmed.hasPrefix(">") {
                flushParagraph()
                var quoted: [String] = []
                while index < lines.count, lines[index].trimmingCharacters(in: .whitespaces).hasPrefix(">") {
                    quoted.append(String(lines[index].trimmingCharacters(in: .whitespaces).dropFirst())
                        .trimmingCharacters(in: .whitespaces))
                    index += 1
                }
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .quote,
                                                 text: quoted.joined(separator: "\n")))
                continue
            }

            // Пустая строка завершает абзац
            if trimmed.isEmpty {
                flushParagraph()
                index += 1
                continue
            }

            buffer.append(line)
            index += 1
        }
        flushParagraph()
        return blocks
    }

    /// Разбор блока ```ask — до 30 вопросов с вариантами.
    /// Формат: `? Вопрос` и ниже строки вариантов, начиная с `- `.
    /// `+` вместо `?` означает «разрешить свой вариант ответа».
    static func parseQuestions(_ body: String) -> [QuickQuestion] {
        var result: [QuickQuestion] = []
        var current: (text: String, options: [String], custom: Bool)?
        for rawLine in body.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.hasPrefix("?") || line.hasPrefix("+") {
                if let current { result.append(QuickQuestion(text: current.text, options: current.options,
                                                             allowsCustom: current.custom)) }
                let custom = line.hasPrefix("+")
                let text = String(line.dropFirst()).trimmingCharacters(in: .whitespaces)
                current = (text, [], custom)
            } else if line.hasPrefix("-") || line.hasPrefix("*") {
                guard current != nil else { continue }
                let option = String(line.dropFirst()).trimmingCharacters(in: .whitespaces)
                if !option.isEmpty { current?.options.append(option) }
            } else if current != nil && current?.options.isEmpty == true {
                // продолжение текста вопроса
                current?.text += " " + line
            }
            if result.count >= 30 { break }
        }
        if let current { result.append(QuickQuestion(text: current.text, options: current.options,
                                                     allowsCustom: current.custom)) }
        return Array(result.prefix(30))
    }

    private static func isDivider(_ line: String) -> Bool {
        let stripped = line.replacingOccurrences(of: " ", with: "")
        guard stripped.count >= 3 else { return false }
        return stripped.allSatisfy { $0 == "-" } || stripped.allSatisfy { $0 == "*" } || stripped.allSatisfy { $0 == "_" }
    }

    /// Строка вида `=====` или `-----` служит подчёркиванием Setext-заголовка,
    /// если над ней есть текст (пункт 1 ТЗ).
    private static func isSetextUnderline(lines: [String], index: Int) -> Bool {
        guard index > 0 else { return false }
        let current = lines[index].trimmingCharacters(in: .whitespaces)
        let previous = lines[index - 1].trimmingCharacters(in: .whitespaces)
        guard !previous.isEmpty, !previous.hasPrefix("#"), !previous.hasPrefix("|") else { return false }
        let stripped = current.replacingOccurrences(of: " ", with: "")
        guard stripped.count >= 3 else { return false }
        return stripped.allSatisfy { $0 == "=" } || stripped.allSatisfy { $0 == "-" }
    }

    private static func headingLevel(_ line: String) -> Int? {
        var level = 0
        for character in line {
            if character == "#" { level += 1 } else { break }
        }
        guard (1...6).contains(level) else { return nil }
        let rest = line.dropFirst(level)
        guard rest.isEmpty || rest.hasPrefix(" ") else { return nil }
        return level
    }

    private static func isChecklistItem(_ line: String) -> Bool {
        let lower = line.lowercased()
        return lower.hasPrefix("- [ ]") || lower.hasPrefix("- [x]") || lower.hasPrefix("* [ ]") || lower.hasPrefix("* [x]")
    }

    private static func listItem(_ line: String) -> (ordered: Bool, text: String)? {
        if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") {
            return (false, String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces))
        }
        // 1. пункт
        var digits = ""
        var cursor = line.startIndex
        while cursor < line.endIndex, line[cursor].isNumber {
            digits.append(line[cursor])
            cursor = line.index(after: cursor)
        }
        guard !digits.isEmpty, cursor < line.endIndex,
              line[cursor] == "." || line[cursor] == ")" else { return nil }
        let afterMarker = line.index(after: cursor)
        guard afterMarker < line.endIndex, line[afterMarker] == " " else { return nil }
        return (true, String(line[line.index(after: afterMarker)...]).trimmingCharacters(in: .whitespaces))
    }

    private static func splitRow(_ line: String) -> [String] {
        var value = line.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("|") { value = String(value.dropFirst()) }
        if value.hasSuffix("|") { value = String(value.dropLast()) }
        return value.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private static func alignmentRow(_ line: String) -> [TableAlignment]? {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("|"), trimmed.contains("-") else { return nil }
        let cells = splitRow(trimmed)
        guard !cells.isEmpty else { return nil }
        var result: [TableAlignment] = []
        for cell in cells {
            let dashes = cell.replacingOccurrences(of: " ", with: "")
            guard dashes.count >= 1, dashes.allSatisfy({ $0 == "-" || $0 == ":" }) else { return nil }
            let left = dashes.hasPrefix(":")
            let right = dashes.hasSuffix(":")
            if left && right { result.append(.center) }
            else if right { result.append(.trailing) }
            else { result.append(.leading) }
        }
        return result
    }
}

// MARK: - Отрисовка блоков

// MARK: - Линии навигации по сообщениям

/// Полоска линий справа: каждая линия — одно сообщение чата.
/// Тап по линии плавно прокручивает чат к этому сообщению.
struct MessageNavigationLines: View {
    let messages: [ChatMessage]
    let anchors: [UUID: Anchor<CGRect>]
    let geometry: GeometryProxy
    let streamingMessageID: UUID?
    let settings: AppSettings
    let onSelect: (UUID) -> Void

    @State private var activeID: UUID?

    private var visible: [ChatMessage] {
        messages.filter { anchors[$0.id] != nil }
    }

    /// Позиция линии в координатах всего контента чата.
    var body: some View {
        let items = visible
        let padded = geometry.safeAreaInsets.top + 56
        let available = max(CGFloat(80), geometry.size.height - padded - geometry.safeAreaInsets.bottom - 90)
        // При большом числе сообщений показываем каждое, но ограничиваем перекрытие.
        let step = items.count > 1 ? available / CGFloat(items.count - 1) : 0

        return VStack(alignment: .trailing, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, message in
                Capsule()
                    .fill(color(for: message))
                    .frame(width: width(for: message), height: message.role == .user ? 3.5 : 2.5)
                    .frame(width: 16, height: max(4, min(step, 14)), alignment: .trailing)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        activeID = message.id
                        UISelectionFeedbackGenerator().selectionChanged()
                        onSelect(message.id)
                    }
                    .onLongPressGesture(minimumDuration: 0.25) {
                        activeID = message.id
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        onSelect(message.id)
                    }
                    .accessibilityLabel(settings.text("Перейти к сообщению \(index + 1)",
                                                      "Jump to message \(index + 1)"))
                    .accessibilityIdentifier("chat.nav.line." + message.id.uuidString)
                    .accessibilityAddTraits(.isButton)
            }
            Spacer(minLength: 0)
        }
        .padding(.trailing, 3)
        .padding(.top, padded)
        .frame(width: 20, alignment: .trailing)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
    }

    private func color(for message: ChatMessage) -> Color {
        if message.id == activeID { return HonorTheme.accent }
        if message.id == streamingMessageID { return HonorTheme.accent.opacity(0.75) }
        if message.error != nil { return Color.orange.opacity(0.7) }
        return message.role == .user ? HonorTheme.secondary.opacity(0.45) : HonorTheme.secondary.opacity(0.9)
    }

    private func width(for message: ChatMessage) -> CGFloat {
        message.role == .user ? 12 : 9
    }
}

/// Инлайн-содержимое абзаца: текст плюс картинки, если модель вставила
/// Markdown-изображение `![подпись](url)` (пункт 3 ТЗ).
struct InlineContentView: View {
    let source: String
    let size: Double
    let weight: Font.Weight
    let attributed: AttributedString

    private struct Part: Identifiable {
        let id = UUID()
        let text: String?
        let imageURL: URL?
        let caption: String
    }

    private static let imagePattern = try! NSRegularExpression(pattern: "!\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)")

    private var parts: [Part] {
        let pattern = Self.imagePattern
        let full = NSRange(source.startIndex..., in: source)
        let matches = pattern.matches(in: source, range: full)
        guard !matches.isEmpty else { return [Part(text: source, imageURL: nil, caption: "")] }
        var result: [Part] = []
        var cursor = source.startIndex
        for match in matches {
            guard let whole = Range(match.range, in: source),
                  let captionRange = Range(match.range(at: 1), in: source),
                  let urlRange = Range(match.range(at: 2), in: source) else { continue }
            let before = String(source[cursor..<whole.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !before.isEmpty { result.append(Part(text: before, imageURL: nil, caption: "")) }
            result.append(Part(text: nil,
                               imageURL: URL(string: String(source[urlRange])),
                               caption: String(source[captionRange])))
            cursor = whole.upperBound
        }
        let tail = String(source[cursor...]).trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty { result.append(Part(text: tail, imageURL: nil, caption: "")) }
        return result
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(parts) { part in
                if let url = part.imageURL {
                    VStack(alignment: .leading, spacing: 4) {
                        AsyncImage(url: url) { phase in
                            switch phase {
                            case .success(let image):
                                image.resizable().scaledToFit()
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                            case .failure:
                                Label("Не удалось загрузить изображение", systemImage: "photo.badge.exclamationmark")
                                    .font(.system(size: size * 0.85))
                                    .foregroundStyle(HonorTheme.secondary)
                            default:
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(HonorTheme.surface)
                                    .frame(height: 140)
                                    .overlay(ProgressView())
                            }
                        }
                        if !part.caption.isEmpty {
                            Text(part.caption)
                                .font(.system(size: size * 0.78))
                                .foregroundStyle(HonorTheme.secondary)
                        }
                    }
                    .accessibilityIdentifier("message.image")
                } else if let text = part.text {
                    Text(text == source ? attributed : AttributedString(text))
                        .font(.system(size: size, weight: weight))
                        .lineSpacing(5)
                        .tint(HonorTheme.accent)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

struct BlockMarkdownView: View {
    let content: String
    let fontSize: Double
    let sources: [WebSource]
    let findQuery: String
    /// Нажатие варианта ответа в блоке ```ask (пункт 33).
    var onAnswer: ((String) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(MarkdownBlockParser.parse(content)) { block in
                MarkdownBlockView(block: block, fontSize: fontSize,
                                  sources: sources, findQuery: findQuery, onAnswer: onAnswer)
            }
        }
    }
}

/// Отрисовка одного блока. Вынесено в отдельный тип, чтобы компилятор не захлёбывался
/// на одном огромном выражении.
private struct MarkdownBlockView: View {
    let block: MarkdownBlockModel
    let fontSize: Double
    let sources: [WebSource]
    let findQuery: String
    var onAnswer: ((String) -> Void)? = nil

    var body: some View {
        Group {
            switch block.kind {
            case .heading(let level):
                inline(block.text, size: headingSize(level), weight: level <= 2 ? .bold : .semibold)
                    .padding(.top, level <= 2 ? 8 : 4)
            case .paragraph:
                inline(block.text, size: fontSize, weight: .regular)
            case .bullets(let items, let ordered):
                bullets(items, ordered: ordered)
            case .checklist(let items):
                checklist(items)
            case .quote:
                quote
            case .code(let language):
                CodeBlockView(text: block.text, language: language,
                              fontSize: fontSize, findQuery: findQuery)
            case .copyBlock:
                CopyBlockView(text: block.text, fontSize: fontSize)
            case .card(let style, _):
                CardBlockView(style: style, cardText: block.text, fontSize: fontSize,
                              sources: sources, findQuery: findQuery)
            case .ask(let questions):
                QuestionsCardView(questions: questions, fontSize: fontSize) { answer in
                    onAnswer?(answer)
                }
            case .mathBlock(let expression):
                MathExpressionView(latex: expression, fontSize: fontSize * 1.1, block: true)
            case .diagram(let source):
                MermaidDiagramView(source: source, fontSize: fontSize)
            case .table(let headers, let alignments, let rows):
                MarkdownTableView(headers: headers, alignments: alignments, rows: rows,
                                  fontSize: fontSize, findQuery: findQuery)
            case .divider:
                Rectangle().fill(HonorTheme.divider).frame(height: 1).padding(.vertical, 4)
            }
        }
    }

    private func bullets(_ items: [String], ordered: Bool) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(items.enumerated()), id: \.offset) { position, item in
                HStack(alignment: .top, spacing: 9) {
                    Text(ordered ? "\(position + 1)." : "•")
                        .font(.system(size: fontSize, weight: ordered ? .semibold : .regular))
                        .foregroundStyle(ordered ? HonorTheme.accent : HonorTheme.secondary)
                        .frame(minWidth: ordered ? 22 : 12, alignment: .trailing)
                    inline(item, size: fontSize, weight: .regular)
                }
            }
        }
    }

    private func checklist(_ items: [ChecklistItem]) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: item.done ? "checkmark.square.fill" : "square")
                        .font(.system(size: fontSize * 0.9))
                        .foregroundStyle(item.done ? HonorTheme.accent : HonorTheme.secondary)
                    inline(item.text, size: fontSize, weight: .regular)
                        .foregroundStyle(item.done ? HonorTheme.secondary : HonorTheme.foreground)
                }
            }
        }
    }

    private var quote: some View {
        HStack(alignment: .top, spacing: 11) {
            Rectangle().fill(HonorTheme.accent.opacity(0.55)).frame(width: 3)
            inline(block.text, size: fontSize * 0.96, weight: .regular)
                .foregroundStyle(HonorTheme.secondary)
        }
        .padding(.vertical, 2)
    }

    private func headingSize(_ level: Int) -> Double {
        switch level {
        case 1: return fontSize * 1.5
        case 2: return fontSize * 1.3
        case 3: return fontSize * 1.16
        case 4: return fontSize * 1.06
        default: return fontSize
        }
    }

    private func inline(_ text: String, size: Double, weight: Font.Weight) -> some View {
        InlineContentView(source: text, size: size, weight: weight,
                          attributed: inlineAttributed(text))
    }

    private func inlineAttributed(_ source: String) -> AttributedString {
        let cited = linkedCitations(source, sources: sources)
        var value = (try? AttributedString(markdown: cited,
                                           options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(source)
        value = InlineStyleParser.apply(to: value)
        return highlighted(value, query: findQuery)
    }
}

/// Кастомные расширения разметки из ТЗ: цвет текста, фон, капс, подсветка, спойлер.
///
/// Стандартный Markdown этого не умеет, поэтому приложение само разбирает
/// `{color:#fff}текст{/color}`, `{bg:yellow}текст{/bg}`, `{upper}текст{/upper}`,
/// `==выделение==` и `||спойлер||` и превращает их в нативные стили SwiftUI.
enum InlineStyleParser {
    private static let colorPattern = try! NSRegularExpression(
        pattern: "\\{color:(#[0-9A-Fa-f]{3,8}|[a-zA-Zа-яА-Я]+)\\}(.*?)\\{/color\\}", options: [.dotMatchesLineSeparators])
    private static let backgroundPattern = try! NSRegularExpression(
        pattern: "\\{bg:(#[0-9A-Fa-f]{3,8}|[a-zA-Zа-яА-Я]+)\\}(.*?)\\{/bg\\}", options: [.dotMatchesLineSeparators])
    private static let upperPattern = try! NSRegularExpression(
        pattern: "\\{upper\\}(.*?)\\{/upper\\}", options: [.dotMatchesLineSeparators])
    private static let markPattern = try! NSRegularExpression(pattern: "==([^=\\n]+)==")
    private static let spoilerPattern = try! NSRegularExpression(pattern: "\\|\\|([^|\\n]+)\\|\\|")

    private static let namedColors: [String: UInt32] = [
        "white": 0xFFFFFF, "белый": 0xFFFFFF, "black": 0x000000, "чёрный": 0x000000, "черный": 0x000000,
        "red": 0xFF4D4D, "красный": 0xFF4D4D, "green": 0x3DDC84, "зелёный": 0x3DDC84, "зеленый": 0x3DDC84,
        "blue": 0x4D94FF, "синий": 0x4D94FF, "orange": 0xFFA53D, "оранжевый": 0xFFA53D,
        "purple": 0xB07CFF, "фиолетовый": 0xB07CFF, "gray": 0x9A9A9A, "grey": 0x9A9A9A, "серый": 0x9A9A9A,
        "yellow": 0xFFE066, "жёлтый": 0xFFE066, "желтый": 0xFFE066
    ]

    static func color(from token: String) -> Color? {
        let value = token.lowercased()
        if value.hasPrefix("#") {
            var hex = String(value.dropFirst())
            if hex.count == 3 { hex = hex.map { "\($0)\($0)" }.joined() }
            guard hex.count == 6, let number = UInt32(hex, radix: 16) else { return nil }
            return Color(red: Double((number >> 16) & 255) / 255,
                         green: Double((number >> 8) & 255) / 255,
                         blue: Double(number & 255) / 255)
        }
        guard let number = namedColors[value] else { return nil }
        return Color(red: Double((number >> 16) & 255) / 255,
                     green: Double((number >> 8) & 255) / 255,
                     blue: Double(number & 255) / 255)
    }

    static func apply(to value: AttributedString) -> AttributedString {
        var result = value
        applyStyle(backgroundPattern, in: &result, background: true)
        applyStyle(colorPattern, in: &result, background: false)
        applyUpper(in: &result)
        applyMark(in: &result)
        applySpoiler(in: &result)
        applyInlineMath(in: &result)
        return result
    }

    /// Инлайн-формулы вида $x^2$ превращаются в читаемый текст с настоящими
    /// надстрочными и подстрочными символами — нативно, без веб-вью.
    private static let inlineMathPattern = try! NSRegularExpression(pattern: "\\$([^$\\n]{1,120})\\$")

    private static func applyInlineMath(in value: inout AttributedString) {
        var guardCount = 0
        while guardCount < 100 {
            guardCount += 1
            let plain = String(value.characters)
            guard let match = inlineMathPattern.firstMatch(in: plain, range: NSRange(plain.startIndex..., in: plain)),
                  let whole = Range(match.range, in: plain),
                  let bodyRange = Range(match.range(at: 1), in: plain) else { return }
            let converted = unicodeMath(String(plain[bodyRange]))
            guard replace(&value, whole: whole, body: converted,
                          background: false, color: HonorTheme.foreground) else { return }
        }
    }

    private static let superscripts: [Character: Character] = [
        "0": "⁰", "1": "¹", "2": "²", "3": "³", "4": "⁴", "5": "⁵", "6": "⁶", "7": "⁷", "8": "⁸", "9": "⁹",
        "+": "⁺", "-": "⁻", "=": "⁼", "(": "⁽", ")": "⁾", "n": "ⁿ", "i": "ⁱ", "x": "ˣ"
    ]
    private static let subscripts: [Character: Character] = [
        "0": "₀", "1": "₁", "2": "₂", "3": "₃", "4": "₄", "5": "₅", "6": "₆", "7": "₇", "8": "₈", "9": "₉",
        "+": "₊", "-": "₋", "=": "₌", "(": "₍", ")": "₎", "a": "ₐ", "e": "ₑ", "i": "ᵢ", "j": "ⱼ", "o": "ₒ",
        "x": "ₓ", "n": "ₙ", "m": "ₘ", "k": "ₖ", "p": "ₚ", "s": "ₛ", "t": "ₜ"
    ]
    private static let greek: [String: String] = [
        "alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "epsilon": "ε", "zeta": "ζ", "eta": "η",
        "theta": "θ", "iota": "ι", "kappa": "κ", "lambda": "λ", "mu": "μ", "nu": "ν", "xi": "ξ", "pi": "π",
        "rho": "ρ", "sigma": "σ", "tau": "τ", "phi": "φ", "chi": "χ", "psi": "ψ", "omega": "ω",
        "sum": "∑", "int": "∫", "infty": "∞", "times": "×", "cdot": "·", "pm": "±", "le": "≤", "leq": "≤",
        "ge": "≥", "geq": "≥", "ne": "≠", "neq": "≠", "approx": "≈", "to": "→", "rightarrow": "→",
        "sqrt": "√", "partial": "∂", "nabla": "∇", "in": "∈", "notin": "∉", "forall": "∀", "exists": "∃"
    ]

    /// Переводит простую LaTeX-запись в юникод: x^2 → x², H_2O → H₂O, \alpha → α, \frac{a}{b} → a/b.
    static func unicodeMath(_ input: String) -> String {
        var text = input
        // \frac{a}{b} → (a)/(b)
        while let range = text.range(of: "\\frac"), let open = text[range.upperBound...].firstIndex(of: "{"),
              let close = text[open...].firstIndex(of: "}") {
            let numerator = String(text[text.index(after: open)..<close])
            let afterNumerator = text.index(after: close)
            guard let secondOpen = text[afterNumerator...].firstIndex(of: "{"),
                  let secondClose = text[secondOpen...].firstIndex(of: "}") else { break }
            let denominator = String(text[text.index(after: secondOpen)..<secondClose])
            // replaceSubrange требует Range, а не ClosedRange — иначе не компилируется.
            let whole = range.lowerBound..<text.index(after: secondClose)
            text.replaceSubrange(whole, with: "(\(numerator))/(\(denominator))")
        }
        // \команды → символы
        for (command, symbol) in greek {
            text = text.replacingOccurrences(of: "\\" + command, with: symbol)
        }
        text = text.replacingOccurrences(of: "\\left", with: "")
        text = text.replacingOccurrences(of: "\\right", with: "")
        text = text.replacingOccurrences(of: "{", with: "")
        text = text.replacingOccurrences(of: "}", with: "")
        // Степени и индексы
        var result = ""
        var iterator = Array(text)
        var position = 0
        while position < iterator.count {
            let character = iterator[position]
            if (character == "^" || character == "_"), position + 1 < iterator.count {
                let map = character == "^" ? superscripts : subscripts
                var converted = ""
                var cursor = position + 1
                while cursor < iterator.count, let symbol = map[iterator[cursor]] {
                    converted.append(symbol)
                    cursor += 1
                }
                if converted.isEmpty {
                    result.append(character)
                    position += 1
                } else {
                    result.append(converted)
                    position = cursor
                }
                continue
            }
            result.append(character)
            position += 1
        }
        return result
    }

    /// Заменяет разметку на чистый текст, попутно применяя стиль.
    private static func applyStyle(_ expression: NSRegularExpression, in value: inout AttributedString,
                                   background: Bool) {
        var guardCount = 0
        while guardCount < 200 {
            guardCount += 1
            let plain = String(value.characters)
            let range = NSRange(plain.startIndex..., in: plain)
            guard let match = expression.firstMatch(in: plain, range: range),
                  let whole = Range(match.range, in: plain),
                  let tokenRange = Range(match.range(at: 1), in: plain),
                  let bodyRange = Range(match.range(at: 2), in: plain),
                  let color = color(from: String(plain[tokenRange])) else { return }
            guard replace(&value, whole: whole, body: String(plain[bodyRange]),
                          background: background, color: color) else { return }
        }
    }

    private static func applyUpper(in value: inout AttributedString) {
        var guardCount = 0
        while guardCount < 200 {
            guardCount += 1
            let plain = String(value.characters)
            guard let match = upperPattern.firstMatch(in: plain, range: NSRange(plain.startIndex..., in: plain)),
                  let whole = Range(match.range, in: plain),
                  let bodyRange = Range(match.range(at: 1), in: plain) else { return }
            guard replace(&value, whole: whole, body: String(plain[bodyRange]).uppercased(),
                          background: false, color: HonorTheme.foreground) else { return }
        }
    }

    private static func applyMark(in value: inout AttributedString) {
        var guardCount = 0
        while guardCount < 200 {
            guardCount += 1
            let plain = String(value.characters)
            guard let match = markPattern.firstMatch(in: plain, range: NSRange(plain.startIndex..., in: plain)),
                  let whole = Range(match.range, in: plain),
                  let bodyRange = Range(match.range(at: 1), in: plain) else { return }
            guard replace(&value, whole: whole, body: String(plain[bodyRange]),
                          background: true, color: Color.yellow.opacity(0.35)) else { return }
        }
    }

    private static func applySpoiler(in value: inout AttributedString) {
        var guardCount = 0
        while guardCount < 200 {
            guardCount += 1
            let plain = String(value.characters)
            guard let match = spoilerPattern.firstMatch(in: plain, range: NSRange(plain.startIndex..., in: plain)),
                  let whole = Range(match.range, in: plain),
                  let bodyRange = Range(match.range(at: 1), in: plain) else { return }
            // Содержимое спойлера сохраняется — оно затемнено фоном, а не стёрто.
            guard replace(&value, whole: whole, body: String(plain[bodyRange]),
                          background: true, color: HonorTheme.secondary.opacity(0.45)) else { return }
        }
    }

    /// Возвращает true, если замена выполнена. Без этого при неудачном преобразовании
    /// индекса регулярка совпадала бы снова и цикл крутился бы вечно, вешая интерфейс.
    @discardableResult
    private static func replace(_ value: inout AttributedString, whole: Range<String.Index>, body: String,
                                background: Bool, color: Color) -> Bool {
        guard let lower = AttributedString.Index(whole.lowerBound, within: value),
              let upper = AttributedString.Index(whole.upperBound, within: value) else { return false }
        var replacement = AttributedString(body)
        if background {
            replacement.backgroundColor = color
        } else {
            replacement.foregroundColor = color
        }
        value.replaceSubrange(lower..<upper, with: replacement)
        return true
    }
}

/// Карточка с вопросами и вариантами ответов (пункт 33): до 30 вопросов за раз.
/// Нажатие варианта отправляет ответ в чат; при `+` можно написать свой вариант.
struct QuestionsCardView: View {
    let questions: [QuickQuestion]
    let fontSize: Double
    let onAnswer: (String) -> Void

    @State private var customDrafts: [UUID: String] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(questions.enumerated()), id: \.element.id) { index, question in
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(index + 1). \(question.text)")
                        .font(.system(size: fontSize, weight: .semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(question.options, id: \.self) { option in
                        Button {
                            onAnswer(option)
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "circle")
                                    .font(.system(size: 12))
                                Text(option)
                                    .font(.system(size: fontSize * 0.95))
                                    .multilineTextAlignment(.leading)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 12)
                            .frame(minHeight: 38)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(HonorTheme.raised, in: RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(HonorTheme.foreground)
                        .accessibilityIdentifier("question.option." + option)
                    }
                    if question.allowsCustom {
                        HStack(spacing: 8) {
                            TextField("Свой вариант", text: Binding(
                                get: { customDrafts[question.id] ?? "" },
                                set: { customDrafts[question.id] = $0 }))
                                .font(.system(size: fontSize * 0.95))
                                .textFieldStyle(.plain)
                                .padding(.horizontal, 12)
                                .frame(minHeight: 38)
                                .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 10))
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(HonorTheme.divider, lineWidth: 0.7))
                            Button {
                                let value = (customDrafts[question.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !value.isEmpty else { return }
                                onAnswer(value)
                                customDrafts[question.id] = ""
                            } label: {
                                Image(systemName: "arrow.up")
                                    .font(.system(size: 13, weight: .semibold))
                                    .frame(width: 34, height: 34)
                                    .background(HonorTheme.accent, in: Circle())
                                    .foregroundStyle(.white)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("question.custom.send")
                        }
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(HonorTheme.accent.opacity(0.35), lineWidth: 0.8))
        .accessibilityIdentifier("message.questions")
    }
}

/// Нативный рендер математики без WKWebView: дроби, степени, индексы, корни,
/// греческие буквы и крупные операторы (пункты 9 и 14 ТЗ).
struct MathExpressionView: View {
    let latex: String
    let fontSize: Double
    var block: Bool = false

    var body: some View {
        let tokens = MathTokenizer.tokenize(latex)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .center, spacing: block ? 6 : 2) {
                ForEach(Array(tokens.enumerated()), id: \.offset) { _, token in
                    tokenView(token)
                }
            }
            .padding(.horizontal, block ? 12 : 2)
            .padding(.vertical, block ? 10 : 0)
            .frame(maxWidth: .infinity, alignment: block ? .center : .leading)
        }
        .background(block ? HonorTheme.surface : Color.clear,
                    in: RoundedRectangle(cornerRadius: block ? 10 : 0))
        .overlay {
            if block {
                RoundedRectangle(cornerRadius: 10).stroke(HonorTheme.divider, lineWidth: 0.6)
            }
        }
        .accessibilityIdentifier(block ? "message.math.block" : "message.math.inline")
    }

    @ViewBuilder
    private func tokenView(_ token: MathToken) -> some View {
        switch token {
        case .text(let value):
            Text(value)
                .font(.system(size: fontSize, design: .serif))
                .italic(isSymbolic(value))
        case .sup(let value):
            Text(value)
                .font(.system(size: fontSize * 0.72, design: .serif))
                .baselineOffset(fontSize * 0.45)
        case .sub(let value):
            Text(value)
                .font(.system(size: fontSize * 0.72, design: .serif))
                .baselineOffset(-fontSize * 0.18)
        case .frac(let numerator, let denominator):
            VStack(spacing: 2) {
                Text(numerator).font(.system(size: fontSize * 0.78, design: .serif))
                Rectangle().fill(HonorTheme.foreground).frame(height: 1)
                Text(denominator).font(.system(size: fontSize * 0.78, design: .serif))
            }
        case .sqrt(let value):
            HStack(alignment: .center, spacing: 1) {
                Text("√").font(.system(size: fontSize))
                Text(value)
                    .font(.system(size: fontSize * 0.9, design: .serif))
                    .padding(.horizontal, 3)
                    .overlay(alignment: .top) { Rectangle().fill(HonorTheme.foreground).frame(height: 1) }
            }
        }
    }

    private func isSymbolic(_ value: String) -> Bool {
        value.count == 1 && value.rangeOfCharacter(from: .letters) != nil
    }
}

enum MathToken: Equatable {
    case text(String)
    case sup(String)
    case sub(String)
    case frac(String, String)
    case sqrt(String)
}

enum MathTokenizer {
    /// Простой разбор: \frac{a}{b}, \sqrt{x}, ^ и _ с одним символом или {группой},
    /// греческие команды и прочие \команды превращаются в читаемые символы.
    static func tokenize(_ input: String) -> [MathToken] {
        let source = Array(input)
        var index = 0
        var result: [MathToken] = []

        func readGroup() -> String {
            guard index < source.count, source[index] == "{" else {
                if index < source.count { let value = String(source[index]); index += 1; return value }
                return ""
            }
            index += 1
            var depth = 1
            var value = ""
            while index < source.count {
                let character = source[index]
                if character == "{" { depth += 1 }
                if character == "}" {
                    depth -= 1
                    if depth == 0 { index += 1; break }
                }
                value.append(character)
                index += 1
            }
            return value
        }

        while index < source.count {
            let character = source[index]
            if character == "\\" {
                index += 1
                var command = ""
                while index < source.count, source[index].isLetter {
                    command.append(source[index]); index += 1
                }
                switch command {
                case "frac":
                    let numerator = readGroup()
                    let denominator = readGroup()
                    result.append(.frac(numerator, denominator))
                case "sqrt":
                    result.append(.sqrt(readGroup()))
                case "sum": result.append(.text("∑"))
                case "int": result.append(.text("∫"))
                case "infty": result.append(.text("∞"))
                case "pi": result.append(.text("π"))
                case "alpha": result.append(.text("α"))
                case "beta": result.append(.text("β"))
                case "gamma": result.append(.text("γ"))
                case "delta": result.append(.text("δ"))
                case "theta": result.append(.text("θ"))
                case "lambda": result.append(.text("λ"))
                case "mu": result.append(.text("μ"))
                case "sigma": result.append(.text("σ"))
                case "phi": result.append(.text("φ"))
                case "omega": result.append(.text("ω"))
                case "times": result.append(.text("×"))
                case "cdot": result.append(.text("·"))
                case "pm": result.append(.text("±"))
                case "le", "leq": result.append(.text("≤"))
                case "ge", "geq": result.append(.text("≥"))
                case "ne", "neq": result.append(.text("≠"))
                case "approx": result.append(.text("≈"))
                case "to", "rightarrow": result.append(.text("→"))
                case "left", "right", "displaystyle", "limits", "text": break
                case "": result.append(.text(String(character)))
                default: result.append(.text(command))
                }
                continue
            }
            if character == "^" {
                index += 1
                result.append(.sup(readGroup()))
                continue
            }
            if character == "_" {
                index += 1
                result.append(.sub(readGroup()))
                continue
            }
            if character == "{" || character == "}" {
                index += 1
                continue
            }
            // Собираем обычный текст до следующего специального символа.
            var plain = ""
            while index < source.count,
                  !["\\", "^", "_", "{", "}"].contains(source[index]) {
                plain.append(source[index]); index += 1
            }
            if !plain.isEmpty { result.append(.text(plain)) }
        }
        return result.isEmpty ? [.text(input)] : result
    }
}

/// Простая нативная отрисовка Mermaid-диаграммы: узлы и стрелки без веб-вью (пункт 10 ТЗ).
struct MermaidDiagramView: View {
    let source: String
    let fontSize: Double

    private struct Edge: Identifiable {
        let id = UUID()
        let from: String
        let to: String
        let label: String
    }

    private var edges: [Edge] {
        var result: [Edge] = []
        for rawLine in source.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard line.contains("-->") || line.contains("->") || line.contains("==>") else { continue }
            let separator = line.contains("==>") ? "==>" : (line.contains("-->") ? "-->" : "->")
            let parts = line.components(separatedBy: separator)
            guard parts.count >= 2 else { continue }
            let from = clean(parts[0])
            var to = parts[1]
            var label = ""
            if let labelStart = to.firstIndex(of: "|"), let labelEnd = to[labelStart...].dropFirst().firstIndex(of: "|") {
                label = String(to[to.index(after: labelStart)..<labelEnd])
                to = String(to[to.index(after: labelEnd)...])
            }
            let target = clean(to)
            guard !from.isEmpty, !target.isEmpty else { continue }
            result.append(Edge(from: from, to: target, label: label))
        }
        return result
    }

    private func clean(_ value: String) -> String {
        var text = value.trimmingCharacters(in: .whitespaces)
        if let open = text.firstIndex(of: "["), let close = text.firstIndex(of: "]"), open < close {
            text = String(text[text.index(after: open)..<close])
        }
        if let open = text.firstIndex(of: "("), let close = text.firstIndex(of: ")"), open < close {
            text = String(text[text.index(after: open)..<close])
        }
        return text.trimmingCharacters(in: CharacterSet(charactersIn: " ;\"'"))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if edges.isEmpty {
                // Не удалось разобрать — показываем исходник, чтобы ничего не терялось.
                Text(source)
                    .font(.system(size: fontSize * 0.82, design: .monospaced))
                    .textSelection(.enabled)
            } else {
                ForEach(edges) { edge in
                    HStack(spacing: 8) {
                        node(edge.from)
                        VStack(spacing: 1) {
                            if !edge.label.isEmpty {
                                Text(edge.label)
                                    .font(.system(size: fontSize * 0.72))
                                    .foregroundStyle(HonorTheme.secondary)
                            }
                            Image(systemName: "arrow.right")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(HonorTheme.accent)
                        }
                        node(edge.to)
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HonorTheme.divider, lineWidth: 0.6))
        .accessibilityIdentifier("message.diagram")
    }

    private func node(_ title: String) -> some View {
        Text(title.isEmpty ? "?" : title)
            .font(.system(size: fontSize * 0.85, weight: .medium))
            .padding(.horizontal, 10)
            .frame(minHeight: 30)
            .background(HonorTheme.raised, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(HonorTheme.divider, lineWidth: 0.6))
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Карточки-превью ссылок из ответа: заголовок, описание, картинка и имя сайта (пункт 42 ТЗ).
struct LinkPreviewListView: View {
    let content: String
    let fontSize: Double

    @State private var previews: [LinkPreview] = []

    /// Достаёт http/https ссылки из текста, без дублей и мусора.
    static func links(in text: String) -> [URL] {
        guard let regex = try? NSRegularExpression(pattern: "https?://[^\\s\\)\\]\"'<>]+") else { return [] }
        let matches = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
        var result: [URL] = []
        var seen = Set<String>()
        for match in matches {
            guard let range = Range(match.range, in: text) else { continue }
            var value = String(text[range])
            // Убираем хвостовую пунктуацию, которую модель иногда приклеивает.
            while let last = value.last, ".,;:!?»)".contains(last) { value.removeLast() }
            guard let url = URL(string: value), let host = url.host, host.contains(".") else { continue }
            guard seen.insert(value).inserted else { continue }
            result.append(url)
            if result.count >= 3 { break }
        }
        return result
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(previews) { preview in
                Link(destination: preview.url) {
                    VStack(alignment: .leading, spacing: 8) {
                        if let imageURL = preview.imageURL {
                            AsyncImage(url: imageURL) { phase in
                                if case .success(let image) = phase {
                                    image.resizable().scaledToFill().frame(height: 120).clipped()
                                } else {
                                    Color.clear.frame(height: 0)
                                }
                            }
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            if !preview.siteName.isEmpty {
                                Text(preview.siteName.uppercased())
                                    .font(.system(size: fontSize * 0.68, weight: .semibold))
                                    .foregroundStyle(HonorTheme.secondary)
                            }
                            Text(preview.title)
                                .font(.system(size: fontSize * 0.9, weight: .semibold))
                                .foregroundStyle(HonorTheme.foreground)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                            if !preview.description.isEmpty {
                                Text(preview.description)
                                    .font(.system(size: fontSize * 0.8))
                                    .foregroundStyle(HonorTheme.secondary)
                                    .lineLimit(3)
                                    .multilineTextAlignment(.leading)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.bottom, 12)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.6))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("message.link.preview")
            }
        }
        .task(id: content) {
            let urls = Self.links(in: content)
            guard !urls.isEmpty else {
                previews = []
                return
            }
            var loaded: [LinkPreview] = []
            for url in urls {
                if let preview = await LinkPreviewService.shared.load(url) {
                    loaded.append(preview)
                }
            }
            previews = loaded
        }
    }
}

/// Блок ```copy — фрагмент, который копируется одной кнопкой (пункт 7 ТЗ).
struct CopyBlockView: View {
    let text: String
    let fontSize: Double
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(text)
                .font(.system(size: fontSize))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                UIPasteboard.general.string = text
                copied = true
                Task {
                    try? await Task.sleep(nanoseconds: 1_600_000_000)
                    copied = false
                }
            } label: {
                Label(copied ? "Скопировано" : "Копировать", systemImage: copied ? "checkmark" : "square.on.square")
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 12)
                    .frame(minHeight: 30)
                    .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
            }
            .buttonStyle(.plain)
            .foregroundStyle(copied ? HonorTheme.accent : HonorTheme.secondary)
            .accessibilityIdentifier("message.copy.block")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.6))
    }
}

/// Блок ```card:info|success|warn|error — цветная карточка с полосой слева (пункт 42 ТЗ).
struct CardBlockView: View {
    let style: String
    /// Содержимое карточки. Имя не `body`, иначе конфликтует с View.body.
    let cardText: String
    let fontSize: Double
    let sources: [WebSource]
    let findQuery: String

    private var accent: Color {
        switch style.lowercased() {
        case "success", "ok", "успех": return Color(red: 0.24, green: 0.78, blue: 0.45)
        case "warn", "warning", "предупреждение": return Color(red: 1.0, green: 0.72, blue: 0.24)
        case "error", "danger", "ошибка": return Color(red: 1.0, green: 0.36, blue: 0.36)
        case "quote", "цитата": return HonorTheme.secondary
        default: return HonorTheme.accent
        }
    }

    private var symbol: String {
        switch style.lowercased() {
        case "success", "ok", "успех": return "checkmark.circle.fill"
        case "warn", "warning", "предупреждение": return "exclamationmark.triangle.fill"
        case "error", "danger", "ошибка": return "xmark.octagon.fill"
        case "quote", "цитата": return "quote.opening"
        default: return "info.circle.fill"
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 11) {
            Rectangle().fill(accent).frame(width: 3)
            VStack(alignment: .leading, spacing: 7) {
                Image(systemName: symbol)
                    .font(.system(size: 15))
                    .foregroundStyle(accent)
                Text(rendered)
                    .font(.system(size: fontSize))
                    .lineSpacing(5)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 2)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(accent.opacity(0.35), lineWidth: 0.7))
        .accessibilityIdentifier("message.card." + style.lowercased())
    }

    private var rendered: AttributedString {
        let cited = linkedCitations(cardText, sources: sources)
        var value = (try? AttributedString(markdown: cited,
                                           options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(cardText)
        value = InlineStyleParser.apply(to: value)
        return highlighted(value, query: findQuery)
    }
}

struct CodeBlockView: View {
    let text: String
    let language: String
    let fontSize: Double
    let findQuery: String

    @State private var copied = false
    @State private var showLineNumbers = false
    @State private var useLightTheme = false
    @State private var showDiff = false
    /// Результат запуска кода (пункт «Запустить» из ТЗ).
    @State private var runResult: CodeRunner.Result?

    /// Длинный код сворачивается, чтобы не занимать весь экран.
    private var isLong: Bool { text.components(separatedBy: "\n").count > 24 }
    @State private var expanded = false

    private var lines: [String] { text.components(separatedBy: "\n") }

    private var visibleText: String {
        if isLong && !expanded {
            return lines.prefix(18).joined(separator: "\n")
        }
        return text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text(language.isEmpty ? "код" : language)
                    .font(.system(size: 11, weight: .medium))
                Spacer(minLength: 0)
                Button { showLineNumbers.toggle() } label: {
                    Image(systemName: "list.number").frame(width: 38, height: 32)
                }
                .accessibilityLabel("Номера строк")
                Button { showDiff.toggle() } label: {
                    Image(systemName: showDiff ? "plusminus.circle.fill" : "plusminus.circle").frame(width: 38, height: 32)
                }
                .accessibilityLabel("Режим изменений")
                .accessibilityIdentifier("message.code.diff")
                if CodeRunner.canRun(language) {
                    Button {
                        runResult = CodeRunner.run(text, language: language)
                    } label: {
                        Image(systemName: "play.circle").frame(width: 38, height: 32)
                    }
                    .accessibilityLabel("Запустить код")
                    .accessibilityIdentifier("message.code.run")
                }
                Button { useLightTheme.toggle() } label: {
                    Image(systemName: useLightTheme ? "sun.max" : "moon").frame(width: 38, height: 32)
                }
                .accessibilityLabel("Тема подсветки")
                Button {
                    UIPasteboard.general.string = text
                    copied = true
                    Task {
                        try? await Task.sleep(nanoseconds: 1_600_000_000)
                        copied = false
                    }
                } label: {
                    Image(systemName: copied ? "checkmark" : "square.on.square").frame(width: 38, height: 32)
                }
                .accessibilityLabel("Копировать код")
                .accessibilityIdentifier("message.code.copy")
                ShareLink(item: text) {
                    Image(systemName: "square.and.arrow.up").frame(width: 38, height: 32)
                }
                .accessibilityLabel("Поделиться кодом")
            }
            .foregroundStyle(HonorTheme.secondary)
            .padding(.leading, 12).padding(.trailing, 2)
            .background(HonorTheme.raised)

            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    if showLineNumbers {
                        HStack(alignment: .top, spacing: 10) {
                            VStack(alignment: .trailing, spacing: 0) {
                                ForEach(Array(visibleText.components(separatedBy: "\n").enumerated()), id: \.offset) { index, _ in
                                    Text("\(index + 1)")
                                        .font(.system(size: fontSize * 0.75, design: .monospaced))
                                        .foregroundStyle(HonorTheme.secondary)
                                }
                            }
                            codeText
                        }
                        .padding(12)
                    } else {
                        codeText.padding(12)
                    }
                    if isLong && !expanded {
                        Button {
                            expanded = true
                        } label: {
                            Label("Показать весь код (\(lines.count) строк)", systemImage: "chevron.down")
                                .font(.system(size: 11, weight: .medium))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(HonorTheme.accent)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 10)
                        .accessibilityIdentifier("message.code.expand")
                    }
                }
            }

            // Результат запуска (пункт «Запустить» из ТЗ).
            if let runResult {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Image(systemName: runResult.isError ? "exclamationmark.triangle" : "checkmark.circle")
                            .font(.system(size: 11))
                        Text(runResult.isError ? "Ошибка выполнения" : "Результат")
                            .font(.system(size: 11, weight: .semibold))
                        Spacer(minLength: 0)
                        Button {
                            UIPasteboard.general.string = runResult.output
                        } label: {
                            Image(systemName: "square.on.square").font(.system(size: 11))
                        }
                        .accessibilityLabel("Копировать результат")
                        Button {
                            self.runResult = nil
                        } label: {
                            Image(systemName: "xmark").font(.system(size: 11))
                        }
                        .accessibilityLabel("Скрыть результат")
                    }
                    .foregroundStyle(runResult.isError ? Color.orange : HonorTheme.secondary)
                    Text(runResult.output)
                        .font(.system(size: fontSize * 0.78, design: .monospaced))
                        .foregroundStyle(runResult.isError ? Color.orange : HonorTheme.foreground)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(HonorTheme.raised.opacity(0.7))
                .accessibilityIdentifier("message.code.output")
            }
        }
        .background(useLightTheme ? Color.white : HonorTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.6))
    }

    /// Подсветка синтаксиса своим разбором по языку (пункт 6 ТЗ).
    /// В режиме изменений строки с «+» подсвечиваются зелёным, с «−» красным.
    @ViewBuilder
    private var codeText: some View {
        if showDiff {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(visibleText.components(separatedBy: "\n").enumerated()), id: \.offset) { _, line in
                    let trimmed = line.trimmingCharacters(in: .whitespaces)
                    // highlight возвращает Text — его нужно складывать, а не оборачивать в Text(...)
                    (SyntaxHighlighter.highlight(line.isEmpty ? " " : line,
                                                 language: language,
                                                 theme: SyntaxHighlighter.Theme.named(useLightTheme ? "light" : "dark"),
                                                 fontSize: fontSize)
                        .font(.system(size: fontSize * 0.82, design: .monospaced)))
                        .textSelection(.enabled)
                        .padding(.horizontal, 6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(diffBackground(trimmed))
                }
            }
            .padding(.vertical, 8)
        } else {
            SyntaxHighlighter.highlight(visibleText,
                                        language: language,
                                        theme: SyntaxHighlighter.Theme.named(useLightTheme ? "light" : "dark"),
                                        fontSize: fontSize)
                .font(.system(size: fontSize * 0.82, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    private func diffBackground(_ line: String) -> Color {
        if line.hasPrefix("+") && !line.hasPrefix("+++") { return Color.green.opacity(0.16) }
        if line.hasPrefix("-") && !line.hasPrefix("---") { return Color.red.opacity(0.16) }
        if line.hasPrefix("@@") { return HonorTheme.accent.opacity(0.12) }
        return .clear
    }
}

/// Таблица GFM с выравниванием столбцов, сортировкой, фильтром по строкам,
/// строкой итогов, горизонтальным скроллом и копированием в Markdown / TSV / CSV.
struct MarkdownTableView: View {
    let headers: [String]
    let alignments: [TableAlignment]
    let rows: [[String]]
    let fontSize: Double
    let findQuery: String

    @State private var copied: String?
    @State private var sortColumn: Int?
    @State private var sortAscending = true
    @State private var filter = ""
    @State private var showTotals = false
    @State private var exportRequest: ExportRequest?

    private var columnCount: Int { max(headers.count, rows.map(\.count).max() ?? 0) }

    /// Строки после фильтра и сортировки.
    private var visibleRows: [[String]] {
        var result = rows
        let query = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        if !query.isEmpty {
            result = result.filter { row in
                row.contains { $0.localizedCaseInsensitiveContains(query) }
            }
        }
        if let column = sortColumn {
            result.sort { lhs, rhs in
                let left = column < lhs.count ? lhs[column] : ""
                let right = column < rhs.count ? rhs[column] : ""
                // Числа сравниваем как числа, иначе как текст.
                if let a = Double(left.replacingOccurrences(of: ",", with: ".")),
                   let b = Double(right.replacingOccurrences(of: ",", with: ".")) {
                    return sortAscending ? a < b : a > b
                }
                let order = left.localizedStandardCompare(right)
                return sortAscending ? order == .orderedAscending : order == .orderedDescending
            }
        }
        return result
    }

    /// Есть ли в столбце числа — тогда показываем итоги.
    private var numericColumns: [Int] {
        (0..<columnCount).filter { column in
            let values = visibleRows.compactMap { row -> Double? in
                guard column < row.count else { return nil }
                let cleaned = row[column]
                    .replacingOccurrences(of: " ", with: "")
                    .replacingOccurrences(of: ",", with: ".")
                    .replacingOccurrences(of: "₽", with: "")
                    .replacingOccurrences(of: "%", with: "")
                return Double(cleaned)
            }
            return values.count >= 2 && values.count == visibleRows.count
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !headers.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 12)).foregroundStyle(HonorTheme.secondary)
                    TextField("Фильтр по строкам", text: $filter)
                        .font(.system(size: 12))
                        .textFieldStyle(.plain)
                        .accessibilityIdentifier("table.filter")
                    if !filter.isEmpty {
                        Button { filter = "" } label: {
                            Image(systemName: "xmark.circle.fill").font(.system(size: 13))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(HonorTheme.secondary)
                    }
                    if !numericColumns.isEmpty {
                        Button { showTotals.toggle() } label: {
                            Image(systemName: showTotals ? "sum.circle.fill" : "sum.circle")
                                .font(.system(size: 15))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(showTotals ? HonorTheme.accent : HonorTheme.secondary)
                        .accessibilityLabel("Итоги по столбцам")
                    }
                }
                .padding(.horizontal, 10)
                .frame(minHeight: 32)
                .background(HonorTheme.surface, in: Capsule())
                .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.6))
            }

            ScrollView(.horizontal, showsIndicators: true) {
                VStack(alignment: .leading, spacing: 0) {
                    if !headers.isEmpty {
                        headerRow
                        Rectangle().fill(HonorTheme.divider).frame(height: 1)
                    }
                    ForEach(Array(visibleRows.enumerated()), id: \.offset) { index, cells in
                        row(cells, isHeader: false)
                        if index < visibleRows.count - 1 {
                            Rectangle().fill(HonorTheme.divider.opacity(0.5)).frame(height: 0.5)
                        }
                    }
                    if showTotals && !numericColumns.isEmpty {
                        Rectangle().fill(HonorTheme.divider).frame(height: 1)
                        totalsRow
                    }
                }
                .background(HonorTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(HonorTheme.divider, lineWidth: 0.6))
            }

            HStack(spacing: 8) {
                copyButton("Markdown", value: asMarkdown)
                copyButton("TSV", value: asTSV)
                copyButton("CSV", value: asCSV)
                // Экспорт таблицы файлом — можно открыть в Numbers (пункт 42 ТЗ).
                Button {
                    exportRequest = ExportRequest(content: asCSV)
                } label: {
                    Text("В Numbers").font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 10).frame(minHeight: 28)
                        .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
                }
                .buttonStyle(.plain)
                .foregroundStyle(HonorTheme.secondary)
                .accessibilityIdentifier("table.export.numbers")
                if !filter.isEmpty {
                    Text("найдено \(visibleRows.count) из \(rows.count)")
                        .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
                }
                if let copied {
                    Text("Скопировано: \(copied)")
                        .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
                }
            }
        }
        .sheet(item: $exportRequest) { request in
            DocumentExportSheet(csv: request.content)
        }
    }

    /// Шапка: тап по столбцу сортирует его.
    private var headerRow: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<columnCount, id: \.self) { index in
                Button {
                    if sortColumn == index { sortAscending.toggle() } else { sortColumn = index; sortAscending = true }
                } label: {
                    HStack(spacing: 4) {
                        Text(index < headers.count ? headers[index] : "")
                            .font(.system(size: fontSize * 0.92, weight: .semibold))
                            .multilineTextAlignment(textAlignment(alignment(index)))
                        if sortColumn == index {
                            Image(systemName: sortAscending ? "chevron.up" : "chevron.down")
                                .font(.system(size: 9, weight: .bold))
                        }
                    }
                    .frame(minWidth: 88, maxWidth: 240, alignment: frameAlignment(alignment(index)))
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(sortColumn == index ? HonorTheme.accent : HonorTheme.foreground)
                .accessibilityLabel("Сортировать по столбцу")
                if index < columnCount - 1 {
                    Rectangle().fill(HonorTheme.divider.opacity(0.6)).frame(width: 0.5)
                }
            }
        }
        .background(HonorTheme.raised)
    }

    /// Строка итогов: сумма и среднее по числовым столбцам.
    private var totalsRow: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<columnCount, id: \.self) { index in
                let values = numericColumnValues(index)
                VStack(alignment: .leading, spacing: 2) {
                    if values.isEmpty {
                        Text(index == 0 ? "Итого" : "")
                            .font(.system(size: fontSize * 0.85, weight: .semibold))
                            .foregroundStyle(HonorTheme.secondary)
                    } else {
                        Text("Σ \(formatted(values.reduce(0, +)))")
                            .font(.system(size: fontSize * 0.85, weight: .semibold))
                        Text("сред. \(formatted(values.reduce(0, +) / Double(values.count)))")
                            .font(.system(size: fontSize * 0.75))
                            .foregroundStyle(HonorTheme.secondary)
                    }
                }
                .frame(minWidth: 88, maxWidth: 240, alignment: frameAlignment(alignment(index)))
                .padding(.horizontal, 10).padding(.vertical, 8)
                if index < columnCount - 1 {
                    Rectangle().fill(HonorTheme.divider.opacity(0.6)).frame(width: 0.5)
                }
            }
        }
        .background(HonorTheme.raised.opacity(0.6))
    }

    private func numericColumnValues(_ column: Int) -> [Double] {
        guard numericColumns.contains(column) else { return [] }
        return visibleRows.compactMap { row in
            guard column < row.count else { return nil }
            let cleaned = row[column]
                .replacingOccurrences(of: " ", with: "")
                .replacingOccurrences(of: ",", with: ".")
                .replacingOccurrences(of: "₽", with: "")
                .replacingOccurrences(of: "%", with: "")
            return Double(cleaned)
        }
    }

    private func formatted(_ value: Double) -> String {
        if value == value.rounded() && abs(value) < 1_000_000 {
            return String(Int(value))
        }
        return String(format: "%.2f", value)
    }

    private func alignment(_ index: Int) -> TableAlignment {
        index < alignments.count ? alignments[index] : .leading
    }

    private func frameAlignment(_ value: TableAlignment) -> Alignment {
        switch value {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }

    /// Обычная строка данных (шапка рисуется отдельно, чтобы её можно было сортировать).
    private func row(_ cells: [String], isHeader: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<columnCount, id: \.self) { index in
                let value = index < cells.count ? cells[index] : ""
                Text(highlighted(AttributedString(value), query: findQuery))
                    .font(.system(size: fontSize * 0.9))
                    .multilineTextAlignment(textAlignment(alignment(index)))
                    .frame(minWidth: 88, maxWidth: 240, alignment: frameAlignment(alignment(index)))
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .fixedSize(horizontal: false, vertical: true)
                if index < columnCount - 1 {
                    Rectangle().fill(HonorTheme.divider.opacity(0.6)).frame(width: 0.5)
                }
            }
        }
    }

    private func textAlignment(_ value: TableAlignment) -> TextAlignment {
        switch value {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }

    private func copyButton(_ title: String, value: String) -> some View {
        Button {
            UIPasteboard.general.string = value
            copied = title
            Task { try? await Task.sleep(nanoseconds: 1_600_000_000); copied = nil }
        } label: {
            Text(title).font(.system(size: 11, weight: .medium))
                .padding(.horizontal, 10).frame(minHeight: 28)
                .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
        }
        .buttonStyle(.plain)
        .foregroundStyle(HonorTheme.secondary)
    }

    private var asMarkdown: String {
        var lines: [String] = []
        if !headers.isEmpty {
            lines.append("| " + headers.joined(separator: " | ") + " |")
            lines.append("|" + (0..<max(headers.count, 1)).map { index in
                switch alignment(index) {
                case .leading: return ":---"
                case .center: return ":---:"
                case .trailing: return "---:"
                }
            }.joined(separator: "|") + "|")
        }
        // Копируется то, что видно с учётом фильтра и сортировки.
        for cells in visibleRows { lines.append("| " + cells.joined(separator: " | ") + " |") }
        return lines.joined(separator: "\n")
    }

    private var asTSV: String {
        ([headers] + visibleRows).map { $0.joined(separator: "\t") }.joined(separator: "\n")
    }

    private var asCSV: String {
        func escape(_ value: String) -> String {
            value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
                : value
        }
        return ([headers] + visibleRows).map { $0.map(escape).joined(separator: ",") }.joined(separator: "\n")
    }
}
