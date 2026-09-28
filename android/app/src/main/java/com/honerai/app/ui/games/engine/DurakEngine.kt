package com.honerai.app.ui.games.engine

/**
 * «Дурак» подкидной на двоих, колода 36 карт (порт DurakEngine.swift).
 *
 * Правила: козырь — последняя карта колоды; первым ходит тот, у кого младший козырь.
 * Подкидывать можно карты тех же достоинств, что уже на столе, но не больше шести
 * и не больше, чем карт у отбивающегося. Отбился — «Бито», ход переходит к нему.
 * Не отбился — забирает все карты, ходит снова атакующий. Добор до шести: сначала
 * атаковавший, потом отбивавшийся. Кто первым избавился от карт при пустой колоде —
 * выиграл, оставшийся с картами — «дурак».
 */
data class DurakCard(val suit: Suit, val rank: Int) {
    enum class Suit(val symbol: String) {
        SPADES("♠"), CLUBS("♣"), DIAMONDS("♦"), HEARTS("♥");
        val isRed: Boolean get() = this == DIAMONDS || this == HEARTS
    }

    /** Достоинство 6…14 (валет 11, дама 12, король 13, туз 14). */
    val id: Int get() = suit.ordinal * 100 + rank

    /** Обозначение достоинства: русское (В, Д, К, Т) или английское (J, Q, K, A). */
    fun rankTitle(english: Boolean = false): String = when (rank) {
        11 -> if (english) "J" else "В"
        12 -> if (english) "Q" else "Д"
        13 -> if (english) "K" else "К"
        14 -> if (english) "A" else "Т"
        else -> rank.toString()
    }

    companion object {
        fun fullDeck(): List<DurakCard> = Suit.entries.flatMap { suit -> (6..14).map { DurakCard(suit, it) } }
    }
}

