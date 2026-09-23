import SwiftUI

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
    case table(headers: [String], alignments: [TableAlignment], rows: [[String]])
    case divider
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
                var body: [String] = []
                index += 1
                while index < lines.count,
                      !lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                    body.append(lines[index])
                    index += 1
                }
                if index < lines.count { index += 1 }   // закрывающий ```
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .code(language: language),
                                                 text: body.joined(separator: "\n")))
                continue
            }

            // Горизонтальный разделитель
            if isDivider(trimmed) {
                flushParagraph()
                blocks.append(MarkdownBlockModel(id: blocks.count, kind: .divider, text: ""))
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

    private static func isDivider(_ line: String) -> Bool {
        let stripped = line.replacingOccurrences(of: " ", with: "")
        guard stripped.count >= 3 else { return false }
        return stripped.allSatisfy { $0 == "-" } || stripped.allSatisfy { $0 == "*" } || stripped.allSatisfy { $0 == "_" }
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

struct BlockMarkdownView: View {
    let content: String
    let fontSize: Double
    let sources: [WebSource]
    let findQuery: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(MarkdownBlockParser.parse(content)) { block in
                MarkdownBlockView(block: block, fontSize: fontSize,
                                  sources: sources, findQuery: findQuery)
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
        Text(inlineAttributed(text))
            .font(.system(size: size, weight: weight))
            .lineSpacing(5)
            .tint(HonorTheme.accent)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func inlineAttributed(_ source: String) -> AttributedString {
        let cited = linkedCitations(source, sources: sources)
        let value = (try? AttributedString(markdown: cited,
                                           options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(source)
        return highlighted(value, query: findQuery)
    }
}

struct CodeBlockView: View {
    let text: String
    let language: String
    let fontSize: Double
    let findQuery: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language.isEmpty ? "код" : language)
                    .font(.system(size: 11, weight: .medium))
                Spacer()
                Button { UIPasteboard.general.string = text } label: {
                    Image(systemName: "square.on.square").frame(width: 44, height: 32)
                }
                .accessibilityLabel("Копировать код")
                .accessibilityIdentifier("message.code.copy")
            }
            .foregroundStyle(HonorTheme.secondary)
            .padding(.leading, 12).padding(.trailing, 2)
            .background(HonorTheme.raised)

            ScrollView(.horizontal, showsIndicators: false) {
                Text(highlighted(AttributedString(text), query: findQuery))
                    .font(.system(size: fontSize * 0.82, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(12)
            }
        }
        .background(HonorTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.6))
    }
}

/// Таблица GFM с выравниванием столбцов, горизонтальным скроллом и копированием
/// в Markdown / TSV / CSV.
struct MarkdownTableView: View {
    let headers: [String]
    let alignments: [TableAlignment]
    let rows: [[String]]
    let fontSize: Double
    let findQuery: String

    @State private var copied: String?

    private var columnCount: Int { max(headers.count, rows.map(\.count).max() ?? 0) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: true) {
                VStack(alignment: .leading, spacing: 0) {
                    if !headers.isEmpty {
                        row(headers, isHeader: true)
                        Rectangle().fill(HonorTheme.divider).frame(height: 1)
                    }
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, cells in
                        row(cells, isHeader: false)
                        if index < rows.count - 1 {
                            Rectangle().fill(HonorTheme.divider.opacity(0.5)).frame(height: 0.5)
                        }
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
                if let copied {
                    Text("Скопировано: \(copied)")
                        .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
                }
            }
        }
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

    private func row(_ cells: [String], isHeader: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<columnCount, id: \.self) { index in
                let value = index < cells.count ? cells[index] : ""
                Text(highlighted(AttributedString(value), query: findQuery))
                    .font(.system(size: fontSize * (isHeader ? 0.92 : 0.9),
                                  weight: isHeader ? .semibold : .regular))
                    .multilineTextAlignment(textAlignment(alignment(index)))
                    .frame(minWidth: 88, maxWidth: 240, alignment: frameAlignment(alignment(index)))
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .fixedSize(horizontal: false, vertical: true)
                if index < columnCount - 1 {
                    Rectangle().fill(HonorTheme.divider.opacity(0.6)).frame(width: 0.5)
                }
            }
        }
        .background(isHeader ? HonorTheme.raised : Color.clear)
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
        for cells in rows { lines.append("| " + cells.joined(separator: " | ") + " |") }
        return lines.joined(separator: "\n")
    }

    private var asTSV: String {
        ([headers] + rows).map { $0.joined(separator: "\t") }.joined(separator: "\n")
    }

    private var asCSV: String {
        func escape(_ value: String) -> String {
            value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
                : value
        }
        return ([headers] + rows).map { $0.map(escape).joined(separator: ",") }.joined(separator: "\n")
    }
}
