import AVFoundation
import AVKit
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 27) {
                    SettingsGroup(title: settings.text("Профиль", "Profile")) {
                        NavigationLink { ProfileSettingsPage() } label: {
                            SettingsRow(symbol: "person", title: settings.text("Настройки аккаунта", "Account settings"))
                        }
                        .accessibilityIdentifier("settings.profile")
                        SettingsDivider()
                        NavigationLink { DataSettingsPage() } label: {
                            SettingsRow(symbol: "externaldrive", title: settings.text("Управление данными", "Data management"))
                        }
                        .accessibilityIdentifier("settings.data")
                        SettingsDivider()
                        NavigationLink { ArchivedChatsPage() } label: {
                            SettingsRow(symbol: "archivebox", title: settings.text("Архив чатов", "Archived chats"),
                                        value: "\(store.archivedConversations.count)")
                        }.accessibilityIdentifier("settings.archive")
                    }
                    SettingsGroup(title: settings.text("Приложение", "Application")) {
                        NavigationLink { LanguageSettingsPage() } label: {
                            SettingsRow(symbol: "globe", title: settings.text("Язык", "Language"),
                                        value: settings.language == .russian ? "Русский" : "English")
                        }
                        .accessibilityIdentifier("settings.language")
                        SettingsDivider()
                        Menu {
                            Picker(settings.text("Внешний вид", "Appearance"), selection: $settings.appearance) {
                                Text(settings.text("Система", "System")).tag(AppAppearance.system).accessibilityIdentifier("appearance.system")
                                Text(settings.text("Светлый", "Light")).tag(AppAppearance.light).accessibilityIdentifier("appearance.light")
                                Text(settings.text("Тёмный", "Dark")).tag(AppAppearance.dark).accessibilityIdentifier("appearance.dark")
                            }
                        } label: {
                            SettingsRow(symbol: "moon.zzz", title: settings.text("Внешний вид", "Appearance"),
                                        value: appearanceName, chevron: "chevron.up.chevron.down")
                        }
                        .accessibilityIdentifier("settings.appearance")
                        SettingsDivider()
                        NavigationLink { FontSettingsPage() } label: {
                            SettingsRow(symbol: "textformat.size", title: settings.text("Размер шрифта", "Font size"))
                        }
                        .accessibilityIdentifier("settings.font")
                        SettingsDivider()
                        NavigationLink { PersonalizationSettingsPage() } label: {
                            SettingsRow(symbol: "sparkles", title: settings.text("Персонализация", "Personalization"))
                        }
                        .accessibilityIdentifier("settings.personalization")
                        SettingsDivider()
                        NavigationLink { MemorySettingsPage() } label: {
                            SettingsRow(symbol: "brain", title: settings.text("Память Honer AI", "Honer AI memory"),
                                        value: "\(store.memories.count)")
                        }
                        .accessibilityIdentifier("settings.memory")
                    }
                    SettingsGroup(title: settings.text("Аудио", "Audio")) {
                        NavigationLink { VoiceSettingsPage() } label: {
                            SettingsRow(symbol: "speaker.wave.2", title: settings.text("Голос", "Voice"))
                        }
                        .accessibilityIdentifier("settings.voice")
                        SettingsDivider()
                        Menu {
                            Picker(settings.text("Основной язык", "Speech language"), selection: $settings.speechLanguage) {
                                Text("Русский").tag("ru-RU").accessibilityIdentifier("speechLanguage.ru-RU")
                                Text("English (US)").tag("en-US").accessibilityIdentifier("speechLanguage.en-US")
                                Text("English (UK)").tag("en-GB").accessibilityIdentifier("speechLanguage.en-GB")
                            }
                        } label: {
                            SettingsRow(symbol: "mic", title: settings.text("Основной язык", "Speech language"),
                                        value: speechLanguageName, chevron: "chevron.up.chevron.down")
                        }
                        .accessibilityIdentifier("settings.speechLanguage")
                    }
                    Text(settings.text("Выберите язык, который вы используете для голосового ввода, чтобы улучшить распознавание.",
                                       "Choose the language you use for voice input to improve speech recognition."))
                        .font(.system(size: 14 * settings.fontScale))
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 15)
                        .padding(.top, -13)
                    SettingsGroup(title: settings.text("О программе", "About")) {
                        SettingsRow(symbol: "info.circle", title: settings.text("Версия", "Version"), value: appVersion, chevron: nil)
                            .accessibilityIdentifier("settings.version")
                        SettingsDivider()
                        NavigationLink { AboutSettingsPage() } label: {
                            SettingsRow(symbol: "doc.text", title: "Honer AI")
                        }
                        .accessibilityIdentifier("settings.about")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 35)
            }
            .background(settingsBackground(colorScheme))
            .accessibilityIdentifier("settings.page.root")
            .navigationTitle(settings.text("Настройки", "Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(colorScheme == .dark ? Color.white : Color.black)
                            .frame(width: 35, height: 35)
                            .background(Color.primary.opacity(0.06), in: Circle())
                            .overlay(Circle().stroke(Color.primary.opacity(0.08), lineWidth: 1))
                    }
                    .accessibilityLabel(settings.text("Закрыть", "Close"))
                    .accessibilityIdentifier("settings.close")
                }
            }
        }
        .preferredColorScheme(settings.preferredColorScheme)
        .tint(Color(red: 0.49, green: 0.65, blue: 1))
        .onChange(of: settings.customInstructions) { store.systemInstruction = $0 }

    }

    private var appearanceName: String {
        switch settings.appearance {
        case .system: return settings.text("Система", "System")
        case .light: return settings.text("Светлый", "Light")
        case .dark: return settings.text("Тёмный", "Dark")
        }
    }
    private var speechLanguageName: String {
        switch settings.speechLanguage {
        case "en-US": return "English (US)"
        case "en-GB": return "English (UK)"
        default: return "Русский"
        }
    }
}

