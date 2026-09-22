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
        XCTAssertTrue(app.staticTexts["Ветка · Приветствие"].exists)
        capture("audit-branch-history")
        relaunch(app)
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.staticTexts["Ветка · Приветствие"].waitForExistence(timeout: 5))
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
        enabled.tap()
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
        XCTAssertTrue(app.staticTexts["Audit renamed chat"].waitForExistence(timeout: 5))
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
        app.buttons["history.row." + chatID].tap()
        app.buttons["history.row." + pinnedID].tap()
        XCTAssertTrue(app.buttons["history.row." + chatID].isSelected)
        XCTAssertTrue(app.buttons["history.bulk.pin"].label.contains("Открепить"))
        app.buttons["history.bulk.pin"].tap()
        XCTAssertTrue(app.buttons["sidebar.settings"].waitForExistence(timeout: 5))

        app.buttons["history.select"].tap()
        app.buttons["history.row." + chatID].tap()
        app.buttons["history.row." + pinnedID].tap()
        XCTAssertTrue(app.buttons["history.bulk.pin"].label.contains("Закрепить"))
        app.buttons["history.bulk.delete"].tap()
        tapSystemAction("history.delete.confirm", title: "Удалить", app: app)
        waitAbsent(app.buttons["history.row." + chatID])
        XCTAssertFalse(app.buttons["history.row." + pinnedID].exists)
        XCTAssertTrue(app.staticTexts["Выбор смартфона"].exists)
        XCTAssertTrue(app.staticTexts["Дизайн приложения"].exists)
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

    /// UIKit-backed menus/alerts may expose their title instead of SwiftUI's identifier.
    private func tapSystemAction(_ identifier: String, title: String, app: XCUIApplication) {
        let identified = app.buttons[identifier]
        if identified.waitForExistence(timeout: 1) {
            identified.tap()
        } else {
            let titled = app.buttons[title].firstMatch
            XCTAssertTrue(titled.waitForExistence(timeout: 5), "Missing action: \(identifier)")
            titled.tap()
        }
    }
}
