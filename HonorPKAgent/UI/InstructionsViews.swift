import SwiftUI

/// Плашка закреплённой инструкции над перепиской — как закреплённое сообщение
/// в Telegram или ВКонтакте. Показывает, что нейросеть видит эту инструкцию
/// в каждом ответе. Нажатие открывает список инструкций чата.
struct PinnedInstructionBar: View {
    let instructions: [ChatInstruction]
    let settings: AppSettings
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 10) {
                Rectangle()
                    .fill(HonorTheme.accent)
                    .frame(width: 3)
                    .clipShape(Capsule())
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Image(systemName: "pin.fill").font(.system(size: 11, weight: .semibold))
                        Text(title)
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .foregroundStyle(HonorTheme.accent)
                    Text(instructions.last.map { Self.preview($0.text) } ?? "")
                        .font(.system(size: 14))
                        .foregroundStyle(HonorTheme.foreground)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "list.bullet")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(HonorTheme.secondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .background(HonorTheme.surface)
            .overlay(alignment: .bottom) { Rectangle().fill(HonorTheme.divider).frame(height: 0.5) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(settings.text("Закреплённая инструкция: ", "Pinned instruction: ") + (instructions.last?.text ?? ""))
        .accessibilityHint(settings.text("Открыть инструкции чата", "Open chat instructions"))
        .accessibilityIdentifier("chat.instructions.bar")
    }

    private var title: String {
        instructions.count > 1
            ? settings.text("Инструкции · \(instructions.count)", "Instructions · \(instructions.count)")
            : settings.text("Инструкция", "Instruction")
    }

    static func preview(_ text: String) -> String {
        text.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "**", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Инструкции чата: закреплённые и сохранённые («черновики»).
struct InstructionsSheet: View {
    let chatID: UUID
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var editor: InstructionEditorTarget?

    private var pinned: [ChatInstruction] {
        store.conversations.first(where: { $0.id == chatID })?.instructions ?? []
    }

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if pinned.isEmpty {
                        Text(text("Здесь пока пусто. Зажмите любое сообщение в чате — своё или ответ Honer AI — и выберите «Закрепить как инструкцию». Или нажмите «+», чтобы написать инструкцию.",
                                  "Nothing here yet. Touch and hold any message and choose “Pin as instruction”, or tap + to write one."))
                            .font(.system(size: 14))
                            .foregroundStyle(HonorTheme.secondary)
                            .accessibilityIdentifier("instructions.empty")
                    }
                    ForEach(pinned) { item in
                        pinnedRow(item)
                    }
                } header: {
                    Text(text("Закреплено в этом чате", "Pinned in this chat"))
                } footer: {
                    Text(text("Honer AI видит закреплённые инструкции в каждом ответе этого чата и понимает, что их закрепили вы. Это не память: память — это факты о вас, а инструкции — правила ответа.",
                              "Honer AI sees pinned instructions in every answer of this chat. Memory holds facts; instructions are rules."))
                }

                Section {
                    if store.instructionLibrary.isEmpty {
                        Text(text("Откреплённые инструкции сохраняются сюда — их можно закрепить снова в любом чате.",
                                  "Unpinned instructions are kept here to reuse in any chat."))
                            .font(.system(size: 14))
                            .foregroundStyle(HonorTheme.secondary)
                    }
                    ForEach(store.instructionLibrary) { saved in
                        savedRow(saved)
                    }
                } header: {
                    Text(text("Сохранённые инструкции", "Saved instructions"))
                }
            }
            .navigationTitle(text("Инструкции чата", "Chat instructions"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(text("Готово", "Done")) { dismiss() }
                        .accessibilityIdentifier("instructions.done")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { editor = InstructionEditorTarget(instructionID: nil, text: "") } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel(text("Новая инструкция", "New instruction"))
                    .accessibilityIdentifier("instructions.add")
                }
            }
            .sheet(item: $editor) { target in
                InstructionEditor(target: target, settings: settings) { value, saveCopy in
                    if let id = target.instructionID {
                        store.updateInstruction(chatID: chatID, id: id, text: value)
                    } else {
                        store.addInstruction(chatID: chatID, text: value)
                    }
                    if saveCopy { store.saveInstructionToLibrary(value) }
                }
            }
        }
        .accessibilityIdentifier("instructions.sheet")
    }

    private func pinnedRow(_ item: ChatInstruction) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "pin.fill").font(.system(size: 11))
                Text(item.author == .assistant
                     ? text("Ответ Honer AI · закрепили вы", "Honer AI answer · pinned by you")
                     : text("Ваш текст · закрепили вы", "Your text · pinned by you"))
                    .font(.system(size: 12, weight: .semibold))
            }
            .foregroundStyle(HonorTheme.accent)
            Text(item.text)
                .font(.system(size: 15))
                .lineLimit(8)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Button(text("Изменить", "Edit")) {
                    editor = InstructionEditorTarget(instructionID: item.id, text: item.text)
                }
                .accessibilityIdentifier("instructions.edit." + item.id.uuidString)
                Button(text("Открепить", "Unpin")) {
                    withAnimation { store.unpinInstruction(chatID: chatID, id: item.id) }
                }
                .accessibilityIdentifier("instructions.unpin." + item.id.uuidString)
                Spacer(minLength: 0)
                Button(role: .destructive) {
                    withAnimation { store.deleteInstruction(chatID: chatID, id: item.id) }
                } label: { Image(systemName: "trash") }
                .accessibilityLabel(text("Удалить", "Delete"))
                .accessibilityIdentifier("instructions.delete." + item.id.uuidString)
            }
            .font(.system(size: 14, weight: .medium))
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("instructions.row." + item.id.uuidString)
    }

    private func savedRow(_ saved: SavedInstruction) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(saved.text)
                .font(.system(size: 15))
                .lineLimit(5)
            HStack(spacing: 16) {
                Button(text("Закрепить в этом чате", "Pin in this chat")) {
                    withAnimation { _ = store.applySavedInstruction(id: saved.id, to: chatID) }
                }
                .disabled(pinned.contains { $0.text == saved.text })
                .accessibilityIdentifier("instructions.saved.apply." + saved.id.uuidString)
                Spacer(minLength: 0)
                Button(role: .destructive) {
                    withAnimation { store.deleteSavedInstruction(id: saved.id) }
                } label: { Image(systemName: "trash") }
                .accessibilityLabel(text("Удалить из сохранённых", "Delete saved"))
                .accessibilityIdentifier("instructions.saved.delete." + saved.id.uuidString)
            }
            .font(.system(size: 14, weight: .medium))
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("instructions.saved.row." + saved.id.uuidString)
    }
}