private var appVersion: String {
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "10.0"
    let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
    return "\(version) (\(build))"
}

private func settingsBackground(_ scheme: ColorScheme) -> Color {
    scheme == .dark ? Color(white: 0.115) : Color(white: 0.95)
}

private struct SettingsGroup<Content: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String
    let content: Content
    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            if !title.isEmpty {
                Text(title).font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.secondary).padding(.leading, 14)
            }
            VStack(spacing: 0) { content }
                .background(colorScheme == .dark ? Color(white: 0.155) : .white,
                            in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct SettingsRow: View {
    @EnvironmentObject private var settings: AppSettings
    let symbol: String
    let title: String
    var value: String = ""
    var chevron: String? = "chevron.right"
    var body: some View {
        HStack(spacing: 13) {
            Image(systemName: symbol).font(.system(size: 21)).frame(width: 27)
            Text(title).font(.system(size: 17 * settings.fontScale)).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if !value.isEmpty {
                Text(value).font(.system(size: 16 * settings.fontScale))
                    .foregroundStyle(.secondary).multilineTextAlignment(.trailing)
            }
            if let chevron {
                Image(systemName: chevron).font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
            }
        }
        .foregroundStyle(.primary)
        .padding(.horizontal, 16)
        .padding(.vertical, 17)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }
}

private struct SettingsDivider: View {
    var body: some View { Divider().padding(.leading, 56).padding(.trailing, 16) }
}

private struct ProfileSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "person.crop.circle.fill").font(.system(size: 48)).foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(settings.displayName.isEmpty ? settings.text("Ваш профиль", "Your profile") : settings.displayName).font(.headline)
                        Text(settings.text("Локальный профиль", "Local profile")).font(.subheadline).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 7)
            }
            Section(settings.text("Имя", "Name")) {
                TextField(settings.text("Ваше имя", "Your name"), text: $settings.displayName)
                    .textContentType(.nickname)
                    .onChange(of: settings.displayName) { if $0.count > 60 { settings.displayName = String($0.prefix(60)) } }
                    .accessibilityIdentifier("profile.name")
            }
            Section {
                Text(settings.text("Профиль и история чатов хранятся на этом iPhone. Вход в аккаунт не требуется.",
                                   "Your profile and chat history are stored on this iPhone. No account sign-in is required."))
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(settings.text("Настройки аккаунта", "Account settings"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.profile")
    }
}

private struct DataSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @State private var export: ExportedHistory?
    @State private var showsImport = false
    @State private var confirmsDeletion = false
    @State private var status: String?
    @State private var isTransferring = false
    @State private var transferTask: Task<Void, Never>?
    var body: some View {
        Form {
            Section {
                LabeledContent(settings.text("Чатов", "Conversations"), value: "\(store.conversations.count)")
                    .accessibilityIdentifier("data.count")
                Button {
                    isTransferring = true
                    status = nil
                    transferTask = Task { @MainActor in
                        defer { isTransferring = false }
                        do {
                            let url = try await store.exportDataAsync()
                            try Task.checkCancellation()
                            export = ExportedHistory(url: url)
                        } catch {
                            if !(error is CancellationError) { status = error.localizedDescription }
                        }
                    }
                } label: { Label(settings.text("Экспортировать историю", "Export history"), systemImage: "square.and.arrow.up") }
                .accessibilityIdentifier("data.export")
                .disabled(isTransferring)
                Button { showsImport = true } label: {
                    Label(settings.text("Импортировать историю", "Import history"), systemImage: "square.and.arrow.down")
                }
                .accessibilityIdentifier("data.import")
                .disabled(isTransferring)
            } footer: {
                Text(settings.text("Резервная копия в JSON содержит переписку, вложения и память Honer AI. Импорт добавляет сохранённые чаты и факты в историю.",
                                   "The JSON backup contains conversations, attachments and Honer AI memory. Import adds saved conversations and memories."))
            }
            Section {
                Button(role: .destructive) { confirmsDeletion = true } label: {
                    Label(settings.text("Удалить всю историю", "Delete all history"), systemImage: "trash")
                }
                .accessibilityIdentifier("data.delete")
                .disabled(isTransferring)
            }
            if isTransferring {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(settings.text("Обрабатываю архив…", "Processing backup…"))
                    }
                    .accessibilityIdentifier("data.busy")
                }
            }
            if let status {
                Section {
                    Text(status).font(.subheadline).foregroundStyle(.secondary)
                        .accessibilityIdentifier("data.status")
                }
            }
        }
        .navigationTitle(settings.text("Управление данными", "Data management"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.data")
        .sheet(item: $export) { export in ActivitySheet(items: [export.url]) }
        .fileImporter(isPresented: $showsImport, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                isTransferring = true
                status = nil
                transferTask = Task { @MainActor in
                    let access = url.startAccessingSecurityScopedResource()
                    defer {
                        if access { url.stopAccessingSecurityScopedResource() }
                        isTransferring = false
                    }
                    do {
                        try await store.importDataAsync(from: url)
                        try Task.checkCancellation()
                        status = settings.text("История импортирована.", "History imported.")
                    } catch {
                        if !(error is CancellationError) { status = error.localizedDescription }
                    }
                }
            case .failure(let error):
                if (error as NSError).code != NSUserCancelledError { status = error.localizedDescription }
            }
        }
        .alert(settings.text("Удалить всю историю?", "Delete all history?"), isPresented: $confirmsDeletion) {
            Button(settings.text("Отмена", "Cancel"), role: .cancel) {}
                .accessibilityIdentifier("data.delete.cancel")
            Button(settings.text("Удалить", "Delete"), role: .destructive) {
                store.clearAllChats()
                status = settings.text("История удалена.", "History deleted.")
            }
            .accessibilityIdentifier("data.delete.confirm")
        } message: {
            Text(settings.text("Все чаты и их вложения будут удалены с этого iPhone. Память Honer AI останется. Сначала можно сохранить экспорт.",
                               "All conversations and attachments will be removed from this iPhone. Honer AI memory will be kept. You can export a backup first."))
        }
        .onDisappear { transferTask?.cancel() }
    }
}

