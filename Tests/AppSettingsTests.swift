import XCTest
@testable import HonorPKAgent

@MainActor
final class AppSettingsTests: XCTestCase {
    func testSystemAppearanceUsesDarkAndOnboardingSurvivesRelaunch() {
        let suite = "honer.settings.test.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AppSettings(defaults: defaults)
        XCTAssertFalse(settings.completedOnboarding)
        settings.appearance = .system
        XCTAssertEqual(settings.preferredColorScheme, .dark)
        settings.displayName = "Сокол"
        settings.completedOnboarding = true
        settings.language = .english
        let restored = AppSettings(defaults: defaults)
        XCTAssertTrue(restored.completedOnboarding)
        XCTAssertEqual(restored.displayName, "Сокол")
        XCTAssertEqual(restored.language, .english)
        XCTAssertEqual(restored.preferredColorScheme, .dark)
    }
}
