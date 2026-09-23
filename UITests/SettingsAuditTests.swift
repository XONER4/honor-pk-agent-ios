import XCTest

final class SettingsAuditTests: HonorAuditCase {
    func testEnglishSettingsAndChatMenuRemainUsable() {
        let app = launch(["-UITestDemo"])
        settings(app)
        openSettingsRow("settings.language", app: app)
        app.buttons["language.en"].tap()
        back(app)
        XCTAssertTrue(app.navigationBars["Settings"].exists)
        capture("34-english-settings")
        openSettingsRow("settings.font", app: app)
        XCTAssertTrue(app.navigationBars["Font size"].exists)
        app.buttons["font.reset"].tap()
        back(app)
        openSettingsRow("settings.personalization", app: app)
        XCTAssertTrue(app.navigationBars["Personalization"].exists)
        XCTAssertTrue(app.textViews["personalization.instructions"].exists)
        back(app)
        openSettingsRow("settings.memory", app: app)
        XCTAssertTrue(app.buttons["memory.add"].exists)
        back(app)
        openSettingsRow("settings.voice", app: app)
        XCTAssertTrue(app.navigationBars["Voice"].exists)
        XCTAssertTrue(app.buttons["voice.preview"].exists)
        back(app)
        openSettingsRow("settings.about", app: app)
        XCTAssertTrue(app.navigationBars["About"].exists)
        back(app)
        app.buttons["settings.close"].tap()
        let tools = app.buttons["chat.tools"]
        XCTAssertTrue(tools.isEnabled && app.frame.contains(tools.frame))
        tools.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["chat.tools.find"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["chat.tools.find"].label.contains("Find"))
        app.buttons["chat.tools.find"].tap()
        XCTAssertTrue(app.textFields["chat.find.field"].waitForExistence(timeout: 5))
        app.buttons["chat.find.close"].tap()
        capture("35-english-chat")
    }

    func testBundledPersonalizationVideoOpensAndCloses() {
        let app = launch()
        settings(app)
        openSettingsRow("settings.personalization", app: app)
        let video = app.buttons["personalization.video"]
        for _ in 0..<4 where !video.isHittable { app.swipeUp() }
        XCTAssertTrue(video.exists)
        video.tap()
        XCTAssertTrue(element(app, "personalization.video.player").waitForExistence(timeout: 8))
        capture("36-personalization-video-player")
        app.buttons["personalization.video.close"].tap()
        XCTAssertTrue(app.textViews["personalization.instructions"].waitForExistence(timeout: 5))
    }

    func testProfilePersonalizationAndLanguagePersist() {
        let app = launch(["-UITestDemo"])
        settings(app)
        openSettingsRow("settings.profile", app: app)
        replaceText("Alex Honor", in: app.textFields["profile.name"])
        back(app)
        openSettingsRow("settings.personalization", app: app)
        let instructions = app.textViews["personalization.instructions"]
        replaceText("Keep answers concise.", in: instructions)
        back(app)
        openSettingsRow("settings.language", app: app)
        app.buttons["language.en"].tap()
        XCTAssertTrue(app.navigationBars["Language"].exists)
        app.buttons["language.ru"].tap()
        back(app)
        app.buttons["settings.close"].tap()
        relaunch(app)
        settings(app)
        openSettingsRow("settings.profile", app: app)
        XCTAssertEqual(app.textFields["profile.name"].value as? String, "Alex Honor")
        back(app)
        openSettingsRow("settings.personalization", app: app)
        XCTAssertEqual(app.textViews["personalization.instructions"].value as? String, "Keep answers concise.")
        app.buttons["personalization.clear"].tap()
        XCTAssertEqual(app.textViews["personalization.instructions"].value as? String, "")
    }

    func testAppearanceFontVoiceAndAboutControls() {
        let app = launch()
        settings(app)
        openSettingsRow("settings.appearance", app: app)
        choose("appearance.light", fallback: "Светлый", app: app)
        capture("11-light-settings")
        openSettingsRow("settings.appearance", app: app)
        choose("appearance.dark", fallback: "Тёмный", app: app)
        openSettingsRow("settings.font", app: app)
        app.sliders["font.slider"].adjust(toNormalizedSliderPosition: 1)
        assertDisplayedValue("140%", id: "font.value", app: app)
        capture("12-font-preview")
        app.buttons["font.reset"].tap()
        assertDisplayedValue("100%", id: "font.value", app: app)
        back(app)
        openSettingsRow("settings.voice", app: app)
        XCTAssertFalse(app.switches["voice.autoRead"].exists, "Reading is controlled in the chat header")
        app.buttons["voice.option.system"].tap()
        app.buttons["voice.preview"].tap()
        XCTAssertTrue(app.buttons["voice.preview"].exists)
        // Voice preview can complete immediately if this simulator has no downloaded voice.
        if app.buttons["voice.preview"].value as? String == "speaking" { app.buttons["voice.preview"].tap() }
        back(app)
        openSettingsRow("settings.speechLanguage", app: app)
        choose("speechLanguage.en-US", fallback: "English (US)", app: app)
        openSettingsRow("settings.speechLanguage", app: app)
        choose("speechLanguage.ru-RU", fallback: "Русский", app: app)
        XCTAssertFalse(element(app, "settings.api").exists, "Connection settings must be hidden")
        openSettingsRow("settings.about", app: app)
        XCTAssertTrue(app.staticTexts["Honer AI"].firstMatch.exists)
        XCTAssertFalse(element(app, "about.documentation").exists)
        XCTAssertTrue(app.staticTexts["10.0 (10)"].exists)
    }

    func testDataExportImportPickerAndDeleteConfirmation() {
        let app = launch(["-UITestDemo"])
        settings(app)
        openSettingsRow("settings.data", app: app)
        app.buttons["data.export"].tap()
        let activity = app.otherElements["ActivityListView"]
        let shareCopy = app.buttons["Copy"]
        XCTAssertTrue(activity.waitForExistence(timeout: 10) || shareCopy.waitForExistence(timeout: 5), "Export must open the native share sheet")
        capture("13-backup-export")
        let shareClose = app.buttons["header.closeButton"].firstMatch
        XCTAssertTrue(shareClose.waitForExistence(timeout: 8))
        shareClose.tap()
        waitAbsent(activity)
        XCTAssertTrue(app.buttons["data.import"].waitForExistence(timeout: 5))
        app.buttons["data.import"].tap()
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["Cancel", "Отмена"])).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 8))
        cancel.tap()
        app.buttons["data.delete"].tap()
        app.buttons["data.delete.cancel"].firstMatch.tap()
        assertDisplayedValue("4", id: "data.count", app: app)
        app.buttons["data.delete"].tap()
        app.buttons["data.delete.confirm"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["История удалена."].waitForExistence(timeout: 5))
        assertDisplayedValue("0", id: "data.count", app: app)
    }

    private func choose(_ id: String, fallback: String, app: XCUIApplication) {
        let option = element(app, id)
        if option.exists { option.tap() }
        else { app.buttons[fallback].tap() }
    }
}
