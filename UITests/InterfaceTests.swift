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
        app.launchArguments = ["-UITestWelcome"]
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        capture("01-welcome")
        app.buttons["composer.attachments"].tap()
        XCTAssertTrue(app.buttons["Камера"].waitForExistence(timeout: 5))
        capture("02-attachments")
        app.buttons["composer.attachments"].tap()
        let field = app.textFields["chat.composer"]
        if field.exists { field.tap(); field.typeText("Hello Honor") }
        capture("03-keyboard")
    }

    func testConversationHistoryAndSettings() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestDemo"]
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Привет, как твои дела?"].exists)
        capture("04-conversation")
        app.staticTexts["Привет, как твои дела?"].press(forDuration: 1.1)
        capture("05-message-menu")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.14)).tap()
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.buttons["sidebar.settings"].waitForExistence(timeout: 5))
        capture("06-history")
        app.buttons["sidebar.settings"].tap()
        XCTAssertTrue(app.staticTexts["Настройки"].waitForExistence(timeout: 5))
        capture("07-settings")
    }
}
