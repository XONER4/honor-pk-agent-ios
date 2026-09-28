package com.honerai.app.games

import com.honerai.app.ui.games.engine.DurakCard
import com.honerai.app.ui.games.engine.DurakCard.Suit
import com.honerai.app.ui.games.engine.DurakGame
import com.honerai.app.ui.games.engine.DurakGame.Player
import com.honerai.app.ui.games.engine.SlotsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DurakAndSlotsTest {
    /** Колода, где у человека и соперника заданные карты, а козырь — последняя карта. */
    private fun seeded(human: List<DurakCard>, ai: List<DurakCard>, trump: DurakCard): List<DurakCard> {
        val used = (human + ai + trump).toSet()
        val rest = DurakCard.fullDeck().filter { it !in used }
        val deck = ArrayList<DurakCard>()
        for (i in 0 until 6) { deck.add(human[i]); deck.add(ai[i]) }
        deck.addAll(rest)
        deck.add(trump)
        return deck
    }

    private fun c(suit: Suit, rank: Int) = DurakCard(suit, rank)

    @Test
    fun dealIsCorrect() {
        val game = DurakGame.create(random = Random(1))
        assertEquals(36, DurakCard.fullDeck().toSet().size)
        assertEquals(6, game.hand(Player.HUMAN).size)
        assertEquals(6, game.hand(Player.AI).size)
        assertEquals(24, game.deck.size)
        assertEquals(game.trump, game.deck.last())
        val all = game.hand(Player.HUMAN) + game.hand(Player.AI) + game.deck
        assertEquals(36, all.toSet().size)
    }

    @Test
    fun beatingRulesAndTrump() {
        val game = DurakGame.create(seeded(
            listOf(c(Suit.SPADES, 6), c(Suit.SPADES, 7), c(Suit.CLUBS, 8), c(Suit.CLUBS, 9), c(Suit.DIAMONDS, 10), c(Suit.DIAMONDS, 11)),
            listOf(c(Suit.SPADES, 12), c(Suit.SPADES, 13), c(Suit.CLUBS, 12), c(Suit.CLUBS, 13), c(Suit.DIAMONDS, 12), c(Suit.DIAMONDS, 13)),
            c(Suit.HEARTS, 14),
        ))
        assertEquals(Suit.HEARTS, game.trumpSuit)
        assertTrue(game.beats(c(Suit.SPADES, 10), c(Suit.SPADES, 6)))
        assertFalse(game.beats(c(Suit.SPADES, 6), c(Suit.SPADES, 10)))
        assertTrue(game.beats(c(Suit.HEARTS, 6), c(Suit.SPADES, 14)))
        assertFalse(game.beats(c(Suit.SPADES, 14), c(Suit.HEARTS, 6)))
        assertFalse(game.beats(c(Suit.CLUBS, 14), c(Suit.SPADES, 6)))
        assertTrue(game.beats(c(Suit.HEARTS, 9), c(Suit.HEARTS, 7)))
    }

    @Test
    fun firstAttackerHasLowestTrump() {
        val game = DurakGame.create(seeded(
            listOf(c(Suit.SPADES, 6), c(Suit.SPADES, 7), c(Suit.CLUBS, 8), c(Suit.CLUBS, 9), c(Suit.DIAMONDS, 10), c(Suit.HEARTS, 11)),
            listOf(c(Suit.SPADES, 12), c(Suit.SPADES, 13), c(Suit.CLUBS, 12), c(Suit.CLUBS, 13), c(Suit.DIAMONDS, 12), c(Suit.HEARTS, 7)),
            c(Suit.HEARTS, 14),
        ))
        assertEquals(Player.AI, game.attacker)
    }

    @Test
    fun takingAndThrowIn() {
        val game = DurakGame.create(seeded(
            listOf(c(Suit.SPADES, 6), c(Suit.CLUBS, 6), c(Suit.CLUBS, 8), c(Suit.CLUBS, 9), c(Suit.DIAMONDS, 10), c(Suit.HEARTS, 6)),
            listOf(c(Suit.SPADES, 12), c(Suit.SPADES, 13), c(Suit.CLUBS, 12), c(Suit.CLUBS, 13), c(Suit.DIAMONDS, 12), c(Suit.DIAMONDS, 13)),
            c(Suit.HEARTS, 14),
        ))
        assertEquals(Player.HUMAN, game.attacker)
        assertTrue(game.attack(c(Suit.SPADES, 6), Player.HUMAN))
        // Подкинуть можно только достоинство со стола.
        assertFalse(game.canAttack(c(Suit.CLUBS, 8), Player.HUMAN))
        game.declareTake()
        assertTrue(game.defenderTakes)
        assertTrue(game.attack(c(Suit.CLUBS, 6), Player.HUMAN))
        val aiBefore = game.hand(Player.AI).size
        game.finishRound()
        // Взявший забрал обе карты; атакует тот же игрок, человек добрал до шести.
        assertEquals(aiBefore + 2, game.hand(Player.AI).size)
        assertEquals(Player.HUMAN, game.attacker)
        assertEquals(6, game.hand(Player.HUMAN).size)
        assertTrue(game.table.isEmpty())
    }

    @Test
    fun beatenRoundGoesToDiscardAndTurnPasses() {
        val game = DurakGame.create(seeded(
            listOf(c(Suit.SPADES, 6), c(Suit.CLUBS, 7), c(Suit.CLUBS, 8), c(Suit.CLUBS, 9), c(Suit.DIAMONDS, 10), c(Suit.HEARTS, 6)),
            listOf(c(Suit.SPADES, 12), c(Suit.SPADES, 13), c(Suit.CLUBS, 12), c(Suit.CLUBS, 13), c(Suit.DIAMONDS, 12), c(Suit.DIAMONDS, 13)),
            c(Suit.HEARTS, 14),
        ))
        assertTrue(game.attack(c(Suit.SPADES, 6), Player.HUMAN))
        assertFalse(game.defend(c(Suit.SPADES, 6), c(Suit.CLUBS, 12)))
        assertTrue(game.defend(c(Suit.SPADES, 6), c(Suit.SPADES, 12)))
        assertTrue(game.allBeaten)
        game.finishRound()
        assertEquals(2, game.discard.size)
        assertEquals(Player.AI, game.attacker)
        assertEquals(6, game.hand(Player.HUMAN).size)
        assertEquals(6, game.hand(Player.AI).size)
    }

    @Test
    fun fullGamesTerminate() {
        repeat(40) { seed ->
            val random = Random(seed)
            val game = DurakGame.create(random = random)
            var steps = 0
            while (game.outcome == null && steps < 3000) {
                steps++
                if (game.aiStep()) continue
                // Ход человека: случайное допустимое действие.
                if (game.attacker == Player.HUMAN) {
                    val cards = game.hand(Player.HUMAN).filter { game.canAttack(it, Player.HUMAN) }
                    if (cards.isNotEmpty() && (game.table.isEmpty() || random.nextBoolean())) {
                        game.attack(cards.random(random), Player.HUMAN)
                    } else {
                        game.finishRound()
                    }
                } else {
                    val open = game.table.firstOrNull { it.defense == null }
                    val options = open?.let { game.defenses(it.attack) } ?: emptyList()
                    if (open != null && options.isNotEmpty() && random.nextInt(4) != 0) game.defend(open.attack, options.random(random))
                    else game.declareTake()
                }
            }
            assertNotNull("game $seed did not finish", game.outcome)
            val cards = game.hand(Player.HUMAN) + game.hand(Player.AI) + game.deck + game.discard +
                game.table.flatMap { listOfNotNull(it.attack, it.defense) }
            assertEquals(36, cards.toSet().size)
            assertEquals(36, cards.size)
        }
    }

    // Слоты

    @Test
    fun slotsPayouts() {
        assertEquals(500, SlotsEngine.payout(listOf("💎", "💎", "💎"), 10))
        assertEquals(300, SlotsEngine.payout(listOf("7️⃣", "7️⃣", "7️⃣"), 10))
        assertEquals(30, SlotsEngine.payout(listOf("🍒", "🍒", "🍒"), 10))
        assertEquals(20, SlotsEngine.payout(listOf("🍒", "🍋", "🍒"), 10))
        assertEquals(0, SlotsEngine.payout(listOf("🍒", "🍋", "🔔"), 10))
        assertEquals(0, SlotsEngine.payout(listOf("💎", "💎", "🍋"), 10))
        assertTrue(SlotsEngine.isBigWin(150, 10))
        assertFalse(SlotsEngine.isBigWin(80, 10))
    }

    @Test
    fun slotsWeights() {
        assertEquals(100, SlotsEngine.totalWeight)
        assertEquals("🍒", SlotsEngine.symbolForRoll(0))
        assertEquals("🍒", SlotsEngine.symbolForRoll(25))
        assertEquals("🍋", SlotsEngine.symbolForRoll(26))
        assertEquals("💎", SlotsEngine.symbolForRoll(99))
        val random = Random(42)
        val counts = HashMap<String, Int>()
        val n = 200_000
        repeat(n) { counts.merge(SlotsEngine.randomSymbol(random), 1, Int::plus) }
        for ((symbol, weight) in SlotsEngine.symbols) {
            val share = counts.getValue(symbol).toDouble() / n
            assertTrue("$symbol $share", abs(share - weight / 100.0) < 0.01)
        }
    }
}
