import Foundation

protocol WebSearching {
    func search(_ query: String) async throws -> [WebSource]
}

protocol SearchURLDiscovering {
    func candidates(for query: String) async throws -> [URL]
}

struct WebSearchClient: WebSearching {
    var session: URLSession = .shared
    var urlDiscovery: SearchURLDiscovering? = DeepSeekURLDiscovery(configuration: .bundled)

    func search(_ query: String) async throws -> [WebSource] {
        if let location = WeatherIntent.location(in: query) {
            return [try await WeatherClient(session: session).forecast(location: location)]
        }
        let directURLs = WebPageText.urls(in: query)
        if !directURLs.isEmpty {
            let sources = directURLs.prefix(5).map { WebSource(title: $0.host ?? "Страница", url: $0, snippet: "Ссылка пользователя") }
            let fetched = await readPages(Array(sources), limit: 5)
            try Task.checkCancellation()
            guard fetched.contains(where: { $0.content != nil }) else { throw HonorError.searchUnavailable }
            return fetched
        }
        let searchQuery = SearchRelevance.compactQuery(query)
        async let bing = bingResults(searchQuery)
        async let duck = duckDuckGoResults(searchQuery)
        let candidates = await (bing, duck)
        try Task.checkCancellation()
        var seen = Set<String>()
        let ranked = (candidates.0 + candidates.1).filter {
            SearchRelevance.score(source: $0, query: searchQuery) > 0 && seen.insert($0.url.absoluteString).inserted
        }.sorted { SearchRelevance.score(source: $0, query: searchQuery) > SearchRelevance.score(source: $1, query: searchQuery) }
        if ranked.isEmpty {
            guard let urlDiscovery else { throw HonorError.searchUnavailable }
            let suggested = try await urlDiscovery.candidates(for: query)
            try Task.checkCancellation()
            let candidates = Array(suggested.prefix(3)).map { WebSource(title: $0.host ?? "Страница", url: $0, snippet: "Проверенная по прямой ссылке страница; не поисковая выдержка") }
            let fetched = (await readPages(candidates, limit: 3, timeout: 8)).filter { $0.content != nil }
            try Task.checkCancellation()
            guard !fetched.isEmpty else { throw HonorError.searchUnavailable }
            return fetched
        }
        let fetched = await readPages(Array(ranked.prefix(12)), limit: 5)
        try Task.checkCancellation()
        return fetched
    }

    private func bingResults(_ query: String) async -> [WebSource] {
        var components = URLComponents(string: "https://www.bing.com/search")!
        components.queryItems = [URLQueryItem(name: "q", value: String(query.prefix(400))), URLQueryItem(name: "format", value: "rss"), URLQueryItem(name: "setlang", value: "ru-RU")]
        guard let data = try? await fetch(components.url!, maximumBytes: 1_500_000) else { return [] }
        return RSSResultsParser.parse(data)
    }

    private func duckDuckGoResults(_ query: String) async -> [WebSource] {
        var components = URLComponents(string: "https://html.duckduckgo.com/html/")!
        components.queryItems = [URLQueryItem(name: "q", value: String(query.prefix(400))), URLQueryItem(name: "kl", value: "ru-ru")]
        guard let data = try? await fetch(components.url!, maximumBytes: 1_500_000),
              let html = String(data: data, encoding: .utf8) else { return [] }
        return WebPageText.searchResults(html)
    }

