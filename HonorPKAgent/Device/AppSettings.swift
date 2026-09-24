import SwiftUI

enum AppAppearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
}

enum AppLanguage: String, CaseIterable, Identifiable {
    case russian = "ru", english = "en"
    var id: String { rawValue }
}

@MainActor
final class AppSettings: ObservableObject {
    private let defaults: UserDefaults

    @Published var appearance: AppAppearance { didSet { defaults.set(appearance.rawValue, forKey: "honor.appearance") } }
    @Published var language: AppLanguage { didSet { defaults.set(language.rawValue, forKey: "honor.language") } }
    @Published var fontScale: Double { didSet { defaults.set(fontScale, forKey: "honor.fontScale") } }
    @Published var customInstructions: String { didSet { defaults.set(customInstructions, forKey: "honor.customInstructions") } }
    @Published var voiceIdentifier: String { didSet { defaults.set(voiceIdentifier, forKey: "honor.voiceIdentifier") } }
    @Published var speechLanguage: String { didSet { defaults.set(speechLanguage, forKey: "honor.speechLanguage") } }
    @Published var autoRead: Bool { didSet { defaults.set(autoRead, forKey: "honor.autoRead") } }
    @Published var displayName: String { didSet { defaults.set(displayName, forKey: "honor.displayName") } }
    @Published var apiKeyOverride: String { didSet { defaults.set(apiKeyOverride, forKey: "honor.apiKeyOverride") } }
    @Published var completedOnboarding: Bool { didSet { defaults.set(completedOnboarding, forKey: "honer.onboarding.completed") } }
    /// Скорость чтения вслух: 0.5 — медленно, 1.0 — обычная, 1.6 — быстро.
    @Published var voiceRate: Double { didSet { defaults.set(voiceRate, forKey: "honor.voiceRate") } }
    /// Через сколько дней автоматически удалять чаты. 0 — никогда.
    @Published var autoDeleteDays: Int { didSet { defaults.set(autoDeleteDays, forKey: "honor.autoDeleteDays") } }
    /// Уведомлять о готовом ответе, если приложение свёрнуто.
    @Published var notificationsEnabled: Bool { didSet { defaults.set(notificationsEnabled, forKey: "honor.notificationsEnabled") } }
    /// Память между чатами: запоминать важное из разных чатов и подмешивать в новые.
    @Published var crossChatMemoryEnabled: Bool { didSet { defaults.set(crossChatMemoryEnabled, forKey: "honor.crossChatMemoryEnabled") } }
    /// Стикеры и эмодзи в ответах.
    @Published var stickersEnabled: Bool { didSet { defaults.set(stickersEnabled, forKey: "honor.stickersEnabled") } }
    /// Фото профиля (путь в песочнице приложения).
    @Published var profilePhotoPath: String { didSet { defaults.set(profilePhotoPath, forKey: "honor.profilePhotoPath") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        appearance = AppAppearance(rawValue: defaults.string(forKey: "honor.appearance") ?? "dark") ?? .dark
        language = AppLanguage(rawValue: defaults.string(forKey: "honor.language") ?? "ru") ?? .russian
        let scale = defaults.object(forKey: "honor.fontScale") as? Double ?? 1
        fontScale = min(1.4, max(0.85, scale))
        customInstructions = defaults.string(forKey: "honor.customInstructions") ?? ""
        voiceIdentifier = defaults.string(forKey: "honor.voiceIdentifier") ?? ""
        speechLanguage = defaults.string(forKey: "honor.speechLanguage") ?? "ru-RU"
        autoRead = defaults.bool(forKey: "honor.autoRead")
        let savedName = defaults.string(forKey: "honor.displayName") ?? ""
        displayName = savedName == "Honor" ? "" : savedName
        apiKeyOverride = defaults.string(forKey: "honor.apiKeyOverride") ?? ""
        completedOnboarding = defaults.bool(forKey: "honer.onboarding.completed")
        let rate = defaults.object(forKey: "honor.voiceRate") as? Double ?? 0.95
        voiceRate = min(1.8, max(0.4, rate))
        autoDeleteDays = defaults.object(forKey: "honor.autoDeleteDays") as? Int ?? 0
        notificationsEnabled = defaults.bool(forKey: "honor.notificationsEnabled")
        crossChatMemoryEnabled = defaults.object(forKey: "honor.crossChatMemoryEnabled") as? Bool ?? true
        stickersEnabled = defaults.object(forKey: "honor.stickersEnabled") as? Bool ?? true
        profilePhotoPath = defaults.string(forKey: "honor.profilePhotoPath") ?? ""
    }

    var preferredColorScheme: ColorScheme? {
        switch appearance {
        case .system: return .dark
        case .light: return .light
        case .dark: return .dark
        }
    }

    func text(_ russian: String, _ english: String) -> String {
        language == .russian ? russian : english
    }

    /// Папка приложения, в которой лежит фото профиля.
    static var profilePhotoURL: URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPK", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("profile-photo.jpg")
    }

    /// Починить путь к фото профиля после обновления приложения.
    ///
    /// iOS при обновлении меняет идентификатор песочницы, поэтому сохранённый
    /// абсолютный путь перестаёт существовать — фото «пропадало» в профиле,
    /// хотя файл лежал на месте. Здесь путь пересчитывается по текущей песочнице.
    /// Если файла нет вовсе, путь очищается, чтобы приложение не показывало пустоту.
    @discardableResult
    func repairProfilePhotoPath() -> Bool {
        let target = Self.profilePhotoURL
        if FileManager.default.fileExists(atPath: target.path) {
            if profilePhotoPath != target.path { profilePhotoPath = target.path }
            return true
        }
        // Старый путь ещё рабочий — оставляем как есть.
        if !profilePhotoPath.isEmpty, FileManager.default.fileExists(atPath: profilePhotoPath) {
            return true
        }
        if !profilePhotoPath.isEmpty { profilePhotoPath = "" }
        return false
    }
}
