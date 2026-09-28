import SwiftUI

/// Мини-игры с Honer AI.
enum GameKind: String, CaseIterable, Identifiable, Sendable {
    case chess, checkers, durak, slots
    var id: String { rawValue }

    var title: String {
        switch self {
        case .chess: return "Шахматы"
        case .checkers: return "Шашки"
        case .durak: return "Дурак"
        case .slots: return "Удача"
        }
    }

    var subtitle: String {
        switch self {
        case .chess: return "Партия против Honer AI"
        case .checkers: return "Русские шашки"
        case .durak: return "Подкидной, 36 карт"
        case .slots: return "Крутите барабаны"
        }
    }

    var symbol: String {
        switch self {
        case .chess: return "crown.fill"
        case .checkers: return "circle.grid.2x2.fill"
        case .durak: return "suit.spade.fill"
        case .slots: return "dice.fill"
        }
    }

    var colors: [Color] {
        switch self {
        case .chess: return [Color(red: 0.35, green: 0.45, blue: 1), Color(red: 0.6, green: 0.35, blue: 1)]
        case .checkers: return [Color(red: 0.95, green: 0.45, blue: 0.3), Color(red: 0.85, green: 0.25, blue: 0.45)]
        case .durak: return [Color(red: 0.15, green: 0.7, blue: 0.5), Color(red: 0.1, green: 0.5, blue: 0.75)]
        case .slots: return [Color(red: 1, green: 0.7, blue: 0.2), Color(red: 1, green: 0.4, blue: 0.4)]
        }
    }

    /// Разбор названия игры, которое прислала модель.
    static func from(_ raw: String) -> GameKind? {
        let value = raw.lowercased()
        if value.contains("chess") || value.contains("шахмат") { return .chess }
        if value.contains("checker") || value.contains("draught") || value.contains("шашк") { return .checkers }
        if value.contains("durak") || value.contains("дурак") || value.contains("карт") { return .durak }
        if value.contains("slot") || value.contains("казино") || value.contains("удач") || value.contains("рулет") { return .slots }
        return nil
    }
}

/// Витрина игр.
struct GameHubView: View {
    let onPlay: (GameKind) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 14) {
                    ForEach(GameKind.allCases.filter { ParentalControl.shared.isGameAllowed($0.rawValue) }) { kind in
                        Button { onPlay(kind) } label: {
                            VStack(alignment: .leading, spacing: 10) {
                                Image(systemName: kind.symbol)
                                    .font(.system(size: 30, weight: .semibold))
                                    .foregroundStyle(.white)
                                Spacer(minLength: 0)
                                Text(kind.title).font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
                                Text(kind.subtitle).font(.system(size: 13)).foregroundStyle(.white.opacity(0.85))
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, minHeight: 150, alignment: .leading)
                            .background(LinearGradient(colors: kind.colors, startPoint: .topLeading, endPoint: .bottomTrailing),
                                        in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                            .shadow(color: kind.colors[0].opacity(0.35), radius: 10, y: 5)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("games.play." + kind.rawValue)
                    }
                }
                .padding(16)
            }
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationTitle("Игры с Honer AI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") { dismiss() }.accessibilityIdentifier("games.close")
                }
            }
        }
        .accessibilityIdentifier("games.hub")
    }
}

/// Экран одной игры. По окончании результат уходит в чат.
struct GameScreen: View {
    let kind: GameKind
    let onResult: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch kind {
                case .chess: ChessGameView(onResult: onResult)
                case .checkers: CheckersGameView(onResult: onResult)
                case .durak: DurakGameView(onResult: onResult)
                case .slots: SlotsGameView(onResult: onResult)
                }
            }
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationTitle(kind.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") { dismiss() }.accessibilityIdentifier("game.close")
                }
            }
        }
        .accessibilityIdentifier("game.screen." + kind.rawValue)
    }
}

/// Строка состояния партии.
private struct GameStatusBar: View {
    let text: String
    let thinking: Bool

    var body: some View {
        HStack(spacing: 8) {
            if thinking { ProgressView().scaleEffect(0.8) }
            Text(text).font(.system(size: 16, weight: .semibold))
        }
        .padding(.horizontal, 16).padding(.vertical, 9)
        .background(HonorTheme.surface, in: Capsule())
        .overlay(Capsule().stroke(HonorTheme.divider, lineWidth: 0.7))
        .animation(.easeInOut(duration: 0.2), value: text)
        .accessibilityIdentifier("game.status")
    }
}