    private func readPages(_ sources: [WebSource], limit: Int, timeout: TimeInterval = 10) async -> [WebSource] {
        await withTaskGroup(of: (Int, WebSource).self) { group in
            for (index, source) in sources.prefix(limit).enumerated() {
                group.addTask {
                    var source = source
                    if let data = try? await fetch(source.url, maximumBytes: 1_500_000, timeout: timeout),
                       let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .windowsCP1251) {
                        let text = WebPageText.extract(html)
                        if text.count >= 100 && !WebPageText.looksLikeChallenge(text) {
                            source.content = String(text.prefix(9000)); source.fetchedAt = Date()
                            if let title = WebPageText.title(html) { source.title = title }
                        }
                    }
                    return (index, source)
                }
            }
            var result = sources
            for await (index, source) in group { result[index] = source }
            return result
        }
    }

    private func fetch(_ url: URL, maximumBytes: Int, timeout: TimeInterval = 10) async throws -> Data {
        guard WebPageText.isPublicWebURL(url) else { throw HonorError.searchUnavailable }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        request.setValue("ru-RU,ru;q=0.9,en;q=0.5", forHTTPHeaderField: "Accept-Language")
        let (bytes, response) = try await session.bytes(for: request)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              response.url.map(WebPageText.isPublicWebURL) == true else { throw HonorError.searchUnavailable }
        var data = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            data.append(byte)
            if data.count >= maximumBytes { break }
        }
        return data
    }

    static func context(_ sources: [WebSource]) -> String {
        sources.enumerated().map { index, source in
            let provenance = source.content == nil ? "Только поисковая выдержка; страница не прочитана." : "Страница/структурированные данные получены: \(source.fetchedAt.map { ISO8601DateFormatter().string(from: $0) } ?? "в этом запросе")."
            return "[\(index + 1)] \(source.title)\nURL: \(source.url.absoluteString)\n\(provenance)\n\(source.content ?? source.snippet)"
        }.joined(separator: "\n\n")
    }
}

/// Model suggestions are URL candidates only. A source exists only after the page was fetched.
struct DeepSeekURLDiscovery: SearchURLDiscovering {
    let configuration: DeepSeekConfiguration
    var session: URLSession = .shared

    func candidates(for query: String) async throws -> [URL] {
        guard !configuration.apiKey.isEmpty else { throw HonorError.searchUnavailable }
        try Task.checkCancellation()
        let instruction = """
        Нужно найти первичные источники для запроса пользователя, когда поисковые сайты недоступны. Верни JSON вида {"urls":["https://..."]} — не больше трёх конкретных, уверенно известных тебе URL официальных страниц документации, технических характеристик или первичных справочных источников. Не выдумывай неизвестные адреса и не отвечай на вопрос. Если точных известных URL нет, верни пустой массив. Предложенные адреса будут проверены реальным HTTP-запросом, поэтому они не считаются уже найденными источниками. Не включай поисковые выдачи, ссылки с API-ключами, локальные адреса или личные данные.
        """
        let payload: [String: Any] = ["model": configuration.model, "thinking": ["type": "disabled"], "stream": false,
                                    "max_tokens": 600, "response_format": ["type": "json_object"],
                                    "messages": [["role": "system", "content": instruction], ["role": "user", "content": String(query.prefix(1600))]]]
        var request = URLRequest(url: configuration.baseURL.appendingPathComponent("chat/completions"))
        request.httpMethod = "POST"; request.timeoutInterval = 6
        request.setValue("Bearer \(configuration.apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count < 32_000,
              let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = envelope["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let content = message["content"] as? String,
              let planData = content.data(using: .utf8),
              let plan = try JSONSerialization.jsonObject(with: planData) as? [String: Any],
              let urls = plan["urls"] as? [String] else { throw HonorError.searchUnavailable }
        var seen = Set<String>()
        return urls.compactMap(URL.init(string:)).filter { WebPageText.isPublicWebURL($0) && seen.insert($0.absoluteString).inserted }.prefix(3).map { $0 }
    }
}

enum SearchRelevance {
    static func compactQuery(_ query: String) -> String {
        let firstSentence = query.components(separatedBy: ". ").first ?? query
        let stripped = firstSentence.replacingOccurrences(of: "(?i)^(?:please\\s+)?(?:find|search for|look up|найди|найдите|поищи|покажи|пожалуйста)[,: ]+", with: "", options: .regularExpression)
        return String(stripped.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300))
    }

    static func score(source: WebSource, query: String) -> Int {
        let query = query.lowercased()
        let text = (source.title + " " + source.snippet + " " + (source.url.host ?? "") + " " + source.url.path).lowercased()
        if !query.contains("bing"), ["bing quiz", "bingquiz", "bing rewards", "bing homepage quiz", "microsoft rewards"].contains(where: text.contains) { return 0 }
        let stops: Set<String> = ["какая", "какой", "какие", "найди", "найти", "пожалуйста", "расскажи", "сейчас", "сегодня", "нужно", "можешь", "сделай", "покажи", "информацию", "интернет", "поиск", "узнай", "what", "which", "tell", "please", "search", "about", "latest", "the", "for", "and"]
        let words = query.components(separatedBy: CharacterSet.letters.inverted).filter { $0.count >= 3 && !stops.contains($0) }
        guard !words.isEmpty else { return 1 }
        let hits = words.reduce(0) { $0 + (text.contains(String($1.prefix(5))) ? 1 : 0) }
        guard hits > 0, Double(hits) / Double(words.count) >= 0.3 else { return 0 }
        return hits * 10 + (source.url.host?.hasSuffix(".gov") == true ? 2 : 0)
    }
}

enum WebPageText {
    static func isPublicWebURL(_ url: URL) -> Bool {
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host?.lowercased(), !host.isEmpty, url.user == nil, url.password == nil else { return false }
        return !["localhost", "::1", "0.0.0.0"].contains(host) && !host.hasSuffix(".local") &&
            !host.hasPrefix("127.") && !host.hasPrefix("10.") && !host.hasPrefix("192.168.") && !host.hasPrefix("169.254.") &&
            !(host.hasPrefix("172.") && (16...31).contains(Int(host.split(separator: ".").dropFirst().first ?? "") ?? 0))
    }

