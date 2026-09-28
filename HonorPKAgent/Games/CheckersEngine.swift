import Foundation

/// Русские шашки: обязательное взятие, взятие назад, дамка ходит на любое расстояние,
/// шашка, дошедшая до последнего ряда во время взятия, продолжает бить как дамка.
/// Снятые шашки убираются после хода (турецкий удар запрещён).
struct CheckersBoard: Equatable, Sendable {
    enum Side: Int, Sendable { case white = 1, black = -1 }

    struct Piece: Equatable, Sendable {
        var side: Side
        var king: Bool
        var id: Int
    }

    /// Ход: путь по клеткам и снятые шашки.
    struct Move: Equatable, Hashable, Sendable {
        var path: [Int]
        var captured: [Int]
        var from: Int { path[0] }
        var to: Int { path[path.count - 1] }
    }

    /// Клетки 0…63: строка 0 — сверху (сторона чёрных), белые идут вверх.
    private(set) var cells: [Piece?]
    private(set) var turn: Side = .white

    init() {
        var cells = [Piece?](repeating: nil, count: 64)
        var id = 0
        for row in 0..<8 {
            for column in 0..<8 where (row + column) % 2 == 1 {
                if row < 3 { cells[row * 8 + column] = Piece(side: .black, king: false, id: id); id += 1 }
                if row > 4 { cells[row * 8 + column] = Piece(side: .white, king: false, id: id); id += 1 }
            }
        }
        self.cells = cells
    }

    init(cells: [Piece?], turn: Side) {
        self.cells = cells
        self.turn = turn
    }

    static func row(_ square: Int) -> Int { square / 8 }
    static func column(_ square: Int) -> Int { square % 8 }
    private static let directions = [(-1, -1), (-1, 1), (1, -1), (1, 1)]

    private static func square(_ row: Int, _ column: Int) -> Int? {
        guard (0..<8).contains(row), (0..<8).contains(column) else { return nil }
        return row * 8 + column
    }

    /// Все допустимые ходы стороны, которая ходит.
    func legalMoves() -> [Move] {
        var captures: [Move] = []
        var quiet: [Move] = []
        for square in 0..<64 {
            guard let piece = cells[square], piece.side == turn else { continue }
            captures += captureSequences(from: square, piece: piece)
        }
        if !captures.isEmpty { return captures }
        for square in 0..<64 {
            guard let piece = cells[square], piece.side == turn else { continue }
            quiet += quietMoves(from: square, piece: piece)
        }
        return quiet
    }

    private func quietMoves(from square: Int, piece: Piece) -> [Move] {
        let row = Self.row(square), column = Self.column(square)
        var result: [Move] = []
        let forward = piece.side == .white ? -1 : 1
        for (dr, dc) in Self.directions {
            if piece.king {
                var step = 1
                while let target = Self.square(row + dr * step, column + dc * step), cells[target] == nil {
                    result.append(Move(path: [square, target], captured: []))
                    step += 1
                }
            } else if dr == forward, let target = Self.square(row + dr, column + dc), cells[target] == nil {
                result.append(Move(path: [square, target], captured: []))
            }
        }
        return result
    }

    private func captureSequences(from square: Int, piece: Piece) -> [Move] {
        var results: [Move] = []
        var board = cells
        board[square] = nil
        search(board: &board, at: square, king: piece.king, side: piece.side, path: [square], captured: [], results: &results)
        return results
    }

