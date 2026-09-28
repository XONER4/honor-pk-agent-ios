import XCTest
@testable import HonorPKAgent

/// Живые проверки функций 10.44 с настоящей нейросетью и настоящими сайтами.
@MainActor
final class LiveFeatures1044Tests: XCTestCase {
    private func store() throws -> ChatStore {
        guard !DeepSeekConfiguration.bundled.apiKey.isEmpty else { throw XCTSkip("Bundled API key required") }
        return ChatStore(storageURL: FileManager.default.temporaryDirectory.appendingPathComponent("honer-1044-\(UUID()).json"))
    }

    @discardableResult
    private func ask(_ store: ChatStore, _ prompt: String, search: Bool = false, quote: String? = nil,
                     attachments: [MessageAttachment] = []) async throws -> ChatMessage {
        store.searchEnabled = search
        store.reasoningEnabled = false
        store.draft = prompt
        store.attachments = attachments
        if let quote { store.quote(quote) }
        let started = Date()
        store.send()
        while store.isGenerating && Date().timeIntervalSince(started) < 240 {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        if store.isGenerating { store.stop(); XCTFail("Live response timed out") }
        let result = try XCTUnwrap(store.messages.last)
        XCTAssertNil(result.error, result.error ?? "")
        let steps = (result.activity ?? []).map { "\($0.title) [\($0.detail)] \($0.sites.joined(separator: ","))" }
        let evidence = XCTAttachment(string: "Q: \(prompt)\nA: \(result.content)\nSTEPS:\n\(steps.joined(separator: "\n"))\nSOURCES: \(result.sources.map(\.url.absoluteString))")
        evidence.name = "live1044-\(name)"
        evidence.lifetime = .keepAlways
        add(evidence)
        print("HONER_LIVE1044 \(name) seconds=\(Int(Date().timeIntervalSince(started))) steps=\(steps.count) sources=\(result.sources.count)")
        return result
    }

    private func titles(_ message: ChatMessage) -> [String] { (message.activity ?? []).map(\.title) }

    func testLiveCreatesEditableTableAndSeesUserEdits() async throws {
        let store = try store()
        let first = try await ask(store, "Создай редактируемую таблицу для учёта моих расходов: дата, категория, сумма. Добавь 3 примера.")
        let table = try XCTUnwrap(store.selectedConversation?.tables?.first, "Таблица должна создаться инструментом: \(first.content)")
        XCTAssertTrue(table.editable)
        XCTAssertFalse(first.tableIDs?.isEmpty ?? true, "Карточка таблицы показана под ответом")
        // Пользователь сам дописал строку.
        var edited = table
        edited.rows.append(TableEditing.normalized(["01.10", "Такси", "777"], width: edited.columns.count))
        store.saveTable(edited)
        let second = try await ask(store, "Сколько всего строк сейчас в таблице и какая сумма у такси?")
        XCTAssertTrue(second.content.contains("777"), "Модель видит правку пользователя: \(second.content)")
        try await ask(store, "Добавь в таблицу строку: 02.10, кафе, 540")
        let updated = try XCTUnwrap(store.selectedConversation?.tables?.first)
        XCTAssertTrue(updated.rows.contains { $0.contains("540") }, "Строка добавлена в ту же таблицу: \(updated.rows)")
        XCTAssertEqual(store.selectedConversation?.tables?.count, 1, "Новая таблица не создаётся")
    }

    func testLiveMakesQuizWithCorrectAnswers() async throws {
        let store = try store()
        let result = try await ask(store, "Проведи мне мини-тест из 3 вопросов по географии с вариантами ответов.")
        let blocks = MarkdownBlockParser.parse(result.content)
        guard let quiz = blocks.first(where: { if case .ask = $0.kind { return true } else { return false } }),
              case .ask(let questions, _) = quiz.kind else {
            return XCTFail("Нет блока вопросов: \(result.content)")
        }
        XCTAssertGreaterThanOrEqual(questions.count, 3)
        XCTAssertTrue(questions.allSatisfy { !$0.correct.isEmpty }, "У каждого вопроса теста отмечен правильный ответ: \(quiz.text)")
        XCTAssertTrue(QuestionnaireHeader.parse(quiz.text).quiz || questions.contains { !$0.correct.isEmpty })
    }

    func testLiveYouTubeGitHubAndMarketplaceIntegrations() async throws {
        let store = try store()
        let video = try await ask(store, "Найди на YouTube видео, как сварить борщ. Дай ссылки.", search: true)
        XCTAssertTrue(video.content.contains("youtube.com") || video.content.contains("youtu.be"), video.content)
        store.newChat()
        let repo = try await ask(store, "Что за репозиторий apple/swift на GitHub? Сколько у него звёзд и на каком языке он написан?", search: true)
        XCTAssertTrue(titles(repo).contains("Смотрю GitHub"), "Должна сработать интеграция GitHub: \(titles(repo))")
        XCTAssertTrue(repo.content.lowercased().contains("swift"))
        store.newChat()
        let goods = try await ask(store, "Найди на Wildberries беспроводные наушники до 3000 рублей, дай 3 варианта со ссылками.", search: true)
        XCTAssertTrue(titles(goods).contains("Ищу товары"), "Должен сработать поиск по маркетплейсу: \(titles(goods))")
    }

    func testLiveReadsManySitesQuickly() async throws {
        let store = try store()
        let result = try await ask(store, "Прочитай не меньше 25 разных сайтов и собери, что пишут про пользу и вред кофе. Используй массовое чтение сайтов.", search: true)
        XCTAssertTrue(titles(result).contains("Читаю много сайтов"), "Должно сработать массовое чтение: \(titles(result))")
        XCTAssertGreaterThanOrEqual(result.sources.count, 5, "Ответ опирается на много источников")
    }

    func testLiveUnderstandsSpreadsheetAttachment() async throws {
        let store = try store()
        let csv = "Товар;Цена;Количество\nЯблоки;120;3\nГруши;200;2\nСливы;90;5\n"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Покупки-\(UUID().uuidString.prefix(4)).csv")
        try csv.write(to: url, atomically: true, encoding: .utf8)
        let attachment = try await AttachmentService.importFile(url: url)
        XCTAssertTrue(attachment.extractedText.contains("Груши") && attachment.extractedText.contains("200"), attachment.extractedText)
        let result = try await ask(store, "Сколько я потрачу на все товары из таблицы (цена × количество)? Ответь числом.", attachments: [attachment])
        XCTAssertTrue(result.content.contains("1210"), "360 + 400 + 450 = 1210: \(result.content)")
    }

    func testLiveEnglishModeAnswersInEnglish() async throws {
        let store = try store()
        store.setResponseLanguage("en")
        let result = try await ask(store, "Какая самая высокая гора в мире? Одним предложением.")
        XCTAssertTrue(result.content.contains("Everest"), result.content)
        let cyrillic = result.content.unicodeScalars.filter { (0x0400...0x04FF).contains(Int($0.value)) }.count
        XCTAssertLessThan(cyrillic, 5, "В английском режиме ответ по-английски: \(result.content)")
    }

    func testLiveQuotedFragmentIsUnderstood() async throws {
        let store = try store()
        let result = try await ask(store, "Объясни простыми словами.", quote: "Митохондрии — энергетические станции клетки.")
        XCTAssertTrue(result.content.lowercased().contains("энерг"), result.content)
        XCTAssertEqual(store.messages.first(where: { $0.role == .user })?.quote, "Митохондрии — энергетические станции клетки.")
    }

    func testLiveUpdatesMemoryWhenFactChanges() async throws {
        let store = try store()
        store.addMemory("Пользователь живёт в Казани")
        try await ask(store, "Я переехал из Казани в Москву, обнови, пожалуйста, это в своей памяти.")
        XCTAssertTrue(store.memories.contains { $0.text.contains("Москв") }, "Факт обновлён: \(store.memories.map(\.text))")
        XCTAssertFalse(store.memories.contains { $0.text.contains("живёт в Казани") }, "Устаревший факт исправлен или удалён: \(store.memories.map(\.text))")
    }
}
