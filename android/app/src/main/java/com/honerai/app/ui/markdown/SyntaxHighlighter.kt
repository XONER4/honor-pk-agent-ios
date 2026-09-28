package com.honerai.app.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Подсветка синтаксиса без внешних библиотек (порт SyntaxHighlighter.swift):
 * Swift, Python, JavaScript/TypeScript, JSON, SQL, Bash, Kotlin/Java и C-подобные.
 * Разбор по строкам: комментарии, строки, числа, ключевые слова, типы.
 * В отличие от iOS текст сохраняется символ в символ (там между словами вставлялись пробелы).
 */
object SyntaxHighlighter {
    data class Theme(
        val keyword: Color, val string: Color, val comment: Color,
        val number: Color, val type: Color, val plain: Color,
    )

    val dark = Theme(
        keyword = Color(1.00f, 0.48f, 0.62f), string = Color(0.60f, 0.85f, 0.55f),
        comment = Color(0.52f, 0.56f, 0.60f), number = Color(0.85f, 0.72f, 0.45f),
        type = Color(0.45f, 0.78f, 0.98f), plain = Color(0.90f, 0.91f, 0.93f),
    )

    /** Светлая тема — для тех, кому тёмный блок кода неудобен. */
    val light = Theme(
        keyword = Color(0.68f, 0.10f, 0.34f), string = Color(0.10f, 0.45f, 0.18f),
        comment = Color(0.45f, 0.48f, 0.52f), number = Color(0.55f, 0.35f, 0.05f),
        type = Color(0.08f, 0.32f, 0.62f), plain = Color(0.12f, 0.13f, 0.15f),
    )

    private val keywords: Map<String, Set<String>> = mapOf(
        "swift" to setOf("actor", "any", "as", "async", "await", "break", "case", "catch", "class", "continue",
            "default", "defer", "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate",
            "for", "func", "guard", "if", "import", "in", "init", "inout", "internal", "is", "lazy",
            "let", "nil", "open", "operator", "private", "protocol", "public", "repeat", "return",
            "self", "static", "struct", "subscript", "super", "switch", "throw", "throws", "true",
            "try", "typealias", "var", "where", "while", "some", "weak", "unowned", "mutating"),
        "python" to setOf("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
            "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
            "while", "with", "yield", "self"),
        "javascript" to setOf("async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
            "function", "if", "import", "in", "instanceof", "let", "new", "null", "return",
            "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var",
            "void", "while", "yield"),
        "sql" to setOf("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
            "table", "alter", "drop", "index", "join", "inner", "left", "right", "outer", "on", "group",
            "by", "order", "having", "limit", "offset", "and", "or", "not", "null", "as", "distinct",
            "count", "sum", "avg", "min", "max", "primary", "key", "foreign", "references"),
        "bash" to setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
            "return", "exit", "echo", "export", "local", "readonly", "source", "cd", "sudo", "apt",
            "brew", "git", "npm", "pnpm", "python3", "curl", "wget", "chmod", "mkdir", "rm", "cp", "mv"),
        "java" to setOf("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "val", "var", "fun", "when", "object", "data", "sealed", "companion"),
        "kotlin" to setOf("abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
            "const", "constructor", "continue", "data", "do", "else", "enum", "expect", "external",
            "false", "final", "finally", "for", "fun", "get", "if", "import", "in", "interface",
            "internal", "is", "lateinit", "null", "object", "open", "operator", "out", "override",
            "package", "private", "protected", "public", "return", "sealed", "set", "super", "suspend",
            "this", "throw", "true", "try", "typealias", "val", "var", "when", "while"),
        "json" to setOf("true", "false", "null"),
    )

    /** Название языка из блока кода → известный ключ. */
    fun languageKey(language: String): String {
        val value = language.lowercase()
        return when {
            value.startsWith("swift") -> "swift"
            value.startsWith("py") -> "python"
            value.startsWith("js") || value.startsWith("ts") || value.startsWith("javascript") ||
                value.startsWith("node") -> "javascript"
            value.startsWith("sql") -> "sql"
            value.startsWith("sh") || value.startsWith("bash") || value.startsWith("zsh") ||
                value.startsWith("shell") -> "bash"
            value.startsWith("kt") || value.startsWith("kotlin") -> "kotlin"
            value.startsWith("java") -> "java"
            value.startsWith("json") -> "json"
            value.startsWith("c") || value.startsWith("objc") -> "java"
            else -> ""
        }
    }

