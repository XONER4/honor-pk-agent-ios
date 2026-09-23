import XCTest

/// Records the real settings flow and a live response, never a canned assistant reply.
final class PersonalizationTutorialTests: HonorAuditCase {
    func testRecordRealPersonalization() {
        let app = launch(live: true)
        print("HONER_DEMO_START=\(Date().timeIntervalSince1970)")
        settings(app)
        openSettingsRow("settings.personalization", app: app)
        let field = app.textViews["personalization.instructions"]
        field.tap()
        field.typeText("Обращайся ко мне на ты, отвечай кратко по-русски.")
        capture("31-personalization-instructions")
        back(app)
        app.buttons["settings.close"].tap()
        app.buttons["composer.reasoning"].tap()
        typeMessage("Я хочу начать рисовать, но сомневаюсь в себе. Поддержи меня.", app: app)
        app.buttons["chat.send"].tap()
        XCTAssertTrue(button(app, prefix: "message.action.copy.").waitForExistence(timeout: 90))
        waitAbsent(app.buttons["chat.stop"], timeout: 90)
        let answer = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.content.")).firstMatch
        XCTAssertTrue(answer.exists)
        print("HONER_PERSONALIZATION_RESPONSE=\(answer.label)")
        XCTAssertLessThan(answer.label.count, 1000)
        XCTAssertTrue(answer.label.lowercased().range(of: "\\b(ты|тебе|тебя|твой|твои|твоё|твоей|твоих)\\b", options: .regularExpression) != nil,
                      "Personalization must result in an informal Russian reply")
        capture("32-personalization-real-answer")
        // A brief reading interval is intentional in the user-facing tutorial recording.
        RunLoop.current.run(until: Date().addingTimeInterval(4))
        print("HONER_DEMO_END=\(Date().timeIntervalSince1970)")
    }
}
