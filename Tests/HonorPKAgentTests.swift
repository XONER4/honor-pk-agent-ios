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

    func testSSEDecoderAcceptsBOMAndCarriageReturnOnlyFrames() {
        var decoder = SSEDecoder()
        let input = "\u{FEFF}data: first\r\rdata: second\r\rdata: [DONE]"
        let events = input.utf8.compactMap { decoder.append($0) }
        XCTAssertEqual(events, ["first", "second"])
        XCTAssertEqual(decoder.finish(), "[DONE]")
    }

    func testStreamCompletesAtFinishReasonBeforeLaterTransportFailure() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [TerminatingStreamURLProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let client = DeepSeekClient(configuration: DeepSeekConfiguration(apiKey: "test", baseURL: URL(string: "https://honor-stream.test/complete")!), session: session)
        var content = ""
        var finished = false
        for try await event in client.stream(messages: [ChatMessage(role: .user, content: "Hello")], thinking: false, systemInstruction: "", searchContext: "") {
            content += event.content
            finished = finished || event.finishReason == "stop"
        }
        XCTAssertEqual(content, "Complete answer")
        XCTAssertTrue(finished)
    }

    func testStreamRejectsTransportFailureBeforeFinishReason() async {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [TerminatingStreamURLProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let client = DeepSeekClient(configuration: DeepSeekConfiguration(apiKey: "test", baseURL: URL(string: "https://honor-stream.test/incomplete")!), session: session)
        do {
            for try await _ in client.stream(messages: [ChatMessage(role: .user, content: "Hello")], thinking: false, systemInstruction: "", searchContext: "") { }
            XCTFail("Premature transport failure must remain an error")
        } catch { /* Expected: incomplete responses must not silently look finished. */ }
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

    @MainActor
    func testMissingAttachmentFileDoesNotKillTheWholeRequest() throws {
        // Если файл вложения исчез (очистка, смена песочницы после обновления,
        // восстановление из резервной копии), запрос раньше падал целиком — пользователь
        // не получал ответа вообще. Теперь такое вложение пропускается, а модель
        // получает пометку, чтобы честно сказать об этом.
        let missing = MessageAttachment(name: "пропавшее.jpg", kind: .image,
                                        localPath: FileManager.default.temporaryDirectory
                                            .appendingPathComponent("нет-такого-\(UUID()).jpg").path)
        let message = ChatMessage(role: .user, content: "Что на фото?", attachments: [missing])
        let client = DeepSeekClient(configuration: DeepSeekConfiguration(apiKey: "test-key"))
        let request = try client.makeRequest(messages: [message], thinking: false,
                                             systemInstruction: "", searchContext: "")
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        let content = messages.last?["content"]
        let text = (content as? String) ?? ((content as? [[String: Any]])?.first?["text"] as? String) ?? ""
        XCTAssertTrue(text.contains("пропавшее.jpg"), "Про недоступное вложение не сказано: [\(text)]")
        XCTAssertTrue(text.contains("недоступно") || text.contains("не удалось"),
                      "Нет пояснения, что вложение недоступно: [\(text)]")

        // Пустое вложение (без текста и без файла) тоже не должно ломать запрос.
        let empty = MessageAttachment(name: "пустое.pdf", kind: .document)
        let second = ChatMessage(role: .user, content: "Прочитай", attachments: [empty])
        _ = try client.makeRequest(messages: [second], thinking: false,
                                   systemInstruction: "", searchContext: "")
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
        let client = ImmediateClient(events: [.init(reasoning: "Анализ вопроса"), .init(content: "Ответ готов."), .init(finishReason: "stop")])
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: client, storageURL: url)
        store.draft = "Вопрос"
        store.send()
        try await waitUntilIdle(store)
        let answer = try XCTUnwrap(store.messages.last)
        XCTAssertEqual(answer.content, "Ответ готов.")
        XCTAssertEqual(answer.reasoning, "Анализ вопроса")
        XCTAssertGreaterThanOrEqual(answer.reasoningSeconds, 1)
        store.setFeedback(messageID: answer.id, feedback: .like)
        store.persistNow()
        let restored = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), storageURL: url)
        XCTAssertEqual(restored.messages.last?.feedback, .like)
        XCTAssertEqual(restored.messages.last?.content, "Ответ готов.")
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
        try await waitForContinuation(client)
        let firstID = try XCTUnwrap(store.selectedConversationID)
        let oldStream = try XCTUnwrap(client.continuations.first)
        oldStream.yield(.init(content: "Начало ответа."))
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
        // Раньше неудача поиска полностью отменяла ответ: пользователь видел ошибку
        // «Поиск недоступен» и не получал ничего. Теперь приложение отвечает по своим
        // знаниям, но ЧЕСТНО помечает ответ: свежих данных из интернета в нём нет,
        // и источников у такого ответа быть не должно.
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"),
                              client: ImmediateClient(events: [.init(content: "Отвечаю по своим знаниям.")]),
                              searchClient: FailingSearch(), storageURL: temporaryHistory())
        store.searchEnabled = true
        store.draft = "Новости"
        store.send()
        try await waitUntilIdle(store)
        XCTAssertEqual(store.messages.last?.content, "Отвечаю по своим знаниям.")
        XCTAssertNil(store.messages.last?.error)
        XCTAssertTrue(store.messages.last?.sources.isEmpty ?? false,
                      "Без открытых страниц источников быть не должно")
        XCTAssertTrue(store.messages.last?.searchFailed ?? false,
                      "Ответ без свежих данных обязан быть помечен")
    }

    @MainActor
    func testImportNeverFollowsExternalLocalPaths() throws {
        let sourceURL = temporaryHistory()
        let privatePath = FileManager.default.temporaryDirectory.appendingPathComponent("external-private-photo.jpg").path
        let attachment = MessageAttachment(name: "photo.jpg", kind: .image, localPath: privatePath)
        let conversation = Conversation(messages: [ChatMessage(role: .user, content: "Photo", attachments: [attachment])])
        let archive = HistoryArchive(conversations: [conversation], selectedConversationID: conversation.id)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(archive).write(to: sourceURL)
        defer { try? FileManager.default.removeItem(at: sourceURL) }
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), storageURL: temporaryHistory())
        try store.importData(from: sourceURL)
        XCTAssertNil(store.conversations.first?.messages.first?.attachments.first?.localPath)
        XCTAssertEqual(store.conversations.first?.messages.first?.attachments.first?.name, "photo.jpg")
    }

    @MainActor
    func testRelaunchMarksPartiallySavedStreamInterrupted() throws {
        let url = temporaryHistory()
        let answer = ChatMessage(role: .assistant, content: "Частичный ответ")
        let conversation = Conversation(messages: [ChatMessage(role: .user, content: "Вопрос"), answer])
        let archive = HistoryArchive(conversations: [conversation], selectedConversationID: conversation.id, inFlightMessageID: answer.id)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(archive).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), storageURL: url)
        XCTAssertEqual(store.messages.last?.content, "Частичный ответ")
        XCTAssertEqual(store.messages.last?.isInterrupted, true)
        XCTAssertFalse(store.isGenerating)
    }

    @MainActor
    func testImportMarksExportedInFlightAnswerInterrupted() throws {
        let answer = ChatMessage(role: .assistant, content: "Partial imported answer")
        let source = Conversation(messages: [ChatMessage(role: .user, content: "Question"), answer])
        let archive = HistoryArchive(conversations: [source], selectedConversationID: source.id, inFlightMessageID: answer.id)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let url = temporaryHistory()
        try encoder.encode(archive).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())
        try store.importData(from: url)
        XCTAssertEqual(store.conversations.first?.messages.last?.content, "Partial imported answer")
        XCTAssertEqual(store.conversations.first?.messages.last?.isInterrupted, true)
        XCTAssertFalse(store.isGenerating)
    }

    @MainActor
    func testForkPreservesSourceAndCopiesOnlyThroughSelectedMessageWithFreshIDs() throws {
        let attachment = MessageAttachment(name: "note.txt", kind: .text, extractedText: "Source")
        let source = Conversation(title: "Original", messages: [
            ChatMessage(role: .user, content: "Question", attachments: [attachment]),
            ChatMessage(role: .assistant, content: "Answer", sources: [WebSource(title: "Source", url: URL(string: "https://example.com")!, snippet: "Text")]),
            ChatMessage(role: .user, content: "Later")
        ], pinned: true)
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())
        store.conversations = [source]
        store.selectedConversationID = source.id
        let branchID = try XCTUnwrap(store.forkConversation(at: source.messages[1].id))
        XCTAssertEqual(store.conversations.first(where: { $0.id == source.id }), source)
        let branch = try XCTUnwrap(store.selectedConversation)
        XCTAssertEqual(branch.id, branchID)
        XCTAssertEqual(branch.messages.map(\.content), ["Question", "Answer"])
        XCTAssertTrue(Set(branch.messages.map(\.id)).isDisjoint(with: Set(source.messages.map(\.id))))
        XCTAssertNotEqual(branch.messages[0].attachments[0].id, attachment.id)
        XCTAssertNotEqual(branch.messages[1].sources[0].id, source.messages[1].sources[0].id)
        XCTAssertEqual(branch.parentConversationID, source.id)
        XCTAssertEqual(branch.forkedAtMessageID, source.messages[1].id)
        XCTAssertFalse(branch.pinned)
        XCTAssertFalse(store.isGenerating)
    }

    @MainActor
    func testDeletingSourceKeepsSharedBranchPhotoUntilLastReferenceDeleted() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let imageURL = directory.appendingPathComponent("test-\(UUID()).jpg")
        try Data([0xff, 0xd8, 0xff, 0xd9]).write(to: imageURL)
        defer { try? FileManager.default.removeItem(at: imageURL) }
        let message = ChatMessage(role: .user, content: "Image", attachments: [MessageAttachment(name: "photo.jpg", kind: .image, localPath: imageURL.path)])
        let source = Conversation(messages: [message])
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())
        store.conversations = [source]; store.selectedConversationID = source.id
        let branchID = try XCTUnwrap(store.forkConversation(at: message.id))
        store.deleteChats(ids: [source.id])
        XCTAssertTrue(FileManager.default.fileExists(atPath: imageURL.path))
        store.deleteChats(ids: [branchID])
        XCTAssertFalse(FileManager.default.fileExists(atPath: imageURL.path))
    }

    @MainActor
    func testDraftRemovalAndNewChatCleanOrphansButPreserveEditedAndBranchedFiles() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPKAgent/Attachments")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let urls = (0..<3).map { _ in directory.appendingPathComponent("draft-test-\(UUID()).jpg") }
        for url in urls { try Data([0xff, 0xd8, 0xff, 0xd9]).write(to: url) }
        defer { for url in urls { try? FileManager.default.removeItem(at: url) } }
        let items = urls.map { MessageAttachment(name: "photo.jpg", kind: .image, localPath: $0.path) }
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())

        store.attachments = [items[0]]
        store.attachments.removeAll { $0.id == items[0].id }
        XCTAssertFalse(FileManager.default.fileExists(atPath: urls[0].path))

        store.attachments = [items[1]]
        store.newChat()
        XCTAssertFalse(FileManager.default.fileExists(atPath: urls[1].path))

        let message = ChatMessage(role: .user, content: "Keep shared photo", attachments: [items[2]])
        let source = Conversation(messages: [message])
        store.conversations = [source]; store.selectedConversationID = source.id
        store.edit(messageID: message.id)
        store.attachments.removeAll()
        XCTAssertTrue(FileManager.default.fileExists(atPath: urls[2].path))
        store.cancelEditing()
        let branchID = try XCTUnwrap(store.forkConversation(at: message.id))
        store.deleteChats(ids: [source.id])
        let copiedMessage = try XCTUnwrap(store.selectedConversation?.messages.first)
        store.edit(messageID: copiedMessage.id)
        store.newChat()
        XCTAssertTrue(FileManager.default.fileExists(atPath: urls[2].path))
        store.deleteChats(ids: [branchID])
        XCTAssertFalse(FileManager.default.fileExists(atPath: urls[2].path))
    }

    @MainActor
    func testExplicitMemoryIsValidatedEditablePersistentAndPreservedWhenChatsCleared() throws {
        let url = temporaryHistory()
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        XCTAssertFalse(store.addMemory(" \n"))
        XCTAssertFalse(store.addMemory(String(repeating: "x", count: ChatStore.maximumMemoryLength + 1)))
        XCTAssertTrue(store.addMemory("  Обращайся ко мне на ты  "))
        XCTAssertFalse(store.addMemory("обращайся ко мне на ты"))
        let id = try XCTUnwrap(store.memories.first?.id)
        XCTAssertTrue(store.updateMemory(id: id, text: "Отвечай кратко"))
        store.memoryEnabled = false
        store.clearAllChats()
        store.persistNow()
        let restored = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        XCTAssertEqual(restored.memories.map(\.text), ["Отвечай кратко"])
        XCTAssertFalse(restored.memoryEnabled)
        restored.deleteMemory(id: id)
        XCTAssertTrue(restored.memories.isEmpty)
    }

    @MainActor
    func testMemoryToggleControlsRealRequestInstructionsAndDoesNotExtractReplies() async throws {
        let client = CapturingClient()
        let store = ChatStore(configuration: .init(apiKey: "test"), client: client, storageURL: temporaryHistory())
        store.systemInstruction = "Будь вежлив"
        store.addMemory("Мой любимый цвет — синий")
        store.draft = "Первый вопрос"; store.send()
        try await waitUntilIdle(store)
        XCTAssertTrue(client.instructions[0].contains("Мой любимый цвет — синий"))
        XCTAssertTrue(client.instructions[0].contains("Будь вежлив"))
        XCTAssertEqual(store.memories.count, 1)
        store.memoryEnabled = false
        store.draft = "Второй вопрос"; store.send()
        try await waitUntilIdle(store)
        XCTAssertEqual(client.instructions[1], "Будь вежлив")
        XCTAssertEqual(store.memories.count, 1)
    }

    @MainActor
    func testLegacyArchiveLoadsWithoutMemoryOrBranchMetadata() throws {
        let url = temporaryHistory()
        let old = HistoryArchive(conversations: [Conversation(title: "Old")], selectedConversationID: nil)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(old)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("memories"))
        try data.write(to: url)
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        XCTAssertEqual(store.conversations.first?.title, "Old")
        XCTAssertTrue(store.memories.isEmpty)
        XCTAssertTrue(store.memoryEnabled)
        XCTAssertNil(store.conversations.first?.parentConversationID)
    }

    @MainActor
    func testAsyncArchiveRoundTripRestoresMemoryWithoutDuplicates() async throws {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())
        store.addMemory("Люблю подробные примеры")
        store.memoryEnabled = false
        store.conversations = [Conversation(title: "Exported", messages: [ChatMessage(role: .user, content: "Hello")])]
        let url = try await store.exportDataAsync()
        defer { try? FileManager.default.removeItem(at: url) }
        let restored = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())
        try await restored.importDataAsync(from: url)
        try await restored.importDataAsync(from: url)
        XCTAssertEqual(restored.conversations.count, 1)
        XCTAssertEqual(restored.memories.map(\.text), ["Люблю подробные примеры"])
        XCTAssertFalse(restored.memoryEnabled)
    }

    @MainActor
    func testOverCapacityMemoryImportLeavesExistingChatsAndMemoriesUntouched() throws {
        // Лимит памяти теперь 5000 записей: наполнять его через addMemory в тесте
        // слишком дорого. Проверяем сам запрет импорта: архив с числом записей
        // больше лимита должен быть отклонён целиком, без частичного импорта.
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: temporaryHistory())
        let old = Conversation(title: "Keep me")
        store.conversations = [old]
        XCTAssertTrue(store.addMemory("Уже сохранённый факт"))
        let overload = (0...ChatStore.maximumMemoryCount).map { HonorMemory(text: "Memory \($0)") }
        let incoming = HistoryArchive(conversations: [Conversation(title: "Do not partially import")],
                                      selectedConversationID: nil, memories: overload)
        let url = temporaryHistory()
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(incoming).write(to: url)
        XCTAssertThrowsError(try store.importData(from: url))
        XCTAssertEqual(store.conversations, [old])
        XCTAssertEqual(store.memories.count, 1)
    }

    @MainActor
    func testStopFlushesBufferedTokensAndDeletedConversationCannotReturnFromPendingWrites() async throws {
        let url = temporaryHistory()
        let client = ControlledClient()
        let store = ChatStore(configuration: .init(apiKey: "test"), client: client, storageURL: url)
        store.draft = "Delete this conversation"; store.send()
        try await waitForContinuation(client)
        let firstID = try XCTUnwrap(store.selectedConversationID)
        let firstStream = try XCTUnwrap(client.continuations.first)
        firstStream.yield(.init(content: "Первая часть. "))
        firstStream.yield(.init(content: "вторая часть."))
        try await Task.sleep(nanoseconds: 10_000_000)
        store.stop()
        XCTAssertEqual(store.messages.last?.content, "Первая часть. вторая часть.")
        store.deleteChats(ids: [firstID])
        firstStream.yield(.init(content: "STALE")); firstStream.finish()
        print("HONER_STOP after delete: generating=\(store.isGenerating) canSend=\(store.canSend) "
            + "conversations=\(store.conversations.count) conts=\(client.continuations.count) error=\(store.errorMessage ?? "nil")")
        store.draft = "Keep this conversation"; store.send()
        print("HONER_STOP after send: generating=\(store.isGenerating) conversations=\(store.conversations.count) conts=\(client.continuations.count)")
        try await waitForContinuation(client, count: 2)
        XCTAssertEqual(client.continuations.count, 2)
        client.continuations.last?.yield(.init(content: "Свежий ответ."))
        client.continuations.last?.finish()
        try await waitUntilIdle(store)
        store.persistNow()
        let restored = ChatStore(configuration: .init(apiKey: "test"), storageURL: url)
        XCTAssertEqual(restored.conversations.count, 1)
        XCTAssertFalse(restored.conversations.contains(where: { $0.id == firstID }))
        XCTAssertEqual(restored.messages.last?.content, "Свежий ответ.")
    }

    @MainActor
    func testEmptyAnswerNeverLeavesTheChatSilent() async throws {
        // Клиент, который вообще ничего не отдаёт: раньше в чате не появлялось
        // ни текста, ни ошибки — пользователь видел пустоту и «молчание».
        let client = ImmediateClient(events: [.init(finishReason: "stop")])
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"),
                              client: client, storageURL: temporaryHistory())
        store.reasoningEnabled = true
        store.draft = "Ку"
        store.send()
        try await waitUntilIdle(store)
        let answer = try XCTUnwrap(store.messages.last)
        XCTAssertTrue(answer.role == .assistant)
        let hasText = answer.content.count > 3
        let hasError = !(answer.error ?? "").isEmpty
        XCTAssertTrue(hasText || hasError,
                      "Пустой ответ обязан показать сообщение: content=[\(answer.content)] error=[\(answer.error ?? "nil")]")
        XCTAssertTrue(store.errorMessage != nil || hasError || hasText)
    }

    func testTypingPaceIsSmoothAndNeverJumps() {
        // Заказчик просил, чтобы текст шёл плавно, без рывков. Раньше скорость
        // переключалась ступенями (×1,8 → ×3 → ×6 → ×10), и печать то ползла, то
        // срывалась вперёд. Теперь скорость меняется непрерывно: соседние кадры
        // показывают почти одинаковое число символов.
        let pace = StreamPace()
        var state = StreamPaceState()
        let frame = 1.0 / 60.0
        var available = 0
        var shown = 0
        var steps: [Int] = []
        // Сервис присылает текст пачками по 60 символов каждые 0,4 с (≈150 символов/с).
        for index in 0..<(60 * 8) {
            if index % 24 == 0 { available += 60 }
            let now = pace.step(state: &state, available: available, elapsed: frame, streamOpen: true)
            XCTAssertGreaterThanOrEqual(now, shown, "Видимый текст не может уменьшаться")
            steps.append(now - shown)
            shown = now
        }
        // Первый кадр — не больше пары символов: ответ не «выстреливает».
        XCTAssertLessThanOrEqual(steps.first ?? 0, 2, "Ответ начинается рывком")
        // После разгона (первые 2 с) шаг между соседними кадрами почти не меняется.
        let settled = Array(steps.dropFirst(120))
        let jumps = zip(settled, settled.dropFirst()).map { abs($0 - $1) }
        XCTAssertLessThanOrEqual(jumps.max() ?? 0, 2, "Скорость печати скачет: \(jumps.max() ?? 0) символов между кадрами")
        // Печать не отстаёт от потока больше чем на пару секунд.
        XCTAssertGreaterThan(Double(shown), Double(available) - 150 * 2, "Печать безнадёжно отстала: \(shown) из \(available)")
    }

    func testTypingPaceFinishesTheTailQuicklyAfterTheStreamEnds() {
        // Хвост ответа не должен «висеть»: после конца потока остаток допечатывается
        // быстро и гарантированно — не дольше maximumTail секунд.
        let pace = StreamPace()
        var state = StreamPaceState()
        let frame = 1.0 / 60.0
        // Весь ответ пришёл разом, поток закрыт.
        let total = 2400
        var shown = 0
        var frames = 0
        while shown < total, frames < 600 {
            frames += 1
            shown = pace.step(state: &state, available: total, elapsed: frame, streamOpen: false)
        }
        XCTAssertEqual(shown, total, "Хвост ответа так и не допечатался")
        XCTAssertLessThanOrEqual(Double(frames) * frame, pace.maximumTail + 0.05,
                                 "Хвост дописывается слишком долго: \(Double(frames) * frame) с")
        // Короткий ответ из одного куска всё равно печатается, а не вываливается.
        var short = StreamPaceState()
        let first = pace.step(state: &short, available: 40, elapsed: frame, streamOpen: true)
        XCTAssertLessThanOrEqual(first, 2)
    }

    func testTypedTextGrowsAndSurvivesReplacement() {
        // Печатаемый текст: показанная часть — всегда начало полученной. Если текст
        // заменили (перевод, очистка служебной строки), остаётся общее начало,
        // а не пустой экран.
        var text = TypedText()
        text.setTarget("Привет, мир")
        text.reveal(upTo: 7)
        XCTAssertEqual(text.shown, "Привет,")
        text.setTarget("Привет, мир! Как дела? 👍🏽")
        XCTAssertEqual(text.shown, "Привет,")
        text.reveal(upTo: 1000)
        XCTAssertEqual(text.shown, "Привет, мир! Как дела? 👍🏽")
        XCTAssertTrue(text.isComplete)
        text.setTarget("Привет! Другой ответ")
        XCTAssertEqual(text.shown, "Привет")
        XCTAssertFalse(text.isComplete)
        text.revealAll()
        XCTAssertEqual(text.shown, "Привет! Другой ответ")
        text.setTarget("")
        XCTAssertEqual(text.shown, "")
    }

    @MainActor
    func testPacerAlwaysDeliversTheWholeAnswer() async throws {
        // Главная жалоба: итоговый ответ не дописывался, пока не перезайдёшь.
        // Печать обязана довести текст до конца и сообщить о завершении.
        let pacer = TypingPacer()
        let id = UUID()
        var finished: UUID?
        pacer.onFinished = { finished = $0 }
        pacer.begin(messageID: id)
        pacer.update(content: "Первая часть ответа. ", reasoning: "Думаю.")
        pacer.close(content: "Первая часть ответа. Хвост.", reasoning: "Думаю.")
        for _ in 0..<400 where finished == nil {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(finished, id, "Печать не завершилась")
        XCTAssertEqual(pacer.content, "Первая часть ответа. Хвост.")
        XCTAssertEqual(pacer.reasoning, "Думаю.")
        XCTAssertNil(pacer.messageID)
    }

    @MainActor
    func testStreamingTextGoesToLiveBufferAndReachesTheModelAtTheEnd() async throws {
        // Защита от возврата зависаний: пока идёт печать, текст обязан идти в живой
        // буфер (перерисовывается одна строка), а модель чата обновляется только
        // в конце. Если кто-то снова начнёт писать в модель на каждом куске,
        // этот тест это поймает.
        let client = ControlledClient()
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: client,
                              storageURL: temporaryHistory())
        store.draft = "Проверка печати"
        store.send()
        try await waitForContinuation(client)
        let stream = try XCTUnwrap(client.continuations.first)
        stream.yield(.init(reasoning: "Сначала подумаю."))
        stream.yield(.init(content: "Первая часть. "))
        try await Task.sleep(nanoseconds: 60_000_000)
        // Текст виден пользователю через живой буфер…
        XCTAssertTrue(store.live.content.contains("Первая часть"), "Живой буфер пуст: [\(store.live.content)]")
        XCTAssertTrue(store.live.reasoning.contains("подумаю"))
        // …а модель чата ещё не тронута: значит список сообщений не перерисовывается.
        let assistant = try XCTUnwrap(store.messages.last)
        XCTAssertTrue(assistant.content.isEmpty,
                      "Во время печати модель чата обновляться не должна: [\(assistant.content)]")
        stream.yield(.init(content: "Вторая часть."))
        stream.yield(.init(finishReason: "stop"))
        stream.finish()
        try await waitUntilIdle(store)
        // После завершения текст обязан оказаться в модели и сохраниться.
        let final = try XCTUnwrap(store.messages.last)
        XCTAssertTrue(final.content.contains("Первая часть"), "Ответ потерялся: [\(final.content)]")
        XCTAssertTrue(final.content.contains("Вторая часть"))
        XCTAssertTrue(final.reasoning.contains("подумаю"))
        XCTAssertEqual(store.live.content, "", "Живой буфер должен очищаться после генерации")
    }

    @MainActor
    func testStoppingGenerationClearsLiveBufferSoTextDoesNotMoveToAnotherChat() async throws {
        let client = ControlledClient()
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: client,
                              storageURL: temporaryHistory())
        store.draft = "Первый чат"
        store.send()
        try await waitForContinuation(client)
        let stream = try XCTUnwrap(client.continuations.first)
        stream.yield(.init(content: "Начало ответа."))
        try await Task.sleep(nanoseconds: 60_000_000)
        XCTAssertFalse(store.live.content.isEmpty)
        store.stop()
        // После остановки буфер обязан быть пустым: иначе текст «переехал» бы
        // в другой чат, когда пользователь переключится.
        XCTAssertEqual(store.live.content, "", "Живой буфер не очищен после остановки")
        store.newChat()
        store.draft = "Другой чат"
        store.send()
        try await waitForContinuation(client, count: 2)
        let fresh = try XCTUnwrap(store.messages.last)
        XCTAssertFalse(fresh.content.contains("Начало ответа"),
                       "Текст прошлого чата попал в новый: [\(fresh.content)]")
    }

    @MainActor
    func testToolChainRunsListThenReadAndPrintsTheFinalAnswer() async throws {
        // Цепочка «list_chats → read_chat → ответ». Раньше второй проход шёл без
        // инструментов, поэтому read_chat после списка вызвать было нельзя, а сам
        // второй запрос уходил без сообщения с вызовом и отклонялся сервисом.
        let client = ToolChainClient()
        let store = ChatStore(configuration: DeepSeekConfiguration(apiKey: "test-key"), client: client,
                              storageURL: temporaryHistory())
        var vacation = Conversation(title: "Отпуск", messages: [
            ChatMessage(role: .user, content: "Мы отдыхали в Сочи 12 дней."),
            ChatMessage(role: .assistant, content: "Запомнил: 12 дней в Сочи.")
        ])
        vacation.pinned = true
        store.conversations = [vacation]
        store.newChat()
        store.draft = "Сколько дней мы отдыхали? Посмотри в моём чате про отпуск."
        store.send()
        try await waitUntilIdle(store)
        XCTAssertEqual(client.requests.count, 3, "Ожидались три прохода: список, чтение, ответ")
        let second = try XCTUnwrap(client.requests.dropFirst().first)
        XCTAssertTrue(second.contains { $0.role == .assistant && $0.toolCallsRaw.contains("list_chats") },
                      "Во втором проходе нет сообщения с вызовом list_chats")
        XCTAssertTrue(second.contains { $0.role == .tool && $0.toolCallID == "call_list" })
        let third = try XCTUnwrap(client.requests.last)
        XCTAssertTrue(third.contains { $0.role == .tool && $0.content.contains("12 дней") },
                      "read_chat не вернул содержимое чата: \(third.map(\.content))")
        let answer = try XCTUnwrap(store.messages.last)
        XCTAssertEqual(answer.content, "Вы отдыхали в Сочи 12 дней.")
        XCTAssertNil(answer.error)
    }

    @MainActor
    private func waitUntilIdle(_ store: ChatStore) async throws {
        // Раннер в CI медленный: генерация может занять секунды, поэтому ждём до 30 с.
        for _ in 0..<600 {
            if !store.isGenerating { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("Generation did not finish")
    }

    /// Ждёт появления потока у тестового клиента.
    /// Раньше здесь было 250 мс: на загруженном раннере задача генерации не успевала
    /// дойти до вызова клиента, и тест падал по таймауту, а не по существу.
    @MainActor
    private func waitForContinuation(_ client: ControlledClient, count: Int = 1,
                                     file: StaticString = #filePath, line: UInt = #line) async throws {
        for attempt in 0..<400 {
            if client.continuations.count >= count { return }
            // Каждые 20 попыток уступаем планировщику без задержки: так задача
            // генерации получает главный актор, даже если он был занят.
            if attempt.isMultiple(of: 20) { await Task.yield() }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("Continuation did not appear: have \(client.continuations.count), want \(count)",
                file: file, line: line)
    }

    @MainActor
    func testVoiceServiceNeverStaysStuckInRecording() async throws {
        // Проверка той самой жалобы «голосовой ввод зависает»: после завершения
        // и после отмены сервис обязан быть готов к новой записи, а флаги — сняты.
        let speech = SpeechService()
        XCTAssertFalse(speech.isRecording)
        XCTAssertFalse(speech.isPreparingRecording)
        // Отмена без старта не должна ничего ломать.
        speech.cancelRecording()
        XCTAssertFalse(speech.isRecording)
        XCTAssertFalse(speech.isPreparingRecording)
        XCTAssertFalse(speech.isFinalizingRecording)
        // Завершение без старта возвращает текст и не оставляет флагов.
        let text = await speech.finishRecording()
        XCTAssertEqual(text, "")
        XCTAssertFalse(speech.isRecording)
        XCTAssertFalse(speech.isFinalizingRecording)
        // Повторная отмена тоже безопасна — состояние не залипает.
        speech.cancelRecording()
        speech.cancelRecording()
        XCTAssertFalse(speech.isRecording)
        XCTAssertFalse(speech.isPreparingRecording)
        XCTAssertFalse(speech.isFinalizingRecording)
        XCTAssertEqual(speech.transcript, "")
    }

    private func temporaryHistory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("HonorTest-\(UUID()).json")
    }
}

private struct ImmediateClient: DeepSeekStreaming {
    var events: [DeepSeekDelta]
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String,
                searchContext: String, tools: [[String: Any]]?) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        AsyncThrowingStream { continuation in
            events.forEach { continuation.yield($0) }
            continuation.finish()
        }
    }
}

