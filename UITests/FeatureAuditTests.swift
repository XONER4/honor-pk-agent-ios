import XCTest

final class FeatureAuditTests: HonorAuditCase {
    private let originalQuestion = "Привет, как твои дела?"
    private let originalAnswer = "Привет! У меня всё отлично, спасибо, что спросил. А как твои дела?"

    func testBranchPreservesOriginalAndReturnsToParent() {
        let app = launch(["-UITestDemo"])
        openMessageMenu("message.assistant." + assistantID, app: app)
        app.buttons["message.menu.fork"].tap()

        XCTAssertTrue(app.buttons["branch.back"].waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "branch.banner").exists)
        XCTAssertTrue(app.staticTexts[originalQuestion].exists)
        XCTAssertTrue(app.staticTexts[originalAnswer].exists)
        XCTAssertFalse(element(app, "message.assistant." + assistantID).exists,
                       "The branch must use copied message IDs, not mutate the original conversation")
        capture("audit-branch-created")

        app.buttons["branch.back"].tap()
        XCTAssertTrue(element(app, "message.user." + userID).waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "message.assistant." + assistantID).exists)
        XCTAssertFalse(element(app, "branch.banner").exists)
        XCTAssertTrue(app.staticTexts[originalAnswer].exists)

        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.buttons["history.row." + chatID].waitForExistence(timeout: 5))
        let branchRow = historyRow(titled: "Ветка · Приветствие", app: app)
        XCTAssertTrue(branchRow.waitForExistence(timeout: 5))
        capture("audit-branch-history")
        relaunch(app)
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(branchRow.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["history.row." + chatID].exists)
    }

    func testRememberRequiresSaveAndMemoryCanBeEditedDisabledAndDeleted() {
        let app = launch(["-UITestDemo"])
        openMessageMenu("message.assistant." + assistantID, app: app)
        app.buttons["message.menu.remember"].tap()
        let editor = app.textViews["memory.editor.text"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.value as? String, originalAnswer)
        replaceText("This must not be saved", in: editor)
        app.buttons["memory.editor.cancel"].tap()
        waitAbsent(editor)

        openMessageMenu("message.assistant." + assistantID, app: app)
        app.buttons["message.menu.remember"].tap()
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.value as? String, originalAnswer)
        let fact = "I prefer concise answers and metric units."
        replaceText(fact, in: editor)
        XCTAssertEqual(editor.value as? String, fact)
        XCTAssertTrue(app.buttons["memory.editor.save"].isEnabled)
        capture("audit-memory-confirmation")
        app.buttons["memory.editor.save"].tap()
        waitAbsent(editor)

        settings(app)
        openSettingsRow("settings.memory", app: app)
        let memoryRow = button(app, prefix: "memory.row.")
        XCTAssertTrue(memoryRow.waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "memory.row.")).count, 1,
                       "Cancelling the first editor must not add a memory")
        XCTAssertTrue(memoryRow.label.contains(fact))
        let enabled = app.switches["memory.enabled"]
        XCTAssertTrue(enabled.exists)
        XCTAssertEqual(enabled.value as? String, "1")
        setSwitch(enabled, to: false)
        XCTAssertEqual(enabled.value as? String, "0")
        capture("audit-memory-disabled")

        relaunch(app)
        settings(app)
        openSettingsRow("settings.memory", app: app)
        XCTAssertTrue(memoryRow.waitForExistence(timeout: 5))
        XCTAssertTrue(memoryRow.label.contains(fact))
        XCTAssertEqual(enabled.value as? String, "0", "The memory toggle must survive relaunch")
        memoryRow.tap()
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        let revised = "Use metric units and include a short example."
        replaceText(revised, in: editor)
        app.buttons["memory.editor.save"].tap()
        waitAbsent(editor)
        XCTAssertTrue(memoryRow.label.contains(revised))
        memoryRow.tap()
        XCTAssertTrue(app.buttons["memory.editor.delete"].waitForExistence(timeout: 5))
        app.buttons["memory.editor.delete"].tap()
        tapSystemAction("memory.editor.delete.confirm", title: "Удалить", app: app)
        XCTAssertTrue(element(app, "memory.empty").waitForExistence(timeout: 5))
        XCTAssertFalse(memoryRow.exists)
    }

    func testHistoryEllipsisRenamePinContentSearchAndBulkDelete() {
        let app = launch(["-UITestDemo"])
        app.buttons["chat.sidebar"].tap()
        let actions = app.buttons["history.actions." + chatID]
        XCTAssertTrue(actions.waitForExistence(timeout: 5))
        actions.tap()
        tapSystemAction("history.menu.rename", title: "Переименовать", app: app)
        let renameField = app.textFields["history.rename.field"].exists
            ? app.textFields["history.rename.field"] : app.alerts.textFields.firstMatch
        XCTAssertTrue(renameField.waitForExistence(timeout: 5))
        replaceText("Audit renamed chat", in: renameField)
        tapSystemAction("history.rename.save", title: "Сохранить", app: app)
        XCTAssertTrue(historyRow(titled: "Audit renamed chat", app: app).waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["sidebar.settings"].exists, "Using the ellipsis must keep the drawer open")

        actions.tap()
        tapSystemAction("history.menu.pin", title: "Закрепить", app: app)
        let search = app.textFields["history.search"]
        search.tap()
        search.typeText("спросил")
        XCTAssertTrue(app.buttons["history.row." + chatID].exists,
                      "Search must match assistant content even after the chat is renamed")
        XCTAssertFalse(app.buttons["history.row." + pinnedID].exists)
        app.buttons["history.clear"].tap()
        app.buttons["history.select"].tap()
        selectHistoryRow(chatID, app: app)
        selectHistoryRow(pinnedID, app: app)
        XCTAssertTrue(app.buttons["history.bulk.pin"].label.contains("Открепить"))
        app.buttons["history.bulk.pin"].tap()
        XCTAssertTrue(app.buttons["sidebar.settings"].waitForExistence(timeout: 5))

        app.buttons["history.select"].tap()
        selectHistoryRow(chatID, app: app)
        selectHistoryRow(pinnedID, app: app)
        XCTAssertTrue(app.buttons["history.bulk.pin"].label.contains("Закрепить"))
        app.buttons["history.bulk.delete"].tap()
        tapSystemAction("history.delete.confirm", title: "Удалить", app: app)
        waitAbsent(app.buttons["history.row." + chatID])
        XCTAssertFalse(app.buttons["history.row." + pinnedID].exists)
        XCTAssertTrue(historyRow(titled: "Выбор смартфона", app: app).exists)
        XCTAssertTrue(historyRow(titled: "Дизайн приложения", app: app).exists)
        capture("audit-history-after-bulk-delete")
    }

    func testEditCanBeCancelledOrSubmittedAndVoiceRemainsAvailable() {
        let app = launch(["-UITestDemo"])
        openMessageMenu("message.user." + userID, app: app)
        app.buttons["message.menu.edit"].tap()
        let field = composer(app)
        XCTAssertEqual(field.value as? String, originalQuestion)
        XCTAssertTrue(app.buttons["chat.voice"].exists,
                      "Dictation must remain available when a typed draft is present")
        replaceText("Discard this edit", in: field)
        app.buttons["composer.edit.cancel"].tap()
        XCTAssertTrue(element(app, "message.user." + userID).exists)
        XCTAssertTrue(element(app, "message.assistant." + assistantID).exists)
        XCTAssertTrue(app.staticTexts[originalQuestion].exists)

        // Close the native keyboard before targeting the original message again.
        app.buttons["chat.sidebar"].tap()
        app.buttons["history.row." + chatID].tap()
        openMessageMenu("message.user." + userID, app: app)
        app.buttons["message.menu.edit"].tap()
        replaceText("Edited prompt for the audit", in: composer(app))
        app.buttons["chat.send"].tap()
        XCTAssertTrue(app.staticTexts["Готово. Тестовый ответ Honor."].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Edited prompt for the audit"].exists)
        XCTAssertFalse(element(app, "message.user." + userID).exists)
        XCTAssertFalse(element(app, "message.assistant." + assistantID).exists)
        XCTAssertFalse(app.buttons["composer.edit.cancel"].exists)
    }

    func testFeedbackReasoningSelectionAndCopyActions() {
        let app = launch(["-UITestDemo"])
        let like = app.buttons["message.action.like." + assistantID]
        let dislike = app.buttons["message.action.dislike." + assistantID]
        XCTAssertTrue(like.waitForExistence(timeout: 5))
        like.tap()
        XCTAssertTrue(like.isSelected)
        dislike.tap()
        XCTAssertFalse(like.isSelected)
        XCTAssertTrue(dislike.isSelected)

        openMessageMenu("message.assistant." + assistantID, app: app)
        app.buttons["message.menu.like"].tap()
        waitAbsent(app.buttons["message.menu.dismiss"])
        XCTAssertTrue(like.isSelected)
        XCTAssertFalse(dislike.isSelected)
        let reasoning = app.buttons["message.reasoning." + assistantID]
        reasoning.tap()
        XCTAssertTrue(element(app, "message.reasoning.text." + assistantID).waitForExistence(timeout: 5))
        reasoning.tap()
        waitAbsent(element(app, "message.reasoning.text." + assistantID))

        openMessageMenu("message.assistant." + assistantID, app: app)
        app.buttons["message.menu.select"].tap()
        let selection = app.textViews["message.selection.text"]
        XCTAssertTrue(selection.waitForExistence(timeout: 5))
        XCTAssertEqual(selection.value as? String, originalAnswer)
        app.buttons["message.selection.done"].tap()
        waitAbsent(selection)
        app.buttons["message.action.copy." + assistantID].tap()
        XCTAssertTrue(app.staticTexts["Скопировано"].waitForExistence(timeout: 3))
    }

    func testLargeFontMessageMenuStillReachesNewActions() {
        let app = launch(["-UITestDemo"])
        settings(app)
        openSettingsRow("settings.font", app: app)
        let slider = app.sliders["font.slider"]
        XCTAssertTrue(slider.waitForExistence(timeout: 5))
        slider.adjust(toNormalizedSliderPosition: 1)
        assertDisplayedValue("140%", id: "font.value", app: app)
        back(app)
        app.buttons["settings.close"].tap()

        openMessageMenu("message.assistant." + assistantID, app: app)
        let popup = element(app, "message.menu.scroll")
        XCTAssertTrue(popup.waitForExistence(timeout: 5))
        let lastAction = app.buttons["message.menu.share"]
        for _ in 0..<4 {
            if lastAction.isHittable { break }
            popup.swipeUp()
        }
        XCTAssertTrue(lastAction.isHittable, "The last menu action must remain reachable at 140% text size")
        popup.swipeUp()
        capture("audit-maximum-font-message-menu")

        let remember = app.buttons["message.menu.remember"]
        for _ in 0..<4 {
            if remember.isHittable { break }
            popup.swipeDown()
        }
        XCTAssertTrue(remember.isHittable)
        remember.tap()
        let editor = app.textViews["memory.editor.text"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertEqual(editor.value as? String, originalAnswer)
        app.buttons["memory.editor.cancel"].tap()
    }

    /// UIKit-backed menus/alerts may expose their title instead of SwiftUI's identifier.
    private func tapSystemAction(_ identifier: String, title: String, app: XCUIApplication) {
        let identified = app.buttons.matching(identifier: identifier).firstMatch
        if identified.waitForExistence(timeout: 1) {
            identified.tap()
        } else {
            let titled = app.buttons[title].firstMatch
            XCTAssertTrue(titled.waitForExistence(timeout: 5), "Missing action: \(identifier)")
            titled.tap()
        }
    }

    private func historyRow(titled title: String, app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "history.row.", title)).firstMatch
    }

    private func selectHistoryRow(_ id: String, app: XCUIApplication) {
        let row = app.buttons["history.row." + id]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        XCTAssertTrue(row.isEnabled)
        XCTAssertTrue(app.frame.contains(row.frame), "Selection row must be visible before tapping its circle")
        // SwiftUI exposes a full-row StaticText inside the selection button, so XCTest
        // cannot resolve the parent's hit point. Exercise its visible circle directly.
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.085, dy: 0.5)).tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed,
                       "Tapping the row's selection circle must select that conversation")
    }
}
