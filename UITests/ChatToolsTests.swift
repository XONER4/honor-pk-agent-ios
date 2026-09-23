import XCTest

final class ChatToolsTests: HonorAuditCase {
    func testConversationMenuPinAndFindNavigateActualMessages() {
        let app = launch(["-UITestDemo"])
        openTools(app)
        choose("chat.tools.share", title: "Поделиться чатом", app: app)
        let closeShare = app.buttons["header.closeButton"].firstMatch
        XCTAssertTrue(closeShare.waitForExistence(timeout: 15))
        closeShare.tap()
        waitAbsent(closeShare)
        openTools(app)
        choose("chat.tools.pin", title: "Закрепить", app: app)
        openTools(app)
        XCTAssertTrue(app.buttons.matching(identifier: "chat.tools.pin").firstMatch.label.contains("Открепить"))
        choose("chat.tools.find", title: "Найти в чате", app: app)
        let field = app.textFields["chat.find.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("дела")
        assertDisplayedValue("1 / 2", id: "chat.find.count", app: app)
        app.buttons["chat.find.next"].tap()
        assertDisplayedValue("2 / 2", id: "chat.find.count", app: app)
        XCTAssertTrue(element(app, "message.assistant." + assistantID).exists)
        capture("honer10-find-in-chat")
        app.buttons["chat.find.previous"].tap()
        assertDisplayedValue("1 / 2", id: "chat.find.count", app: app)
        replaceText("no-match-928461", in: field)
        assertDisplayedValue("0 / 0", id: "chat.find.count", app: app)
        XCTAssertFalse(app.buttons["chat.find.next"].isEnabled)
        app.buttons["chat.find.close"].tap()
        waitAbsent(field)
        XCTAssertTrue(element(app, "message.user." + userID).exists)
    }

    func testArchiveMovesConversationAndRestorePreservesItsMessages() {
        let app = launch(["-UITestDemo"])
        openTools(app)
        choose("chat.tools.archive", title: "В архив", app: app)
        XCTAssertTrue(element(app, "welcomeMessage").waitForExistence(timeout: 5))
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.buttons["sidebar.settings"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["history.row." + chatID].exists)
        app.buttons["sidebar.settings"].tap()
        openSettingsRow("settings.archive", app: app)
        let restore = app.buttons["archive.restore." + chatID]
        XCTAssertTrue(restore.waitForExistence(timeout: 5))
        capture("honer10-archived-conversation")
        restore.tap()
        waitAbsent(restore)
        back(app)
        app.buttons["settings.close"].tap()
        app.buttons["chat.sidebar"].tap()
        let row = app.buttons["history.row." + chatID]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(element(app, "message.user." + userID).waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "message.content." + assistantID).label.contains("спасибо, что спросил"))
    }

