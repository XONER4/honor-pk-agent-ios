import XCTest

/// Закреплённые инструкции, линии навигации с ветками и выбор голоса.
final class InstructionsNavigationTests: HonorAuditCase {
    func testPinMessageAsInstructionEditUnpinAndReuse() {
        let app = launch(["-UITestDemo"])
        // Закрепить ответ Honer AI как инструкцию — через меню сообщения.
        openMessageMenu("message.assistant." + assistantID, app: app)
        let pin = app.buttons["message.menu.pinInstruction"]
        XCTAssertTrue(pin.waitForExistence(timeout: 5))
        pin.tap()
        let bar = element(app, "chat.instructions.bar")
        XCTAssertTrue(bar.waitForExistence(timeout: 5), "Закреплённая инструкция должна висеть сверху чата")
        capture("40-pinned-instruction-bar")

        // Своё сообщение — тоже можно закрепить.
        openMessageMenu("message.user." + userID, app: app)
        app.buttons["message.menu.pinInstruction"].tap()
        XCTAssertTrue(bar.label.contains("Привет, как твои дела?") || bar.waitForExistence(timeout: 3))

        bar.tap()
        XCTAssertTrue(element(app, "instructions.sheet").waitForExistence(timeout: 5))
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "instructions.row."))
        XCTAssertEqual(rows.count, 2, "Обе закреплённые инструкции должны быть в списке")
        capture("41-instructions-sheet")

        // Изменить инструкцию.
        let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "instructions.edit.")).firstMatch
        edit.tap()
        let editor = app.textViews["instructions.editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.typeText(" Всегда отвечай кратко.")
        app.buttons["instructions.editor.save"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Всегда отвечай кратко.")).firstMatch.waitForExistence(timeout: 5))

        // Открепить — инструкция уходит в «Сохранённые», её можно закрепить снова.
        let unpin = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "instructions.unpin.")).firstMatch
        unpin.tap()
        XCTAssertEqual(rows.count, 1)
        let apply = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "instructions.saved.apply.")).firstMatch
        XCTAssertTrue(apply.waitForExistence(timeout: 5), "Откреплённая инструкция должна сохраниться")
        apply.tap()
        XCTAssertEqual(rows.count, 2)

        // Новая инструкция вручную.
        app.buttons["instructions.add"].tap()
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.typeText("Обращайся ко мне на ты.")
        app.buttons["instructions.editor.save"].tap()
        XCTAssertEqual(rows.count, 3)

        // Удалить.
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "instructions.delete.")).firstMatch.tap()
        XCTAssertEqual(rows.count, 2)
        app.buttons["instructions.done"].tap()
        XCTAssertTrue(bar.waitForExistence(timeout: 5))

        // Инструкции сохраняются между запусками.
        app.terminate()
        app.launchArguments = ["-UITestFixture"]
        app.launch()
        XCTAssertTrue(element(app, "chat.instructions.bar").waitForExistence(timeout: 10))
    }

    func testNavigationStripShowsPreviewJumpsAndBranches() {
        let app = launch(["-UITestDemo"])
        let strip = element(app, "chat.nav.strip")
        XCTAssertTrue(strip.waitForExistence(timeout: 5), "Линии навигации должны быть видны справа")
        strip.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.05)).tap()
        let preview = element(app, "chat.nav.preview")
        XCTAssertTrue(preview.waitForExistence(timeout: 5), "После выбора линии должно показаться превью сообщения")
        capture("42-navigation-preview")
        app.buttons["chat.nav.branch"].tap()
        XCTAssertTrue(element(app, "branch.back").waitForExistence(timeout: 5),
                      "«Продолжить отсюда» должно открыть новую ветку со ссылкой на исходный чат")
    }

    func testVoiceGenderChoiceDefaultsToMale() {
        let app = launch()
        settings(app)
        openSettingsRow("settings.voice", app: app)
        let gender = app.segmentedControls["voice.gender"]
        XCTAssertTrue(gender.waitForExistence(timeout: 5))
        XCTAssertTrue(gender.buttons["Мужской"].isSelected, "По умолчанию должен стоять мужской голос")
        gender.buttons["Женский"].tap()
        XCTAssertTrue(gender.buttons["Женский"].isSelected)
        XCTAssertTrue(element(app, "voice.active.ru").exists)
        XCTAssertTrue(element(app, "voice.active.en").exists)
        capture("43-voice-gender")
        gender.buttons["Мужской"].tap()
    }
}
