import XCTest
@testable import HonorPKAgent

final class Honer10CoreTests: XCTestCase {
    @MainActor
    func testArchiveRestoreAndReloadNeverSelectArchivedConversation() throws {
        let url = historyURL()
        let chat = Conversation(title: "Archive", messages: [ChatMessage(role: .user, content: "Question")])
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        store.conversations = [chat]; store.selectedConversationID = chat.id
        store.archiveChat(id: chat.id)
        XCTAssertNil(store.selectedConversationID)
        XCTAssertEqual(store.archivedConversations.map(\.id), [chat.id])
        store.selectChat(id: chat.id)
        XCTAssertNil(store.selectedConversationID)
        store.selectedConversationID = chat.id // Also repair an old/stale saved selection on launch.
        store.persistNow()
        let reloaded = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        XCTAssertNil(reloaded.selectedConversationID)
        XCTAssertEqual(reloaded.archivedConversations.count, 1)
        let exported = try reloaded.exportData()
        defer { try? FileManager.default.removeItem(at: exported) }
        let imported = ChatStore(configuration: .init(apiKey: "test"), storageURL: historyURL())
        try imported.importData(from: exported)
        XCTAssertNotNil(imported.archivedConversations.first?.archivedAt)
        imported.restoreChat(id: chat.id); imported.selectChat(id: chat.id)
        XCTAssertEqual(imported.selectedConversationID, chat.id)
        XCTAssertTrue(imported.archivedConversations.isEmpty)
    }

