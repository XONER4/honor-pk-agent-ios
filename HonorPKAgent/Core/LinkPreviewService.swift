import Foundation

/// Карточка-превью ссылки: читает Open Graph и Twitter Card мета-теги страницы
/// и отдаёт заголовок, описание, картинку и имя сайта.
///
/// Безопасность: мета-теги извлекаются регулярками, никакой HTML не исполняется,
/// теги <script> и содержимое страницы не рендерятся. Загружаются только
/// http/https адреса, ответ ограничен по размеру.
struct LinkPreview: Identifiable, Equatable, Sendable {
    let id = UUID()
    let url: URL
    let title: String
    let description: String
    let imageURL: URL?
    let siteName: String

    static func == (lhs: LinkPreview, rhs: LinkPreview) -> Bool {
        lhs.url == rhs.url && lhs.title == rhs.title
    }
}

@MainActor
final class LinkPreviewService {
    static let shared = LinkPreviewService()

    private var cache: [String: LinkPreview] = [:]
    private var inFlight: Set<String> = []
    private let session: URLSession

    private init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 12
        session = URLSession(configuration: configuration)
    }

    func cached(_ url: URL) -> LinkPreview? { cache[url.absoluteString] }

    /// Загружает превью и кэширует его. Повторные вызовы для того же адреса
    /// не порождают новых запросов.
    func load(_ url: URL) async -> LinkPreview? {
        let key = url.absoluteString
        if let cached = cache[key] { return cached }
        guard !inFlight.contains(key) else { return nil }
        guard url.scheme == "http" || url.scheme == "https" else { return nil }
        inFlight.insert(key)
        defer { inFlight.remove(key) }

        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1",
                         forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")

        guard let (data, response) = try? await session.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              data.count <= 600_000,
              let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .windowsCP1251)
        else { return nil }

        let preview = Self.parse(html: html, url: url)
        cache[key] = preview
        return preview
    }

    /// Разбор мета-тегов. Никакой HTML не исполняется — только чтение атрибутов.
    static func parse(html: String, url: URL) -> LinkPreview {
        let head = String(html.prefix(200_000))

        func meta(_ keys: [String]) -> String? {
            for key in keys {
                // content="..." может стоять до или после property/name
                let patterns = [
                    "<meta[^>]*(?:property|name)=[\"']\(key)[\"'][^>]*content=[\"']([^\"']*)[\"']",
                    "<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']\(key)[\"']"
                ]
                for pattern in patterns {
                    if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
                       let match = regex.firstMatch(in: head, range: NSRange(head.startIndex..., in: head)),
                       match.numberOfRanges > 1,
                       let range = Range(match.range(at: 1), in: head) {
                        let value = decodeHTMLEntities(String(head[range]))
                        if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return value }
                    }
                }
            }
            return nil
        }

        let title = meta(["og:title", "twitter:title"])
            ?? Self.tag("title", in: head).map(decodeHTMLEntities)
            ?? url.host
            ?? url.absoluteString

        let description = meta(["og:description", "twitter:description", "description"]) ?? ""
        let imageString = meta(["og:image:secure_url", "og:image", "twitter:image"])
        let siteName = meta(["og:site_name"]) ?? (url.host ?? "")

        var imageURL: URL?
        if let imageString {
            imageURL = URL(string: imageString, relativeTo: url)?.absoluteURL
        }

        return LinkPreview(url: url,
                           title: String(title.prefix(160)),
                           description: String(description.prefix(300)),
                           imageURL: imageURL,
                           siteName: String(siteName.prefix(60)))
    }

    private static func tag(_ name: String, in html: String) -> String? {
        let pattern = "<\(name)[^>]*>([^<]*)</\(name)>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: html) else { return nil }
        return String(html[range]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Убирает HTML-сущности, чтобы в карточке не было «&amp;» и «&#39;».
    static func decodeHTMLEntities(_ value: String) -> String {
        var text = value
        let entities: [String: String] = [
            "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'", "&apos;": "'",
            "&nbsp;": " ", "&mdash;": "—", "&ndash;": "–", "&laquo;": "«", "&raquo;": "»", "&hellip;": "…"
        ]
        for (entity, symbol) in entities {
            text = text.replacingOccurrences(of: entity, with: symbol)
        }
        // Числовые сущности вида &#8212;
        if let regex = try? NSRegularExpression(pattern: "&#(\\d{2,6});") {
            let range = NSRange(text.startIndex..., in: text)
            let matches = regex.matches(in: text, range: range).reversed()
            for match in matches {
                guard let whole = Range(match.range, in: text),
                      let digits = Range(match.range(at: 1), in: text),
                      let code = UInt32(text[digits]),
                      let scalar = Unicode.Scalar(code) else { continue }
                text.replaceSubrange(whole, with: String(Character(scalar)))
            }
        }
        return text
    }
}