/// Модель, которая сначала просит список чатов, потом читает чат (номер строкой),
/// потом отвечает.
private final class ToolChainClient: DeepSeekStreaming {
    var requests: [[ChatMessage]] = []
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String,
                searchContext: String, tools: [[String: Any]]?) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        stream(messages: messages, thinking: thinking, systemInstruction: systemInstruction,
               searchContext: searchContext, tools: tools, forceAnswer: false)
    }
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String,
                searchContext: String, tools: [[String: Any]]?, forceAnswer: Bool) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        requests.append(messages)
        let round = requests.count
        return AsyncThrowingStream { continuation in
            switch round {
            case 1:
                continuation.yield(DeepSeekDelta(toolCalls: [ToolCallRequest(id: "call_list", name: "list_chats", arguments: "", index: 0)]))
                continuation.yield(DeepSeekDelta(toolCalls: [ToolCallRequest(id: "", name: "", arguments: "{}", index: 0)]))
                continuation.yield(DeepSeekDelta(finishReason: "tool_calls"))
            case 2:
                continuation.yield(DeepSeekDelta(toolCalls: [ToolCallRequest(id: "call_read", name: "read_chat", arguments: "{\"number\": \"1\"}", index: 0)]))
                continuation.yield(DeepSeekDelta(finishReason: "tool_calls"))
            default:
                continuation.yield(DeepSeekDelta(content: "Вы отдыхали в Сочи "))
                continuation.yield(DeepSeekDelta(content: "12 дней."))
                continuation.yield(DeepSeekDelta(finishReason: "stop"))
            }
            continuation.finish()
        }
    }
}

