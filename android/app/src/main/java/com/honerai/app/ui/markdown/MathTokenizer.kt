package com.honerai.app.ui.markdown

import androidx.compose.runtime.Immutable

/** Кусок формулы для нативной отрисовки (порт MathToken с iOS). */
@Immutable
sealed interface MathToken {
    data class Text(val value: String) : MathToken
    data class Sup(val value: String) : MathToken
    data class Sub(val value: String) : MathToken
    data class Frac(val numerator: String, val denominator: String) : MathToken
    data class Sqrt(val value: String) : MathToken
}

/**
 * Простой разбор LaTeX без веб-вью: \frac{a}{b}, \sqrt{x}, ^ и _ с одним символом
 * или {группой}, греческие буквы и крупные операторы превращаются в символы.
 */
object MathTokenizer {
    /** Команды LaTeX → символы (общие для блочных и строчных формул). */
    val symbols: Map<String, String> = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
        "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮", "infty" to "∞", "times" to "×", "cdot" to "·",
        "div" to "÷", "pm" to "±", "mp" to "∓", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡", "to" to "→", "rightarrow" to "→",
        "leftarrow" to "←", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "sqrt" to "√", "partial" to "∂",
        "nabla" to "∇", "in" to "∈", "notin" to "∉", "forall" to "∀", "exists" to "∃", "cup" to "∪",
        "cap" to "∩", "subset" to "⊂", "subseteq" to "⊆", "emptyset" to "∅", "degree" to "°", "circ" to "∘",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "ldots" to "…", "cdots" to "⋯", "dots" to "…",
    )

    /** Команды, которые ничего не рисуют. */
    private val silent = setOf("left", "right", "displaystyle", "limits", "text", "mathrm", "mathbf", "mathit",
        "big", "Big", "bigg", "quad", "qquad")

    fun tokenize(input: String): List<MathToken> {
        val source = input
        var index = 0
        val result = ArrayList<MathToken>()

        fun readGroup(): String {
            if (index >= source.length) return ""
            if (source[index] != '{') {
                // Одиночный аргумент: символ или команда (\alpha).
                if (source[index] == '\\') {
                    var end = index + 1
                    while (end < source.length && source[end].isLetter()) end++
                    val command = source.substring(index + 1, end)
                    index = end
                    return symbols[command] ?: command
                }
                val value = source[index].toString()
                index += 1
                return value
            }
            index += 1
            var depth = 1
            val value = StringBuilder()
            while (index < source.length) {
                val c = source[index]
                if (c == '{') depth += 1
                if (c == '}') {
                    depth -= 1
                    if (depth == 0) { index += 1; break }
                }
                value.append(c)
                index += 1
            }
            return InlineMath.unicode(value.toString())
        }

        while (index < source.length) {
            val c = source[index]
            if (c == '\\') {
                index += 1
                val start = index
                while (index < source.length && source[index].isLetter()) index += 1
                val command = source.substring(start, index)
                when {
                    command == "frac" || command == "dfrac" || command == "tfrac" -> {
                        val numerator = readGroup()
                        val denominator = readGroup()
                        result.add(MathToken.Frac(numerator, denominator))
                    }
                    command == "sqrt" -> result.add(MathToken.Sqrt(readGroup()))
                    command in silent -> Unit
                    command.isEmpty() -> {
                        // «\,» «\;» — пробелы, «\{» — сама скобка.
                        if (index < source.length) {
                            val next = source[index]
                            index += 1
                            result.add(MathToken.Text(if (next in ",;:! ") " " else next.toString()))
                        }
                    }
                    else -> result.add(MathToken.Text(symbols[command] ?: command))
                }
                continue
            }
            if (c == '^') {
                index += 1
                result.add(MathToken.Sup(readGroup()))
                continue
            }
            if (c == '_') {
                index += 1
                result.add(MathToken.Sub(readGroup()))
                continue
            }
            if (c == '{' || c == '}') {
                index += 1
                continue
            }
            // Обычный текст до следующего специального символа.
            val start = index
            while (index < source.length && source[index] !in "\\^_{}") index += 1
            if (index > start) result.add(MathToken.Text(source.substring(start, index)))
        }
        return result.ifEmpty { listOf(MathToken.Text(input)) }
    }
}

/**
 * Строчные формулы $x^2$ превращаются в читаемый текст с настоящими надстрочными
 * и подстрочными символами (порт InlineStyleParser.unicodeMath).
 */
object InlineMath {
    private val superscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ',
        'i' to 'ⁱ', 'x' to 'ˣ',
    )
    private val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ',
        'e' to 'ₑ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'o' to 'ₒ', 'x' to 'ₓ', 'n' to 'ₙ', 'm' to 'ₘ', 'k' to 'ₖ',
        'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ',
    )

    /** Длинные команды раньше коротких: иначе «\int» превратился бы в «∈t». */
    private val commands = MathTokenizer.symbols.entries.sortedByDescending { it.key.length }

    /** x^2 → x², H_2O → H₂O, \alpha → α, \frac{a}{b} → (a)/(b). */
    fun unicode(input: String): String {
        var text = input
        var guard = 0
        while (guard < 50) {
            guard++
            val at = text.indexOf("\\frac")
            if (at < 0) break
            val open = text.indexOf('{', at + 5)
            if (open < 0) break
            val close = text.indexOf('}', open)
            if (close < 0) break
            val numerator = text.substring(open + 1, close)
            val secondOpen = text.indexOf('{', close + 1)
            if (secondOpen < 0) break
            val secondClose = text.indexOf('}', secondOpen)
            if (secondClose < 0) break
            val denominator = text.substring(secondOpen + 1, secondClose)
            text = text.substring(0, at) + "($numerator)/($denominator)" + text.substring(secondClose + 1)
        }
        if (text.contains('\\')) {
            text = text.replace("\\left", "").replace("\\right", "")
            for ((command, symbol) in commands) {
                if (text.contains("\\$command")) text = text.replace("\\$command", symbol)
            }
        }
        text = text.replace("{", "").replace("}", "")
        if (!text.contains('^') && !text.contains('_')) return text
        val result = StringBuilder()
        var position = 0
        while (position < text.length) {
            val c = text[position]
            if ((c == '^' || c == '_') && position + 1 < text.length) {
                val map = if (c == '^') superscripts else subscripts
                val converted = StringBuilder()
                var cursor = position + 1
                while (cursor < text.length) {
                    val symbol = map[text[cursor]] ?: break
                    converted.append(symbol)
                    cursor++
                }
                if (converted.isEmpty()) {
                    result.append(c)
                    position++
                } else {
                    result.append(converted)
                    position = cursor
                }
                continue
            }
            result.append(c)
            position++
        }
        return result.toString()
    }
}