struct InstructionEditorTarget: Identifiable {
    let id = UUID()
    let instructionID: UUID?
    let text: String
}

/// Редактор одной инструкции.
private struct InstructionEditor: View {
    let target: InstructionEditorTarget
    let settings: AppSettings
    let onSave: (String, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value = ""
    @State private var saveCopy = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(settings.text("Honer AI будет соблюдать эту инструкцию в каждом ответе этого чата.",
                                   "Honer AI will follow this instruction in every answer of this chat."))
                    .font(.system(size: 13))
                    .foregroundStyle(HonorTheme.secondary)
                TextEditor(text: $value)
                    .font(.system(size: 16))
                    .focused($focused)
                    .frame(minHeight: 180)
                    .padding(8)
                    .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(HonorTheme.divider, lineWidth: 0.7))
                    .accessibilityIdentifier("instructions.editor")
                Toggle(settings.text("Также сохранить в «Сохранённые»", "Also keep in Saved"), isOn: $saveCopy)
                    .font(.system(size: 15))
                    .accessibilityIdentifier("instructions.editor.saveCopy")
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationTitle(target.instructionID == nil
                             ? settings.text("Новая инструкция", "New instruction")
                             : settings.text("Изменить инструкцию", "Edit instruction"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(settings.text("Отмена", "Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(settings.text("Закрепить", "Pin")) {
                        onSave(value, saveCopy)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("instructions.editor.save")
                }
            }
            .onAppear { value = target.text; focused = true }
        }
    }
}