// MARK: - Шашки

struct CheckersGameView: View {
    let onResult: (String) -> Void
    @State private var board = CheckersBoard()
    @State private var selected: Int?
    @State private var thinking = false
    @State private var finished = false
    @State private var lastPath: [Int] = []

    private var moves: [CheckersBoard.Move] { board.turn == .white ? board.legalMoves() : [] }
    private var targets: [Int] { moves.filter { $0.from == selected }.map(\.to) }

    private var status: String {
        if let winner = board.winner { return winner == .white ? "Вы победили! 🎉" : "Победил Honer AI" }
        if thinking { return "Honer AI думает…" }
        if moves.contains(where: { !$0.captured.isEmpty }) { return "Ваш ход — бить обязательно" }
        return "Ваш ход"
    }

    var body: some View {
        VStack(spacing: 18) {
            GameStatusBar(text: status, thinking: thinking)
            GeometryReader { geometry in
                let side = min(geometry.size.width, geometry.size.height)
                let cell = side / 8
                ZStack(alignment: .topLeading) {
                    ForEach(0..<64, id: \.self) { square in
                        let dark = (CheckersBoard.row(square) + CheckersBoard.column(square)) % 2 == 1
                        Rectangle()
                            .fill(dark ? Color(red: 0.45, green: 0.3, blue: 0.2) : Color(red: 0.93, green: 0.85, blue: 0.7))
                            .overlay(lastPath.contains(square) ? Color.yellow.opacity(0.25) : Color.clear)
                            .overlay(selected == square ? Color.yellow.opacity(0.35) : Color.clear)
                            .overlay(targets.contains(square) ? Circle().fill(Color.green.opacity(0.55)).padding(cell * 0.34) : nil)
                            .frame(width: cell, height: cell)
                            .contentShape(Rectangle())
                            .onTapGesture { tap(square) }
                            .position(x: CGFloat(CheckersBoard.column(square)) * cell + cell / 2,
                                      y: CGFloat(CheckersBoard.row(square)) * cell + cell / 2)
                    }
                    ForEach(pieces, id: \.piece.id) { entry in
                        CheckersPieceView(piece: entry.piece, size: cell * 0.8)
                            .position(x: CGFloat(CheckersBoard.column(entry.square)) * cell + cell / 2,
                                      y: CGFloat(CheckersBoard.row(entry.square)) * cell + cell / 2)
                            .allowsHitTesting(false)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                .frame(width: side, height: side)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .shadow(color: .black.opacity(0.3), radius: 10, y: 5)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .animation(.easeInOut(duration: 0.35), value: board)
                .accessibilityIdentifier("checkers.board")
            }
            .aspectRatio(1, contentMode: .fit)
            HStack(spacing: 20) {
                Label("\(board.count(.white))", systemImage: "circle.fill").foregroundStyle(HonorTheme.foreground)
                Label("\(board.count(.black))", systemImage: "circle.fill").foregroundStyle(Color(red: 0.8, green: 0.2, blue: 0.2))
                Spacer()
                Button("Новая игра") { reset() }.buttonStyle(.bordered).accessibilityIdentifier("game.new")
            }
            .font(.system(size: 15, weight: .semibold))
        }
        .padding(16)
    }

    private var pieces: [(square: Int, piece: CheckersBoard.Piece)] {
        (0..<64).compactMap { square in board.cells[square].map { (square, $0) } }
    }

    private func tap(_ square: Int) {
        guard !thinking, board.turn == .white, board.winner == nil else { return }
        if let piece = board.cells[square], piece.side == .white, moves.contains(where: { $0.from == square }) {
            selected = square
            UISelectionFeedbackGenerator().selectionChanged()
            return
        }
        guard let from = selected,
              let move = moves.filter({ $0.from == from && $0.to == square }).max(by: { $0.captured.count < $1.captured.count }) else { return }
        play(move)
        selected = nil
        aiTurn()
    }

    private func play(_ move: CheckersBoard.Move) {
        board.apply(move)
        lastPath = move.path
        UIImpactFeedbackGenerator(style: move.captured.isEmpty ? .light : .medium).impactOccurred()
        checkFinish()
    }

    private func aiTurn() {
        guard board.winner == nil, board.turn == .black else { return }
        thinking = true
        let snapshot = board
        Task {
            let started = Date()
            let move = await Task.detached(priority: .userInitiated) { CheckersAI.bestMove(for: snapshot) }.value
            let wait = max(0, 0.6 - Date().timeIntervalSince(started))
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            await MainActor.run {
                thinking = false
                if let move { play(move) }
            }
        }
    }

    private func checkFinish() {
        guard !finished, let winner = board.winner else { return }
        finished = true
        UINotificationFeedbackGenerator().notificationOccurred(winner == .white ? .success : .warning)
        onResult(winner == .white ? "🏆 Партия в шашки: вы победили Honer AI! Отличная игра."
                                  : "♟️ Партия в шашки: победил Honer AI. Сыграем ещё?")
    }

    private func reset() {
        board = CheckersBoard()
        selected = nil
        lastPath = []
        finished = false
        thinking = false
    }
}

private struct CheckersPieceView: View {
    let piece: CheckersBoard.Piece
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(RadialGradient(colors: piece.side == .white ? [.white, Color(white: 0.8)] : [Color(red: 0.9, green: 0.3, blue: 0.3), Color(red: 0.5, green: 0.08, blue: 0.08)],
                                     center: .topLeading, startRadius: 2, endRadius: size))
                .overlay(Circle().stroke(Color.black.opacity(0.35), lineWidth: 1.5))
                .overlay(Circle().stroke(Color.white.opacity(0.3), lineWidth: 1).padding(size * 0.14))
                .shadow(color: .black.opacity(0.4), radius: 3, y: 2)
            if piece.king {
                Image(systemName: "crown.fill")
                    .font(.system(size: size * 0.42))
                    .foregroundStyle(piece.side == .white ? Color(red: 0.85, green: 0.6, blue: 0.1) : Color(red: 1, green: 0.85, blue: 0.3))
            }
        }
        .frame(width: size, height: size)
    }
}

