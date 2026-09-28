import XCTest
import SwiftUI
import UIKit
@testable import HonorPKAgent

/// Инструменты модели: разбор ссылок, набор инструментов и выполнение без сети.
@MainActor
final class WebToolsTests: XCTestCase {
    func testMediaLinksRecognizeVideosDrawingsAndScreenshots() throws {
        let id = "dQw4w9WgXcQ"
        for raw in ["https://www.youtube.com/watch?v=\(id)", "https://youtu.be/\(id)", "https://www.youtube.com/shorts/\(id)",
                    "https://m.youtube.com/watch?v=\(id)&t=42", "https://www.youtube.com/embed/\(id)"] {
            XCTAssertEqual(MediaLinks.youTubeID(try XCTUnwrap(URL(string: raw))), id, raw)
            XCTAssertTrue(MediaLinks.isVideo(try XCTUnwrap(URL(string: raw))), raw)
        }
        XCTAssertTrue(MediaLinks.isVideo(try XCTUnwrap(URL(string: "https://example.com/clip.mp4"))))
        XCTAssertFalse(MediaLinks.isVideo(try XCTUnwrap(URL(string: "https://example.com/photo.jpg"))))
        XCTAssertNil(MediaLinks.youTubeID(try XCTUnwrap(URL(string: "https://www.youtube.com/@channel"))))
        XCTAssertEqual(MediaLinks.videoThumbnail(try XCTUnwrap(URL(string: "https://youtu.be/\(id)")))?.absoluteString,
                       "https://i.ytimg.com/vi/\(id)/hqdefault.jpg")

        let drawing = try XCTUnwrap(MediaLinks.drawing(prompt: "cat astronaut (watercolor), stars", orientation: "landscape", seed: 5))
        XCTAssertEqual(drawing.host, "image.pollinations.ai")
        XCTAssertTrue(drawing.absoluteString.contains("width=1152&height=768"))
        XCTAssertFalse(drawing.absoluteString.contains("("), "Скобки в описании ломают разметку ![…](…)")
        XCTAssertFalse(drawing.absoluteString.contains(" "))

        let shot = try XCTUnwrap(MediaLinks.screenshot(of: try XCTUnwrap(URL(string: "https://example.com/page"))))
        XCTAssertTrue(shot.absoluteString.hasPrefix("https://image.thum.io/"))
        XCTAssertTrue(shot.absoluteString.hasSuffix("https://example.com/page"))
        XCTAssertNil(MediaLinks.screenshot(of: try XCTUnwrap(URL(string: "http://localhost/x"))))
    }

    func testTelegramLinksOpenThroughTheWebPreview() throws {
        XCTAssertEqual(WebSearchClient.readableURL(for: try XCTUnwrap(URL(string: "https://t.me/durov"))).absoluteString, "https://t.me/s/durov")
        XCTAssertEqual(WebSearchClient.readableURL(for: try XCTUnwrap(URL(string: "https://t.me/durov/300"))).absoluteString, "https://t.me/s/durov/300")
        XCTAssertEqual(WebSearchClient.readableURL(for: try XCTUnwrap(URL(string: "https://t.me/s/durov"))).absoluteString, "https://t.me/s/durov")
        XCTAssertEqual(WebSearchClient.readableURL(for: try XCTUnwrap(URL(string: "https://example.com/a"))).absoluteString, "https://example.com/a")
        XCTAssertEqual(WebToolExecutor.url(from: "t.me/durov")?.absoluteString, "https://t.me/durov")
        XCTAssertNil(WebToolExecutor.url(from: ""))
    }

    func testSearchResultLinksAreParsedFromEngineHTML() {
        let html = """
        <a href="https://search.brave.com/settings">Настройки поиска Brave</a>
        <a href="https://ru.investing.com/currencies/usd-rub" class="l1"><div class="title">Доллар США — рубль: курс</div></a>
        <a href="https://cbr.ru/currency_base/daily/">Официальные курсы валют ЦБ РФ</a>
        <a href="https://cbr.ru/currency_base/daily/">Дубль ссылки на курсы ЦБ</a>
        <a href="https://x.io">ok</a>
        """
        let links = WebPageText.resultLinks(html, excludingHostsContaining: ["brave.com"])
        XCTAssertEqual(links.map(\.url.host), ["ru.investing.com", "cbr.ru"])
        XCTAssertEqual(links.first?.title, "Доллар США — рубль: курс")
    }