    @MainActor
    func testAsyncHistoryLoadRecoversDataWithoutAllowingEarlySend() async throws {
        let url = historyURL()
        let first = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        first.conversations = [Conversation(title: "Loaded in background")]
        first.persistNow()
        let loaded = ChatStore(configuration: .init(apiKey: "test"), storageURL: url, loadHistoryAsynchronously: true)
        XCTAssertTrue(loaded.isLoadingHistory)
        XCTAssertFalse(loaded.canSend)
        for _ in 0..<200 where loaded.isLoadingHistory { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertFalse(loaded.isLoadingHistory)
        XCTAssertEqual(loaded.conversations.first?.title, "Loaded in background")
    }

    func testRussianIdentityIsFixedForForeignQuestionsAndLongPCContextIsSelective() throws {
        let client = DeepSeekClient(configuration: .init(apiKey: "test"))
        for question in ["Explain the sky in English", "为什么冰会融化？"] {
            let request = try client.makeRequest(messages: [ChatMessage(role: .user, content: question)], thinking: true, systemInstruction: "", searchContext: "")
            let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
            let system = try XCTUnwrap((body["messages"] as? [[String: Any]])?.first?["content"] as? String)
            XCTAssertTrue(system.contains("Honer AI"))
            XCTAssertTrue(system.contains("Владислав"))
            XCTAssertTrue(system.contains("по-русски"))
            XCTAssertFalse(system.contains("PowerShell"))
        }
        XCTAssertTrue(HonerIdentity.context(for: "Как приготовить суп?").isEmpty)
        XCTAssertTrue(HonerIdentity.context(for: "Кто твой создатель?").contains("из России"))
        let pc = HonerIdentity.context(for: "Что умеет настольный Honer PK Agent?")
        XCTAssertTrue(pc.contains("PowerShell"))
        XCTAssertTrue(pc.contains("закрыто"))
        XCTAssertTrue(pc.contains("Мобильное Honer AI не заявляет управление компьютером"))
    }

    @MainActor
    func testProfileIsIncludedAsDataAndForeignReasoningIsNormalized() async throws {
        let client = NormalizingFixture()
        let store = ChatStore(configuration: .init(apiKey: "test"), client: client, storageURL: historyURL())
        store.profileName = "Алексей"
        store.draft = "Explain ice melting"; store.send()
        try await idle(store)
        XCTAssertTrue(client.receivedInstructions.contains("Алексей"))
        XCTAssertEqual(store.messages.last?.content, "Лёд тает, когда получает тепло.")
        XCTAssertEqual(store.messages.last?.reasoning, "Нужно объяснить переход льда в жидкое состояние.")
        XCTAssertEqual(store.messages.last?.reasoningWasTranslated, true)
        XCTAssertEqual(client.normalizedKinds, [false, true])
    }

    func testRussianPolicyAllowsCodeAndNamesButDetectsEnglishAndChineseProse() {
        XCTAssertTrue(RussianTextPolicy.needsNormalization("The temperature is rising and the ice absorbs heat."))
        XCTAssertTrue(RussianTextPolicy.needsNormalization("冰吸收热量后分子运动加剧，最终从固态转变为液态，这是熔化。"))
        XCTAssertFalse(RussianTextPolicy.needsNormalization("Вот пример на Swift:\n```swift\nlet text = \"Hello world\"\nprint(text)\n```"))
        XCTAssertFalse(RussianTextPolicy.needsNormalization("Honer AI отвечает на русском языке и использует DeepSeek."))
    }

    func testWeatherIntentAndRussianWeatherDataUseCorrectCityDatesAndUnits() throws {
        XCTAssertEqual(WeatherIntent.location(in: "Какая сегодня погода в Клину?"), "Клин")
        XCTAssertEqual(WeatherIntent.location(in: "Weather in Klin tomorrow"), "Клин")
        XCTAssertNil(WeatherIntent.location(in: "Почему лёд тает при нагревании?"))
        let json = """
        {"timezone":"Europe/Moscow","current":{"time":"2026-09-23T10:00","temperature_2m":14.2,"apparent_temperature":13.1,"relative_humidity_2m":80,"weather_code":3,"wind_speed_10m":2.5},"daily":{"time":["2026-09-23","2026-09-24"],"temperature_2m_max":[16.2,17.4],"temperature_2m_min":[9.3,10.1],"precipitation_probability_max":[20,40],"weather_code":[3,61]}}
        """
        let forecast = try JSONDecoder().decode(WeatherForecast.self, from: Data(json.utf8))
        let russian = forecast.russianDescription(location: "Клин, Московская область")
        XCTAssertTrue(russian.contains("2026-09-23T10:00"))
        XCTAssertTrue(russian.contains("14.2 °C"))
        XCTAssertTrue(russian.contains("2.5 м/с"))
        XCTAssertTrue(russian.contains("2026-09-24: 10.1…17.4 °C; дождь"))
        XCTAssertTrue(russian.contains("Europe/Moscow"))
    }

    @MainActor
    func testExplicitWeatherQueryFetchesEvenWhenSearchToggleIsOff() async throws {
        let search = WeatherSearchFixture()
        let store = ChatStore(configuration: .init(apiKey: "test"), client: NormalizingFixture(), searchClient: search, storageURL: historyURL())
        store.searchEnabled = false
        store.draft = "Какая погода в Клину сегодня?"; store.send()
        try await idle(store)
        XCTAssertEqual(search.queries.count, 1)
        XCTAssertEqual(store.messages.last?.sources.count, 1)
        store.draft = "Что такое лёд?"; store.send()
        try await idle(store)
        XCTAssertEqual(search.queries.count, 1)
    }

    func testPageExtractionAndSearchRelevanceRejectBingQuizNoise() {
        let html = "<html><head><title>Экран телефона</title><script>SECRET_SCRIPT</script></head><body><nav>MENU</nav><article><h1>iPhone 13</h1><p>Display: 6.1 inches &amp; OLED.</p><p>Русский текст &#1087;ро экран.</p></article></body></html>"
        let text = WebPageText.extract(html)
        XCTAssertTrue(text.contains("6.1 inches & OLED"))
        XCTAssertTrue(text.contains("про экран"))
        XCTAssertFalse(text.contains("SECRET_SCRIPT"))
        XCTAssertFalse(text.contains("MENU"))
        XCTAssertFalse(WebPageText.isPublicWebURL(URL(string: "http://127.0.0.1/private")!))
        let noise = WebSource(title: "Bing homepage quiz answers", url: URL(string: "https://reddit.com/bing")!, snippet: "Bing quiz latest answers and rewards")
        XCTAssertEqual(SearchRelevance.score(source: noise, query: "погода Клин"), 0)
        let good = WebSource(title: "Клин: прогноз погоды", url: URL(string: "https://example.com/klin")!, snippet: "Погода в Клину сегодня")
        XCTAssertGreaterThan(SearchRelevance.score(source: good, query: "погода Клин"), 0)
        let source = WebSource(title: "Прочитано", url: good.url, snippet: "Выдержка", content: "Полный извлечённый текст", fetchedAt: Date(timeIntervalSince1970: 0))
        let context = WebSearchClient.context([good, source])
        XCTAssertTrue(context.contains("[1]")); XCTAssertTrue(context.contains("[2]"))
        XCTAssertTrue(context.contains("страница не прочитана")); XCTAssertTrue(context.contains("Полный извлечённый текст"))
    }

    func testDuckDuckGoResultsDecodeLinksAndSnippets() {
        let html = """
        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Farticle&amp;rut=abc">Полезная <b>страница</b></a>
        <a class="result__snippet" href="#">Текст &amp; данные.</a>
        """
        let results = WebPageText.searchResults(html)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.url.absoluteString, "https://example.com/article")
        XCTAssertEqual(results.first?.title, "Полезная страница")
        XCTAssertEqual(results.first?.snippet, "Текст & данные.")
    }