    static func urls(in text: String) -> [URL] {
        guard let regex = try? NSRegularExpression(pattern: "https?://[^\\s<>\\\"\\[\\]]+", options: .caseInsensitive) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap {
            guard let range = Range($0.range, in: text) else { return nil }
            let value = String(text[range]).trimmingCharacters(in: CharacterSet(charactersIn: ".,;!?)"))
            guard let url = URL(string: value), isPublicWebURL(url) else { return nil }
            return url
        }
    }

    static func extract(_ html: String) -> String {
        var text = html
        for tag in ["script", "style", "nav", "footer", "header", "noscript", "svg", "form"] {
            text = text.replacingOccurrences(of: "(?is)<\(tag)\\b[^>]*>.*?</\(tag)\\s*>", with: " ", options: .regularExpression)
        }
        text = text.replacingOccurrences(of: "(?is)<!--.*?-->", with: " ", options: .regularExpression)
        text = text.replacingOccurrences(of: "(?i)</?(?:p|div|article|section|h[1-6]|li|br|tr)\\b[^>]*>", with: "\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "<[^>]*>", with: " ", options: .regularExpression)
        return decodeEntities(text).components(separatedBy: .newlines)
            .map { $0.replacingOccurrences(of: "[\\t ]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }.joined(separator: "\n")
    }

    static func title(_ html: String) -> String? {
        guard let match = matches("(?is)<title[^>]*>(.*?)</title>", in: html).first, match.count > 1 else { return nil }
        return String(extract(match[1]).prefix(180))
    }

    static func looksLikeChallenge(_ text: String) -> Bool {
        let text = String(text.prefix(1500)).lowercased()
        return ["verify you are human", "enable javascript and cookies to continue", "checking your browser", "подтвердите, что вы не робот", "access denied", "just a moment"].contains(where: text.contains)
    }

    static func searchResults(_ html: String) -> [WebSource] {
        let links = matches("(?is)<a\\b[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", in: html)
        let snippets = matches("(?is)<(?:a|div|span)\\b[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div|span)>", in: html)
        return links.enumerated().compactMap { index, fields in
            var link = decodeEntities(fields[1])
            if link.hasPrefix("//") { link = "https:" + link }
            guard let original = URL(string: link) else { return nil }
            let redirected = URLComponents(url: original, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "uddg" })?.value
            guard let url = redirected.flatMap(URL.init(string:)) ?? URL(string: link), isPublicWebURL(url), !url.host!.contains("duckduckgo.com") else { return nil }
            let snippet = index < snippets.count ? extract(snippets[index][1]) : ""
            return WebSource(title: extract(fields[2]), url: url, snippet: String(snippet.prefix(1600)))
        }
    }

    static func decodeEntities(_ text: String) -> String {
        var result = text
        let named = ["&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'", "&apos;": "'", "&nbsp;": " ", "&ndash;": "–", "&mdash;": "—"]
        for (entity, character) in named { result = result.replacingOccurrences(of: entity, with: character) }
        guard let regex = try? NSRegularExpression(pattern: "&#(x[0-9a-fA-F]+|[0-9]+);") else { return result }
        for match in regex.matches(in: result, range: NSRange(result.startIndex..., in: result)).reversed() {
            guard let valueRange = Range(match.range(at: 1), in: result), let fullRange = Range(match.range, in: result) else { continue }
            let value = String(result[valueRange])
            let number = value.hasPrefix("x") ? UInt32(value.dropFirst(), radix: 16) : UInt32(value)
            if let number, let scalar = UnicodeScalar(number) { result.replaceSubrange(fullRange, with: String(scalar)) }
        }
        return result
    }

    private static func matches(_ pattern: String, in text: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { match in
            (0..<match.numberOfRanges).map { Range(match.range(at: $0), in: text).map { String(text[$0]) } ?? "" }
        }
    }
}

enum WeatherIntent {
    static func location(in query: String) -> String? {
        let lower = query.lowercased()
        guard ["погод", "температур", "прогноз", "weather", "forecast"].contains(where: lower.contains) else { return nil }
        if lower.range(of: "(?i)\\bклин(?:е|а|у|ом)?\\b|\\bklin\\b", options: .regularExpression) != nil { return "Клин" }
        if lower.contains("москв") || lower.contains("moscow") { return "Москва" }
        if lower.contains("петербург") || lower.contains("saint petersburg") { return "Санкт-Петербург" }
        guard let regex = try? NSRegularExpression(pattern: "(?i)(?:\\bв|\\bво|\\bin|\\bfor)\\s+([\\p{L}][\\p{L} -]{1,60})"),
              let match = regex.firstMatch(in: query, range: NSRange(query.startIndex..., in: query)),
              let range = Range(match.range(at: 1), in: query) else { return nil }
        var location = String(query[range])
        for suffix in [" сегодня", " завтра", " сейчас", " на ", " в ", " today", " tomorrow", " this "] {
            if let range = location.range(of: suffix, options: .caseInsensitive) { location = String(location[..<range.lowerBound]) }
        }
        return location.trimmingCharacters(in: .whitespaces)
    }
}

struct WeatherClient {
    var session: URLSession = .shared

    func forecast(location: String) async throws -> WebSource {
        let place: WeatherPlace
        if location == "Клин" { place = WeatherPlace(name: "Клин, Московская область, Россия", latitude: 56.3333, longitude: 36.7333) }
        else {
            var geo = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
            geo.queryItems = [.init(name: "name", value: location), .init(name: "count", value: "5"), .init(name: "language", value: "ru"), .init(name: "format", value: "json")]
            var request = URLRequest(url: geo.url!); request.timeoutInterval = 10
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let result = try JSONDecoder().decode(GeocodingResult.self, from: data).results?.first else { throw HonorError.searchUnavailable }
            place = WeatherPlace(name: [result.name, result.admin1, result.country].compactMap { $0 }.joined(separator: ", "), latitude: result.latitude, longitude: result.longitude)
        }
        var api = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        api.queryItems = [.init(name: "latitude", value: String(place.latitude)), .init(name: "longitude", value: String(place.longitude)),
                          .init(name: "current", value: "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"),
                          .init(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"),
                          .init(name: "forecast_days", value: "7"), .init(name: "timezone", value: "auto"), .init(name: "wind_speed_unit", value: "ms")]
        var request = URLRequest(url: api.url!); request.timeoutInterval = 12
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw HonorError.searchUnavailable }
        let forecast = try JSONDecoder().decode(WeatherForecast.self, from: data)
        let text = forecast.russianDescription(location: place.name)
        return WebSource(title: "Погода: \(place.name) · Open-Meteo", url: api.url!, snippet: String(text.prefix(500)), content: text, fetchedAt: Date())
    }
}

private struct WeatherPlace { let name: String; let latitude: Double; let longitude: Double }
private struct GeocodingResult: Decodable {
    struct Place: Decodable { let name: String; let latitude: Double; let longitude: Double; let admin1: String?; let country: String? }
    let results: [Place]?
}

struct WeatherForecast: Decodable {
    struct Current: Decodable {
        let time: String; let temperature_2m: Double; let apparent_temperature: Double?
        let relative_humidity_2m: Double?; let precipitation: Double?; let weather_code: Int; let wind_speed_10m: Double?
    }
    struct Daily: Decodable {
        let time: [String]; let temperature_2m_max: [Double?]; let temperature_2m_min: [Double?]
        let precipitation_probability_max: [Double?]?; let weather_code: [Int?]
    }
    let timezone: String
    let current: Current
    let daily: Daily?

    func russianDescription(location: String) -> String {
        var lines = ["Место: \(location). Часовой пояс: \(timezone).", "Текущие модельные условия на \(current.time): \(Self.condition(current.weather_code)); температура \(current.temperature_2m) °C."]
        if let feels = current.apparent_temperature { lines.append("Ощущается как \(feels) °C.") }
        if let wind = current.wind_speed_10m { lines.append("Ветер \(wind) м/с.") }
        if let humidity = current.relative_humidity_2m { lines.append("Влажность \(humidity)%.") }
        if let precipitation = current.precipitation { lines.append("Осадки \(precipitation) мм.") }
        if let daily {
            for index in daily.time.indices.prefix(7) {
                let low = index < daily.temperature_2m_min.count ? daily.temperature_2m_min[index] : nil
                let high = index < daily.temperature_2m_max.count ? daily.temperature_2m_max[index] : nil
                guard let low, let high else { continue }
                let code = index < daily.weather_code.count ? daily.weather_code[index] : nil
                var row = "\(daily.time[index]): \(low)…\(high) °C; \(code.map(Self.condition) ?? "нет данных об облачности")"
                if let chances = daily.precipitation_probability_max, index < chances.count, let chance = chances[index] { row += "; вероятность осадков \(chance)%" }
                lines.append(row + ".")
            }
        }
        lines.append("Источник Open-Meteo: расчёт прогностических моделей, не непосредственное измерение на метеостанции. Отвечай по указанным датам; не выдавай прогноз другого дня за сегодняшний.")
        return lines.joined(separator: "\n")
    }

    static func condition(_ code: Int) -> String {
        switch code {
        case 0: return "ясно"
        case 1: return "преимущественно ясно"
        case 2: return "переменная облачность"
        case 3: return "пасмурно"
        case 45, 48: return "туман"
        case 51, 53, 55, 56, 57: return "морось"
        case 61, 63, 65, 66, 67: return "дождь"
        case 71, 73, 75, 77: return "снег"
        case 80, 81, 82: return "ливень"
        case 85, 86: return "снежные заряды"
        case 95, 96, 99: return "гроза"
        default: return "код погоды \(code)"
        }
    }
}

final class RSSResultsParser: NSObject, XMLParserDelegate {
    private var results: [WebSource] = []
    private var currentElement = ""
    private var inItem = false
    private var title = ""
    private var link = ""
    private var snippet = ""

    static func parse(_ data: Data) -> [WebSource] {
        let delegate = RSSResultsParser()
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse() else { return [] }
        return delegate.results
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String : String] = [:]) {
        currentElement = elementName
        if elementName == "item" { inItem = true; title = ""; link = ""; snippet = "" }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard inItem else { return }
        switch currentElement {
        case "title": title += string
        case "link": link += string
        case "description": snippet += string
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        if let string = String(data: CDATABlock, encoding: .utf8) { self.parser(parser, foundCharacters: string) }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "item" {
            inItem = false
            if let url = URL(string: link.trimmingCharacters(in: .whitespacesAndNewlines)),
               ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
               !results.contains(where: { $0.url == url }) {
                results.append(WebSource(title: Self.clean(title), url: url, snippet: String(Self.clean(snippet).prefix(1600))))
            }
        }
        currentElement = ""
    }

    private static func clean(_ text: String) -> String {
        text.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#39;", with: "'")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
