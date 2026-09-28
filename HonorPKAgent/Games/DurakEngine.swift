import Foundation

/// «Дурак» подкидной на двоих, колода 36 карт.
///
/// Правила: козырь — последняя карта колоды; первым ходит тот, у кого младший козырь.
/// Подкидывать можно карты тех же достоинств, что уже на столе, но не больше шести
/// и не больше, чем карт у отбивающегося. Отбился — «Бито», ход переходит к нему.
/// Не отбился — забирает все карты, ходит снова атакующий. Добор до шести: сначала
/// атаковавший, потом отбивавшийся. Кто первым избавился от карт при пустой колоде —
/// выиграл, оставшийся с картами — «дурак».
struct DurakCard: Equatable, Hashable, Identifiable, Sendable {
    enum Suit: Int, CaseIterable, Sendable { case spades, clubs, diamonds, hearts
        var symbol: String { ["♠", "♣", "♦", "♥"][rawValue] }
        var isRed: Bool { self == .diamonds || self == .hearts }
    }
    let suit: Suit
    /// Достоинство 6…14 (валет 11, дама 12, король 13, туз 14).
    let rank: Int
    var id: Int { suit.rawValue * 100 + rank }

    var rankTitle: String {
        switch rank {
        case 11: return "В"
        case 12: return "Д"
        case 13: return "К"
        case 14: return "Т"
        default: return String(rank)
        }
    }

    static func fullDeck() -> [DurakCard] {
        Suit.allCases.flatMap { suit in (6...14).map { DurakCard(suit: suit, rank: $0) } }
    }
}

struct DurakGame: Sendable {
    enum Player: Int, Sendable { case human, ai
        var other: Player { self == .human ? .ai : .human }
    }

    struct Pair: Equatable, Sendable, Identifiable {
        var attack: DurakCard
        var defense: DurakCard?
        var id: Int { attack.id }
    }

    enum Outcome: Equatable, Sendable { case humanWon, aiWon, draw }

    private(set) var deck: [DurakCard]
    private(set) var trump: DurakCard
    private(set) var hands: [Player: [DurakCard]] = [:]
    private(set) var table: [Pair] = []
    private(set) var discard: [DurakCard] = []
    private(set) var attacker: Player
    /// Отбивающийся решил забрать карты: атакующий может только подкинуть и сказать «Бери».
    private(set) var defenderTakes = false
    /// Сколько карт было у отбивающегося в начале раунда: столько можно подкинуть максимум.
    private var defenderStartCount = 6
    private(set) var outcome: Outcome?

    var defender: Player { attacker.other }
    var trumpSuit: DurakCard.Suit { trump.suit }

    init(seed: [DurakCard]? = nil) {
        var deck = seed ?? DurakCard.fullDeck().shuffled()
        var hands: [Player: [DurakCard]] = [.human: [], .ai: []]
        for _ in 0..<6 {
            hands[.human]!.append(deck.removeFirst())
            hands[.ai]!.append(deck.removeFirst())
        }
        trump = deck.last!
        self.deck = deck
        self.hands = hands
        // Первым ходит тот, у кого младший козырь.
        let suit = deck.last!.suit
        let humanLowest = hands[.human]!.filter { $0.suit == suit }.map(\.rank).min() ?? 99
        let aiLowest = hands[.ai]!.filter { $0.suit == suit }.map(\.rank).min() ?? 99
        attacker = aiLowest < humanLowest ? .ai : .human
        defenderStartCount = 6
        sortHands()
    }

    func hand(_ player: Player) -> [DurakCard] { hands[player] ?? [] }

    /// Бьёт ли карта `defense` карту `attack`.
    func beats(_ defense: DurakCard, _ attack: DurakCard) -> Bool {
        if defense.suit == attack.suit { return defense.rank > attack.rank }
        return defense.suit == trumpSuit && attack.suit != trumpSuit
    }

    private var tableRanks: Set<Int> {
        Set(table.flatMap { [$0.attack.rank] + ($0.defense.map { [$0.rank] } ?? []) })
    }

    /// Можно ли атаковать (или подкинуть) этой картой.
    func canAttack(with card: DurakCard, by player: Player) -> Bool {
        guard outcome == nil, player == attacker, hand(player).contains(card) else { return false }
        if table.isEmpty { return !hand(defender).isEmpty }
        // Всего в раунде — не больше шести карт и не больше, чем было у отбивающегося.
        guard table.count < min(6, defenderStartCount) else { return false }
        if !defenderTakes {
            // Неотбитых карт не может быть больше, чем карт на руке у отбивающегося.
            let open = table.filter { $0.defense == nil }.count
            guard open < hand(defender).count else { return false }
        }
        return tableRanks.contains(card.rank)
    }

    mutating func attack(with card: DurakCard, by player: Player) -> Bool {
        guard canAttack(with: card, by: player) else { return false }
        if table.isEmpty { defenderStartCount = hand(defender).count }
        hands[player]?.removeAll { $0 == card }
        table.append(Pair(attack: card, defense: nil))
        checkOutcome()
        return true
    }

