package com.honerai.app.ui.games.engine

import kotlin.random.Random

/** Слоты «Удача»: символы с весами и выплаты (как SlotsGameView на iOS). */
object SlotsEngine {
    /** Символ и его вес: чем больше вес, тем чаще выпадает. */
    val symbols: List<Pair<String, Int>> = listOf(
        "🍒" to 26, "🍋" to 22, "🍀" to 16, "🔔" to 14, "⭐️" to 10, "7️⃣" to 7, "💎" to 5,
    )

    /** Множитель ставки за три одинаковых символа. */
    val payouts: Map<String, Int> = mapOf(
        "💎" to 50, "7️⃣" to 30, "⭐️" to 15, "🔔" to 10, "🍀" to 8, "🍋" to 5, "🍒" to 3,
    )

    val bets: List<Int> = listOf(10, 50, 100, 250)
    const val START_BALANCE = 1000
    const val BALANCE_KEY = "honor.slots.balance"

    val totalWeight: Int = symbols.sumOf { it.second }

    /** Символ по номеру броска 0 until totalWeight. */
    fun symbolForRoll(roll: Int): String {
        var rest = roll
        for ((symbol, weight) in symbols) {
            if (rest < weight) return symbol
            rest -= weight
        }
        return "🍒"
    }

    fun randomSymbol(random: Random = Random.Default): String = symbolForRoll(random.nextInt(totalWeight))

    /** Выигрыш за линию: три одинаковых — по таблице, две вишни — ×2. */
    fun payout(line: List<String>, bet: Int): Int {
        if (line.size == 3 && line[0] == line[1] && line[1] == line[2]) return bet * (payouts[line[0]] ?: 2)
        if (line.count { it == "🍒" } == 2) return bet * 2
        return 0
    }

    /** Крупный выигрыш — подсветка и сообщение в чат. */
    fun isBigWin(win: Int, bet: Int): Boolean = win >= bet * 10
}