private struct ExportedHistory: Identifiable {
    let id = UUID()
    let url: URL
}

private struct LanguageSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    var body: some View {
        Form {
            ForEach(AppLanguage.allCases) { language in
                Button { settings.language = language } label: {
                    HStack {
                        Text(language == .russian ? "Русский" : "English").foregroundStyle(.primary)
                        Spacer()
                        if settings.language == language { Image(systemName: "checkmark") }
                    }
                }
                .accessibilityIdentifier("language.\(language.rawValue)")
            }
        }
        .navigationTitle(settings.text("Язык", "Language"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.language")
    }
}

private struct FontSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    var body: some View {
        Form {
            Section {
                HStack {
                    Text("A").font(.system(size: 14))
                    Slider(value: $settings.fontScale, in: 0.85...1.4, step: 0.05)
                        .accessibilityLabel(settings.text("Размер шрифта", "Font size"))
                        .accessibilityIdentifier("font.slider")
                    Text("A").font(.system(size: 27))
                }
                LabeledContent(settings.text("Масштаб текста", "Text scale"), value: "\(Int((settings.fontScale * 100).rounded()))%")
                    .accessibilityIdentifier("font.value")
                Button(settings.text("Восстановить стандартный", "Restore default")) { settings.fontScale = 1 }
                    .accessibilityIdentifier("font.reset")
            }
            Section(settings.text("Предпросмотр", "Preview")) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Honer AI").font(.system(size: 21 * settings.fontScale, weight: .semibold))
                    Text(settings.text("Привет! О чём хотите поговорить сегодня?", "Hi! What would you like to talk about today?"))
                        .font(.system(size: 17 * settings.fontScale))
                }.padding(.vertical, 8)
            }
        }
        .navigationTitle(settings.text("Размер шрифта", "Font size"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.font")
    }
}

