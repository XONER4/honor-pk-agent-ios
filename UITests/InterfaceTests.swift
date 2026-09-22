import XCTest

final class InterfaceTests: XCTestCase {
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testWelcomeComposerAndAttachments() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestWelcome", "-UITestReset", "-UITestFixture"]
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        capture("01-welcome")
        app.buttons["composer.attachments"].tap()
        XCTAssertTrue(app.buttons["Камера"].waitForExistence(timeout: 5))
        capture("02-attachments")
        app.buttons["composer.attachments"].tap()
        let field = app.textViews["chat.composer"].exists ? app.textViews["chat.composer"] : app.textFields["chat.composer"]
        XCTAssertTrue(field.exists)
        field.tap()
        field.typeText("Hello Honor")
        XCTAssertTrue(app.buttons["chat.send"].waitForExistence(timeout: 5))
        capture("03-keyboard")
    }

    func testConversationHistoryAndSettings() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestDemo", "-UITestReset", "-UITestFixture"]
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Привет, как твои дела?"].exists)
        capture("04-conversation")
        app.staticTexts["Привет, как твои дела?"].press(forDuration: 1.1)
        XCTAssertTrue(app.buttons["message.menu.edit"].waitForExistence(timeout: 5))
        capture("05-message-menu")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.16)).tap()
        app.staticTexts["Привет! У меня всё отлично, спасибо, что спросил. А как твои дела?"].firstMatch.press(forDuration: 1.1)
        XCTAssertTrue(app.buttons["message.menu.speak"].waitForExistence(timeout: 5))
        capture("08-assistant-menu")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.16)).tap()
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.buttons["sidebar.settings"].waitForExistence(timeout: 5))
        capture("06-history")
        app.buttons["Выбрать чаты"].tap()
        XCTAssertTrue(app.staticTexts["Выберите чаты"].waitForExistence(timeout: 5))
        capture("09-select-chats")
        app.buttons["Отмена"].tap()
        app.buttons["sidebar.settings"].tap()
        XCTAssertTrue(app.staticTexts["Настройки"].waitForExistence(timeout: 5))
        capture("07-settings")
    }

    func testLiveDeepSeekRoundTrip() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestWelcome", "-UITestReset"]
        app.launch()
        XCTAssertTrue(app.buttons["composer.reasoning"].waitForExistence(timeout: 10))
        app.buttons["composer.reasoning"].tap()
        let field = app.textViews["chat.composer"].exists ? app.textViews["chat.composer"] : app.textFields["chat.composer"]
        field.tap()
        field.typeText("Return exactly the concatenation of HONOR, underscore, TEST, underscore, OK. Nothing else.")
        app.buttons["chat.send"].tap()
        let reply = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "HONOR_TEST_OK")).firstMatch
        XCTAssertTrue(reply.waitForExistence(timeout: 60), "Native DeepSeek request should return expected text")
        capture("10-live-reply")
    }
}