private final class ControlledClient: DeepSeekStreaming {
    var continuations: [AsyncThrowingStream<DeepSeekDelta, Error>.Continuation] = []
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String,
                searchContext: String, tools: [[String: Any]]?) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        AsyncThrowingStream { continuation in continuations.append(continuation) }
    }
}

private struct FailingSearch: WebSearching {
    func search(_ query: String) async throws -> [WebSource] { throw HonorError.searchUnavailable }
}

private final class CapturingClient: DeepSeekStreaming {
    var instructions: [String] = []
    func stream(messages: [ChatMessage], thinking: Bool, systemInstruction: String,
                searchContext: String, tools: [[String: Any]]?) -> AsyncThrowingStream<DeepSeekDelta, Error> {
        instructions.append(systemInstruction)
        return AsyncThrowingStream { continuation in
            continuation.yield(.init(content: "Мне нравится зелёный цвет."))
            continuation.yield(.init(finishReason: "stop"))
            continuation.finish()
        }
    }
}

private final class TerminatingStreamURLProtocol: URLProtocol {
    private let stateLock = NSLock()
    private var cancelled = false

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "honor-stream.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/event-stream"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        let terminal = url.path.hasPrefix("/complete/") ? "\"stop\"" : "null"
        let event = "data: {\"choices\":[{\"delta\":{\"content\":\"Complete answer\"},\"finish_reason\":\(terminal)}]}\n\n"
        client?.urlProtocol(self, didLoad: Data(event.utf8))
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self else { return }
            self.stateLock.lock()
            let cancelled = self.cancelled
            self.stateLock.unlock()
            if !cancelled { self.client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost)) }
        }
    }

    override func stopLoading() {
        stateLock.lock(); cancelled = true; stateLock.unlock()
    }
}
