import XCTest

class HonorAuditCase: XCTestCase {
    let userID = "11111111-1111-4111-8111-111111111111"
    let assistantID = "22222222-2222-4222-8222-222222222222"
    let chatID = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
    let pinnedID = "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB"

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func launch(_ arguments: [String] = [], live: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestReset"] + (live ? [] : ["-UITestFixture"]) + arguments
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        return app
    }

    func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    func button(_ app: XCUIApplication, prefix: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
    }

    func composer(_ app: XCUIApplication) -> XCUIElement {
        app.textViews["chat.composer"].exists ? app.textViews["chat.composer"] : app.textFields["chat.composer"]
    }

    func typeMessage(_ text: String, app: XCUIApplication) {
        let field = composer(app)
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(text)
    }

    func replaceText(_ value: String, in field: XCUIElement) {
        field.tap()
        field.press(forDuration: 1.1)
        let app = XCUIApplication()
        let selectAll = app.menuItems["Select All"].exists ? app.menuItems["Select All"] : app.menuItems["Выбрать всё"]
        if selectAll.exists {
            selectAll.tap()
            field.typeText(value)
        } else {
            let existing = field.value as? String ?? ""
            // Text fields with a placeholder have empty actual value.
            let count = field.placeholderValue == existing ? 0 : existing.count
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: count) + value)
        }
    }

    func settings(_ app: XCUIApplication) {
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.buttons["sidebar.settings"].waitForExistence(timeout: 5))
        app.buttons["sidebar.settings"].tap()
        XCTAssertTrue(app.buttons["settings.close"].waitForExistence(timeout: 5))
    }

    func openSettingsRow(_ id: String, app: XCUIApplication) {
        let row = element(app, id)
        for _ in 0..<8 {
            if row.exists && row.isHittable { row.tap(); return }
            app.swipeUp()
        }
        XCTFail("Settings row not reachable: \(id)")
    }

    func back(_ app: XCUIApplication) {
        let backButton = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(backButton.exists)
        backButton.tap()
    }

    func openMessageMenu(_ id: String, app: XCUIApplication) {
        let message = element(app, id)
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        message.press(forDuration: 0.6)
        XCTAssertTrue(app.buttons["message.menu.copy"].waitForExistence(timeout: 5))
    }

    func dismissMessageMenu(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.13)).tap()
    }

    func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func waitAbsent(_ item: XCUIElement, timeout: TimeInterval = 8) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: item)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed)
    }

    func assertDisplayedValue(_ value: String, id: String, app: XCUIApplication) {
        let item = element(app, id)
        let predicate = NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", value, value)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: item)], timeout: 15), .completed,
                       "Expected \(id) to expose \(value); label=\(item.label), value=\(String(describing: item.value))")
    }

    func setSwitch(_ item: XCUIElement, to enabled: Bool) {
        let value = enabled ? "1" : "0"
        XCTAssertTrue(item.exists)
        if item.value as? String != value {
            // SwiftUI exposes the entire Form row as a switch; hit its actual trailing control.
            item.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        }
        let predicate = NSPredicate(format: "value == %@", value)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: item)], timeout: 5), .completed)
    }

    func relaunch(_ app: XCUIApplication, live: Bool = false) {
        XCUIDevice.shared.press(.home)
        app.terminate()
        app.launchArguments = ["-UITestPersist"] + (live ? [] : ["-UITestFixture"])
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
    }
}
