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
}
