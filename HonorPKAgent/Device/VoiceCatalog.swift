import AVFoundation
import SwiftUI

/// Курируемый выбор голосов для чтения вслух (пункт 27).
///
/// iOS не позволяет ставить сторонние TTS-голоса внутрь приложения — доступны
/// только системные. Зато система даёт «улучшенные» и «премиум» голоса, которые
/// звучат заметно естественнее стандартных. Этот сервис:
///  • находит все русские голоса, установленные на устройстве;
///  • делит их на женские и мужские эвристикой по имени;
///  • сортирует так, чтобы улучшенные и премиум были сверху;
///  • показывает, какие голоса можно докачать в настройках iOS.
enum VoiceCatalog {
    struct Voice: Identifiable, Equatable {
        let id: String
        let name: String
        let quality: Quality
        let gender: Gender
        let language: String

        var isDownloaded: Bool { quality != .unavailable }
    }

    enum Quality: Int, Comparable {
        case unavailable = 0
        case standard = 1
        case enhanced = 2
        case premium = 3

        static func < (lhs: Quality, rhs: Quality) -> Bool { lhs.rawValue < rhs.rawValue }

        var title: String {
            switch self {
            case .premium: return "премиум"
            case .enhanced: return "улучшенный"
            case .standard: return "стандартный"
            case .unavailable: return "не загружен"
            }
        }
    }

    enum Gender: String {
        case female, male, unknown

        var title: String {
            switch self {
            case .female: return "женский"
            case .male: return "мужской"
            case .unknown: return "—"
            }
        }
    }

    /// Имена русских голосов Apple: женские и мужские.
    private static let femaleNames: Set<String> = [
        "milena", "katya", "alyona", "alena", "elena", "irina", "marina", "tatyana", "tania",
        "oksana", "vera", "yulia", "julia", "svetlana", "anna", "daria", "dariya", "ksenia",
        "lyudmila", "nadezhda", "olga", "polina", "sofia", "valentina", "yana", "alice", "alisa"
    ]
    private static let maleNames: Set<String> = [
        "yuri", "yuriy", "dmitri", "dmitry", "alexander", "aleksandr", "maxim", "maksim",
        "ivan", "sergey", "sergei", "nikolay", "pavel", "andrey", "andrei", "artem", "boris",
        "victor", "viktor", "george", "georgiy", "kostya", "konstantin", "mikhail", "oleg",
        "roman", "stepan", "timur", "vladimir", "anton", "daniel", "denis", "egor", "fedor",
        "gleb", "igor", "kirill", "leonid", "nikita", "petr", "ruslan", "vadim", "yaroslav"
    ]

    static func gender(of name: String) -> Gender {
        let lowered = name.lowercased()
        // Имя может быть вида «Milena (Enhanced)» — берём первое слово.
        let first = lowered.split(separator: " ").first.map(String.init) ?? lowered
        if femaleNames.contains(first) || femaleNames.contains(where: { lowered.contains($0) }) { return .female }
        if maleNames.contains(first) || maleNames.contains(where: { lowered.contains($0) }) { return .male }
        return .unknown
    }

    static func quality(_ voice: AVSpeechSynthesisVoice) -> Quality {
        switch voice.quality {
        case .premium: return .premium
        case .enhanced: return .enhanced
        default: return .standard
        }
    }