// MARK: - Шахматы

struct ChessGameView: View {
    let onResult: (String) -> Void
    @State private var board = ChessBoard()
    @State private var selected: Int?
    @State private var thinking = false
    @State private var finished = false
    @State private var promotionChoices: [ChessBoard.Move] = []

    private var legal: [ChessBoard.Move] { board.turn == .white ? board.legalMoves() : [] }
    private var targets: Set<Int> { Set(legal.filter { $0.from == selected }.map(\.to)) }

    private var status: String {
        switch board.status {
        case .checkmate(let winner): return winner == .white ? "Мат! Вы победили 🎉" : "Мат. Победил Honer AI"
        case .stalemate: return "Пат — ничья"
        case .draw: return "Ничья"
        case .check: return thinking ? "Шах! Honer AI думает…" : "Шах!"
        case .playing: return thinking ? "Honer AI думает…" : "Ваш ход"
        }
    }

    var body: some View {
        VStack(spacing: 18) {
            GameStatusBar(text: status, thinking: thinking)
            GeometryReader { geometry in
                chessBoard(side: min(geometry.size.width, geometry.size.height))
            }
            .aspectRatio(1, contentMode: .fit)
            HStack {
                Spacer()
                Button("Новая партия") { reset() }.buttonStyle(.bordered).accessibilityIdentifier("game.new")
            }
        }
        .padding(16)
        .confirmationDialog("Во что превратить пешку?", isPresented: promotionShown) {
            ForEach(promotionChoices, id: \.self) { move in
                Button(Self.promotionTitle(move.promotion)) { promotionChoices = []; commit(move) }
            }
        }
    }

    private var promotionShown: Binding<Bool> {
        Binding(get: { !promotionChoices.isEmpty }, set: { if !$0 { promotionChoices = [] } })
    }