    func testInternetToolsAreOfferedOnlyWithSearchButton() {
        func names(_ schemas: [[String: Any]]) -> Set<String> {
            Set(schemas.compactMap { ($0["function"] as? [String: Any])?["name"] as? String })
        }
        let off = names(HonerTool.schemas(searchEnabled: false))
        let on = names(HonerTool.schemas(searchEnabled: true))
        for web in ["web_search", "open_page", "find_images", "find_videos", "screenshot_page", "get_weather"] {
            XCTAssertFalse(off.contains(web), "\(web) не должен быть доступен без кнопки «Поиск»")
            XCTAssertTrue(on.contains(web), "\(web) обязан быть доступен с кнопкой «Поиск»")
        }
        for always in ["draw_image", "get_app_settings", "list_chats", "read_chat", "set_app_setting", "save_memory"] {
            XCTAssertTrue(off.contains(always) && on.contains(always), always)
        }
    }

    func testSettingsAndDrawingToolsAnswerWithoutNetwork() {
        let context = ToolExecutionContext(settingsSummary: "• Поиск в интернете: включено\n• Рассуждение: выключено")
        let settings = ToolExecutor.executeExtended(ToolCallRequest(id: "s", name: "get_app_settings", arguments: "{}"), context: context)
        XCTAssertTrue(settings.content.contains("Поиск в интернете: включено"))
        let draw = ToolExecutor.executeExtended(ToolCallRequest(id: "d", name: "draw_image", arguments: "{\"prompt\": \"red fox in snow\"}"), context: context)
        XCTAssertTrue(draw.content.contains("![Рисунок: red fox in snow](https://image.pollinations.ai/prompt/red%20fox%20in%20snow"), draw.content)
        let empty = ToolExecutor.executeExtended(ToolCallRequest(id: "e", name: "draw_image", arguments: "{}"), context: context)
        XCTAssertTrue(empty.content.contains("Не передано"))
    }

    func testTypedTailFadesInSmoothly() {
        var text = AttributedString("Привет, это плавный текст")
        text.foregroundColor = .white
        let faded = TextFade.tail(text, length: 6)
        XCTAssertEqual(String(faded.characters), "Привет, это плавный текст", "Проявление не должно менять сам текст")
        XCTAssertEqual(faded.runs.count, 7, "Шесть последних символов получают своё проявление")
    }

    func testSearchButtonNoLongerForcesSearchBeforeTheAnswer() async throws {
        // Кнопка «Поиск» даёт модели возможность искать, но не заставляет искать заранее.
        let search = CountingSearch()
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: PlainAnswerClient(),
                              searchClient: search,
                              storageURL: FileManager.default.temporaryDirectory.appendingPathComponent("web-\(UUID()).json"))
        store.searchEnabled = true
        store.draft = "Сколько будет 17 умножить на 23?"
        store.send()
        for _ in 0..<200 where store.isGenerating { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertFalse(store.isGenerating)
        XCTAssertEqual(search.calls, 0, "Поиск не должен запускаться до ответа, когда включена кнопка")
        XCTAssertEqual(store.messages.last?.content, "17 × 23 = 391.")
        // Без кнопки явная просьба найти по-прежнему ищет сама.
        store.searchEnabled = false
        store.draft = "Найди в интернете курс доллара"
        store.send()
        for _ in 0..<200 where store.isGenerating { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(search.calls, 1)
    }
}

private final class CountingSearch: WebSearching {
    var calls = 0
    func search(_ query: String) async throws -> [WebSource] {
        calls += 1
        return [WebSource(title: "Курс", url: URL(string: "https://cbr.ru")!, snippet: "Курс доллара", content: "Курс доллара 90 рублей", fetchedAt: Date())]
    }
}

private struct PlainAnswerClient: DeepSeekStreaming {
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String,
                searchContext: String, tools: [[String: Any]]?) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield(.init(content: searchContext.isEmpty ? "17 × 23 = 391." : "Курс доллара — 90 рублей."))
            continuation.yield(.init(finishReason: "stop"))
            continuation.finish()
        }
    }
}

