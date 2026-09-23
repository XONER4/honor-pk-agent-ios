import XCTest

final class LiveFeatureTests: HonorAuditCase {
    func testLiveReasoningMemoryAndFollowUp() {
        let app = launch(live: true)
        settings(app)
        openSettingsRow("settings.memory", app: app)
        app.buttons["memory.add"].tap()
        let memory = app.textViews["memory.editor.text"]
        memory.tap()
        memory.typeText("My project has the unique name ORION_NOVA_27.")
        app.buttons["memory.editor.save"].tap()
        back(app)
        app.buttons["settings.close"].tap()
        typeMessage("What is my project's unique name? Reply only with the name.", app: app)
        let started = Date()
        app.buttons["chat.send"].tap()
        let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "ORION_NOVA_27")).firstMatch
        XCTAssertTrue(answer.waitForExistence(timeout: 90), "Live request must use the saved Honor memory")
        waitAbsent(app.buttons["chat.stop"], timeout: 30)
        print("HONOR_LIVE_MEMORY_THINKING_SECONDS=\(Date().timeIntervalSince(started))")
        let reasoning = button(app, prefix: "message.reasoning.")
        XCTAssertTrue(reasoning.exists, "Live thinking mode must return reasoning_content")
        reasoning.tap()
        let text = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.reasoning.text.")).firstMatch
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(text.label.count, 5)
        capture("19-live-memory-thinking")
        reasoning.tap()
        typeMessage("What are the last two digits of that project name? Reply with exactly those digits.", app: app)
        app.buttons["chat.send"].tap()
        XCTAssertTrue(app.staticTexts["27"].waitForExistence(timeout: 90), "Second turn must preserve conversation context")
        capture("20-live-followup")
    }

    func testLiveSearchProvidesOpenableSources() {
        let app = launch(live: true)
        app.buttons["composer.search"].tap()
        typeMessage("Find Apple's iPhone 13 technical specifications. Give the display size in one short sentence with a source.", app: app)
        let started = Date()
        app.buttons["chat.send"].tap()
        let sources = button(app, prefix: "message.sources.")
        XCTAssertTrue(sources.waitForExistence(timeout: 90), "Search must attach real source links")
        waitAbsent(app.buttons["chat.stop"], timeout: 90)
        print("HONOR_LIVE_SEARCH_SECONDS=\(Date().timeIntervalSince(started))")
        XCTAssertTrue(button(app, prefix: "message.action.copy.").exists)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.error.")).firstMatch.exists)
        capture("21-live-search")
        sources.tap()
        let source = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.source.")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        capture("33-live-source-details")
    }
}
