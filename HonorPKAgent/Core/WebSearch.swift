import Foundation

protocol WebSearching {
    func search(_ query: String) async throws -> [WebSource]
}

struct WebSearchClient: WebSearching {
    var session: URLSession = .shared

    func search(_ query: String) async throws -> [WebSource] {
        var components = URLComponents(string: "https://www.bing.com/search")!
        components.queryItems = [URLQueryItem(name: "q", value: String(query.prefix(600))), URLQueryItem(name: "format", value: "rss")]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 18
        request.setValue("application/rss+xml, application/xml", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              data.count <= 2 * 1024 * 1024 else { throw HonorError.searchUnavailable }
        let results = RSSResultsParser.parse(data)
        guard !results.isEmpty else { throw HonorError.searchUnavailable }
        return Array(results.prefix(6))
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
