import XCTest

final class StabilityAuditTests: HonorAuditCase {
    func testStopStreamingAndRetry() {
        let app = launch(["-UITestSlow"])
        typeMessage("Stream a response", app: app)
        app.buttons["chat.send"].tap()
        XCTAssertTrue(app.buttons["chat.stop"].waitForExistence(timeout: 8))
        app.buttons["chat.stop"].tap()
        let stopped = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.stopped.")).firstMatch
        XCTAssertTrue(stopped.waitForExistence(timeout: 5))
        button(app, prefix: "message.action.retry.").tap()
        XCTAssertTrue(app.staticTexts["Готово. Тестовый ответ Honor."].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["chat.sidebar"].isHittable)
    }

    func testOfflineErrorThenRetryRecovers() {
        let app = launch(["-UITestError"])
        typeMessage("Check connection", app: app)
        app.buttons["chat.send"].tap()
        let error = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.error.")).firstMatch
        XCTAssertTrue(error.waitForExistence(timeout: 8))
        button(app, prefix: "message.action.retry.").tap()
        XCTAssertTrue(app.staticTexts["Готово. Тестовый ответ Honor."].waitForExistence(timeout: 8))
        waitAbsent(error)
    }

    func testLargeSavedHistoryRemainsNavigable() {
        let app = launch(["-UITestLargeHistory"])
        XCTAssertTrue(app.buttons["chat.sidebar"].isHittable)
        app.swipeDown()
        app.swipeDown()
        app.buttons["chat.sidebar"].tap()
        let search = app.textFields["history.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("Сохранённый чат 498")
        let result = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "history.row.", "498")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 10))
        result.tap()
        XCTAssertTrue(app.staticTexts["Проект 498"].waitForExistence(timeout: 8))
        capture("18-large-history")
    }

    func testLaunchPerformanceWithSavedHistory() {
        let app = launch(["-UITestLargeHistory"])
        app.terminate()
        app.launchArguments = ["-UITestPersist", "-UITestFixture"]
        let options = XCTMeasureOptions()
        options.iterationCount = 3
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: options) {
            app.launch()
            XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
}