/// Живые проверки интернета: каждый инструмент по-настоящему ходит в сеть.
final class LiveWebToolsTests: XCTestCase {
    private let executor = WebToolExecutor()

    private func run(_ name: String, _ arguments: String) async -> ToolCallResult {
        let result = await executor.execute(ToolCallRequest(id: "live", name: name, arguments: arguments))
        print("HONER_WEBTOOL \(name) → \(result.content.prefix(400).replacingOccurrences(of: "\n", with: " ⏎ "))")
        return result
    }

    func testLiveWebSearchReadsPagesFromSeveralEngines() async throws {
        let result = await run("web_search", "{\"query\": \"Эйфелева башня высота\"}")
        guard case .addSources(let sources)? = result.effect else { return XCTFail("Поиск не вернул источников: \(result.content)") }
        XCTAssertGreaterThanOrEqual(sources.count, 2)
        XCTAssertTrue(sources.contains { $0.content != nil }, "Ни одна страница не прочитана")
        XCTAssertTrue(result.content.contains("URL: https://"))
    }

    func testLiveOpenPageReadsTelegramChannel() async throws {
        let result = await run("open_page", "{\"url\": \"https://t.me/durov\"}")
        XCTAssertTrue(result.content.contains("t.me/s/durov"), result.content)
        guard case .addSources(let sources)? = result.effect else { return XCTFail(result.content) }
        XCTAssertGreaterThan(sources.first?.content?.count ?? 0, 300)
    }

    @MainActor
    func testLiveSafariEngineRendersJavaScriptPages() async throws {
        let page = try XCTUnwrap(URL(string: "https://example.com"))
        let started = Date()
        let rendered = await WebPageRenderer.render(page, timeout: 20)
        print("HONER_WEBKIT seconds=\(Date().timeIntervalSince(started)) text=\(rendered?.text.prefix(80) ?? "nil")")
        XCTAssertTrue(rendered?.text.contains("Example Domain") == true, rendered?.text ?? "nil")
    }

    func testLiveFindImagesReturnsLoadableImages() async throws {
        let result = await run("find_images", "{\"query\": \"Eiffel Tower\", \"count\": 3}")
        let urls = WebPageText.urls(in: result.content)
        XCTAssertGreaterThanOrEqual(urls.count, 2, result.content)
        let first = try XCTUnwrap(urls.first)
        let (data, response) = try await URLSession.shared.data(from: first)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertNotNil(UIImage(data: data), "По ссылке не картинка: \(first)")
    }

    func testLiveFindVideosReturnsYouTubeLinks() async throws {
        let result = await run("find_videos", "{\"query\": \"SwiftUI tutorial\", \"count\": 2}")
        let videos = WebPageText.urls(in: result.content).filter { MediaLinks.youTubeID($0) != nil }
        XCTAssertFalse(videos.isEmpty, result.content)
    }

    func testLiveDrawingAndScreenshotLinksReturnImages() async throws {
        // Рисование выполняется без сети на стороне приложения — через общий исполнитель.
        let draw = await MainActor.run {
            ToolExecutor.executeExtended(ToolCallRequest(id: "d", name: "draw_image",
                                                         arguments: "{\"prompt\": \"small red lighthouse on a rocky coast, watercolor\"}"),
                                         context: ToolExecutionContext())
        }
        let shot = await run("screenshot_page", "{\"url\": \"https://example.com\"}")
        for content in [draw.content, shot.content] {
            let raw = String(content.components(separatedBy: "](").last?.dropLast() ?? "")
            let url = try XCTUnwrap(URL(string: raw), content)
            // Загрузка тем же путём, что и в чате: с повторами, если сервис занят.
            let image = await RemoteImageCache.load(url)
            XCTAssertNotNil(image, "Картинка не загрузилась: \(content)")
        }
    }

    func testLiveWeatherTool() async throws {
        let result = await run("get_weather", "{\"city\": \"Москва\"}")
        XCTAssertTrue(result.content.contains("°C"), result.content)
    }
}
