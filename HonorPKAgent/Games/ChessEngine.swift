import Foundation

/// Шахматы по полным правилам: рокировка, взятие на проходе, превращение пешки,
/// шах, мат, пат и ничья при недостатке материала.
struct ChessBoard: Equatable, Sendable {
    enum Side: Int, Sendable { case white, black
        var opponent: Side { self == .white ? .black : .white }
    }
    enum Kind: Int, Sendable, CaseIterable { case pawn, knight, bishop, rook, queen, king }

    struct Piece: Equatable, Sendable {
        var kind: Kind
        var side: Side
        var id: Int
    }

    struct Move: Equatable, Hashable, Sendable {
        var from: Int
        var to: Int
        var promotion: Kind? = nil
        var enPassant = false
        var castleRookFrom: Int? = nil
        var castleRookTo: Int? = nil
    }

    enum Status: Equatable, Sendable {
        case playing, check, checkmate(winner: Side), stalemate, draw
    }

    /// Клетки 0…63: строка 0 — восьмая горизонталь (чёрные сверху).
    private(set) var cells: [Piece?]
    private(set) var turn: Side = .white
    private(set) var castling: Set<String> = ["K", "Q", "k", "q"]
    private(set) var enPassantTarget: Int?
    private(set) var lastMove: Move?

    init() {
        var cells = [Piece?](repeating: nil, count: 64)
        let back: [Kind] = [.rook, .knight, .bishop, .queen, .king, .bishop, .knight, .rook]
        var id = 0
        for column in 0..<8 {
            cells[column] = Piece(kind: back[column], side: .black, id: id); id += 1
            cells[8 + column] = Piece(kind: .pawn, side: .black, id: id); id += 1
            cells[48 + column] = Piece(kind: .pawn, side: .white, id: id); id += 1
            cells[56 + column] = Piece(kind: back[column], side: .white, id: id); id += 1
        }
        self.cells = cells
    }

    init(cells: [Piece?], turn: Side, castling: Set<String> = [], enPassantTarget: Int? = nil) {
        self.cells = cells
        self.turn = turn
        self.castling = castling
        self.enPassantTarget = enPassantTarget
    }

    static func row(_ square: Int) -> Int { square / 8 }
    static func column(_ square: Int) -> Int { square % 8 }
    private static func square(_ row: Int, _ column: Int) -> Int? {
        guard (0..<8).contains(row), (0..<8).contains(column) else { return nil }
        return row * 8 + column
    }