    private func search(board: inout [Piece?], at square: Int, king: Bool, side: Side,
                        path: [Int], captured: [Int], results: inout [Move]) {
        let row = Self.row(square), column = Self.column(square)
        var extended = false
        for (dr, dc) in Self.directions {
            if king {
                var step = 1
                var enemy: Int?
                while let current = Self.square(row + dr * step, column + dc * step) {
                    if let piece = board[current] {
                        if enemy != nil || piece.side == side || captured.contains(current) { break }
                        enemy = current
                    } else if let enemy {
                        extended = true
                        search(board: &board, at: current, king: true, side: side,
                               path: path + [current], captured: captured + [enemy], results: &results)
                    }
                    step += 1
                }
            } else {
                guard let middle = Self.square(row + dr, column + dc),
                      let landing = Self.square(row + 2 * dr, column + 2 * dc),
                      let piece = board[middle], piece.side != side, !captured.contains(middle),
                      board[landing] == nil else { continue }
                extended = true
                let promotes = (side == .white && Self.row(landing) == 0) || (side == .black && Self.row(landing) == 7)
                search(board: &board, at: landing, king: promotes, side: side,
                       path: path + [landing], captured: captured + [middle], results: &results)
            }
        }
        if !extended, !captured.isEmpty {
            results.append(Move(path: path, captured: captured))
        }
    }

    /// Сделать ход (ход обязан быть из legalMoves()).
    mutating func apply(_ move: Move) {
        guard var piece = cells[move.from] else { return }
        cells[move.from] = nil
        for square in move.captured { cells[square] = nil }
        // Дамка: дошла до последнего ряда в любой точке пути.
        for square in move.path.dropFirst() {
            if (piece.side == .white && Self.row(square) == 0) || (piece.side == .black && Self.row(square) == 7) {
                piece.king = true
            }
        }
        cells[move.to] = piece
        turn = turn == .white ? .black : .white
    }

    func count(_ side: Side) -> Int { cells.compactMap { $0 }.filter { $0.side == side }.count }

    /// Победитель: у проигравшего нет шашек или ходов.
    var winner: Side? {
        if legalMoves().isEmpty { return turn == .white ? .black : .white }
        return nil
    }

    /// Оценка позиции с точки зрения белых.
    func evaluate() -> Int {
        var score = 0
        for square in 0..<64 {
            guard let piece = cells[square] else { continue }
            let row = Self.row(square), column = Self.column(square)
            var value = piece.king ? 320 : 100
            if !piece.king {
                value += (piece.side == .white ? (7 - row) : row) * 6
                if (2...5).contains(column) && (2...5).contains(row) { value += 8 }
                if (piece.side == .white && row == 7) || (piece.side == .black && row == 0) { value += 10 }
            }
            score += piece.side == .white ? value : -value
        }
        return score
    }
}

/// Соперник в шашках: поиск с отсечением на несколько ходов вперёд.
enum CheckersAI {
    static func bestMove(for board: CheckersBoard, depth: Int = 6) -> CheckersBoard.Move? {
        let moves = board.legalMoves()
        guard moves.count > 1 else { return moves.first }
        let maximizing = board.turn == .white
        var best: CheckersBoard.Move?
        var bestScore = maximizing ? Int.min : Int.max
        for move in moves.shuffled() {
            var next = board
            next.apply(move)
            let score = minimax(next, depth: depth - 1, alpha: Int.min, beta: Int.max)
            if maximizing ? score > bestScore : score < bestScore {
                bestScore = score
                best = move
            }
        }
        return best
    }

    private static func minimax(_ board: CheckersBoard, depth: Int, alpha: Int, beta: Int) -> Int {
        let moves = board.legalMoves()
        if moves.isEmpty { return board.turn == .white ? -100_000 - depth : 100_000 + depth }
        // Размены доигрываются до конца, но не бесконечно.
        if depth <= -6 || (depth <= 0 && moves.first?.captured.isEmpty != false) { return board.evaluate() }
        var alpha = alpha, beta = beta
        if board.turn == .white {
            var value = Int.min
            for move in moves {
                var next = board; next.apply(move)
                value = max(value, minimax(next, depth: depth - 1, alpha: alpha, beta: beta))
                alpha = max(alpha, value)
                if alpha >= beta { break }
            }
            return value
        } else {
            var value = Int.max
            for move in moves {
                var next = board; next.apply(move)
                value = min(value, minimax(next, depth: depth - 1, alpha: alpha, beta: beta))
                beta = min(beta, value)
                if alpha >= beta { break }
            }
            return value
        }
    }
}