    private const val SEPARATORS = " \t(){}[]<>,;:+-*/%=!&|^~?."

    fun highlight(code: String, language: String, theme: Theme): AnnotatedString {
        val key = languageKey(language)
        val words = keywords[key]
        if (key.isEmpty() || words == null) {
            return buildAnnotatedString { withStyle(SpanStyle(color = theme.plain)) { append(code) } }
        }
        val commentTokens = when (key) {
            "python", "bash" -> listOf("#")
            "sql" -> listOf("--")
            else -> listOf("//", "/*", "*")
        }
        val comment = SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic)
        val string = SpanStyle(color = theme.string)
        val keyword = SpanStyle(color = theme.keyword, fontWeight = FontWeight.Bold)
        val number = SpanStyle(color = theme.number)
        val type = SpanStyle(color = theme.type)
        val plain = SpanStyle(color = theme.plain)
        return buildAnnotatedString {
            var lineStart = 0
            var first = true
            while (lineStart <= code.length) {
                val newline = code.indexOf('\n', lineStart)
                val lineEnd = if (newline < 0) code.length else newline
                if (!first) append('\n')
                first = false
                val line = code.substring(lineStart, lineEnd)
                highlightLine(line, key, words, commentTokens, comment, string, keyword, number, type, plain)
                if (newline < 0) break
                lineStart = newline + 1
            }
        }
    }

    private fun AnnotatedString.Builder.highlightLine(
        line: String, language: String, words: Set<String>, commentTokens: List<String>,
        comment: SpanStyle, string: SpanStyle, keyword: SpanStyle, number: SpanStyle, type: SpanStyle, plain: SpanStyle,
    ) {
        val trimmed = line.trimWs()
        if (commentTokens.any { trimmed.startsWith(it) }) {
            withStyle(comment) { append(line) }
            return
        }
        fun plainPart(value: String) {
            if (value.isEmpty()) return
            // Обычный текст копится одним куском: меньше стилей — быстрее отрисовка.
            val pending = StringBuilder()
            fun flush() {
                if (pending.isNotEmpty()) {
                    withStyle(plain) { append(pending.toString()) }
                    pending.setLength(0)
                }
            }
            var start = 0
            fun word(end: Int) {
                if (end <= start) return
                val piece = value.substring(start, end)
                val style = when {
                    piece in words -> keyword
                    piece[0].isDigit() -> number
                    piece[0].isUpperCase() && piece.length > 1 -> type
                    else -> null
                }
                if (style == null) {
                    pending.append(piece)
                } else {
                    flush()
                    withStyle(style) { append(piece) }
                }
            }
            for (i in value.indices) {
                if (value[i] in SEPARATORS) {
                    word(i)
                    pending.append(value[i])
                    start = i + 1
                }
            }
            word(value.length)
            flush()
        }

        var position = 0
        var segment = 0
        while (position < line.length) {
            val c = line[position]
            val isQuote = c == '"' || c == '\'' || (c == '`' && language == "javascript")
            if (isQuote) {
                plainPart(line.substring(segment, position))
                var end = position + 1
                while (end < line.length) {
                    if (line[end] == c && line[end - 1] != '\\') { end++; break }
                    end++
                }
                withStyle(string) { append(line.substring(position, minOf(end, line.length))) }
                position = end
                segment = end
                continue
            }
            val hashComment = c == '#' && (language == "python" || language == "bash")
            val slashComment = c == '/' && position + 1 < line.length && line[position + 1] == '/'
            if (hashComment || slashComment) {
                plainPart(line.substring(segment, position))
                withStyle(comment) { append(line.substring(position)) }
                return
            }
            position++
        }
        if (segment < line.length) plainPart(line.substring(segment))
    }
}