    func testUploadedFilesOpenOriginalAndSourceDetailsExposeReadPage() {
        let app = launch(["-UITestDemo", "-UITestChatTools"])
        openTools(app)
        choose("chat.tools.attachments", title: "Загруженные файлы", app: app)
        let file = app.buttons["chat.attachments.file.33333333-3333-4333-8333-333333333333"]
        XCTAssertTrue(file.waitForExistence(timeout: 5))
        file.tap()
        XCTAssertTrue(app.buttons["attachment.preview.share"].waitForExistence(timeout: 8),
                      "Preview must resolve the original file, not only extracted text")
        capture("honer10-original-file")
        app.buttons["attachment.preview.close"].tap()
        app.buttons["chat.attachments.close"].tap()
        let sources = app.buttons["message.sources." + assistantID]
        XCTAssertTrue(sources.waitForExistence(timeout: 5))
        sources.tap()
        XCTAssertTrue(app.buttons["sources.close"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["support.apple.com"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "sources.row.")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts["6.1-inch display"].exists)
        let link = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.source.")).firstMatch
        XCTAssertTrue(link.exists)
        XCTAssertTrue(link.label.contains("iPhone 13"))
        capture("honer10-source-details")
        app.buttons["sources.close"].tap()
        app.buttons["message.reasoning." + assistantID].tap()
        let read = app.buttons["message.sources.read." + assistantID]
        XCTAssertTrue(read.waitForExistence(timeout: 5))
        XCTAssertTrue(read.label.contains("1"))
        read.tap()
        XCTAssertTrue(app.navigationBars["Прочитанные страницы"].waitForExistence(timeout: 5))
    }

    func testVoiceModePreservesDraftAndDeleteRequiresConfirmation() {
        let app = launch(["-UITestDemo"])
        typeMessage("Сохранённый черновик", app: app)
        app.buttons["chat.voice"].tap()
        XCTAssertTrue(element(app, "chat.voice.hold").waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["chat.voice"].label, "Клавиатура")
        capture("honer10-hold-to-talk-composer")
        app.buttons["chat.voice"].tap()
        XCTAssertEqual(composer(app).value as? String, "Сохранённый черновик")
        openTools(app)
        choose("chat.tools.delete", title: "Удалить", app: app)
        choose("chat.delete.cancel", title: "Отмена", app: app)
        XCTAssertTrue(element(app, "message.user." + userID).exists)
        openTools(app)
        choose("chat.tools.delete", title: "Удалить", app: app)
        choose("chat.delete.confirm", title: "Удалить", app: app)
        XCTAssertTrue(element(app, "welcomeMessage").waitForExistence(timeout: 5))
        app.buttons["chat.sidebar"].tap()
        XCTAssertFalse(app.buttons["history.row." + chatID].exists)
    }

    func testHoldVoiceSwipeCancelDiscardsAndReleaseSendsFinalTranscript() {
        let app = launch(["-UITestDemo", "-UITestVoice"])
        typeMessage("Не отправлять", app: app)
        app.buttons["chat.voice"].tap()
        let hold = element(app, "chat.voice.hold")
        XCTAssertTrue(hold.waitForExistence(timeout: 5))
        let start = hold.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let cancel = start.withOffset(CGVector(dx: 0, dy: -150))
        start.press(forDuration: 0.7, thenDragTo: cancel)
        waitAbsent(element(app, "chat.voice.cancel.overlay"))
        app.buttons["chat.voice"].tap()
        XCTAssertEqual(composer(app).value as? String, "Не отправлять")
        XCTAssertEqual(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.content.")).count, 1)
        let field = composer(app)
        // Typing an empty string leaves Select All unchanged; delete the selected draft.
        replaceText(XCUIKeyboardKey.delete.rawValue, in: field)
        let clearedValue = field.value as? String ?? ""
        XCTAssertTrue(clearedValue.isEmpty || clearedValue == field.placeholderValue,
                      "The next recording must start with an empty draft")
        app.buttons["chat.voice"].tap()
        XCTAssertTrue(hold.waitForExistence(timeout: 5))
        hold.press(forDuration: 0.7)
        XCTAssertTrue(app.staticTexts["Проверка голосового ввода"].waitForExistence(timeout: 8),
                      "Release must submit the finalized transcript exactly once")
        XCTAssertEqual(app.staticTexts.matching(identifier: "Проверка голосового ввода").count, 1)
        XCTAssertEqual(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "message.user.")).count, 2,
                       "Cancel must not create a message and release must create exactly one")
        waitAbsent(element(app, "chat.voice.recording.overlay"))
    }

    private func openTools(_ app: XCUIApplication) {
        let tools = app.buttons["chat.tools"]
        XCTAssertTrue(tools.waitForExistence(timeout: 5))
        XCTAssertTrue(tools.isEnabled)
        XCTAssertTrue(app.frame.contains(tools.frame), "Conversation menu must be inside the visible header")
        // Native SwiftUI Menu can report no AX hit point despite its on-screen label.
        // Tap that visible label, then require the real menu contents to appear.
        tools.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons.matching(identifier: "chat.tools.find").firstMatch.waitForExistence(timeout: 5))
    }

    private func choose(_ identifier: String, title: String, app: XCUIApplication) {
        let identified = app.buttons.matching(identifier: identifier).firstMatch
        if identified.waitForExistence(timeout: 1) { identified.tap() }
        else {
            let labelled = app.buttons[title].firstMatch
            XCTAssertTrue(labelled.waitForExistence(timeout: 5))
            labelled.tap()
        }
    }
}