    private static let knightSteps = [(-2, -1), (-2, 1), (-1, -2), (-1, 2), (1, -2), (1, 2), (2, -1), (2, 1)]
    private static let kingSteps = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]
    private static let diagonals = [(-1, -1), (-1, 1), (1, -1), (1, 1)]
    private static let straights = [(-1, 0), (1, 0), (0, -1), (0, 1)]

    func kingSquare(_ side: Side) -> Int? {
        cells.firstIndex { $0?.kind == .king && $0?.side == side }
    }

    /// Бьёт ли сторона `side` клетку.
    func isAttacked(_ target: Int, by side: Side) -> Bool {
        let row = Self.row(target), column = Self.column(target)
        let pawnRow = side == .white ? row + 1 : row - 1
        for dc in [-1, 1] {
            if let square = Self.square(pawnRow, column + dc), let piece = cells[square],
               piece.side == side, piece.kind == .pawn { return true }
        }
        for (dr, dc) in Self.knightSteps {
            if let square = Self.square(row + dr, column + dc), let piece = cells[square],
               piece.side == side, piece.kind == .knight { return true }
        }
        for (dr, dc) in Self.kingSteps {
            if let square = Self.square(row + dr, column + dc), let piece = cells[square],
               piece.side == side, piece.kind == .king { return true }
        }
        for (directions, kinds) in [(Self.diagonals, [Kind.bishop, .queen]), (Self.straights, [Kind.rook, .queen])] {
            for (dr, dc) in directions {
                var step = 1
                while let square = Self.square(row + dr * step, column + dc * step) {
                    if let piece = cells[square] {
                        if piece.side == side && kinds.contains(piece.kind) { return true }
                        break
                    }
                    step += 1
                }
            }
        }
        return false
    }

    func inCheck(_ side: Side) -> Bool {
        guard let king = kingSquare(side) else { return false }
        return isAttacked(king, by: side.opponent)
    }

    private func pseudoMoves() -> [Move] {
        var moves: [Move] = []
        for square in 0..<64 {
            guard let piece = cells[square], piece.side == turn else { continue }
            let row = Self.row(square), column = Self.column(square)
            switch piece.kind {
            case .pawn:
                let dir = piece.side == .white ? -1 : 1
                let startRow = piece.side == .white ? 6 : 1
                let lastRow = piece.side == .white ? 0 : 7
                func add(_ to: Int, enPassant: Bool = false) {
                    if Self.row(to) == lastRow {
                        for kind in [Kind.queen, .rook, .bishop, .knight] { moves.append(Move(from: square, to: to, promotion: kind)) }
                    } else {
                        moves.append(Move(from: square, to: to, enPassant: enPassant))
                    }
                }
                if let one = Self.square(row + dir, column), cells[one] == nil {
                    add(one)
                    if row == startRow, let two = Self.square(row + 2 * dir, column), cells[two] == nil {
                        moves.append(Move(from: square, to: two))
                    }
                }
                for dc in [-1, 1] {
                    guard let target = Self.square(row + dir, column + dc) else { continue }
                    if let other = cells[target], other.side != piece.side { add(target) }
                    else if target == enPassantTarget { add(target, enPassant: true) }
                }
            case .knight, .king:
                for (dr, dc) in piece.kind == .knight ? Self.knightSteps : Self.kingSteps {
                    guard let target = Self.square(row + dr, column + dc) else { continue }
                    if let other = cells[target], other.side == piece.side { continue }
                    moves.append(Move(from: square, to: target))
                }
                if piece.kind == .king { moves += castlingMoves(from: square) }
            case .bishop, .rook, .queen:
                let directions = piece.kind == .bishop ? Self.diagonals
                    : piece.kind == .rook ? Self.straights : Self.diagonals + Self.straights
                for (dr, dc) in directions {
                    var step = 1
                    while let target = Self.square(row + dr * step, column + dc * step) {
                        if let other = cells[target] {
                            if other.side != piece.side { moves.append(Move(from: square, to: target)) }
                            break
                        }
                        moves.append(Move(from: square, to: target))
                        step += 1
                    }
                }
            }
        }
        return moves
    }

    private func castlingMoves(from square: Int) -> [Move] {
        let white = turn == .white
        let home = white ? 60 : 4
        guard square == home, !inCheck(turn) else { return [] }
        var moves: [Move] = []
        let kingSide = white ? "K" : "k", queenSide = white ? "Q" : "q"
        if castling.contains(kingSide), cells[home + 1] == nil, cells[home + 2] == nil,
           cells[home + 3]?.kind == .rook, cells[home + 3]?.side == turn,
           !isAttacked(home + 1, by: turn.opponent), !isAttacked(home + 2, by: turn.opponent) {
            moves.append(Move(from: home, to: home + 2, castleRookFrom: home + 3, castleRookTo: home + 1))
        }
        if castling.contains(queenSide), cells[home - 1] == nil, cells[home - 2] == nil, cells[home - 3] == nil,
           cells[home - 4]?.kind == .rook, cells[home - 4]?.side == turn,
           !isAttacked(home - 1, by: turn.opponent), !isAttacked(home - 2, by: turn.opponent) {
            moves.append(Move(from: home, to: home - 2, castleRookFrom: home - 4, castleRookTo: home - 1))
        }
        return moves
    }

    /// Все допустимые ходы (свой король не остаётся под шахом).
    func legalMoves() -> [Move] {
        pseudoMoves().filter { move in
            var next = self
            next.apply(move)
            return !next.inCheck(turn)
        }
    }

    func legalMoves(from square: Int) -> [Move] {
        legalMoves().filter { $0.from == square }
    }

    mutating func apply(_ move: Move) {
        guard var piece = cells[move.from] else { return }
        if move.enPassant {
            let capturedSquare = move.to + (piece.side == .white ? 8 : -8)
            cells[capturedSquare] = nil
        }
        if let rookFrom = move.castleRookFrom, let rookTo = move.castleRookTo {
            cells[rookTo] = cells[rookFrom]
            cells[rookFrom] = nil
        }
        if let promotion = move.promotion { piece.kind = promotion }
        cells[move.from] = nil
        cells[move.to] = piece
        // Права на рокировку теряются, когда ходят король или ладьи (или ладью бьют).
        for (square, flag) in [(60, "K"), (60, "Q"), (63, "K"), (56, "Q"), (4, "k"), (4, "q"), (7, "k"), (0, "q")]
            where move.from == square || move.to == square {
            castling.remove(flag)
        }
        enPassantTarget = nil
        if piece.kind == .pawn, abs(move.to - move.from) == 16 {
            enPassantTarget = (move.to + move.from) / 2
        }
        lastMove = move
        turn = turn.opponent
    }

    var status: Status {
        let moves = legalMoves()
        if moves.isEmpty { return inCheck(turn) ? .checkmate(winner: turn.opponent) : .stalemate }
        let rest = cells.compactMap { $0 }.filter { $0.kind != .king }
        if rest.isEmpty || (rest.count == 1 && [.bishop, .knight].contains(rest[0].kind)) { return .draw }
        return inCheck(turn) ? .check : .playing
    }

    // MARK: - Оценка

    static let values: [Kind: Int] = [.pawn: 100, .knight: 320, .bishop: 330, .rook: 500, .queen: 900, .king: 0]

    private static let pawnTable = [0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10,
                                    5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5,
                                    5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0]
    private static let knightTable = [-50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40,
                                      -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30,
                                      -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30,
                                      -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50]
    private static let bishopTable = [-20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10,
                                      -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10,
                                      -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10,
                                      -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20]
    private static let kingTable = [-30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
                                    -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
                                    -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10,
                                    20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20]

    /// Оценка с точки зрения стороны, которая ходит.
    func evaluate() -> Int {
        var score = 0
        for square in 0..<64 {
            guard let piece = cells[square] else { continue }
            let index = piece.side == .white ? square : (7 - Self.row(square)) * 8 + Self.column(square)
            var value = Self.values[piece.kind] ?? 0
            switch piece.kind {
            case .pawn: value += Self.pawnTable[index]
            case .knight: value += Self.knightTable[index]
            case .bishop: value += Self.bishopTable[index]
            case .king: value += Self.kingTable[index]
            case .rook, .queen: break
            }
            score += piece.side == .white ? value : -value
        }
        return turn == .white ? score : -score
    }
}