    private func chessBoard(side: CGFloat) -> some View {
        let cell = side / 8
        let checkSquare: Int? = board.inCheck(board.turn) ? board.kingSquare(board.turn) : nil
        let targetSet = targets
        return ZStack(alignment: .topLeading) {
            ForEach(0..<64, id: \.self) { square in
                ChessSquareView(square: square, cell: cell, selected: selected == square,
                                target: targetSet.contains(square), occupied: board.cells[square] != nil,
                                lastMove: board.lastMove.map { $0.from == square || $0.to == square } ?? false,
                                check: checkSquare == square) { tap(square) }
            }
            ForEach(pieces, id: \.piece.id) { entry in
                ChessPieceView(piece: entry.piece, cell: cell)
                    .position(x: CGFloat(ChessBoard.column(entry.square)) * cell + cell / 2,
                              y: CGFloat(ChessBoard.row(entry.square)) * cell + cell / 2)
                    .allowsHitTesting(false)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .shadow(color: .black.opacity(0.3), radius: 10, y: 5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.easeInOut(duration: 0.3), value: board)
        .accessibilityIdentifier("chess.board")
    }

    private var pieces: [(square: Int, piece: ChessBoard.Piece)] {
        (0..<64).compactMap { square in board.cells[square].map { (square, $0) } }
    }

    /// Фигуры рисуются одним набором глифов в текстовом (не эмодзи) виде.
    static func glyph(_ kind: ChessBoard.Kind) -> String {
        let base: String
        switch kind {
        case .king: base = "♚"
        case .queen: base = "♛"
        case .rook: base = "♜"
        case .bishop: base = "♝"
        case .knight: base = "♞"
        case .pawn: base = "♟"
        }
        return base + "\u{FE0E}"
    }

    private static func promotionTitle(_ kind: ChessBoard.Kind?) -> String {
        switch kind {
        case .queen: return "Ферзь"
        case .rook: return "Ладья"
        case .bishop: return "Слон"
        case .knight: return "Конь"
        default: return "Ферзь"
        }
    }

    private func tap(_ square: Int) {
        guard !thinking, board.turn == .white, !finished else { return }
        if let piece = board.cells[square], piece.side == .white {
            selected = legal.contains(where: { $0.from == square }) ? square : nil
            UISelectionFeedbackGenerator().selectionChanged()
            return
        }
        guard let from = selected else { return }
        let options = legal.filter { $0.from == from && $0.to == square }
        guard !options.isEmpty else { return }
        if options.count > 1 { promotionChoices = options; return }
        commit(options[0])
    }

    private func commit(_ move: ChessBoard.Move) {
        board.apply(move)
        selected = nil
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        if !checkFinish() { aiTurn() }
    }

    private func aiTurn() {
        thinking = true
        let snapshot = board
        Task {
            let started = Date()
            let move = await Task.detached(priority: .userInitiated) { ChessAI.bestMove(for: snapshot) }.value
            let wait = max(0, 0.5 - Date().timeIntervalSince(started))
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            await MainActor.run {
                thinking = false
                guard let move, !finished else { return }
                board.apply(move)
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                _ = checkFinish()
            }
        }
    }

    @discardableResult
    private func checkFinish() -> Bool {
        let status = board.status
        let text: String
        switch status {
        case .checkmate(let winner):
            text = winner == .white ? "🏆 Шахматы: вы поставили мат Honer AI! Блестяще." : "♟️ Шахматы: Honer AI поставил мат. Реванш?"
        case .stalemate: text = "🤝 Шахматы: пат, ничья."
        case .draw: text = "🤝 Шахматы: ничья — на доске недостаточно фигур."
        default: return false
        }
        guard !finished else { return true }
        finished = true
        onResult(text)
        return true
    }

    private func reset() {
        board = ChessBoard()
        selected = nil
        finished = false
        thinking = false
    }
}

/// Клетка шахматной доски.
private struct ChessSquareView: View {
    let square: Int
    let cell: CGFloat
    let selected: Bool
    let target: Bool
    let occupied: Bool
    let lastMove: Bool
    let check: Bool
    let onTap: () -> Void

    private var light: Bool { (ChessBoard.row(square) + ChessBoard.column(square)) % 2 == 0 }

    private var baseColor: Color {
        light ? Color(red: 0.93, green: 0.93, blue: 0.82) : Color(red: 0.46, green: 0.59, blue: 0.34)
    }

    private var highlight: Color {
        if check { return Color.red.opacity(0.5) }
        if selected { return Color.yellow.opacity(0.4) }
        if lastMove { return Color.yellow.opacity(0.3) }
        return Color.clear
    }

    var body: some View {
        ZStack {
            Rectangle().fill(baseColor)
            Rectangle().fill(highlight)
            if target { marker }
        }
        .frame(width: cell, height: cell)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .position(x: CGFloat(ChessBoard.column(square)) * cell + cell / 2,
                  y: CGFloat(ChessBoard.row(square)) * cell + cell / 2)
    }

    @ViewBuilder
    private var marker: some View {
        if occupied {
            Circle().stroke(Color.black.opacity(0.3), lineWidth: cell * 0.08).padding(cell * 0.04)
        } else {
            Circle().fill(Color.black.opacity(0.22)).padding(cell * 0.36)
        }
    }
}

/// Шахматная фигура.
private struct ChessPieceView: View {
    let piece: ChessBoard.Piece
    let cell: CGFloat

    var body: some View {
        let white = piece.side == .white
        Text(ChessGameView.glyph(piece.kind))
            .font(.system(size: cell * 0.78))
            .foregroundStyle(white ? Color.white : Color.black)
            .shadow(color: white ? Color.black.opacity(0.9) : Color.white.opacity(0.35), radius: 0.8)
            .shadow(color: Color.black.opacity(0.35), radius: 2, y: 1)
    }
}

// MARK: - Дурак

struct DurakGameView: View {
    let onResult: (String) -> Void
    @State private var game = DurakGame()
    @State private var busy = false
    @State private var finished = false
    @Namespace private var cards

    private var status: String {
        if let outcome = game.outcome {
            switch outcome {
            case .humanWon: return "Вы выиграли! Honer AI — дурак 😄"
            case .aiWon: return "Выиграл Honer AI. Вы дурак 🙃"
            case .draw: return "Ничья"
            }
        }
        if busy { return "Honer AI ходит…" }
        if game.attacker == .human {
            if game.defenderTakes { return "Honer AI берёт — подкиньте или «Бери»" }
            if game.table.isEmpty { return "Ваш ход — атакуйте" }
            if game.allBeaten { return "Отбито — подкиньте или «Бито»" }
            return "Honer AI отбивается…"
        }
        if game.table.contains(where: { $0.defense == nil }) { return "Отбивайтесь или «Беру»" }
        return "Ход Honer AI"
    }

    var body: some View {
        VStack(spacing: 14) {
            GameStatusBar(text: status, thinking: busy)
            // Карты соперника рубашкой вверх.
            HStack(spacing: -22) {
                ForEach(game.hand(.ai)) { card in
                    CardBackView().frame(width: 46, height: 66)
                        .matchedGeometryEffect(id: card.id, in: cards)
                }
            }
            .frame(height: 70)
            .accessibilityIdentifier("durak.ai.hand")
            HStack(alignment: .center, spacing: 16) {
                // Колода и козырь.
                ZStack {
                    if !game.deck.isEmpty {
                        PlayingCardView(card: game.trump, width: 54)
                            .rotationEffect(.degrees(90))
                            .offset(x: 14)
                        if game.deck.count > 1 { CardBackView().frame(width: 54, height: 78) }
                    } else {
                        Text(game.trumpSuit.symbol)
                            .font(.system(size: 40))
                            .foregroundStyle(game.trumpSuit.isRed ? Color.red : HonorTheme.foreground)
                    }
                }
                .frame(width: 90, height: 90)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Козырь \(game.trumpSuit.symbol)").font(.system(size: 15, weight: .semibold))
                    Text("В колоде: \(game.deck.count)").font(.system(size: 13)).foregroundStyle(HonorTheme.secondary)
                    Text("Бито: \(game.discard.count)").font(.system(size: 13)).foregroundStyle(HonorTheme.secondary)
                }
                Spacer()
            }
            // Стол.
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 74), spacing: 10)], spacing: 12) {
                ForEach(game.table) { pair in
                    ZStack(alignment: .topLeading) {
                        PlayingCardView(card: pair.attack, width: 58)
                            .matchedGeometryEffect(id: pair.attack.id, in: cards)
                        if let defense = pair.defense {
                            PlayingCardView(card: defense, width: 58)
                                .rotationEffect(.degrees(12))
                                .offset(x: 14, y: 16)
                                .matchedGeometryEffect(id: defense.id, in: cards)
                        }
                    }
                    .frame(width: 80, height: 104, alignment: .topLeading)
                }
            }
            .frame(minHeight: 120)
            .padding(10)
            .background(Color(red: 0.1, green: 0.4, blue: 0.25).opacity(0.85), in: RoundedRectangle(cornerRadius: 18))
            .accessibilityIdentifier("durak.table")
            // Кнопки.
            HStack(spacing: 12) {
                if game.attacker == .human && game.allBeaten && !game.defenderTakes {
                    actionButton("Бито", id: "durak.done") { game.finishRound(); afterHuman() }
                }
                if game.attacker == .human && game.defenderTakes {
                    actionButton("Бери", id: "durak.give") { game.finishRound(); afterHuman() }
                }
                if game.attacker == .ai && game.table.contains(where: { $0.defense == nil }) && !game.defenderTakes {
                    actionButton("Беру", id: "durak.take") { game.declareTake(); afterHuman() }
                }
                Spacer()
                Button("Заново") { reset() }.buttonStyle(.bordered).accessibilityIdentifier("game.new")
            }
            .frame(minHeight: 44)
            // Мои карты.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: -14) {
                    ForEach(game.hand(.human)) { card in
                        let playable = isPlayable(card)
                        PlayingCardView(card: card, width: 64)
                            .offset(y: playable ? -8 : 0)
                            .opacity(playable || game.outcome != nil ? 1 : 0.55)
                            .matchedGeometryEffect(id: card.id, in: cards)
                            .onTapGesture { play(card) }
                            .accessibilityIdentifier("durak.card.\(card.id)")
                    }
                }
                .padding(.horizontal, 20).padding(.top, 12)
            }
            .frame(height: 112)
            .accessibilityIdentifier("durak.hand")
        }
        .padding(16)
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: layoutSignature)
        .task { if game.attacker == .ai { runAI() } }
    }

    private var layoutSignature: [Int] {
        var result: [Int] = game.hand(.human).map(\.id)
        result.append(-1)
        result.append(contentsOf: game.hand(.ai).map(\.id))
        result.append(-2)
        for pair in game.table {
            result.append(pair.attack.id)
            result.append(pair.defense?.id ?? 0)
        }
        result.append(game.deck.count)
        return result
    }

    private func actionButton(_ title: String, id: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .font(.system(size: 16, weight: .semibold))
            .buttonStyle(.borderedProminent)
            .disabled(busy)
            .accessibilityIdentifier(id)
    }

    private func isPlayable(_ card: DurakCard) -> Bool {
        guard !busy, game.outcome == nil else { return false }
        if game.attacker == .human { return game.canAttack(with: card, by: .human) }
        guard !game.defenderTakes, let open = game.table.first(where: { $0.defense == nil }) else { return false }
        return game.beats(card, open.attack) || game.table.contains { $0.defense == nil && game.beats(card, $0.attack) }
    }

    private func play(_ card: DurakCard) {
        guard isPlayable(card) else { return }
        if game.attacker == .human {
            _ = game.attack(with: card, by: .human)
        } else if let target = game.table.first(where: { $0.defense == nil && game.beats(card, $0.attack) }) {
            _ = game.defend(target.attack, with: card)
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        afterHuman()
    }

    private func afterHuman() {
        checkFinish()
        runAI()
    }

    /// Соперник делает шаги с паузами, пока ход не перейдёт к человеку.
    private func runAI() {
        guard !busy, game.outcome == nil else { return }
        busy = true
        Task { @MainActor in
            defer { busy = false; checkFinish() }
            var steps = 0
            while steps < 12 {
                try? await Task.sleep(nanoseconds: 650_000_000)
                guard game.outcome == nil else { return }
                var copy = game
                let acted = copy.aiStep()
                guard acted else { return }
                game = copy
                steps += 1
                // После защиты или атаки соперника решение за человеком.
                if game.attacker == .human && !game.defenderTakes { return }
                if game.attacker == .ai && game.table.contains(where: { $0.defense == nil }) && !game.defenderTakes { return }
            }
        }
    }

    private func checkFinish() {
        guard !finished, let outcome = game.outcome else { return }
        finished = true
        UINotificationFeedbackGenerator().notificationOccurred(outcome == .humanWon ? .success : .warning)
        switch outcome {
        case .humanWon: onResult("🃏 Дурак: вы выиграли, Honer AI остался в дураках! 😄")
        case .aiWon: onResult("🃏 Дурак: выиграл Honer AI. Отыграемся?")
        case .draw: onResult("🃏 Дурак: ничья.")
        }
    }

    private func reset() {
        game = DurakGame()
        finished = false
        busy = false
        if game.attacker == .ai { runAI() }
    }
}