class DurakGame private constructor(
    private val deckList: ArrayList<DurakCard>,
    val trump: DurakCard,
    private val hands: HashMap<Player, ArrayList<DurakCard>>,
    private val tableList: ArrayList<Pair>,
    private val discardList: ArrayList<DurakCard>,
    attacker: Player,
    defenderTakes: Boolean,
    private var defenderStartCount: Int,
    outcome: Outcome?,
) {
    enum class Player { HUMAN, AI;
        val other: Player get() = if (this == HUMAN) AI else HUMAN
    }

    data class Pair(val attack: DurakCard, val defense: DurakCard? = null) {
        val id: Int get() = attack.id
    }

    enum class Outcome { HUMAN_WON, AI_WON, DRAW }

    var attacker: Player = attacker; private set
    /** Отбивающийся решил забрать карты: атакующий может только подкинуть и сказать «Бери». */
    var defenderTakes: Boolean = defenderTakes; private set
    var outcome: Outcome? = outcome; private set

    val deck: List<DurakCard> get() = deckList
    val table: List<Pair> get() = tableList
    val discard: List<DurakCard> get() = discardList
    val defender: Player get() = attacker.other
    val trumpSuit: DurakCard.Suit get() = trump.suit

    fun copy(): DurakGame = DurakGame(
        ArrayList(deckList), trump,
        hashMapOf(Player.HUMAN to ArrayList(hand(Player.HUMAN)), Player.AI to ArrayList(hand(Player.AI))),
        ArrayList(tableList), ArrayList(discardList), attacker, defenderTakes, defenderStartCount, outcome,
    )

    fun hand(player: Player): List<DurakCard> = hands[player] ?: emptyList()

    /** Бьёт ли карта [defense] карту [attack]. */
    fun beats(defense: DurakCard, attack: DurakCard): Boolean {
        if (defense.suit == attack.suit) return defense.rank > attack.rank
        return defense.suit == trumpSuit && attack.suit != trumpSuit
    }

    private val tableRanks: Set<Int>
        get() = tableList.flatMapTo(HashSet()) { listOfNotNull(it.attack.rank, it.defense?.rank) }

    /** Можно ли атаковать (или подкинуть) этой картой. */
    fun canAttack(card: DurakCard, player: Player): Boolean {
        if (outcome != null || player != attacker || card !in hand(player)) return false
        if (tableList.isEmpty()) return hand(defender).isNotEmpty()
        // Всего в раунде — не больше шести карт и не больше, чем было у отбивающегося.
        if (tableList.size >= minOf(6, defenderStartCount)) return false
        if (!defenderTakes) {
            // Неотбитых карт не может быть больше, чем карт на руке у отбивающегося.
            val open = tableList.count { it.defense == null }
            if (open >= hand(defender).size) return false
        }
        return card.rank in tableRanks
    }

    fun attack(card: DurakCard, player: Player): Boolean {
        if (!canAttack(card, player)) return false
        if (tableList.isEmpty()) defenderStartCount = hand(defender).size
        hands[player]?.remove(card)
        tableList.add(Pair(card))
        checkOutcome()
        return true
    }

    /** Карты отбивающегося, которыми можно побить карту [attack]. */
    fun defenses(attack: DurakCard): List<DurakCard> = hand(defender).filter { beats(it, attack) }

    fun defend(attackCard: DurakCard, card: DurakCard): Boolean {
        if (outcome != null || defenderTakes || card !in hand(defender)) return false
        val index = tableList.indexOfFirst { it.attack == attackCard && it.defense == null }
        if (index < 0 || !beats(card, attackCard)) return false
        hands[defender]?.remove(card)
        tableList[index] = tableList[index].copy(defense = card)
        checkOutcome()
        return true
    }

    /** Отбивающийся берёт: атакующий ещё может подкинуть, потом «Бери». */
    fun declareTake() {
        if (outcome != null || tableList.isEmpty()) return
        defenderTakes = true
    }

    /** Раунд окончен: «Бито» (всё отбито) или «Бери» (отбивающийся забирает). */
    fun finishRound() {
        if (tableList.isEmpty()) return
        val everythingBeaten = tableList.all { it.defense != null }
        val cards = tableList.flatMap { listOfNotNull(it.attack, it.defense) }
        tableList.clear()
        if (defenderTakes || !everythingBeaten) {
            hands[defender]?.addAll(cards)
            defenderTakes = false
            refill(attacker)
            // Взявший пропускает ход: атакует тот же игрок.
        } else {
            discardList.addAll(cards)
            refill(attacker)
            attacker = attacker.other
        }
        sortHands()
        checkOutcome()
    }

    val allBeaten: Boolean get() = tableList.isNotEmpty() && tableList.all { it.defense != null }

    private fun refill(first: Player) {
        for (player in listOf(first, first.other)) {
            val hand = hands.getOrPut(player) { ArrayList() }
            while (hand.size < 6 && deckList.isNotEmpty()) hand.add(deckList.removeAt(0))
        }
    }

    private fun sortHands() {
        val trump = trumpSuit
        for (player in Player.entries) {
            hands[player]?.sortWith(compareBy<DurakCard>({ it.suit == trump }, { it.suit.ordinal }, { it.rank }))
        }
    }

    private fun checkOutcome() {
        if (deckList.isNotEmpty()) return
        val humanEmpty = hand(Player.HUMAN).isEmpty()
        val aiEmpty = hand(Player.AI).isEmpty()
        // Отбивающийся, у которого кончились карты посреди раунда, ещё должен отбиться.
        if (tableList.any { it.defense == null }) return
        outcome = when {
            humanEmpty && aiEmpty -> Outcome.DRAW
            humanEmpty && tableList.isEmpty() -> Outcome.HUMAN_WON
            aiEmpty && tableList.isEmpty() -> Outcome.AI_WON
            humanEmpty && allBeaten -> Outcome.HUMAN_WON
            aiEmpty && allBeaten -> Outcome.AI_WON
            else -> outcome
        }
    }

    // Соперник

    /** Ход соперника. Возвращает false, если соперник ничего не сделал (ждёт человека). */
    fun aiStep(): Boolean {
        if (outcome != null) return false
        if (attacker == Player.AI) {
            // Отбивающийся берёт — подкидываем подходящие некозырные карты, потом «Бери».
            if (defenderTakes) {
                val card = aiThrowIn(limitTrumps = true)
                if (card != null) { attack(card, Player.AI); return true }
                finishRound()
                return true
            }
            if (tableList.isEmpty()) {
                val card = aiLowestAttack() ?: return false
                attack(card, Player.AI)
                return true
            }
            if (allBeaten) {
                val card = aiThrowIn(limitTrumps = deckList.isNotEmpty())
                if (card != null) { attack(card, Player.AI); return true }
                finishRound()
                return true
            }
            return false
        } else {
            // Защита: бьём первую неотбитую карту самой младшей подходящей.
            if (defenderTakes) return false
            val open = tableList.firstOrNull { it.defense == null } ?: return false
            val best = defenses(open.attack).minByOrNull { cost(it) }
            if (best != null && !(deckList.size > 6 && best.suit == trumpSuit && best.rank >= 12 && tableList.size == 1)) {
                defend(open.attack, best)
            } else {
                declareTake()
            }
            return true
        }
    }

    private fun cost(card: DurakCard): Int = card.rank + if (card.suit == trumpSuit) 20 else 0

    private fun aiLowestAttack(): DurakCard? = hand(Player.AI).minByOrNull { cost(it) }

    private fun aiThrowIn(limitTrumps: Boolean): DurakCard? = hand(Player.AI)
        .filter { canAttack(it, Player.AI) && (!limitTrumps || it.suit != trumpSuit) && it.rank <= (if (deckList.isEmpty()) 14 else 11) }
        .minByOrNull { cost(it) }

    companion object {
        /** Новая раздача; [seed] — колода в заданном порядке (для тестов). */
        fun create(seed: List<DurakCard>? = null, random: kotlin.random.Random = kotlin.random.Random.Default): DurakGame {
            val deck = ArrayList(seed ?: DurakCard.fullDeck().shuffled(random))
            val human = ArrayList<DurakCard>()
            val ai = ArrayList<DurakCard>()
            repeat(6) {
                human.add(deck.removeAt(0))
                ai.add(deck.removeAt(0))
            }
            val trump = deck.last()
            // Первым ходит тот, у кого младший козырь.
            val humanLowest = human.filter { it.suit == trump.suit }.minOfOrNull { it.rank } ?: 99
            val aiLowest = ai.filter { it.suit == trump.suit }.minOfOrNull { it.rank } ?: 99
            val attacker = if (aiLowest < humanLowest) Player.AI else Player.HUMAN
            val game = DurakGame(
                deck, trump, hashMapOf(Player.HUMAN to human, Player.AI to ai),
                ArrayList(), ArrayList(), attacker, false, 6, null,
            )
            game.sortHands()
            return game
        }
    }
}