/// Соперник в шахматах: поиск с отсечением и доигрыванием взятий.
enum ChessAI {
    static func bestMove(for board: ChessBoard, depth: Int = 3) -> ChessBoard.Move? {
        let moves = ordered(board.legalMoves(), board)
        guard moves.count > 1 else { return moves.first }
        var best: ChessBoard.Move?
        var bestScore = Int.min + 1
        var alpha = Int.min + 1
        for move in moves {
            var next = board
            next.apply(move)
            let score = -negamax(next, depth: depth - 1, alpha: -Int.max, beta: -alpha)
            if score > bestScore || (score == bestScore && Bool.random()) {
                bestScore = score
                best = move
            }
            alpha = max(alpha, score)
        }
        return best
    }

    private static func ordered(_ moves: [ChessBoard.Move], _ board: ChessBoard) -> [ChessBoard.Move] {
        moves.sorted { lhs, rhs in score(lhs, board) > score(rhs, board) }
    }

    private static func score(_ move: ChessBoard.Move, _ board: ChessBoard) -> Int {
        var value = 0
        if let victim = board.cells[move.to], let attacker = board.cells[move.from] {
            value += 10 * (ChessBoard.values[victim.kind] ?? 0) - (ChessBoard.values[attacker.kind] ?? 0)
        }
        if move.promotion == .queen { value += 800 }
        return value
    }

    private static func negamax(_ board: ChessBoard, depth: Int, alpha: Int, beta: Int) -> Int {
        let moves = board.legalMoves()
        if moves.isEmpty { return board.inCheck(board.turn) ? -100_000 - depth : 0 }
        if depth <= 0 { return quiescence(board, alpha: alpha, beta: beta, depth: 2) }
        var alpha = alpha
        var best = Int.min + 1
        for move in ordered(moves, board) {
            var next = board
            next.apply(move)
            let value = -negamax(next, depth: depth - 1, alpha: -beta, beta: -alpha)
            best = max(best, value)
            alpha = max(alpha, value)
            if alpha >= beta { break }
        }
        return best
    }

    private static func quiescence(_ board: ChessBoard, alpha: Int, beta: Int, depth: Int) -> Int {
        let stand = board.evaluate()
        if stand >= beta || depth == 0 { return stand }
        var alpha = max(alpha, stand)
        let captures = ordered(board.legalMoves().filter { board.cells[$0.to] != nil || $0.enPassant }, board)
        for move in captures {
            var next = board
            next.apply(move)
            let value = -quiescence(next, alpha: -beta, beta: -alpha, depth: depth - 1)
            if value >= beta { return value }
            alpha = max(alpha, value)
        }
        return alpha
    }
}