    func testUnavailableSearchUsesOnlyActuallyReadDiscoveredPages() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ResearchURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let client = WebSearchClient(session: session, urlDiscovery: ResearchDiscoveryFixture())
        let sources = try await client.search("Find telescope specifications")
        XCTAssertEqual(sources.map(\.url.absoluteString), ["https://research.example/specifications"])
        XCTAssertEqual(sources.first?.title, "Telescope specifications")
        XCTAssertTrue(sources.first?.content?.contains("Aperture is 120 mm") == true)
        XCTAssertNotNil(sources.first?.fetchedAt)
        XCTAssertTrue(sources.first?.snippet.contains("не поисковая выдержка") == true)
        XCTAssertFalse(WebSearchClient.context(sources).contains("missing"))
    }

    func testBriefPersonalizationAddsConcreteAnswerLengthRule() throws {
        let client = DeepSeekClient(configuration: .init(apiKey: "test"))
        let request = try client.makeRequest(messages: [ChatMessage(role: .user, content: "Почему небо голубое?")], thinking: true,
                                             systemInstruction: "Обращайся ко мне на ты, отвечай кратко по-русски.", searchContext: "")
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        let system = try XCTUnwrap((body["messages"] as? [[String: Any]])?.first?["content"] as? String)
        XCTAssertTrue(system.contains("1–3 коротких предложения"))
        XCTAssertTrue(system.contains("когда в текущем вопросе прямо просят подробности"))
        XCTAssertFalse(PersonalizationPolicy.prefersBriefAnswers("Отвечай подробно, не кратко."))
    }

    @MainActor
    func testVideoUsesFramesOnlyAndBackupRestoresFramesAndSharedCleanup() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("HonorPKAgent/Attachments")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let movie = directory.appendingPathComponent("video-test-\(UUID()).mp4")
        let frame = directory.appendingPathComponent("frame-test-\(UUID()).jpg")
        let movieBytes = Data("WHOLE_MOVIE_MUST_NOT_BE_SENT".utf8)
        try movieBytes.write(to: movie); try Data([0xff, 0xd8, 0xff, 0xd9]).write(to: frame)
        defer { try? FileManager.default.removeItem(at: movie); try? FileManager.default.removeItem(at: frame) }
        let video = MessageAttachment(name: "clip.mp4", kind: .video, extractedText: "Кадр на 1.0 секунде", localPath: movie.path, videoFramePaths: [frame.path])
        let message = ChatMessage(role: .user, content: "Что видно?", attachments: [video])
        let request = try DeepSeekClient(configuration: .init(apiKey: "test")).makeRequest(messages: [message], thinking: false, systemInstruction: "", searchContext: "")
        let body = String(decoding: try XCTUnwrap(request.httpBody), as: UTF8.self)
        XCTAssertTrue(body.contains("image_url"))
        XCTAssertFalse(body.contains(movieBytes.base64EncodedString()))
        XCTAssertTrue(body.contains("аудио не передано"))
        let source = Conversation(messages: [message])
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: historyURL())
        store.conversations = [source]; store.selectedConversationID = source.id
        let branchID = try XCTUnwrap(store.forkConversation(at: message.id))
        let backup = try store.exportData()
        defer { try? FileManager.default.removeItem(at: backup) }
        let restored = ChatStore(configuration: .init(apiKey: "test"), storageURL: historyURL())
        try restored.importData(from: backup)
        let restoredVideo = try XCTUnwrap(restored.conversations.first?.messages.first?.attachments.first)
        XCTAssertEqual(restoredVideo.resolvedFrameURLs.count, 1)
        XCTAssertNotNil(restoredVideo.resolvedURL)
        restored.deleteChats(ids: Set(restored.conversations.map(\.id)))
        store.deleteChats(ids: [source.id])
        XCTAssertTrue(FileManager.default.fileExists(atPath: frame.path))
        store.deleteChats(ids: [branchID])
        XCTAssertFalse(FileManager.default.fileExists(atPath: frame.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: movie.path))
    }

    @MainActor private func idle(_ store: ChatStore) async throws {
        for _ in 0..<200 {
            if !store.isGenerating { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("Generation did not complete")
    }

    private func historyURL() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("Honer10-\(UUID()).json") }
}