    /// Карты отбивающегося, которыми можно побить первую неотбитую карту.
    func defenses(for attack: DurakCard) -> [DurakCard] {
        hand(defender).filter { beats($0, attack) }
    }

    mutating func defend(_ attackCard: DurakCard, with card: DurakCard) -> Bool {
        guard outcome == nil, !defenderTakes, hand(defender).contains(card),
              let index = table.firstIndex(where: { $0.attack == attackCard && $0.defense == nil }),
              beats(card, attackCard) else { return false }
        hands[defender]?.removeAll { $0 == card }
        table[index].defense = card
        checkOutcome()
        return true
    }

    /// Отбивающийся берёт: атакующий ещё может подкинуть, потом «Бери».
    mutating func declareTake() {
        guard outcome == nil, !table.isEmpty else { return }
        defenderTakes = true
    }

    /// Раунд окончен: «Бито» (всё отбито) или «Бери» (отбивающийся забирает).
    mutating func finishRound() {
        guard !table.isEmpty else { return }
        let allBeaten = table.allSatisfy { $0.defense != nil }
        if defenderTakes || !allBeaten {
            let cards = table.flatMap { [$0.attack] + ($0.defense.map { [$0] } ?? []) }
            hands[defender]?.append(contentsOf: cards)
            table = []
            defenderTakes = false
            refill(first: attacker)
            // Взявший пропускает ход: атакует тот же игрок.
        } else {
            discard += table.flatMap { [$0.attack] + ($0.defense.map { [$0] } ?? []) }
            table = []
            refill(first: attacker)
            attacker = attacker.other
        }
        sortHands()
        checkOutcome()
    }

    var allBeaten: Bool { !table.isEmpty && table.allSatisfy { $0.defense != nil } }

    private mutating func refill(first: Player) {
        for player in [first, first.other] {
            while (hands[player]?.count ?? 0) < 6, !deck.isEmpty {
                hands[player]?.append(deck.removeFirst())
            }
        }
    }

    private mutating func sortHands() {
        let trump = trumpSuit
        for player in [Player.human, .ai] {
            hands[player]?.sort { lhs, rhs in
                let lTrump = lhs.suit == trump, rTrump = rhs.suit == trump
                if lTrump != rTrump { return !lTrump }
                if lhs.suit != rhs.suit { return lhs.suit.rawValue < rhs.suit.rawValue }
                return lhs.rank < rhs.rank
            }
        }
    }

    private mutating func checkOutcome() {
        guard deck.isEmpty else { return }
        let humanEmpty = hand(.human).isEmpty, aiEmpty = hand(.ai).isEmpty
        // Отбивающийся, у которого кончились карты посреди раунда, ещё должен отбиться.
        if table.contains(where: { $0.defense == nil }) { return }
        if humanEmpty && aiEmpty { outcome = .draw }
        else if humanEmpty && table.isEmpty { outcome = .humanWon }
        else if aiEmpty && table.isEmpty { outcome = .aiWon }
        else if humanEmpty && allBeaten { outcome = .humanWon }
        else if aiEmpty && allBeaten { outcome = .aiWon }
    }

    // MARK: - Соперник

    /// Ход соперника. Возвращает `false`, если соперник ничего не сделал (ждёт человека).
    mutating func aiStep() -> Bool {
        guard outcome == nil else { return false }
        if attacker == .ai {
            // Отбивающийся берёт — подкидываем подходящие некозырные карты, потом «Бери».
            if defenderTakes {
                if let card = aiThrowIn(limitTrumps: true) { _ = attack(with: card, by: .ai); return true }
                finishRound()
                return true
            }
            if table.isEmpty {
                let card = aiLowestAttack()
                if let card { _ = attack(with: card, by: .ai); return true }
                return false
            }
            if allBeaten {
                if let card = aiThrowIn(limitTrumps: !deck.isEmpty) { _ = attack(with: card, by: .ai); return true }
                finishRound()
                return true
            }
            return false
        } else {
            // Защита: бьём первую неотбитую карту самой младшей подходящей.
            guard !defenderTakes, let open = table.first(where: { $0.defense == nil }) else { return false }
            let options = defenses(for: open.attack).sorted { cost($0) < cost($1) }
            if let best = options.first, !(deck.count > 6 && best.suit == trumpSuit && best.rank >= 12 && table.count == 1) {
                _ = defend(open.attack, with: best)
            } else {
                declareTake()
            }
            return true
        }
    }

    private func cost(_ card: DurakCard) -> Int { card.rank + (card.suit == trumpSuit ? 20 : 0) }

    private func aiLowestAttack() -> DurakCard? {
        hand(.ai).min { cost($0) < cost($1) }
    }

    private func aiThrowIn(limitTrumps: Bool) -> DurakCard? {
        hand(.ai)
            .filter { canAttack(with: $0, by: .ai) && (!limitTrumps || $0.suit != trumpSuit) && $0.rank <= (deck.isEmpty ? 14 : 11) }
            .min { cost($0) < cost($1) }
    }
}
