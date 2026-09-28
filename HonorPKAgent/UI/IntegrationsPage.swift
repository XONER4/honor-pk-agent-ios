import SwiftUI

/// Интеграции: какие сервисы Honer AI может использовать сам.
/// Выключенная интеграция не передаётся модели вовсе.
enum Integrations {
    struct Service: Identifiable {
        let id: String
        let name: String
        let symbol: String
        let color: Color
        let descriptionRU: String
        let descriptionEN: String
        let tools: [HonerTool]
    }

    static let defaultsKey = "honer.integrations.disabled"

    static let services: [Service] = [
        Service(id: "youtube", name: "YouTube", symbol: "play.rectangle.fill", color: .red,
                descriptionRU: "Поиск роликов, описание, длительность и субтитры — нейросеть понимает, о чём видео.",
                descriptionEN: "Video search, descriptions, length and subtitles — the AI understands what a video is about.",
                tools: [.youtubeSearch, .youtubeVideo]),
        Service(id: "github", name: "GitHub", symbol: "chevron.left.forwardslash.chevron.right", color: Color(red: 0.2, green: 0.2, blue: 0.25),
                descriptionRU: "Поиск репозиториев, README, файлы кода, задачи и релизы публичных проектов.",
                descriptionEN: "Repository search, READMEs, code files, issues and releases of public projects.",
                tools: [.github]),
        Service(id: "marketplaces", name: "Wildberries · Ozon · Avito · Маркет", symbol: "cart.fill", color: Color(red: 0.6, green: 0.2, blue: 0.8),
                descriptionRU: "Поиск товаров и объявлений: названия, цены, рейтинг и ссылки.",
                descriptionEN: "Product and listing search: names, prices, ratings and links.",
                tools: [.marketplaceSearch]),
        Service(id: "vk", name: "ВКонтакте", symbol: "person.2.fill", color: Color(red: 0.15, green: 0.45, blue: 0.95),
                descriptionRU: "Публичные сообщества и страницы: описание и открытые записи.",
                descriptionEN: "Public communities and pages: description and open posts.",
                tools: [.vkPage]),
        Service(id: "telegram", name: "Telegram", symbol: "paperplane.fill", color: Color(red: 0.15, green: 0.65, blue: 0.9),
                descriptionRU: "Последние публикации публичных каналов.",
                descriptionEN: "Latest posts of public channels.",
                tools: [.telegramChannel]),
        Service(id: "research", name: "Массовое чтение сайтов", symbol: "square.stack.3d.up.fill", color: .orange,
                descriptionRU: "Параллельное чтение сотен и тысяч страниц для исследований и сравнений.",
                descriptionEN: "Parallel reading of hundreds and thousands of pages for research.",
                tools: [.readManyPages])
    ]

    static var disabled: Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: defaultsKey) ?? [])
    }

    static func isEnabled(_ id: String) -> Bool { !disabled.contains(id) }

    static func setEnabled(_ id: String, _ enabled: Bool) {
        var set = disabled
        if enabled { set.remove(id) } else { set.insert(id) }
        UserDefaults.standard.set(Array(set).sorted(), forKey: defaultsKey)
    }

    /// Инструмент выключен вместе со своей интеграцией.
    static func allows(_ tool: HonerTool) -> Bool {
        let off = disabled
        guard !off.isEmpty else { return true }
        return !services.contains { off.contains($0.id) && $0.tools.contains(tool) }
    }
}

struct IntegrationsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var disabled: Set<String> = Integrations.disabled

    var body: some View {
        Form {
            Section {
                ForEach(Integrations.services) { service in
                    row(service)
                }
            } footer: {
                Text(settings.text("Интеграции работают через публичные страницы и открытые данные, без входа в ваши аккаунты. Нужна включённая кнопка «Поиск» в чате. Закрытые страницы и личные сообщения недоступны.",
                                   "Integrations use public pages and open data, without signing in to your accounts. The Search button in the chat must be on. Private pages and messages are not available."))
            }
        }
        .navigationTitle(settings.text("Интеграции", "Integrations"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.integrations")
    }

    private func row(_ service: Integrations.Service) -> some View {
        let binding = Binding<Bool>(
            get: { !disabled.contains(service.id) },
            set: { enabled in
                Integrations.setEnabled(service.id, enabled)
                withAnimation(.easeInOut(duration: 0.2)) { disabled = Integrations.disabled }
            })
        return Toggle(isOn: binding) {
            HStack(spacing: 12) {
                Image(systemName: service.symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(service.color.gradient, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(service.name).font(.system(size: 16, weight: .semibold))
                    Text(settings.language == .english ? service.descriptionEN : service.descriptionRU)
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(HonorTheme.accent)
        .accessibilityIdentifier("integration." + service.id)
    }
}
