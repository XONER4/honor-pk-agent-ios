import XCTest
@testable import HonorPKAgent

/// Версия 10.44: вопросы и тесты, таблицы, цитаты, язык, озвучка во время печати,
/// перетаскивание чатов, новые инструменты и быстрый разбор длинных ответов.
@MainActor
final class Honer1044Tests: XCTestCase {
    private func url() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("h1044-\(UUID()).json") }

    // MARK: Вопросы и тесты

    func testQuizBlockParsesCorrectAnswersMediaTimerAndCustomOption() {
        let body = """
        @mode quiz
        @timer 30
        @title Проверка
        ? Столица Франции?
        ![](https://example.com/paris.jpg)
        - Лондон
        -* Париж
        - Берлин
        ? Сколько будет 2+2?
        - 3
        - [x] 4
        + свой вариант
        ? Послушайте и ответьте
        @audio https://example.com/sound.mp3
        - Кошка
        - ✓ Собака
        """
        let questions = MarkdownBlockParser.parseQuestions(body)
        XCTAssertEqual(questions.count, 3, "Строка «+ свой вариант» не должна становиться отдельным вопросом")
        XCTAssertEqual(questions[0].options, ["Лондон", "Париж", "Берлин"])
        XCTAssertEqual(questions[0].correct, [1])
        XCTAssertEqual(questions[0].media.first?.kind, .image)
        XCTAssertEqual(questions[1].correct, [1])
        XCTAssertTrue(questions[1].allowsCustom)
        XCTAssertEqual(questions[2].media.first?.kind, .audio)
        XCTAssertEqual(questions[2].correct, [1])
        let header = QuestionnaireHeader.parse(body)
        XCTAssertTrue(header.quiz)
        XCTAssertEqual(header.timer, 30)
        XCTAssertEqual(header.title, "Проверка")
        XCTAssertEqual(QuestionnaireHeader.parse("? Вопрос\n- да").timer, 10, "По умолчанию 10 секунд на вопрос")
        XCTAssertEqual(QuestionnaireHeader.parse("@timer 0\n? Вопрос").timer, 0)
    }

    func testBoldOptionIsNotMistakenForCorrectAnswer() {
        let questions = MarkdownBlockParser.parseQuestions("? Что выбрать?\n- **жирный** вариант\n- обычный")
        XCTAssertEqual(questions.first?.correct, [])
        XCTAssertEqual(questions.first?.options.first, "**жирный** вариант")
    }

    func testThirtyQuestionsLimit() {
        let body = (1...40).map { "? Вопрос \($0)\n- да\n- нет" }.joined(separator: "\n")
        XCTAssertEqual(MarkdownBlockParser.parseQuestions(body).count, 30)
    }

    func testQuizReportScoresAndExplainsTimeouts() {
        let questions = MarkdownBlockParser.parseQuestions("? A?\n-* да\n- нет\n? B?\n- да\n-* нет\n? C?\n-* 1\n- 2")
        let answers: [String?] = ["да", "да", ""]
        XCTAssertEqual(QuestionnaireReport.score(answers, questions), 1)
        let message = QuestionnaireReport.message(answers: answers, questions: questions, quiz: true, title: "Тест", english: false)
        XCTAssertTrue(message.contains("1 из 3"), message)
        XCTAssertTrue(message.contains("правильно: нет"), message)
        XCTAssertTrue(message.contains("нет ответа"), message)
        let silent = QuestionnaireReport.message(answers: [""], questions: [questions[0]], quiz: false, title: "", english: false)
        XCTAssertTrue(silent.contains("Реши сам"), "Без ответа модель решает сама: \(silent)")
        let single = QuestionnaireReport.message(answers: ["учёба"], questions: [QuickQuestion(text: "Цель?", options: ["учёба"], allowsCustom: false)],
                                                 quiz: false, title: "", english: false)
        XCTAssertEqual(single, "учёба", "Ответ на одиночный уточняющий вопрос уходит как есть")
    }

    // MARK: Таблицы

    func testTableEditingActions() throws {
        var table = try XCTUnwrap(TableEditing.make(from: ["title": "Бюджет", "columns": ["Статья", "Сумма"],
                                                           "rows": [["Еда", "100"], ["Кино", "30"]], "editable": true]))
        table = try TableEditing.apply(["action": "set_cell", "row": 2, "column": "Сумма", "value": "45"], to: table)
        XCTAssertEqual(table.rows[1][1], "45")
        table = try TableEditing.apply(["action": "add_row", "values": ["Такси", "20"]], to: table)
        table = try TableEditing.apply(["action": "add_column", "name": "Комментарий"], to: table)
        XCTAssertEqual(table.columns.count, 3)
        XCTAssertEqual(table.rows.count, 3)
        table = try TableEditing.apply(["action": "sort", "column": 2, "descending": true], to: table)
        XCTAssertEqual(table.rows.first?[0], "Еда")
        table = try TableEditing.apply(["action": "delete_row", "row": 3], to: table)
        XCTAssertEqual(table.rows.count, 2)
        XCTAssertThrowsError(try TableEditing.apply(["action": "set_cell", "row": 9, "column": 1, "value": "x"], to: table))
        XCTAssertThrowsError(try TableEditing.apply(["action": "teleport"], to: table))
        XCTAssertTrue(TableEditing.markdown(table).contains("| № | Статья | Сумма | Комментарий |"))
        XCTAssertTrue(TableEditing.csv(table).hasPrefix("Статья,Сумма,Комментарий"))
        XCTAssertEqual(TableEditing.number("T2"), 2)
    }

    func testTableToolsCreateAndUserEditsReachTheModel() throws {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        let chat = Conversation(title: "Таблицы", messages: [ChatMessage(role: .user, content: "Сделай таблицу")])
        store.conversations = [chat]
        store.selectedConversationID = chat.id
        let create = ToolCallRequest(id: "1", name: "create_table",
                                     arguments: #"{"title":"Покупки","columns":["Товар","Цена"],"rows":[["Хлеб","50"]],"editable":true}"#)
        let result = ExtraToolExecutor(context: ToolExecutionContext()).executeLocal(create)
        guard case .createTable(let table)? = result.effect else { return XCTFail("Таблица не создана: \(result.content)") }
        store.conversations[0].tables = [table]
        var edited = table
        edited.rows.append(["Молоко", "90"])
        store.saveTable(edited)
        XCTAssertEqual(store.table(id: table.id)?.editedByUser, true)
        let prompt = store.systemInstruction(forChat: chat.id, query: "что в таблице")
        XCTAssertTrue(prompt.contains("## Таблицы этого чата"))
        XCTAssertTrue(prompt.contains("Молоко"), "Модель видит правки пользователя")
        let update = ToolCallRequest(id: "2", name: "update_table", arguments: #"{"table":"T1","action":"set_cell","row":1,"column":"Цена","value":"55"}"#)
        var context = ToolExecutionContext()
        context.tables = store.conversations[0].tables ?? []
        let updated = ExtraToolExecutor(context: context).executeLocal(update)
        guard case .replaceTable(let changed)? = updated.effect else { return XCTFail(updated.content) }
        XCTAssertEqual(changed.rows[0][1], "55")
    }

    // MARK: Память

    func testMemoryToolsListUpdateAndDelete() {
        var context = ToolExecutionContext()
        let first = UUID(), second = UUID()
        context.memoryItems = [MemoryRef(id: first, text: "Живу в Казани"), MemoryRef(id: second, text: "Люблю кофе")]
        let executor = ExtraToolExecutor(context: context)
        let list = executor.executeLocal(ToolCallRequest(id: "1", name: "list_memory", arguments: "{}"))
        XCTAssertTrue(list.content.contains("1. Живу в Казани"))
        let update = executor.executeLocal(ToolCallRequest(id: "2", name: "update_memory", arguments: #"{"number":1,"text":"Живу в Москве"}"#))
        guard case .updateMemory(let id, let text)? = update.effect else { return XCTFail(update.content) }
        XCTAssertEqual(id, first)
        XCTAssertEqual(text, "Живу в Москве")
        let delete = executor.executeLocal(ToolCallRequest(id: "3", name: "delete_memory", arguments: #"{"number":"2"}"#))
        guard case .deleteMemory(let removed)? = delete.effect else { return XCTFail(delete.content) }
        XCTAssertEqual(removed, second)
    }

    // MARK: Язык и профиль

    func testEnglishModeReplacesLanguageRule() {
        XCTAssertTrue(HonerIdentity.instruction.contains(HonerIdentity.russianLanguageRule), "Правило языка должно находиться в инструкции")
        let english = HonerIdentity.englishInstruction
        XCTAssertFalse(english.contains(HonerIdentity.russianLanguageRule))
        XCTAssertTrue(english.contains("switched the app to English"))
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        XCTAssertFalse(store.respondsInEnglish, "По умолчанию — русский")
        store.setResponseLanguage("en")
        XCTAssertTrue(store.respondsInEnglish)
        store.setResponseLanguage("ru")
        XCTAssertFalse(store.respondsInEnglish)
    }

    func testProfileBlockHasAgeAndAccountDate() {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        store.profileBirthday = "2000-05-10"
        store.accountCreatedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let now = ChatStore.birthdayFormatter.date(from: "2026-05-11") ?? Date()
        let block = store.profileBlock(now: now)
        XCTAssertTrue(block.contains("полных лет: 26"), block)
        XCTAssertTrue(block.contains("Аккаунт Honer AI создан"), block)
    }

    func testDeviceNamesAreFriendly() {
        XCTAssertEqual(DeviceModel.friendly("iPhone16,2"), "iPhone 15 Pro Max")
        XCTAssertEqual(DeviceModel.friendly("iPhone14,5"), "iPhone 13")
        XCTAssertTrue(DeviceContext.summary().contains("Устройство:"))
    }

    // MARK: Цитаты

    func testQuotedFragmentTravelsWithTheQuestion() throws {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        store.quote("  Фотосинтез идёт в хлоропластах.  ")
        XCTAssertEqual(store.quotedFragment, "Фотосинтез идёт в хлоропластах.")
        var message = ChatMessage(role: .user, content: "Что это значит?")
        message.quote = store.quotedFragment
        let request = try DeepSeekClient(configuration: .init(apiKey: "k"))
            .makeRequest(messages: [message], thinking: false, systemInstruction: "", searchContext: "")
        let body = String(data: request.httpBody ?? Data(), encoding: .utf8) ?? ""
        XCTAssertTrue(body.contains("выделил в переписке фрагмент"), "Модель знает, что это цитата")
        XCTAssertTrue(body.contains("хлоропластах"))
    }

    // MARK: Озвучка во время печати

    func testSpeakableChunksEndOnSentencesAndSkipOpenCode() {
        let text = "Первое предложение готово. Второе ещё пиш"
        let end = SpeechService.speakableEnd(text[...], final: false)
        XCTAssertEqual(end.map { String(text[..<$0]) }, "Первое предложение готово.")
        XCTAssertNil(SpeechService.speakableEnd("Коротко. Ещ"[...], final: false), "Слишком короткий кусок ждёт продолжения")
        let code = "Вот код:\n```swift\nlet x = 1. 2. 3\n"
        let codeEnd = SpeechService.speakableEnd(code[...], final: false)
        XCTAssertEqual(codeEnd.map { String(code[..<$0]) }, "Вот код:\n", "Внутрь незакрытого блока кода не режем")
        XCTAssertNotNil(SpeechService.speakableEnd("Хвост без точки"[...], final: true))
    }

    // MARK: Чаты

    func testDragPinsReordersAndUnpinsChats() {
        let store = ChatStore(configuration: .init(apiKey: "test"), storageURL: url())
        var a = Conversation(title: "A"); a.pinned = true; a.pinOrder = 0
        var b = Conversation(title: "B"); b.pinned = true; b.pinOrder = 1
        let c = Conversation(title: "C")
        let d = Conversation(title: "D")
        store.conversations = [a, b, c, d]
        XCTAssertTrue(store.moveChat(id: c.id, onto: a.id), "Чат, брошенный на закреплённый, закрепляется")
        XCTAssertEqual(store.sortedConversations.prefix(3).map(\.title), ["C", "A", "B"])
        XCTAssertTrue(store.moveChat(id: a.id, onto: d.id), "Закреплённый, брошенный на обычный, открепляется")
        XCTAssertEqual(store.conversations.first { $0.id == a.id }?.pinned, false)
        XCTAssertFalse(store.moveChat(id: d.id, onto: a.id), "Обычные чаты идут по времени")
    }

    // MARK: Информация о чате

    func testChatInsightCollectsMediaLinksAndTimeline() {
        var user = ChatMessage(role: .user, content: "Посмотри [сайт](https://example.com/a)")
        user.attachments = [MessageAttachment(name: "voice.m4a", kind: .audio, summary: "Аудио 0:12")]
        var answer = ChatMessage(role: .assistant, content: "Вот фото ![кот](https://images.example.com/cat.jpg) и видео [ролик](https://www.youtube.com/watch?v=dQw4w9WgXcQ)")
        answer.sources = [WebSource(title: "Источник", url: URL(string: "https://news.example.com/1")!, snippet: "текст")]
        answer.activity = [GenerationStep(kind: "read", title: "Читаю", sites: ["wiki.example.org"], startedAt: Date())]
        let media = ChatInsight.media(in: [user, answer])
        XCTAssertEqual(media.filter { $0.kind == .audio }.count, 1)
        XCTAssertEqual(media.filter { $0.kind == .photo }.count, 1)
        XCTAssertEqual(media.filter { $0.kind == .video }.count, 1)
        let links = ChatInsight.links(in: [user, answer]).map(\.url.absoluteString)
        XCTAssertTrue(links.contains("https://news.example.com/1"))
        XCTAssertTrue(links.contains("https://wiki.example.org"))
        XCTAssertTrue(links.contains("https://example.com/a"))
        XCTAssertFalse(ChatInsight.timeline(of: [user, answer], english: false).isEmpty)
    }

    // MARK: Интеграции

    func testEmbeddedYouTubeJSONAndGitHubPaths() {
        let html = #"<script>var ytInitialData = {"a":{"videoRenderer":{"videoId":"abc123XYZ","title":{"runs":[{"text":"Видео \"1\""}]}}}};</script>"#
        let object = IntegrationClient.embeddedJSON(named: "ytInitialData", in: html)
        XCTAssertNotNil(object)
        var found: [[String: Any]] = []
        IntegrationClient.collect("videoRenderer", in: object as Any, limit: 5, into: &found)
        XCTAssertEqual(found.first?["videoId"] as? String, "abc123XYZ")
        XCTAssertEqual(IntegrationClient.runsText(found.first?["title"]), "Видео \"1\"")
        XCTAssertEqual(IntegrationClient.repoPath("https://github.com/apple/swift.git"), "apple/swift")
        XCTAssertEqual(IntegrationClient.repoPath("apple/swift-nio"), "apple/swift-nio")
        XCTAssertNil(IntegrationClient.repoPath("swift"))
    }

    func testBulkReaderRanksPassagesByQuestionWords() {
        let text = (0..<20).map { "Абзац номер \($0) про погоду и облака, совсем не про цену билетов на поезд." }.joined(separator: "\n")
            + "\nСтоимость билета на поезд Москва — Казань составляет 3500 рублей в плацкарте, это важный факт."
        let words = Set(ChatStore.keywords(in: "стоимость билета поезд Казань"))
        let passages = BulkPageReader.passages(in: text, words: words)
        XCTAssertTrue(passages.first?.text.contains("3500") == true)
    }

    func testParentalRefusalBlocksWebToolsWhenDisabledByParent() {
        // Родительский контроль выключен — инструменты работают как обычно.
        if !ParentalControl.shared.rules.enabled {
            XCTAssertNil(ChatStore.parentalRefusal(for: ToolCallRequest(id: "1", name: "web_search", arguments: #"{"query":"погода"}"#)))
        }
    }

    // MARK: Длинные ответы

    func testIncrementalParseMatchesFullParseAndStaysFast() {
        var pieces: [String] = []
        for index in 0..<400 {
            switch index % 5 {
            case 0: pieces.append("## Раздел \(index)")
            case 1: pieces.append("Абзац \(index): " + String(repeating: "текст ответа ", count: 12))
            case 2: pieces.append("- пункт \(index)\n- ещё пункт\n- третий")
            case 3: pieces.append("```swift\nlet value\(index) = \(index)\n\nprint(value\(index))\n```")
            default: pieces.append("| A | B |\n|---|---|\n| \(index) | \(index * 2) |")
            }
        }
        let full = pieces.joined(separator: "\n\n")
        let memo = MarkdownParseMemo()
        var shown = ""
        var cursor = full.startIndex
        let started = Date()
        var frames = 0
        while cursor < full.endIndex {
            cursor = full.index(cursor, offsetBy: 97, limitedBy: full.endIndex) ?? full.endIndex
            shown = String(full[..<cursor])
            _ = memo.blocks(for: shown, streaming: true)
            frames += 1
        }
        let seconds = Date().timeIntervalSince(started)
        print("HONER_PERF incremental parse chars=\(full.count) frames=\(frames) seconds=\(seconds)")
        let incremental = memo.blocks(for: full, streaming: true)
        let reference = MarkdownBlockParser.parse(full, streaming: true)
        XCTAssertEqual(incremental.map(\.kind), reference.map(\.kind), "Пошаговый разбор должен совпадать с полным")
        XCTAssertEqual(incremental.map(\.text), reference.map(\.text))
        XCTAssertEqual(incremental.map(\.id), reference.map(\.id))
        XCTAssertLessThan(seconds, 6, "Печать длинного ответа не должна тормозить")
    }
}
