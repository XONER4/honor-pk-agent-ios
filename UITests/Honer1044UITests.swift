import XCTest

/// Функции 10.44 на реальных экранах: тест с вопросами и таймером, таблица,
/// «Информация о чате», цитата, руководство, родительский контроль, первый экран.
final class Honer1044UITests: HonorAuditCase {
    func testQuizScoresAnswersAndSendsResult() {
        let app = launch(["-UITestDemo", "-UITestQuiz"])
        let card = element(app, "message.questions")
        XCTAssertTrue(card.waitForExistence(timeout: 8))
        let paris = app.buttons["question.option.Париж"]
        XCTAssertTrue(paris.waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "question.timer").waitForExistence(timeout: 5), "У вопроса идёт таймер")
        capture("60-quiz-question")
        paris.tap()
        let four = app.buttons["question.option.4"]
        XCTAssertTrue(four.waitForExistence(timeout: 5), "После ответа открывается следующий вопрос")
        four.tap()
        XCTAssertTrue(element(app, "question.score").waitForExistence(timeout: 6), "Итог теста со счётом")
        capture("61-quiz-result")
        // Результат уходит в чат сам, и Honer AI отвечает.
        XCTAssertTrue(element(app, "question.sent").waitForExistence(timeout: 10))
        let sentMessage = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Результаты теста")).firstMatch
        XCTAssertTrue(sentMessage.waitForExistence(timeout: 5))
    }

    func testUnansweredQuestionClosesByTimerAndHonerDecides() {
        let app = launch(["-UITestDemo", "-UITestQuestionTimeout"])
        XCTAssertTrue(element(app, "message.questions").waitForExistence(timeout: 8))
        XCTAssertTrue(element(app, "question.sent").waitForExistence(timeout: 15),
                      "Без ответа вопрос закрывается по таймеру и решение уходит Honer AI")
        let note = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "не ответил")).firstMatch
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        capture("62-question-timeout")
    }

    func testTableOpensEditsAndGrows() {
        let app = launch(["-UITestDemo", "-UITestTable"])
        let card = app.buttons["message.table.CCCCCCCC-CCCC-4CCC-8CCC-CCCCCCCCCCCC"]
        XCTAssertTrue(card.waitForExistence(timeout: 8))
        card.tap()
        XCTAssertTrue(element(app, "table.editor").waitForExistence(timeout: 5))
        let cell = element(app, "table.cell.0.1")
        XCTAssertTrue(cell.waitForExistence(timeout: 5))
        cell.tap()
        let field = app.textFields["table.cell.field"].exists ? app.textFields["table.cell.field"] : app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        replaceText("75", in: field)
        let save = app.buttons["table.cell.save"].exists ? app.buttons["table.cell.save"].firstMatch
            : app.alerts.buttons.matching(NSPredicate(format: "label IN %@", ["Сохранить", "Save"])).firstMatch
        save.tap()
        assertDisplayedValue("75", id: "table.cell.0.1", app: app)
        app.buttons["table.addRow"].tap()
        XCTAssertTrue(element(app, "table.cell.2.0").waitForExistence(timeout: 5), "Добавленная строка появилась")
        capture("63-table-editor")
        app.buttons["table.close"].tap()
        waitAbsent(element(app, "table.editor"))
    }

    func testChatInformationShowsMediaFilesLinksAndTimeline() {
        let app = launch(["-UITestDemo", "-UITestRichChat"])
        let tools = app.buttons["chat.tools"]
        XCTAssertTrue(tools.waitForExistence(timeout: 8))
        tools.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let info = app.buttons["chat.tools.info"]
        XCTAssertTrue(info.waitForExistence(timeout: 5))
        info.tap()
        XCTAssertTrue(element(app, "chat.info.overview").waitForExistence(timeout: 5))
        capture("64-chat-info-overview")
        let tabs = app.segmentedControls["chat.info.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 5))
        tabs.buttons.element(boundBy: 3).tap()
        XCTAssertTrue(element(app, "chat.info.links").waitForExistence(timeout: 5))
        let link = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Статья о закатах")).firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 5), "Страница, которую читал Honer AI, видна в ссылках")
        capture("65-chat-info-links")
        tabs.buttons.element(boundBy: 2).tap()
        XCTAssertTrue(element(app, "chat.info.files").waitForExistence(timeout: 5))
        tabs.buttons.element(boundBy: 4).tap()
        XCTAssertTrue(element(app, "chat.info.timeline").waitForExistence(timeout: 5))
        app.buttons["chat.info.close"].tap()
    }

    func testQuoteFromMessageMenuAppearsAboveComposer() {
        let app = launch(["-UITestDemo"])
        openMessageMenu("message.assistant." + assistantID, app: app)
        let quote = app.buttons["message.menu.quote"]
        XCTAssertTrue(quote.waitForExistence(timeout: 5))
        quote.tap()
        XCTAssertTrue(element(app, "composer.quote").waitForExistence(timeout: 5))
        capture("66-quote")
        app.buttons["composer.quote.remove"].tap()
        waitAbsent(element(app, "composer.quote"))
    }

    func testGuideAndParentalControlPagesOpen() {
        let app = launch(["-UITestDemo"])
        settings(app)
        openSettingsRow("settings.guide", app: app)
        XCTAssertTrue(element(app, "help.page").waitForExistence(timeout: 8))
        capture("67-guide")
        let search = app.searchFields.firstMatch.exists ? app.searchFields.firstMatch : app.textFields["help.search"]
        if search.waitForExistence(timeout: 3) {
            search.tap()
            search.typeText("таблиц")
            XCTAssertTrue(button(app, prefix: "help.article.").waitForExistence(timeout: 5), "Поиск по руководству находит статьи")
        }
        back(app)
        openSettingsRow("settings.parental", app: app)
        XCTAssertTrue(element(app, "parental.page").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "parental.enable").waitForExistence(timeout: 5), "Родительский контроль по умолчанию выключен")
        capture("68-parental")
    }

    func testOnboardingOffersLanguageAndBirthday() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestReset", "-UITestFixture", "-UITestOnboarding"]
        app.launch()
        XCTAssertTrue(element(app, "onboarding.page").waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, "onboarding.language").waitForExistence(timeout: 5), "Выбор языка на первом экране")
        let toggle = element(app, "onboarding.birthday.toggle")
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertTrue(element(app, "onboarding.birthday").waitForExistence(timeout: 5), "Появляется выбор даты рождения")
        capture("69-onboarding-birthday")
    }
}
