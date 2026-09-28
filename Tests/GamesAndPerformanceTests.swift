import XCTest
@testable import HonorPKAgent

/// Правила мини-игр и соперник Honer AI.
final class GamesTests: XCTestCase {
    private func perft(_ board: ChessBoard, _ depth: Int) -> Int {
        guard depth > 0 else { return 1 }
        return board.legalMoves().reduce(0) { total, move in
            var next = board
            next.apply(move)
            return total + perft(next, depth - 1)
        }
    }

    func testChessMoveGenerationMatchesReferencePerft() {
        // Эталонные числа позиций из начальной расстановки: 20, 400, 8902.
        let board = ChessBoard()
        XCTAssertEqual(perft(board, 1), 20)
        XCTAssertEqual(perft(board, 2), 400)
        XCTAssertEqual(perft(board, 3), 8902, "Генератор ходов шахмат ошибается")
    }

    func testChessCheckmateCastlingAndEnPassant() throws {
        // Детский мат: 1.f3 e5 2.g4 Qh4#.
        var board = ChessBoard()
        func play(_ from: Int, _ to: Int) {
            let move = board.legalMoves().first { $0.from == from && $0.to == to }
            XCTAssertNotNil(move, "Ход \(from)→\(to) должен быть допустим")
            if let move { board.apply(move) }
        }
        play(53, 45); play(12, 28); play(54, 38); play(3, 39)
        XCTAssertEqual(board.status, .checkmate(winner: .black))

        // Рокировка: король e1 и ладья h1 на месте, поля свободны.
        var cells = [ChessBoard.Piece?](repeating: nil, count: 64)
        cells[60] = .init(kind: .king, side: .white, id: 1)
        cells[63] = .init(kind: .rook, side: .white, id: 2)
        cells[4] = .init(kind: .king, side: .black, id: 3)
        var castle = ChessBoard(cells: cells, turn: .white, castling: ["K"])
        let move = try XCTUnwrap(castle.legalMoves().first { $0.from == 60 && $0.to == 62 })
        castle.apply(move)
        XCTAssertEqual(castle.cells[61]?.kind, .rook)
        XCTAssertEqual(castle.cells[62]?.kind, .king)

        // Взятие на проходе.
        var pawns = [ChessBoard.Piece?](repeating: nil, count: 64)
        pawns[60] = .init(kind: .king, side: .white, id: 1)
        pawns[4] = .init(kind: .king, side: .black, id: 2)
        pawns[28] = .init(kind: .pawn, side: .white, id: 3)   // e5
        pawns[11] = .init(kind: .pawn, side: .black, id: 4)   // d7
        var passant = ChessBoard(cells: pawns, turn: .black)
        passant.apply(try XCTUnwrap(passant.legalMoves().first { $0.from == 11 && $0.to == 27 }))
        let capture = try XCTUnwrap(passant.legalMoves().first { $0.enPassant })
        passant.apply(capture)
        XCTAssertNil(passant.cells[27], "Пешка, взятая на проходе, должна исчезнуть")
        XCTAssertEqual(passant.cells[19]?.kind, .pawn)
    }

    func testChessAIRepliesQuicklyWithALegalMove() throws {
        var board = ChessBoard()
        board.apply(try XCTUnwrap(board.legalMoves().first { $0.from == 52 && $0.to == 36 }))
        let started = Date()
        let reply = try XCTUnwrap(ChessAI.bestMove(for: board))
        XCTAssertLessThan(Date().timeIntervalSince(started), 8)
        XCTAssertTrue(board.legalMoves().contains(reply))
        // Соперник забирает незащищённого ферзя.
        var cells = [ChessBoard.Piece?](repeating: nil, count: 64)
        cells[60] = .init(kind: .king, side: .white, id: 1)
        cells[4] = .init(kind: .king, side: .black, id: 2)
        cells[36] = .init(kind: .queen, side: .white, id: 3)
        cells[27] = .init(kind: .knight, side: .black, id: 4)   // конь бьёт e4? нет: d5 → e3/c3/…
        cells[28] = .init(kind: .rook, side: .black, id: 5)     // ладья e5 бьёт ферзя e4
        let hanging = ChessBoard(cells: cells, turn: .black)
        XCTAssertEqual(ChessAI.bestMove(for: hanging, depth: 2)?.to, 36, "Соперник должен взять ферзя")
    }

