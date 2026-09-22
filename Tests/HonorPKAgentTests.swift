import XCTest
@testable import HonorPKAgent

final class HonorPKAgentTests: XCTestCase {
    func testSSEDecoderHandlesSplitUTF8CRLFMultilineAndComments() {
        var decoder = SSEDecoder()
        let wire = ": keepalive\r\ndata: {\"choices\":\r\ndata: [{\"delta\":{\"content\":\"Привет 🌍\"}}]}\r\n\r\ndata: [DONE]\n\n"
        let events = Array(wire.utf8).compactMap { decoder.append($0) }
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[1], "[DONE]")
        let client = DeepSeekClient(configuration: DeepSeekConfiguration(apiKey: "test-key"))
        XCTAssertEqual(try client.decodeEvent(events[0])?.content, "Привет 🌍")
        XCTAssertNil(decoder.finish())
    }

    func testSSEDecoderFlushesLastEventAtEOF() {
        var decoder = SSEDecoder()
        for byte in "data: {\"choices\":[]}".utf8 { _ = decoder.append(byte) }
        XCTAssertEqual(decoder.finish(), "{\"choices\":[]}")
    }

    func testRequestUsesCurrentModelThinkingAndDoesNotReplayReasoning() throws {
        let configuration = DeepSeekConfiguration(apiKey: "test-key")
        let client = DeepSeekClient(configuration: configuration)
        let input = [ChatMessage(role: .user, content: "Привет"),
                     ChatMessage(role: .assistant, content: "Здравствуйте", reasoning: "private-model-reasoning"),
                     ChatMessage(role: .user, content: "Продолжай")]
        let request = try client.makeRequest(messages: input, thinking: true, systemInstruction: "Кратко", searchContext: "")
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(request.url?.absoluteString, "https://api.deepseek.com/chat/completions")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-key")
        XCTAssertEqual(body["model"] as? String, "deepseek-flash")
        XCTAssertEqual((body["thinking"] as? [String: String])?["type"], "enabled")
        XCTAssertFalse(String(decoding: request.httpBody!, as: UTF8.self).contains("private-model-reasoning"))
        let plain = try client.makeRequest(messages: input, thinking: false, systemInstruction: "", searchContext: "")
        let plainBody = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(plain.httpBody)) as? [String: Any])
        XCTAssertEqual((plainBody["thinking"] as? [String: String])?["type"], "disabled")
        XCTAssertNil(plainBody["reasoning_effort"])
    }

    func testRequestSendsImageBytesAndDocumentText() throws {
        let imageURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).jpg")
        let bytes = Data([0xff, 0xd8, 0xff, 0xd9])
        try bytes.write(to: imageURL)
        defer { try? FileManager.default.removeItem(at: imageURL) }
        let message = ChatMessage(role: .user, content: "Сравни", attachments: [
            MessageAttachment(name: "photo.jpg", kind: .image, localPath: imageURL.path),
            MessageAttachment(name: "note.txt", kind: .text, extractedText: "Факт из файла")
        ])
        let client = DeepSeekClient(configuration: DeepSeekConfiguration(apiKey: "test-key"))
        let request = try client.makeRequest(messages: [message], thinking: false, systemInstruction: "", searchContext: "")
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        let parts = try XCTUnwrap(messages.last?["content"] as? [[String: Any]])
        XCTAssertTrue((parts[0]["text"] as? String ?? "").contains("Факт из файла"))
        XCTAssertEqual((parts[1]["image_url"] as? [String: String])?["url"], "data:image/jpeg;base64,\(bytes.base64EncodedString())")
    }

    func testSearchParserFiltersUnsafeURLsAndDeduplicates() {
        let xml = """
        <rss><channel><item><title>A &amp; B</title><link>https://example.com/a</link><description><![CDATA[<b>Text</b>]]></description></item>
        <item><title>Duplicate</title><link>https://example.com/a</link></item>
        <item><title>Bad</title><link>javascript:alert(1)</link></item></channel></rss>
        """
        let results = RSSResultsParser.parse(Data(xml.utf8))
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].title, "A & B")
        XCTAssertEqual(results[0].snippet, "Text")
    }

    @MainActor
    func testMissingKeyPreservesDraftWithoutCreatingConversation() {
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: ""), storageURL: temporaryHistory())
        store.draft = "Не потеряй мой текст"
        store.send()
        XCTAssertEqual(store.draft, "Не потеряй мой текст")
        XCTAssertTrue(store.conversations.isEmpty)
        XCTAssertFalse(store.isGenerating)
        XCTAssertNotNil(store.errorMessage)
    }

    @MainActor
    func testStreamPersistsContentReasoningAndFeedback() async throws {
        let url = temporaryHistory()
        defer { try? FileManager.default.removeItem(at: url) }
        let client = ImmediateClient(events: [.init(reasoning: "Анализ"), .init(content: "Ответ"), .init(finishReason: "stop")])
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: client, storageURL: url)
        store.draft = "Вопрос"
        store.send()
        try await waitUntilIdle(store)
        let answer = try XCTUnwrap(store.messages.last)
        XCTAssertEqual(answer.content, "Ответ")
        XCTAssertEqual(answer.reasoning, "Анализ")
        XCTAssertGreaterThanOrEqual(answer.reasoningSeconds, 1)
        store.setFeedback(messageID: answer.id, feedback: .like)
        let restored = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), storageURL: url)
        XCTAssertEqual(restored.messages.last?.feedback, .like)
        XCTAssertEqual(restored.messages.last?.content, "Ответ")
    }

    @MainActor
    func testEditIsNonDestructiveUntilSendAndReplacesFollowingTurns() async throws {
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"),
                              client: ImmediateClient(events: [.init(content: "Новый ответ"), .init(finishReason: "stop")]),
                              storageURL: temporaryHistory())
        let original = ChatMessage(role: .user, content: "Первый вопрос")
        let chat = Conversation(title: "Тест", messages: [original, ChatMessage(role: .assistant, content: "Первый ответ"), ChatMessage(role: .user, content: "Второй вопрос")])
        store.conversations = [chat]
        store.selectedConversationID = chat.id
        store.edit(messageID: original.id)
        XCTAssertEqual(store.messages.count, 3)
        store.cancelEditing()
        XCTAssertEqual(store.messages.count, 3)
        store.edit(messageID: original.id)
        store.draft = "Исправленный вопрос"
        store.send()
        try await waitUntilIdle(store)
        XCTAssertEqual(store.messages.map(\.content), ["Исправленный вопрос", "Новый ответ"])
    }

    @MainActor
    func testStopAndNewChatCannotReceiveOldStreamTokens() async throws {
        let client = ControlledClient()
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: client, storageURL: temporaryHistory())
        store.draft = "Первый чат"
        store.send()
        for _ in 0..<50 where client.continuations.isEmpty { try await Task.sleep(nanoseconds: 5_000_000) }
        let firstID = try XCTUnwrap(store.selectedConversationID)
        let oldStream = try XCTUnwrap(client.continuations.first)
        oldStream.yield(.init(content: "Начало"))
        try await Task.sleep(nanoseconds: 20_000_000)
        store.newChat()
        oldStream.yield(.init(content: "Устаревший токен"))
        oldStream.finish()
        try await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertNil(store.selectedConversationID)
        XCTAssertFalse(store.isGenerating)
        let oldAnswer = try XCTUnwrap(store.conversations.first(where: { $0.id == firstID })?.messages.last)
        XCTAssertTrue(oldAnswer.isInterrupted)
        XCTAssertFalse(oldAnswer.content.contains("Устаревший токен"))
    }

    @MainActor
    func testSearchFailureDoesNotGenerateFabricatedAnswer() async throws {
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"),
                              client: ImmediateClient(events: [.init(content: "Нельзя показывать")]),
                              searchClient: FailingSearch(), storageURL: temporaryHistory())
        store.searchEnabled = true
        store.draft = "Новости"
        store.send()
        try await waitUntilIdle(store)
        XCTAssertEqual(store.messages.last?.content, "")
        XCTAssertNotNil(store.messages.last?.error)
        XCTAssertTrue(store.messages.last?.sources.isEmpty ?? false)
    }

    @MainActor
    private func waitUntilIdle(_ store: ChatStore) async throws {
        for _ in 0..<200 {
            if !store.isGenerating { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("Generation did not finish")
    }

    private func temporaryHistory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("HonorTest-\(UUID()).json")
    }
}

private struct ImmediateClient: DeepSeekStreaming {
    var events: [DeepSeekDelta]
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String, searchContext: String) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        AsyncThrowingStream { continuation in
            events.forEach { continuation.yield($0) }
            continuation.finish()
        }
    }
}

private final class ControlledClient: DeepSeekStreaming {
    var continuations: [AsyncThrowingStream<DeepSeekDelta, Error>.Continuation] = []
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String, searchContext: String) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        AsyncThrowingStream { continuation in continuations.append(continuation) }
    }
}

private struct FailingSearch: WebSearching {
    func search(_ query: String) async throws -> [WebSource] { throw HonorError.searchUnavailable }
}