    /// Все русские голоса, установленные на устройстве: улучшенные и премиум — сверху.
    static func russianVoices() -> [Voice] {
        let voices = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix("ru") }
            .map { voice in
                Voice(id: voice.identifier,
                      name: voice.name,
                      quality: quality(voice),
                      gender: gender(of: voice.name),
                      language: voice.language)
            }
        return voices.sorted { lhs, rhs in
            if lhs.quality != rhs.quality { return lhs.quality > rhs.quality }
            return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
        }
    }

    /// Лучший доступный голос: сначала премиум, затем улучшенный, затем любой русский.
    static func bestAvailable(wanting gender: Gender? = nil) -> Voice? {
        let voices = russianVoices()
        if let gender, let match = voices.first(where: { $0.gender == gender }) { return match }
        return voices.first
    }

    /// Рекомендуемые пары «2 женских + 2 мужских», как просил пользователь:
    /// сначала уже установленные, затем — подсказка, что докачать.
    static func recommendedPairs() -> (installed: [Voice], toDownload: [String]) {
        let voices = russianVoices()
        let bestFemale = voices.filter { $0.gender == .female }.prefix(2)
        let bestMale = voices.filter { $0.gender == .male }.prefix(2)
        let installed = Array(bestFemale) + Array(bestMale)

        var missing: [String] = []
        if bestFemale.count < 2 { missing.append("Milena (улучшенный) — женский") }
        if bestMale.count < 2 { missing.append("Yuri (улучшенный) — мужской") }
        return (installed, missing)
    }
}

/// Страница выбора голоса: сгруппировано по полу и качеству, с подсказками.
struct VoicePickerPage: View {
    let settings: AppSettings
    @State private var voices: [VoiceCatalog.Voice] = []
    @State private var previewing: String?

    private var femaleVoices: [VoiceCatalog.Voice] { voices.filter { $0.gender == .female } }
    private var maleVoices: [VoiceCatalog.Voice] { voices.filter { $0.gender == .male } }
    private var otherVoices: [VoiceCatalog.Voice] { voices.filter { $0.gender == .unknown } }

    var body: some View {
        List {
            Section {
                Button {
                    settings.voiceIdentifier = ""
                } label: {
                    row(title: "Лучший доступный автоматически",
                        subtitle: "Приложение само выберет самый качественный русский голос",
                        selected: settings.voiceIdentifier.isEmpty,
                        badge: nil)
                }
                .accessibilityIdentifier("voice.auto")
            } footer: {
                Text("iOS не разрешает устанавливать сторонние голоса внутрь приложения — доступны только системные. Улучшенные и премиум-голоса звучат заметно естественнее стандартных.")
            }

            if !femaleVoices.isEmpty {
                Section("Женские голоса") {
                    ForEach(femaleVoices) { voice in voiceRow(voice) }
                }
            }
            if !maleVoices.isEmpty {
                Section("Мужские голоса") {
                    ForEach(maleVoices) { voice in voiceRow(voice) }
                }
            }
            if !otherVoices.isEmpty {
                Section("Другие русские голоса") {
                    ForEach(otherVoices) { voice in voiceRow(voice) }
                }
            }

            Section("Как получить самые реалистичные голоса") {
                Text("Открой настройки iPhone: Универсальный доступ → Устный контент → Голоса → Русский. Там скачай голоса Milena и Yuri в варианте «Улучшенный» или «Премиум» — они появятся в этом списке и будут звучать чётче.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    Link("Открыть настройки iPhone", destination: url)
                        .font(.footnote)
                }
            }
        }
        .navigationTitle("Голос чтения")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.voicePicker")
        .onAppear { voices = VoiceCatalog.russianVoices() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            voices = VoiceCatalog.russianVoices()
        }
    }

    private func voiceRow(_ voice: VoiceCatalog.Voice) -> some View {
        Button {
            settings.voiceIdentifier = voice.id
        } label: {
            row(title: voice.name,
                subtitle: "\(voice.gender.title) · \(voice.quality.title)",
                selected: settings.voiceIdentifier == voice.id,
                badge: voice.quality == .premium ? "★" : (voice.quality == .enhanced ? "✓" : nil))
        }
        .accessibilityIdentifier("voice.row." + voice.id)
    }

    private func row(title: String, subtitle: String, selected: Bool, badge: String?) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(title).foregroundStyle(.primary)
                    if let badge {
                        Text(badge)
                            .font(.caption2)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(badge == "★" ? Color.yellow.opacity(0.3) : Color.green.opacity(0.25),
                                        in: Capsule())
                    }
                }
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(.tint) }
        }
    }
}