private struct PersonalizationSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var showsTutorial = false
    var body: some View {
        Form {
            Section {
                TextEditor(text: $settings.customInstructions)
                    .frame(minHeight: 200)
                    .font(.system(size: 17 * settings.fontScale))
                    .accessibilityLabel(settings.text("Пользовательские инструкции", "Custom instructions"))
                    .accessibilityIdentifier("personalization.instructions")
                if !settings.customInstructions.isEmpty {
                    Button(settings.text("Очистить инструкции", "Clear instructions"), role: .destructive) {
                        settings.customInstructions = ""
                    }
                    .accessibilityIdentifier("personalization.clear")
                }
            } header: { Text(settings.text("Как Honer AI должен отвечать?", "How should Honer AI respond?")) }
              footer: {
                Text(settings.text("Например: «Обращайся ко мне на ты. Отвечай кратко и по-русски». Изменения сохраняются автоматически и применяются к следующим сообщениям.",
                                   "For example: “Use a friendly tone and keep answers concise.” Changes are saved automatically and apply to your next messages."))
            }
            Section {
                Button { showsTutorial = true } label: {
                    Label(settings.text("Как это работает · видео", "How it works · video"), systemImage: "play.rectangle")
                }.accessibilityIdentifier("personalization.video")
            } footer: {
                Text(settings.text("Короткая запись: задаём стиль общения и проверяем его в настоящем ответе Honer AI.",
                                   "A short recording: set a conversation style and see it applied in a real Honer AI reply."))
            }
        }
        .navigationTitle(settings.text("Персонализация", "Personalization"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.personalization")
        .sheet(isPresented: $showsTutorial) { PersonalizationVideoPage() }
    }
}

private struct VoiceSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var speech = SpeechService()
    @State private var voices: [AVSpeechSynthesisVoice] = []
    var body: some View {
        Form {
            Section(settings.text("Голос для чтения", "Reading voice")) {
                voiceRow(name: settings.text("Лучший доступный", "Best available"), identifier: "")
                ForEach(voices, id: \.identifier) { voice in
                    voiceRow(name: "\(voice.name) · \(quality(voice))", identifier: voice.identifier)
                }
            }
            Section {
                Button {
                    if speech.isSpeaking { speech.stopSpeaking() }
                    else {
                        speech.speak("Привет! Я Honer AI, твой личный помощник. Давай обсудим твои идеи.",
                                     voiceIdentifier: settings.voiceIdentifier, language: "ru-RU")
                    }
                } label: {
                    Label(settings.text(speech.isSpeaking ? "Остановить" : "Послушать голос", speech.isSpeaking ? "Stop" : "Preview voice"),
                          systemImage: speech.isSpeaking ? "stop.circle" : "play.circle")
                }
                .accessibilityIdentifier("voice.preview")
                .accessibilityValue(speech.isSpeaking ? "speaking" : "idle")
            }
            Section {
                Text(settings.text("Автоматическое чтение включается кнопкой динамика вверху чата.",
                                   "Turn automatic reading on or off with the speaker button at the top of the chat."))
                Text(settings.text("Для более естественного звучания загрузите русский голос улучшенного качества в настройках iPhone: Универсальный доступ → Устный контент (или Чтение и речь) → Голоса. После загрузки он появится здесь.",
                                   "For more natural speech, download an enhanced Russian voice in iPhone Settings: Accessibility → Spoken Content (or Read & Speak) → Voices. It will then appear here."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let error = speech.errorMessage {
                Section { Text(error).foregroundStyle(.secondary).accessibilityIdentifier("voice.error") }
            }
        }
        .navigationTitle(settings.text("Голос", "Voice"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.voice")
        .onAppear { voices = SpeechService.availableVoices(language: "ru-RU") }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            voices = SpeechService.availableVoices(language: "ru-RU")
        }
        .onDisappear { speech.stopSpeaking() }
        .onChange(of: settings.voiceIdentifier) { _ in speech.stopSpeaking() }
    }
    private func quality(_ voice: AVSpeechSynthesisVoice) -> String {
        switch voice.quality {
        case .premium: return settings.text("Премиум", "Premium")
        case .enhanced: return settings.text("Улучшенный", "Enhanced")
        default: return settings.text("Стандартный", "Standard")
        }
    }
    private func voiceRow(name: String, identifier: String) -> some View {
        Button { settings.voiceIdentifier = identifier } label: {
            HStack {
                Text(name).foregroundStyle(.primary)
                Spacer()
                if settings.voiceIdentifier == identifier { Image(systemName: "checkmark") }
            }
        }
        .accessibilityIdentifier("voice.option.\(identifier.isEmpty ? "system" : identifier)")
    }
}

private struct AboutSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 9) {
                    Text("Honer AI").font(.title2.bold())
                    Text(settings.text("Ваш личный ИИ-помощник", "Your personal AI assistant")).foregroundStyle(.secondary)
                    Text(appVersion).font(.footnote).foregroundStyle(.secondary)
                }.padding(.vertical, 10)
            }
            Section {
                Text(settings.text("Honer AI — приложение разработчика Владислава. Общение, поиск с источниками, фото и видео, документы, голосовой ввод и личная память — в одном месте.",
                                   "Honer AI is an app created by developer Vladislav. Conversations, search with sources, photos and videos, documents, voice input and personal memory — in one place."))
                Text(settings.text("Чаты сохраняются на вашем iPhone. Оценки ответов сохраняются локально.",
                                   "Conversations are saved on your iPhone. Response feedback is stored locally."))
                    .foregroundStyle(.secondary)
            }
            Section("Honer AI") {
                Text(settings.text("Версия для личного использования. Установочный файл предоставляет разработчик.",
                                   "A private release. The installation file is supplied by the developer."))
                Text(settings.text("Ассистент общается по-русски при любом языке интерфейса.",
                                   "The assistant always responds in Russian, regardless of the interface language."))
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(settings.text("О программе", "About"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.about")
    }
}

@MainActor
private struct MemorySettingsPage: View {
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    @State private var editor: MemoryEditorRequest?
    @State private var confirmsClear = false

    var body: some View {
        Form {
            Section {
                Toggle(settings.text("Использовать память", "Use memory"), isOn: $store.memoryEnabled)
                    .accessibilityIdentifier("memory.enabled")
            } footer: {
                VStack(alignment: .leading, spacing: 7) {
                    Text(settings.text(store.memoryEnabled ? "Память включена" : "Память выключена", store.memoryEnabled ? "Memory enabled" : "Memory disabled"))
                        .accessibilityIdentifier("memory.status")
                    Text(settings.text("Honer AI использует только факты, которые вы сохранили сами. При выключении они остаются в списке, но не добавляются в следующие запросы.",
                                       "Honer AI uses only the facts you explicitly save. When turned off, memories stay in this list but are not included in future requests."))
                }
            }
            Section {
                Button {
                    editor = MemoryEditorRequest(memory: nil)
                } label: {
                    Label(settings.text("Добавить в память", "Add memory"), systemImage: "plus")
                }
                .accessibilityIdentifier("memory.add")
                .disabled(store.memories.count >= ChatStore.maximumMemoryCount)
                if store.memories.isEmpty {
                    Text(settings.text("Пока ничего не сохранено. Добавьте предпочтение или важный факт — либо выберите «Запомнить» в меню сообщения.",
                                       "No memories yet. Add a preference or useful fact, or choose Remember from a message menu."))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("memory.empty")
                }
                ForEach(store.memories) { memory in
                    Button { editor = MemoryEditorRequest(memory: memory) } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(memory.text).foregroundStyle(.primary)
                                .font(.system(size: 16 * settings.fontScale))
                                .multilineTextAlignment(.leading)
                            Text(memory.createdAt, style: .date)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 3)
                        .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("memory.row.\(memory.id.uuidString)")
                    .swipeActions {
                        Button(role: .destructive) { store.deleteMemory(id: memory.id) } label: {
                            Label(settings.text("Удалить", "Delete"), systemImage: "trash")
                        }
                        .accessibilityIdentifier("memory.delete.\(memory.id.uuidString)")
                    }
                }
            } header: {
                Text(settings.text("Сохранённые факты", "Saved facts"))
            } footer: {
                Text("\(store.memories.count) / \(ChatStore.maximumMemoryCount)")
                    .accessibilityIdentifier("memory.count")
            }
            if !store.memories.isEmpty {
                Section {
                    Button(settings.text("Очистить память", "Clear memory"), role: .destructive) { confirmsClear = true }
                        .accessibilityIdentifier("memory.clear")
                }
            }
        }
        .navigationTitle(settings.text("Память Honer AI", "Honer AI memory"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.memory")
        .sheet(item: $editor) { request in
            MemoryEditorSheet(memory: request.memory)
        }
        .alert(settings.text("Очистить память Honer AI?", "Clear Honer AI memory?"), isPresented: $confirmsClear) {
            Button(settings.text("Отмена", "Cancel"), role: .cancel) {}
                .accessibilityIdentifier("memory.clear.cancel")
            Button(settings.text("Очистить", "Clear"), role: .destructive) { store.clearMemories() }
                .accessibilityIdentifier("memory.clear.confirm")
        } message: {
            Text(settings.text("Все сохранённые факты будут удалены. Переписка останется.", "All saved facts will be deleted. Your conversations will be kept."))
        }
    }
}

private struct MemoryEditorRequest: Identifiable {
    let id = UUID()
    let memory: HonorMemory?
}

@MainActor
struct MemoryEditorSheet: View {
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var draft: String
    @State private var validationError: String?
    @State private var confirmsDeletion = false
    @FocusState private var isFocused: Bool
    private let memory: HonorMemory?
    private let onSaved: () -> Void

    init(memory: HonorMemory? = nil, initialText: String = "", onSaved: @escaping () -> Void = {}) {
        self.memory = memory
        self.onSaved = onSaved
        _draft = State(initialValue: memory?.text ?? initialText)
    }

    private var cleanedDraft: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool {
        !cleanedDraft.isEmpty && cleanedDraft.count <= ChatStore.maximumMemoryLength
            && (memory != nil || store.memories.count < ChatStore.maximumMemoryCount)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $draft)
                        .font(.system(size: 17 * settings.fontScale))
                        .frame(minHeight: 190)
                        .focused($isFocused)
                        .accessibilityLabel(settings.text("Факт для памяти Honer AI", "Fact for Honer AI memory"))
                        .accessibilityIdentifier("memory.editor.text")
                    Text("\(cleanedDraft.count) / \(ChatStore.maximumMemoryLength)")
                        .font(.caption)
                        .foregroundStyle(cleanedDraft.count > ChatStore.maximumMemoryLength ? Color.red : Color.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .accessibilityIdentifier("memory.editor.count")
                } footer: {
                    Text(settings.text("Оставьте только факт или предпочтение, которое стоит помнить. Honer AI будет учитывать его в следующих ответах, пока память включена. Сохранение — только по вашей кнопке.",
                                       "Keep only a fact or preference worth remembering. Honer AI will use it in future replies while memory is enabled. Nothing is saved until you tap Save."))
                }
                if let validationError {
                    Section {
                        Text(validationError).foregroundStyle(.red)
                            .accessibilityIdentifier("memory.editor.error")
                    }
                }
                if memory == nil && store.memories.count >= ChatStore.maximumMemoryCount {
                    Section {
                        Text(settings.text("Память заполнена. Удалите ненужный факт, чтобы добавить новый.",
                                           "Memory is full. Delete an existing fact before adding another."))
                            .foregroundStyle(.secondary)
                    }
                }
                if memory != nil {
                    Section {
                        Button(settings.text("Удалить этот факт", "Delete this fact"), role: .destructive) { confirmsDeletion = true }
                            .accessibilityIdentifier("memory.editor.delete")
                    }
                }
            }
            .accessibilityIdentifier("memory.editor.page")
            .navigationTitle(settings.text(memory == nil ? "Запомнить" : "Изменить память", memory == nil ? "Remember" : "Edit memory"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(settings.text("Отмена", "Cancel")) { dismiss() }
                        .accessibilityIdentifier("memory.editor.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(settings.text("Сохранить", "Save")) {
                        let success = memory.map { store.updateMemory(id: $0.id, text: cleanedDraft) }
                            ?? store.addMemory(cleanedDraft)
                        if success {
                            isFocused = false
                            onSaved()
                            dismiss()
                        } else { validationError = store.errorMessage }
                    }
                    .fontWeight(.semibold)
                    .disabled(!canSave)
                    .accessibilityIdentifier("memory.editor.save")
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(settings.text("Готово", "Done")) { isFocused = false }
                        .accessibilityIdentifier("memory.editor.keyboardDone")
                }
            }
            .alert(settings.text("Удалить этот факт?", "Delete this fact?"), isPresented: $confirmsDeletion) {
                Button(settings.text("Отмена", "Cancel"), role: .cancel) {}
                    .accessibilityIdentifier("memory.editor.delete.cancel")
                Button(settings.text("Удалить", "Delete"), role: .destructive) {
                    if let memory { store.deleteMemory(id: memory.id) }
                    dismiss()
                }
                .accessibilityIdentifier("memory.editor.delete.confirm")
            }
        }
        .preferredColorScheme(settings.preferredColorScheme)
        .onChange(of: draft) { _ in validationError = nil }
    }
}