struct PlayingCardView: View {
    let card: DurakCard
    let width: CGFloat

    var body: some View {
        let color: Color = card.suit.isRed ? Color(red: 0.85, green: 0.1, blue: 0.15) : .black
        RoundedRectangle(cornerRadius: width * 0.12, style: .continuous)
            .fill(Color.white)
            .overlay(RoundedRectangle(cornerRadius: width * 0.12, style: .continuous).stroke(Color.black.opacity(0.2), lineWidth: 1))
            .overlay(alignment: .topLeading) {
                VStack(spacing: -2) {
                    Text(card.rankTitle).font(.system(size: width * 0.26, weight: .bold))
                    Text(card.suit.symbol).font(.system(size: width * 0.22))
                }
                .foregroundStyle(color)
                .padding(width * 0.08)
            }
            .overlay {
                Text(card.suit.symbol)
                    .font(.system(size: width * 0.5))
                    .foregroundStyle(color)
                    .offset(x: width * 0.1, y: width * 0.14)
            }
            .frame(width: width, height: width * 1.42)
            .shadow(color: .black.opacity(0.25), radius: 3, y: 2)
            .accessibilityLabel("\(card.rankTitle) \(card.suit.symbol)")
    }
}

struct CardBackView: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 7, style: .continuous)
            .fill(LinearGradient(colors: [Color(red: 0.25, green: 0.4, blue: 0.95), Color(red: 0.45, green: 0.25, blue: 0.85)],
                                 startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(RoundedRectangle(cornerRadius: 5).stroke(Color.white.opacity(0.6), lineWidth: 1.2).padding(4))
            .overlay(HonorMark(size: 18).opacity(0.9))
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
    }
}

