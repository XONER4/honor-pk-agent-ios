import XCTest

final class OnboardingTests: HonorAuditCase {
    func testFirstLaunchNicknamePersistsAndIsEditable() {
        let app = XCUIApplication()
        app.launchArguments = ["-UITestReset", "-UITestFixture", "-UITestOnboarding"]
        app.launch()
        let field = app.textFields["onboarding.name"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["onboarding.continue"].isEnabled)
        field.tap()
        field.typeText("Vlad")
        capture("30-onboarding")
        app.buttons["onboarding.continue"].tap()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        app.terminate()
        app.launchArguments = ["-UITestPersist", "-UITestFixture", "-UITestOnboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["chat.sidebar"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.textFields["onboarding.name"].exists)
        settings(app)
        openSettingsRow("settings.profile", app: app)
        XCTAssertEqual(app.textFields["profile.name"].value as? String, "Vlad")
        replaceText("Pilot", in: app.textFields["profile.name"])
        back(app)
        app.buttons["settings.close"].tap()
        app.buttons["chat.sidebar"].tap()
        XCTAssertTrue(app.staticTexts["Pilot"].exists)
    }
}