    func testCheckersRulesCaptureIsMandatoryKingsFlyAndMultiJump() {
        let board = CheckersBoard()
        XCTAssertEqual(board.count(.white), 12)
        XCTAssertEqual(board.count(.black), 12)
        XCTAssertEqual(board.legalMoves().count, 7)

        // Обязательное взятие с продолжением: белая шашка бьёт две чёрные подряд.
        var cells = [CheckersBoard.Piece?](repeating: nil, count: 64)
        cells[5 * 8 + 0] = .init(side: .white, king: false, id: 1)   // a3
        cells[4 * 8 + 1] = .init(side: .black, king: false, id: 2)   // b4
        cells[2 * 8 + 3] = .init(side: .black, king: false, id: 3)   // d6
        cells[7 * 8 + 6] = .init(side: .white, king: false, id: 4)
        let jump = CheckersBoard(cells: cells, turn: .white)
        let moves = jump.legalMoves()
        XCTAssertTrue(moves.allSatisfy { !$0.captured.isEmpty }, "Бить обязательно")
        XCTAssertEqual(moves.first?.captured.count, 2, "Взятие продолжается, пока есть что бить")

        // Дамка бьёт издалека.
        var far = [CheckersBoard.Piece?](repeating: nil, count: 64)
        far[7 * 8 + 0] = .init(side: .white, king: true, id: 1)      // a1
        far[3 * 8 + 4] = .init(side: .black, king: false, id: 2)     // e5
        let flying = CheckersBoard(cells: far, turn: .white)
        XCTAssertTrue(flying.legalMoves().contains { $0.captured == [3 * 8 + 4] })

        // Шашка, дошедшая до последнего ряда, становится дамкой.
        var promo = [CheckersBoard.Piece?](repeating: nil, count: 64)
        promo[1 * 8 + 2] = .init(side: .white, king: false, id: 1)
        promo[7 * 8 + 7] = .init(side: .black, king: false, id: 2)
        var promotion = CheckersBoard(cells: promo, turn: .white)
        let step = promotion.legalMoves().first!
        promotion.apply(step)
        XCTAssertEqual(promotion.cells[step.to]?.king, true)
    }

    func testCheckersAIPlaysAWholeGameWithoutHanging() {
        var board = CheckersBoard()
        var plies = 0
        let started = Date()
        while board.winner == nil && plies < 200 {
            let move = board.turn == .white ? board.legalMoves().randomElement() : CheckersAI.bestMove(for: board, depth: 4)
            guard let move else { break }
            board.apply(move)
            plies += 1
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 60)
        print("HONER_GAME checkers plies=\(plies) winner=\(String(describing: board.winner))")
    }

    func testDurakFullGamesAlwaysFinish() {
        for round in 0..<20 {
            var game = DurakGame()
            XCTAssertEqual(game.hand(.human).count, 6)
            XCTAssertEqual(game.hand(.ai).count, 6)
            XCTAssertEqual(game.deck.count, 24)
            var steps = 0
            while game.outcome == nil && steps < 2000 {
                steps += 1
                if game.aiStep() { continue }
                // Простой «человек»: атакует младшей, подкидывает, отбивается или берёт.
                if game.attacker == .human {
                    if let card = game.hand(.human).first(where: { game.canAttack(with: $0, by: .human) }),
                       game.table.isEmpty || game.allBeaten || game.defenderTakes {
                        _ = game.attack(with: card, by: .human)
                    } else if !game.table.isEmpty && (game.allBeaten || game.defenderTakes) {
                        game.finishRound()
                    }
                } else if let open = game.table.first(where: { $0.defense == nil }) {
                    if let card = game.defenses(for: open.attack).first {
                        _ = game.defend(open.attack, with: card)
                    } else {
                        game.declareTake()
                    }
                }
            }
            XCTAssertNotNil(game.outcome, "Партия \(round) не закончилась за \(steps) шагов")
            // Карты не теряются и не появляются.
            let total = game.hand(.human).count + game.hand(.ai).count + game.deck.count + game.discard.count
                + game.table.reduce(0) { $0 + 1 + ($1.defense == nil ? 0 : 1) }
            XCTAssertEqual(total, 36)
        }
    }