// MARK: - Слоты «Удача»

struct SlotsGameView: View {
    let onResult: (String) -> Void
    @AppStorage("honor.slots.balance") private var balance = 1000
    @State private var bet = 50
    @State private var strips: [[String]] = Array(repeating: ["🍒", "💎", "🍋"], count: 3)
    @State private var offsets: [CGFloat] = [0, 0, 0]
    @State private var spinning = false
    @State private var lastWin: Int?
    @State private var bigWinGlow = false
    @State private var spins = 0

    static let symbols: [(String, Int)] = [("🍒", 26), ("🍋", 22), ("🍀", 16), ("🔔", 14), ("⭐️", 10), ("7️⃣", 7), ("💎", 5)]
    static let payouts: [String: Int] = ["💎": 50, "7️⃣": 30, "⭐️": 15, "🔔": 10, "🍀": 8, "🍋": 5, "🍒": 3]
    private let cell: CGFloat = 86

    var body: some View {
        VStack(spacing: 22) {
            HStack {
                Label("\(balance)", systemImage: "circle.hexagongrid.fill")
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundStyle(Color(red: 1, green: 0.75, blue: 0.2))
                    .accessibilityIdentifier("slots.balance")
                Spacer()
                if let lastWin {
                    Text(lastWin > 0 ? "+\(lastWin)" : "Мимо")
                        .font(.system(size: 20, weight: .heavy, design: .rounded))
                        .foregroundStyle(lastWin > 0 ? .green : HonorTheme.secondary)
                        .transition(.scale.combined(with: .opacity))
                        .accessibilityIdentifier("slots.result")
                }
            }
            HStack(spacing: 10) {
                ForEach(0..<3, id: \.self) { index in
                    VStack(spacing: 0) {
                        ForEach(Array(strips[index].enumerated()), id: \.offset) { _, symbol in
                            Text(symbol).font(.system(size: cell * 0.56)).frame(width: cell, height: cell)
                        }
                    }
                    .offset(y: offsets[index])
                    .frame(width: cell, height: cell * 3, alignment: .top)
                    .clipped()
                    .background(LinearGradient(colors: [Color(white: 0.97), .white, Color(white: 0.97)], startPoint: .top, endPoint: .bottom))
                    .overlay(LinearGradient(colors: [.black.opacity(0.35), .clear, .clear, .black.opacity(0.35)], startPoint: .top, endPoint: .bottom))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
            .padding(12)
            .background(LinearGradient(colors: [Color(red: 0.5, green: 0.1, blue: 0.2), Color(red: 0.25, green: 0.05, blue: 0.15)], startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: 22))
            .overlay(Rectangle().fill(Color.yellow.opacity(0.8)).frame(height: 2).padding(.horizontal, 6))
            .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.yellow.opacity(bigWinGlow ? 0.95 : 0.35), lineWidth: bigWinGlow ? 5 : 2))
            .shadow(color: Color.yellow.opacity(bigWinGlow ? 0.7 : 0), radius: 20)
            .animation(.easeInOut(duration: 0.4), value: bigWinGlow)
            .accessibilityIdentifier("slots.reels")

            Picker("Ставка", selection: $bet) {
                ForEach([10, 50, 100, 250], id: \.self) { Text("\($0)").tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(spinning)
            .accessibilityIdentifier("slots.bet")

            Button {
                spin()
            } label: {
                Text(spinning ? "Крутится…" : "Крутить")
                    .font(.system(size: 20, weight: .bold))
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
                    .background(LinearGradient(colors: [Color(red: 1, green: 0.6, blue: 0.15), Color(red: 1, green: 0.35, blue: 0.3)], startPoint: .leading, endPoint: .trailing),
                                in: Capsule())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .disabled(spinning || balance < bet)
            .accessibilityIdentifier("slots.spin")

            if balance < 10 {
                Button("Пополнить до 1000 монет") { balance = 1000 }.accessibilityIdentifier("slots.refill")
            }
            Text("Три одинаковых: 💎 ×50 · 7️⃣ ×30 · ⭐️ ×15 · 🔔 ×10 · 🍀 ×8 · 🍋 ×5 · 🍒 ×3. Две вишни — ×2. Монеты игровые.")
                .font(.footnote).foregroundStyle(HonorTheme.secondary).multilineTextAlignment(.center)
            Spacer(minLength: 0)
        }
        .padding(20)
    }

    static func randomSymbol() -> String {
        let total = symbols.reduce(0) { $0 + $1.1 }
        var roll = Int.random(in: 0..<total)
        for (symbol, weight) in symbols {
            if roll < weight { return symbol }
            roll -= weight
        }
        return "🍒"
    }

    static func payout(_ line: [String], bet: Int) -> Int {
        if line.count == 3, line[0] == line[1], line[1] == line[2] { return bet * (payouts[line[0]] ?? 2) }
        if line.filter({ $0 == "🍒" }).count == 2 { return bet * 2 }
        return 0
    }

    private func spin() {
        guard !spinning, balance >= bet else { return }
        spinning = true
        lastWin = nil
        bigWinGlow = false
        balance -= bet
        spins += 1
        let result = (0..<3).map { _ in Self.randomSymbol() }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        for index in 0..<3 {
            let visible = Array(strips[index].suffix(3))
            let fillers = (0..<(18 + index * 7)).map { _ in Self.randomSymbol() }
            strips[index] = visible + fillers + [Self.randomSymbol(), result[index], Self.randomSymbol()]
            offsets[index] = 0
        }
        Task { @MainActor in
            // Барабаны стартуют с нового положения на следующем кадре.
            try? await Task.sleep(nanoseconds: 30_000_000)
            for index in 0..<3 {
                let target = -CGFloat(strips[index].count - 3) * cell
                withAnimation(.timingCurve(0.15, 0.75, 0.25, 1, duration: 1.4 + Double(index) * 0.45)) {
                    offsets[index] = target
                }
            }
            for index in 0..<3 {
                try? await Task.sleep(nanoseconds: UInt64((index == 0 ? 1.4 : 0.45) * 1_000_000_000))
                UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
            }
            let win = Self.payout(result, bet: bet)
            withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) {
                lastWin = win
                balance += win
            }
            if win >= bet * 10 {
                bigWinGlow = true
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                onResult("🎰 Удача: выпало \(result.joined()) — выигрыш \(win) монет! 🎉")
            } else if win > 0 {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            spinning = false
        }
    }
}
