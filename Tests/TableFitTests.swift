import XCTest
@testable import HonorPKAgent

/// Компактная таблица: столбцы не уже самого длинного слова, сумма ширин — ровно ширина сообщения.
final class TableFitTests: XCTestCase {
    func testLongestWordsIgnoreMarkup() {
        XCTAssertEqual(TableColumnLayout.longestWords("**Меркурий** и Венера"), ["Меркурий", "Венера"])
        XCTAssertEqual(TableColumnLayout.longestWords("  "), [])
    }

    func testWidthsRespectMinimumsAndFillAvailable() throws {
        let widths = try XCTUnwrap(TableColumnLayout.fitWidths(shares: [0.2, 0.4, 0.4], minimums: [90, 40, 40], available: 300))
        XCTAssertGreaterThanOrEqual(widths[0], 90)
        XCTAssertEqual(widths.reduce(0, +), 300, accuracy: 0.5)
        // Остаток делится между остальными по долям.
        XCTAssertEqual(widths[1], widths[2], accuracy: 0.5)
    }

    func testSharesUsedWhenMinimumsAreSmall() throws {
        let widths = try XCTUnwrap(TableColumnLayout.fitWidths(shares: [0.25, 0.75], minimums: [10, 10], available: 400))
        XCTAssertEqual(widths[0], 100, accuracy: 0.5)
        XCTAssertEqual(widths[1], 300, accuracy: 0.5)
    }

    func testTooNarrowFallsBackToWideTable() {
        XCTAssertNil(TableColumnLayout.fitWidths(shares: [0.5, 0.5], minimums: [200, 200], available: 300))
    }
}