    func testDurakBeatsRespectTrump() {
        let game = DurakGame()
        let trump = game.trumpSuit
        let other = DurakCard.Suit.allCases.first { $0 != trump }!
        XCTAssertTrue(game.beats(DurakCard(suit: trump, rank: 6), DurakCard(suit: other, rank: 14)))
        XCTAssertFalse(game.beats(DurakCard(suit: other, rank: 14), DurakCard(suit: trump, rank: 6)))
        XCTAssertTrue(game.beats(DurakCard(suit: other, rank: 9), DurakCard(suit: other, rank: 8)))
        XCTAssertFalse(game.beats(DurakCard(suit: other, rank: 7), DurakCard(suit: other, rank: 8)))
    }

    @MainActor
    func testSlotsPayouts() {
        XCTAssertEqual(SlotsGameView.payout(["💎", "💎", "💎"], bet: 10), 500)
        XCTAssertEqual(SlotsGameView.payout(["🍒", "🍒", "🍋"], bet: 10), 20)
        XCTAssertEqual(SlotsGameView.payout(["🍒", "🍋", "🔔"], bet: 10), 0)
        XCTAssertEqual(GameKind.from("давай в шахматы"), .chess)
        XCTAssertEqual(GameKind.from("durak"), .durak)
    }
}

/// Нагрузка: огромные ответы, память и переписка не должны тормозить.
@MainActor
final class PerformanceStressTests: XCTestCase {
    func testHugeMarkdownParsesFast() {
        var text = ""
        for index in 0..<400 {
            text += "## Раздел \(index)\n**Жирный** текст и [ссылка](https://example.com/\(index)). Обычное предложение для объёма.\n- пункт\n- пункт\n\n"
            if index % 20 == 0 { text += "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\n```swift\nlet x = \(index)\n```\n\n" }
        }
        let started = Date()
        let blocks = MarkdownBlockParser.parse(text)
        let elapsed = Date().timeIntervalSince(started)
        print("HONER_PERF markdown chars=\(text.count) blocks=\(blocks.count) seconds=\(elapsed)")
        XCTAssertGreaterThan(blocks.count, 1000)
        XCTAssertLessThan(elapsed, 1.0, "Разбор огромного ответа слишком медленный")
    }

    func testHugeMemoryAndHistoryStayFast() {
        let store = ChatStore(configuration: .init(apiKey: "test"),
                              storageURL: FileManager.default.temporaryDirectory.appendingPathComponent("perf-\(UUID()).json"))
        for index in 0..<ChatStore.maximumMemoryCount {
            store.addMemory("Факт номер \(index): пользователь любит тему \(index % 97) и город \(index % 31)")
        }
        XCTAssertEqual(store.memories.count, ChatStore.maximumMemoryCount)
        var messages: [ChatMessage] = []
        for index in 0..<6000 {
            messages.append(ChatMessage(role: index.isMultiple(of: 2) ? .user : .assistant,
                                        content: "Сообщение \(index). " + String(repeating: "текст ", count: 40)))
        }
        let chat = Conversation(title: "Огромный чат", messages: messages)
        store.conversations = [chat]
        store.selectedConversationID = chat.id
        store.addInstruction(chatID: chat.id, text: "Отвечай кратко")

        var started = Date()
        let history = ChatStore.requestHistory(from: messages)
        XCTAssertLessThan(Date().timeIntervalSince(started), 0.5)
        XCTAssertLessThanOrEqual(history.count, 61)

        started = Date()
        let prompt = store.systemInstruction(forChat: chat.id, query: "Какую тему я люблю под номером 42?")
        let promptTime = Date().timeIntervalSince(started)
        print("HONER_PERF prompt memories=\(store.memories.count) seconds=\(promptTime) length=\(prompt.count)")
        XCTAssertLessThan(promptTime, 1.0, "Подбор памяти для запроса слишком медленный")
        XCTAssertTrue(prompt.contains("## Закреплённые инструкции этого чата"))
    }

    func testTypingLongAnswerIsLinear() {
        var text = TypedText()
        var full = ""
        let started = Date()
        for chunk in 0..<2000 {
            full += "Кусок \(chunk) ответа с кириллицей и emoji 👍🏽. "
            text.setTarget(full)
            text.reveal(upTo: text.shownCount + 25)
        }
        text.revealAll()
        XCTAssertEqual(text.shown, full)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2.0, "Печать длинного ответа не должна замедляться")
    }
}
