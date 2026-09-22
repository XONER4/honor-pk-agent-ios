import AVFoundation
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
                        SettingsDivider()
                        NavigationLink { DataSettingsPage() } label: {
                            SettingsRow(symbol: "externaldrive.badge.gearshape", title: settings.text("Управление данными", "Data management"))
                        }
                    }
                    SettingsGroup(title: settings.text("Приложение", "Application")) {
                        NavigationLink { LanguageSettingsPage() } label: {
                            SettingsRow(symbol: "globe", title: settings.text("Язык", "Language"),
                                        value: settings.language == .russian ? "Русский" : "English")
                        }
                        SettingsDivider()
                        Menu {
                            Picker(settings.text("Внешний вид", "Appearance"), selection: $settings.appearance) {
                                Text(settings.text("Система", "System")).tag(AppAppearance.system)
                                Text(settings.text("Светлый", "Light")).tag(AppAppearance.light)
                                Text(settings.text("Тёмный", "Dark")).tag(AppAppearance.dark)
                            }
                        } label: {
                            SettingsRow(symbol: "moon.zzz", title: settings.text("Внешний вид", "Appearance"),
                                        value: appearanceName, chevron: "chevron.up.chevron.down")
                        }
                        SettingsDivider()
                        NavigationLink { FontSettingsPage() } label: {
                            SettingsRow(symbol: "textformat.size", title: settings.text("Размер шрифта", "Font size"))
                        }
                        SettingsDivider()
                        NavigationLink { PersonalizationSettingsPage() } label: {
                            SettingsRow(symbol: "sparkles", title: settings.text("Персонализация", "Personalization"))
                        }
                    }
                    SettingsGroup(title: settings.text("Аудио", "Audio")) {
                        NavigationLink { VoiceSettingsPage() } label: {
                            SettingsRow(symbol: "speaker.wave.2", title: settings.text("Голос", "Voice"))
                        }
                        SettingsDivider()
                        Menu {
                            Picker(settings.text("Основной язык", "Speech language"), selection: $settings.speechLanguage) {
                                Text("Русский").tag("ru-RU")
                                Text("English (US)").tag("en-US")
                                Text("English (UK)").tag("en-GB")
                            }
                        } label: {
                            SettingsRow(symbol: "mic", title: settings.text("Основной язык", "Speech language"),
                                        value: speechLanguageName, chevron: "chevron.up.chevron.down")
                        }
                    }
                    Text(settings.text("Выберите язык, который вы используете для голосового ввода, чтобы улучшить распознавание.",
                                       "Choose the language you use for voice input to improve speech recognition."))
                        .font(.system(size: 14 * settings.fontScale))
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 15)
                        .padding(.top, -13)
                    SettingsGroup(title: settings.text("Подключение", "Connection")) {
                        NavigationLink { APIKeySettingsPage() } label: {
                            SettingsRow(symbol: "key", title: settings.text("API DeepSeek", "DeepSeek API"),
                                        value: store.hasAPIKey ? settings.text("Подключён", "Configured") : settings.text("Добавить", "Add key"))
                        }
                    }
                    SettingsGroup(title: settings.text("О программе", "About")) {
                        SettingsRow(symbol: "info.circle", title: settings.text("Версия", "Version"), value: appVersion, chevron: nil)
                        SettingsDivider()
                        NavigationLink { AboutSettingsPage() } label: {
                            SettingsRow(symbol: "doc.text", title: "Honor PK Agent")
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 35)
            }
            .background(settingsBackground(colorScheme))
            .navigationTitle(settings.text("Настройки", "Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(.primary)
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
        .onChange(of: settings.apiKeyOverride) { key in
            store.updateAPIKey(key.isEmpty ? DeepSeekConfiguration.bundled.apiKey : key)
        }
        .onChange(of: settings.speechLanguage) { _ in settings.voiceIdentifier = "" }
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
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
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
                        Text(settings.displayName.isEmpty ? "Honor" : settings.displayName).font(.headline)
                        Text(settings.text("Локальный профиль", "Local profile")).font(.subheadline).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 7)
            }
            Section(settings.text("Имя", "Name")) {
                TextField(settings.text("Ваше имя", "Your name"), text: $settings.displayName)
                    .textContentType(.nickname)
            }
            Section {
                Text(settings.text("Профиль и история чатов хранятся на этом iPhone. Вход в аккаунт не требуется.",
                                   "Your profile and chat history are stored on this iPhone. No account sign-in is required."))
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(settings.text("Настройки аккаунта", "Account settings"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DataSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @State private var shareURL: URL?
    @State private var showsShare = false
    @State private var showsImport = false
    @State private var confirmsDeletion = false
    @State private var status: String?
    var body: some View {
        Form {
            Section {
                LabeledContent(settings.text("Чатов", "Conversations"), value: "\(store.conversations.count)")
                Button {
                    do { shareURL = try store.exportData(); showsShare = true }
                    catch { status = error.localizedDescription }
                } label: { Label(settings.text("Экспортировать историю", "Export history"), systemImage: "square.and.arrow.up") }
                Button { showsImport = true } label: {
                    Label(settings.text("Импортировать историю", "Import history"), systemImage: "square.and.arrow.down")
                }
            } footer: {
                Text(settings.text("Резервная копия в JSON содержит переписку и вложения. Импорт добавляет сохранённые чаты в историю.",
                                   "The JSON backup contains conversations and attachments. Import adds saved conversations to your history."))
            }
            Section {
                Button(role: .destructive) { confirmsDeletion = true } label: {
                    Label(settings.text("Удалить всю историю", "Delete all history"), systemImage: "trash")
                }
            }
            if let status { Section { Text(status).font(.subheadline).foregroundStyle(.secondary) } }
        }
        .navigationTitle(settings.text("Управление данными", "Data management"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showsShare) { if let shareURL { ActivitySheet(items: [shareURL]) } }
        .fileImporter(isPresented: $showsImport, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                do {
                    try store.importData(from: url)
                    status = settings.text("История импортирована.", "History imported.")
                } catch { status = error.localizedDescription }
            case .failure(let error): status = error.localizedDescription
            }
        }
        .alert(settings.text("Удалить всю историю?", "Delete all history?"), isPresented: $confirmsDeletion) {
            Button(settings.text("Отмена", "Cancel"), role: .cancel) {}
            Button(settings.text("Удалить", "Delete"), role: .destructive) {
                store.clearAllChats()
                status = settings.text("История удалена.", "History deleted.")
            }
        } message: {
            Text(settings.text("Все чаты и их вложения будут удалены с этого iPhone. Сначала можно сохранить экспорт.",
                               "All conversations and their attachments will be removed from this iPhone. You can export a backup first."))
        }
    }
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
            }
        }
        .navigationTitle(settings.text("Язык", "Language"))
        .navigationBarTitleDisplayMode(.inline)
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
                    Text("A").font(.system(size: 27))
                }
                LabeledContent(settings.text("Масштаб текста", "Text scale"), value: "\(Int((settings.fontScale * 100).rounded()))%")
                Button(settings.text("Восстановить стандартный", "Restore default")) { settings.fontScale = 1 }
            }
            Section(settings.text("Предпросмотр", "Preview")) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Honor PK Agent").font(.system(size: 21 * settings.fontScale, weight: .semibold))
                    Text(settings.text("Привет! О чём хотите поговорить сегодня?", "Hi! What would you like to talk about today?"))
                        .font(.system(size: 17 * settings.fontScale))
                }.padding(.vertical, 8)
            }
        }
        .navigationTitle(settings.text("Размер шрифта", "Font size"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct PersonalizationSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    var body: some View {
        Form {
            Section {
                TextEditor(text: $settings.customInstructions)
                    .frame(minHeight: 200)
                    .font(.system(size: 17 * settings.fontScale))
                    .accessibilityLabel(settings.text("Пользовательские инструкции", "Custom instructions"))
                if !settings.customInstructions.isEmpty {
                    Button(settings.text("Очистить инструкции", "Clear instructions"), role: .destructive) {
                        settings.customInstructions = ""
                    }
                }
            } header: { Text(settings.text("Как Honor должен отвечать?", "How should Honor respond?")) }
              footer: {
                Text(settings.text("Например: «Обращайся ко мне на ты. Отвечай кратко и по-русски». Изменения сохраняются автоматически и применяются к следующим сообщениям.",
                                   "For example: “Use a friendly tone and keep answers concise.” Changes are saved automatically and apply to your next messages."))
            }
        }
        .navigationTitle(settings.text("Персонализация", "Personalization"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct VoiceSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var speech = SpeechService()
    private var voices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix(String(settings.speechLanguage.prefix(2))) }
            .sorted { $0.name < $1.name }
    }
    var body: some View {
        Form {
            Section {
                Toggle(settings.text("Читать ответы автоматически", "Read answers automatically"), isOn: $settings.autoRead)
            }
            Section(settings.text("Голос для чтения", "Reading voice")) {
                voiceRow(name: settings.text("Системный голос", "System voice"), identifier: "")
                ForEach(voices, id: \.identifier) { voice in
                    voiceRow(name: "\(voice.name) · \(voice.language)", identifier: voice.identifier)
                }
            }
            Section {
                Button {
                    if speech.isSpeaking { speech.stopSpeaking() }
                    else {
                        speech.speak(settings.text("Привет! Я Honor, ваш личный помощник.", "Hello! I am Honor, your personal assistant."),
                                     voiceIdentifier: settings.voiceIdentifier, language: settings.speechLanguage)
                    }
                } label: {
                    Label(settings.text(speech.isSpeaking ? "Остановить" : "Послушать голос", speech.isSpeaking ? "Stop" : "Preview voice"),
                          systemImage: speech.isSpeaking ? "stop.circle" : "play.circle")
                }
            }
            if let error = speech.errorMessage { Section { Text(error).foregroundStyle(.secondary) } }
        }
        .navigationTitle(settings.text("Голос", "Voice"))
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { speech.stopSpeaking() }
    }
    private func voiceRow(name: String, identifier: String) -> some View {
        Button { settings.voiceIdentifier = identifier } label: {
            HStack {
                Text(name).foregroundStyle(.primary)
                Spacer()
                if settings.voiceIdentifier == identifier { Image(systemName: "checkmark") }
            }
        }
    }
}

private struct APIKeySettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @State private var draftKey = ""
    @State private var saved = false
    var body: some View {
        Form {
            Section {
                HStack {
                    Image(systemName: store.hasAPIKey ? "checkmark.circle.fill" : "key")
                        .foregroundStyle(store.hasAPIKey ? Color.green : Color.secondary)
                    Text(settings.text(store.hasAPIKey ? "Ключ настроен" : "Добавьте ключ DeepSeek",
                                       store.hasAPIKey ? "API key configured" : "Add your DeepSeek key"))
                }
                SecureField("sk-…", text: $draftKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                Button(settings.text("Сохранить ключ", "Save key")) {
                    settings.apiKeyOverride = draftKey.trimmingCharacters(in: .whitespacesAndNewlines)
                    store.updateAPIKey(settings.apiKeyOverride.isEmpty ? DeepSeekConfiguration.bundled.apiKey : settings.apiKeyOverride)
                    saved = true
                }
            } footer: {
                Text(settings.text("Этот ключ используется для запросов из приложения. Пустое поле восстанавливает встроенный ключ, если он добавлен в сборку.",
                                   "This key is used for requests from the app. An empty field restores the bundled key, if one was included in the build."))
            }
            if saved { Section { Text(settings.text("Сохранено", "Saved")).foregroundStyle(.green) } }
        }
        .navigationTitle("API DeepSeek")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { draftKey = settings.apiKeyOverride }
        .onChange(of: draftKey) { _ in saved = false }
    }
}

private struct AboutSettingsPage: View {
    @EnvironmentObject private var settings: AppSettings
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 9) {
                    Text("Honor PK Agent").font(.title2.bold())
                    Text(settings.text("Ваш личный ИИ-помощник", "Your personal AI assistant")).foregroundStyle(.secondary)
                    Text(appVersion).font(.footnote).foregroundStyle(.secondary)
                }.padding(.vertical, 10)
            }
            Section {
                Text(settings.text("Ответы и рассуждения поступают из DeepSeek API. Фото, документы, голосовой ввод, история и настройки доступны в одном приложении.",
                                   "Answers and reasoning are provided by the DeepSeek API. Photos, documents, voice input, history and preferences are available in one app."))
                Text(settings.text("Чаты сохраняются на вашем iPhone. Оценки ответов сохраняются локально.",
                                   "Conversations are saved on your iPhone. Response feedback is stored locally."))
                    .foregroundStyle(.secondary)
            }
            Section {
                Link(destination: URL(string: "https://api-docs.deepseek.com")!) {
                    Label(settings.text("Документация DeepSeek API", "DeepSeek API documentation"), systemImage: "arrow.up.right.square")
                }
            }
        }
        .navigationTitle(settings.text("О программе", "About"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