private final class NormalizingFixture: DeepSeekStreaming, RussianTextNormalizing {
    var receivedInstructions = ""
    var normalizedKinds: [Bool] = []
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String, searchContext: String) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        receivedInstructions = systemInstruction
        return AsyncThrowingStream { continuation in
            continuation.yield(.init(reasoning: "We need to explain how ice changes from solid to liquid."))
            continuation.yield(.init(content: "Ice melts when it receives enough heat energy."))
            continuation.yield(.init(finishReason: "stop")); continuation.finish()
        }
    }
    func normalizeRussian(_ text: String, reasoning: Bool) async throws -> String {
        normalizedKinds.append(reasoning)
        return reasoning ? "Нужно объяснить переход льда в жидкое состояние." : "Лёд тает, когда получает тепло."
    }
}

private final class WeatherSearchFixture: WebSearching {
    var queries: [String] = []
    func search(_ query: String) async throws -> [WebSource] {
        queries.append(query)
        return [WebSource(title: "Клин", url: URL(string: "https://api.open-meteo.com/v1/forecast")!, snippet: "14 °C", content: "Клин: 14 °C", fetchedAt: Date())]
    }
}

private struct ResearchDiscoveryFixture: SearchURLDiscovering {
    func candidates(for query: String) async throws -> [URL] {
        [URL(string: "https://research.example/specifications")!, URL(string: "https://research.example/missing")!]
    }
}

private final class ResearchURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        let status: Int
        let body: String
        switch url.host {
        case "www.bing.com":
            status = 200
            body = "<rss><channel><item><title>Bing homepage quiz answers</title><link>https://reddit.com/bing</link><description>Bing rewards quiz.</description></item></channel></rss>"
        case "html.duckduckgo.com": status = 200; body = "<html><p>Verify you are human</p></html>"
        case "research.example" where url.path == "/specifications":
            status = 200
            body = "<html><title>Telescope specifications</title><article><h1>Technical specifications</h1><p>Aperture is 120 mm. Focal length is 900 mm. This technical document describes the optical equipment and the supplied mounting assembly in detail.</p></article></html>"
        default: status = 404; body = "Page not found"
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/html; charset=utf-8"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
