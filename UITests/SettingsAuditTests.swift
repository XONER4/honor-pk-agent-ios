import XCTest

final class SettingsAuditTests: HonorAuditCase {
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

    func testAppearanceFontVoiceAPIAndAboutControls() {
        let app = launch()
        settings(app)
        openSettingsRow("settings.appearance", app: app)
        choose("appearance.light", fallback: "Светлый", app: app)
        capture("11-light-settings")
        openSettingsRow("settings.appearance", app: app)
        choose("appearance.dark", fallback: "Тёмный", app: app)
        openSettingsRow("settings.font", app: app)
        app.sliders["font.slider"].adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.staticTexts["140%"].exists)
        capture("12-font-preview")
        app.buttons["font.reset"].tap()
        XCTAssertTrue(app.staticTexts["100%"].exists)
        back(app)
        openSettingsRow("settings.voice", app: app)
        let toggle = app.switches["voice.autoRead"]
        XCTAssertTrue(toggle.exists)
        let oldValue = toggle.value as? String
        toggle.tap()
        XCTAssertNotEqual(toggle.value as? String, oldValue)
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
        openSettingsRow("settings.api", app: app)
        XCTAssertTrue(element(app, "api.status").exists)
        app.secureTextFields["api.key"].tap()
        app.secureTextFields["api.key"].typeText("sk-ui-audit-only")
        app.buttons["api.save"].tap()
        XCTAssertTrue(element(app, "api.saved").exists)
        app.buttons["api.reset"].tap()
        XCTAssertFalse(app.buttons["api.reset"].isEnabled)
        back(app)
        openSettingsRow("settings.about", app: app)
        XCTAssertTrue(app.staticTexts["Honor PK Agent"].exists)
        XCTAssertTrue(app.links["about.documentation"].exists || element(app, "about.documentation").exists)
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
        if app.buttons["Close"].exists { app.buttons["Close"].tap() }
        else { app.swipeDown() }
        XCTAssertTrue(app.buttons["data.import"].waitForExistence(timeout: 5))
        app.buttons["data.import"].tap()
        let cancel = app.buttons["Cancel"].exists ? app.buttons["Cancel"] : app.buttons["Отмена"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 8))
        cancel.tap()
        app.buttons["data.delete"].tap()
        app.buttons["data.delete.cancel"].tap()
        XCTAssertTrue(app.staticTexts["4"].exists)
        app.buttons["data.delete"].tap()
        app.buttons["data.delete.confirm"].tap()
        XCTAssertTrue(app.staticTexts["История удалена."].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["0"].exists)
    }

    private func choose(_ id: String, fallback: String, app: XCUIApplication) {
        let option = element(app, id)
        if option.exists { option.tap() }
        else { app.buttons[fallback].tap() }
    }
}
