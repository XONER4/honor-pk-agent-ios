import XCTest

/// Мини-игры и работа приложения под большой нагрузкой.
final class GamesStressTests: HonorAuditCase {
    func testGamesOpenAndPlay() {
        let app = launch(["-UITestDemo"])
        app.buttons["chat.tools"].tap()
        let games = app.buttons["chat.tools.games"]
        XCTAssertTrue(games.waitForExistence(timeout: 5))
        games.tap()
        XCTAssertTrue(element(app, "games.hub").waitForExistence(timeout: 5))
        capture("50-games-hub")

        // Слоты: барабаны крутятся и дают результат.
        app.buttons["games.play.slots"].tap()
        XCTAssertTrue(element(app, "game.screen.slots").waitForExistence(timeout: 5))
        app.buttons["slots.spin"].tap()
        XCTAssertTrue(element(app, "slots.result").waitForExistence(timeout: 8), "Барабаны должны остановиться и показать итог")
        capture("51-slots")
        app.buttons["game.close"].tap()

        // Шашки: ход человека и ответ соперника.
        app.buttons["chat.tools"].tap()
        app.buttons["chat.tools.games"].tap()
        app.buttons["games.play.checkers"].tap()
        let board = element(app, "checkers.board")
        XCTAssertTrue(board.waitForExistence(timeout: 5))
        // Белая шашка c3 (строка 5, столбец 2) → d4 (строка 4, столбец 3).
        board.coordinate(withNormalizedOffset: CGVector(dx: 2.5 / 8, dy: 5.5 / 8)).tap()
        board.coordinate(withNormalizedOffset: CGVector(dx: 3.5 / 8, dy: 4.5 / 8)).tap()
        let status = element(app, "game.status")
        XCTAssertTrue(status.waitForExistence(timeout: 5))
        let answered = NSPredicate(format: "label CONTAINS %@", "Ваш ход")
        expectation(for: answered, evaluatedWith: status)
        waitForExpectations(timeout: 15)
        capture("52-checkers")
        app.buttons["game.close"].tap()

        // Шахматы и дурак открываются.
        for kind in ["chess", "durak"] {
            app.buttons["chat.tools"].tap()
            app.buttons["chat.tools.games"].tap()
            app.buttons["games.play." + kind].tap()
            XCTAssertTrue(element(app, "game.screen." + kind).waitForExistence(timeout: 5))
            capture("53-" + kind)
            app.buttons["game.close"].tap()
        }
    }

    func testHugeHistoryStaysResponsive() {
        let app = launch(["-UITestLargeHistory"])
        let started = Date()
        // Быстрая прокрутка большого чата вверх и вниз.
        for _ in 0..<6 { app.swipeDown(velocity: .fast) }
        for _ in 0..<6 { app.swipeUp(velocity: .fast) }
        XCTAssertTrue(app.buttons["chat.sidebar"].isHittable, "Интерфейс должен отвечать после прокрутки")
        // Линии навигации по сотням сообщений.
        let strip = element(app, "chat.nav.strip")
        if strip.waitForExistence(timeout: 5) {
            strip.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
            XCTAssertTrue(element(app, "chat.nav.preview").waitForExistence(timeout: 5))
        }
        // Поиск по всем 500 чатам.
        app.buttons["chat.sidebar"].tap()
        let search = element(app, "history.search")
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("Проект 42")
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "history.row.", "Сохранённый чат 42")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8), "Поиск по истории должен найти нужный чат")
        capture("54-large-history-search")
        print("HONER_STRESS seconds=\(Date().timeIntervalSince(started))")
    }
}
