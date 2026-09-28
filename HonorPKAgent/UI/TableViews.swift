import SwiftUI
import UIKit

/// Таблицы, созданные в ответе: карточки под текстом. Данные берутся из ChatStore,
/// поэтому правки пользователя сразу видны и здесь, и в следующих ответах модели.
struct ChatTableCards: View {
    let ids: [UUID]
    let scale: Double
    @EnvironmentObject private var store: ChatStore
    @State private var opened: OpenedTable?

    private struct OpenedTable: Identifiable { let id: UUID }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(ids, id: \.self) { id in
                if let table = store.table(id: id) {
                    Button { opened = OpenedTable(id: id) } label: {
                        TableCardView(table: table, scale: scale)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("message.table." + id.uuidString)
                }
            }
        }
        .fullScreenCover(item: $opened) { item in
            TableEditorView(tableID: item.id)
                .environmentObject(store)
        }
    }
}

/// Карточка таблицы: название, размер и первые строки.
struct TableCardView: View {
    let table: ChatTable
    let scale: Double
    @AppStorage("honor.language") private var language = "ru"

    private func text(_ ru: String, _ en: String) -> String { language == "en" ? en : ru }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            preview
            Label(table.editable ? text("Открыть и редактировать", "Open and edit") : text("Открыть таблицу", "Open table"),
                  systemImage: table.editable ? "square.and.pencil" : "arrow.up.left.and.arrow.down.right")
                .font(.system(size: 13 * scale, weight: .semibold))
                .foregroundStyle(HonorTheme.accent)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.8))
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "tablecells.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(LinearGradient(colors: [Color(red: 0.2, green: 0.7, blue: 0.45), Color(red: 0.1, green: 0.55, blue: 0.4)],
                                           startPoint: .top, endPoint: .bottom),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(table.title)
                    .font(.system(size: 16 * scale, weight: .semibold))
                    .foregroundStyle(HonorTheme.foreground)
                    .lineLimit(2)
                Text(summary)
                    .font(.system(size: 12 * scale))
                    .foregroundStyle(HonorTheme.secondary)
            }
            Spacer(minLength: 0)
        }
    }

    private var summary: String {
        var parts = [text("\(table.rows.count) строк · \(table.columns.count) столбцов",
                          "\(table.rows.count) rows · \(table.columns.count) columns")]
        parts.append(table.editable ? text("можно править", "editable") : text("только просмотр", "read-only"))
        if table.editedByUser { parts.append(text("есть ваши правки", "edited by you")) }
        return parts.joined(separator: " · ")
    }

    private var preview: some View {
        let columns = Array(table.columns.prefix(3))
        let rows = Array(table.rows.prefix(3))
        return VStack(spacing: 0) {
            previewRow(columns, bold: true)
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                Divider().overlay(HonorTheme.divider)
                previewRow(Array(TableEditing.normalized(row, width: table.columns.count).prefix(3)), bold: false)
            }
        }
        .background(HonorTheme.raised.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func previewRow(_ values: [String], bold: Bool) -> some View {
        HStack(spacing: 8) {
            ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                Text(value.isEmpty ? "—" : value)
                    .font(.system(size: 12.5 * scale, weight: bold ? .semibold : .regular))
                    .foregroundStyle(bold ? HonorTheme.foreground : HonorTheme.secondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
    }
}

// MARK: - Полноэкранный редактор

/// Таблица на весь экран. Редактируемую можно править: ячейки, строки, столбцы, название.
/// Каждая правка сохраняется сразу — Honer AI видит её в следующем ответе.
struct TableEditorView: View {
    let tableID: UUID
    @EnvironmentObject private var store: ChatStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("honor.language") private var language = "ru"
    @State private var editing: CellAddress?
    @State private var draft = ""
    @State private var renamingColumn: Int?
    @State private var renamingTitle = false
    @State private var nameDraft = ""
    @State private var share: SharedTableFile?
    @State private var toast: String?
    @State private var search = ""

    struct CellAddress: Identifiable, Equatable {
        let row: Int
        let column: Int
        var id: String { "\(row)-\(column)" }
    }

    private struct SharedTableFile: Identifiable { let id = UUID(); let url: URL }

    private func text(_ ru: String, _ en: String) -> String { language == "en" ? en : ru }
    private var table: ChatTable? { store.table(id: tableID) }
    private let columnWidth: CGFloat = 150
    private let numberWidth: CGFloat = 44

    var body: some View {
        NavigationStack {
            Group {
                if let table { content(table) } else { missing }
            }
            .navigationTitle(table?.title ?? text("Таблица", "Table"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic),
                        prompt: text("Найти в таблице", "Search table"))
            .background(HonorTheme.background.ignoresSafeArea())
        }
        .alert(text("Ячейка", "Cell"), isPresented: editingShown) {
            TextField(text("Значение", "Value"), text: $draft)
                .accessibilityIdentifier("table.cell.field")
            Button(text("Сохранить", "Save")) { commitCell() }
                .accessibilityIdentifier("table.cell.save")
            Button(text("Отмена", "Cancel"), role: .cancel) { editing = nil }
        }
        .alert(text("Название столбца", "Column name"), isPresented: renamingColumnShown) {
            TextField(text("Название", "Name"), text: $nameDraft)
            Button(text("Сохранить", "Save")) { commitColumnName() }
            Button(text("Отмена", "Cancel"), role: .cancel) { renamingColumn = nil }
        }
        .alert(text("Название таблицы", "Table title"), isPresented: $renamingTitle) {
            TextField(text("Название", "Title"), text: $nameDraft)
            Button(text("Сохранить", "Save")) { commitTitle() }
            Button(text("Отмена", "Cancel"), role: .cancel) {}
        }
        .sheet(item: $share) { file in ActivitySheet(items: [file.url]) }
        .overlay(alignment: .bottom) { toastView }
        .accessibilityIdentifier("table.editor")
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
    }

    private var renamingColumnShown: Binding<Bool> {
        Binding(get: { renamingColumn != nil }, set: { if !$0 { renamingColumn = nil } })
    }

    private var missing: some View {
        VStack(spacing: 12) {
            Image(systemName: "tablecells.badge.ellipsis").font(.system(size: 40))
            Text(text("Таблица удалена", "The table was deleted"))
        }
        .foregroundStyle(HonorTheme.secondary)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Сетка

    private func visibleRows(_ table: ChatTable) -> [Int] {
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return Array(table.rows.indices) }
        return table.rows.indices.filter { index in
            table.rows[index].contains { $0.lowercased().contains(query) }
        }
    }

    private func content(_ table: ChatTable) -> some View {
        GeometryReader { geometry in
            ScrollView([.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                    Section(header: headerRow(table)) {
                        ForEach(visibleRows(table), id: \.self) { rowIndex in
                            dataRow(table, rowIndex: rowIndex)
                        }
                        if table.editable { addRowButton }
                    }
                }
                .padding(.bottom, 40)
                // Небольшая таблица прижата к верху, а не висит посреди экрана.
                .frame(minWidth: geometry.size.width, minHeight: geometry.size.height, alignment: .topLeading)
            }
        }
        .accessibilityIdentifier("table.grid")
    }

    private func headerRow(_ table: ChatTable) -> some View {
        HStack(spacing: 0) {
            Text("№")
                .font(.system(size: 13, weight: .bold))
                .frame(width: numberWidth, height: 44)
            ForEach(Array(table.columns.enumerated()), id: \.offset) { index, name in
                headerCell(name: name, index: index, editable: table.editable)
            }
            if table.editable {
                Button { addColumn() } label: {
                    Image(systemName: "plus").font(.system(size: 14, weight: .bold))
                        .frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("table.addColumn")
            }
        }
        .background(HonorTheme.raised)
        .overlay(alignment: .bottom) { Rectangle().fill(HonorTheme.divider).frame(height: 1) }
    }

    private func headerCell(name: String, index: Int, editable: Bool) -> some View {
        Menu {
            Button { UIPasteboard.general.string = name; showToast(text("Скопировано", "Copied")) } label: {
                Label(text("Копировать", "Copy"), systemImage: "doc.on.doc")
            }
            Button { sort(column: index, descending: false) } label: {
                Label(text("Сортировать по возрастанию", "Sort ascending"), systemImage: "arrow.up")
            }
            Button { sort(column: index, descending: true) } label: {
                Label(text("Сортировать по убыванию", "Sort descending"), systemImage: "arrow.down")
            }
            if editable {
                Button { nameDraft = name; renamingColumn = index } label: {
                    Label(text("Переименовать", "Rename"), systemImage: "pencil")
                }
                Button(role: .destructive) { deleteColumn(index) } label: {
                    Label(text("Удалить столбец", "Delete column"), systemImage: "trash")
                }
            }
        } label: {
            Text(name)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(HonorTheme.foreground)
                .lineLimit(2)
                .padding(.horizontal, 10)
                .frame(width: columnWidth, height: 44, alignment: .leading)
        }
        .accessibilityIdentifier("table.header.\(index)")
    }

    private func dataRow(_ table: ChatTable, rowIndex: Int) -> some View {
        let row = TableEditing.normalized(table.rows[rowIndex], width: table.columns.count)
        return HStack(spacing: 0) {
            Text("\(rowIndex + 1)")
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundStyle(HonorTheme.secondary)
                .frame(width: numberWidth, height: 46)
            ForEach(Array(row.enumerated()), id: \.offset) { column, value in
                cell(value: value, row: rowIndex, column: column, editable: table.editable)
            }
        }
        .background(rowIndex % 2 == 0 ? HonorTheme.background : HonorTheme.surface.opacity(0.6))
        .contextMenu { rowMenu(table, rowIndex: rowIndex) }
    }

    private func cell(value: String, row: Int, column: Int, editable: Bool) -> some View {
        Text(value.isEmpty ? " " : value)
            .font(.system(size: 14))
            .foregroundStyle(HonorTheme.foreground)
            .lineLimit(3)
            .padding(.horizontal, 10)
            .frame(width: columnWidth, height: 46, alignment: .leading)
            .overlay(alignment: .trailing) { Rectangle().fill(HonorTheme.divider.opacity(0.5)).frame(width: 0.5) }
            .contentShape(Rectangle())
            .onTapGesture {
                if editable {
                    draft = value
                    editing = CellAddress(row: row, column: column)
                } else {
                    UIPasteboard.general.string = value
                    showToast(text("Скопировано: ", "Copied: ") + String(value.prefix(30)))
                }
            }
            .accessibilityIdentifier("table.cell.\(row).\(column)")
    }

    @ViewBuilder
    private func rowMenu(_ table: ChatTable, rowIndex: Int) -> some View {
        Button {
            UIPasteboard.general.string = TableEditing.normalized(table.rows[rowIndex], width: table.columns.count).joined(separator: "\t")
            showToast(text("Строка скопирована", "Row copied"))
        } label: { Label(text("Копировать строку", "Copy row"), systemImage: "doc.on.doc") }
        if table.editable {
            Button { insertRow(after: rowIndex) } label: {
                Label(text("Вставить строку ниже", "Insert row below"), systemImage: "text.insert")
            }
            Button(role: .destructive) { deleteRow(rowIndex) } label: {
                Label(text("Удалить строку", "Delete row"), systemImage: "trash")
            }
        }
    }

    private var addRowButton: some View {
        Button { insertRow(after: (table?.rows.count ?? 0) - 1) } label: {
            Label(text("Добавить строку", "Add row"), systemImage: "plus.circle.fill")
                .font(.system(size: 15, weight: .semibold))
                .padding(.horizontal, 14)
                .frame(height: 48)
        }
        .foregroundStyle(HonorTheme.accent)
        .accessibilityIdentifier("table.addRow")
    }

    // MARK: Панель

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(text("Готово", "Done")) { dismiss() }
                .accessibilityIdentifier("table.close")
        }
        ToolbarItem(placement: .primaryAction) {
            Menu {
                if table?.editable == true {
                    Button { nameDraft = table?.title ?? ""; renamingTitle = true } label: {
                        Label(text("Переименовать таблицу", "Rename table"), systemImage: "pencil")
                    }
                }
                Button { copyMarkdown() } label: {
                    Label(text("Копировать как текст", "Copy as text"), systemImage: "doc.on.doc")
                }
                Button { exportCSV() } label: {
                    Label(text("Экспорт CSV", "Export CSV"), systemImage: "square.and.arrow.up")
                }
                Button { askAboutTable() } label: {
                    Label(text("Спросить Honer AI о таблице", "Ask Honer AI about the table"), systemImage: "sparkles")
                }
                Divider()
                Button(role: .destructive) { store.deleteTable(id: tableID); dismiss() } label: {
                    Label(text("Удалить таблицу", "Delete table"), systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityIdentifier("table.menu")
        }
    }

    @ViewBuilder
    private var toastView: some View {
        if let toast {
            Text(toast)
                .font(.system(size: 14, weight: .medium))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: Правки

    private func mutate(_ change: (inout ChatTable) -> Void) {
        guard var value = table else { return }
        change(&value)
        withAnimation(.easeInOut(duration: 0.2)) { store.saveTable(value) }
    }

    private func commitCell() {
        guard let address = editing else { return }
        mutate { table in
            guard address.row < table.rows.count else { return }
            table.rows[address.row] = TableEditing.normalized(table.rows[address.row], width: table.columns.count)
            table.rows[address.row][address.column] = draft
        }
        editing = nil
    }

    private func commitColumnName() {
        guard let index = renamingColumn else { return }
        let name = nameDraft.trimmingCharacters(in: .whitespaces)
        if !name.isEmpty { mutate { if index < $0.columns.count { $0.columns[index] = name } } }
        renamingColumn = nil
    }

    private func commitTitle() {
        let title = nameDraft.trimmingCharacters(in: .whitespaces)
        if !title.isEmpty { mutate { $0.title = String(title.prefix(120)) } }
    }

    private func insertRow(after index: Int) {
        mutate { table in
            let row = Array(repeating: "", count: table.columns.count)
            let position = min(max(index + 1, 0), table.rows.count)
            table.rows.insert(row, at: position)
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func deleteRow(_ index: Int) {
        mutate { if index < $0.rows.count { $0.rows.remove(at: index) } }
    }

    private func addColumn() {
        mutate { table in
            table.columns.append(text("Столбец", "Column") + " \(table.columns.count + 1)")
            for index in table.rows.indices { table.rows[index].append("") }
        }
    }

    private func deleteColumn(_ index: Int) {
        mutate { table in
            guard table.columns.count > 1, index < table.columns.count else { return }
            table.columns.remove(at: index)
            for row in table.rows.indices where index < table.rows[row].count { table.rows[row].remove(at: index) }
        }
    }

    private func sort(column: Int, descending: Bool) {
        guard let current = table else { return }
        let arguments: [String: Any] = ["action": "sort", "column": column + 1, "descending": descending]
        if let sorted = try? TableEditing.apply(arguments, to: current) {
            withAnimation(.easeInOut(duration: 0.25)) { store.saveTable(sorted, byUser: current.editable) }
        }
    }

    private func copyMarkdown() {
        guard let table else { return }
        UIPasteboard.general.string = table.title + "\n" + TableEditing.markdown(table, limitRows: 5000)
        showToast(text("Таблица скопирована", "Table copied"))
    }

    private func exportCSV() {
        guard let table else { return }
        let name = table.title.replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name + ".csv")
        // BOM нужен, чтобы Excel открыл кириллицу правильно.
        let data = Data([0xEF, 0xBB, 0xBF]) + Data(TableEditing.csv(table).utf8)
        do {
            try data.write(to: url, options: .atomic)
            share = SharedTableFile(url: url)
        } catch {
            showToast(error.localizedDescription)
        }
    }

    private func askAboutTable() {
        guard let table else { return }
        let number = (store.selectedConversation?.tables?.firstIndex { $0.id == table.id } ?? 0) + 1
        store.draft = text("Посмотри таблицу T\(number) «\(table.title)» и ", "Look at table T\(number) \"\(table.title)\" and ")
        dismiss()
    }

    private func showToast(_ message: String) {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { toast = message }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            withAnimation(.easeOut(duration: 0.25)) { toast = nil }
        }
    }
}
