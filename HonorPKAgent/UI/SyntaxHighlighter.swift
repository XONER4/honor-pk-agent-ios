import SwiftUI

/// Подсветка синтаксиса без внешних библиотек: свой разбор по языку.
/// Поддерживает Swift, Python, JavaScript/TypeScript, JSON, SQL, Bash,
/// HTML/XML, Kotlin/Java и C-подобные языки (пункт 6 ТЗ).
enum SyntaxHighlighter {
    struct Theme {
        let keyword: Color
        let string: Color
        let comment: Color
        let number: Color
        let type: Color
        let plain: Color

        static let dark = Theme(
            keyword: Color(red: 1.00, green: 0.48, blue: 0.62),
            string: Color(red: 0.60, green: 0.85, blue: 0.55),
            comment: Color(red: 0.52, green: 0.56, blue: 0.60),
            number: Color(red: 0.85, green: 0.72, blue: 0.45),
            type: Color(red: 0.45, green: 0.78, blue: 0.98),
            plain: Color(red: 0.90, green: 0.91, blue: 0.93)
        )

        /// Светлая тема — для тех, кому тёмный блок кода неудобен (пункт «выбор темы подсветки»).
        static let light = Theme(
            keyword: Color(red: 0.68, green: 0.10, blue: 0.34),
            string: Color(red: 0.10, green: 0.45, blue: 0.18),
            comment: Color(red: 0.45, green: 0.48, blue: 0.52),
            number: Color(red: 0.55, green: 0.35, blue: 0.05),
            type: Color(red: 0.08, green: 0.32, blue: 0.62),
            plain: Color(red: 0.12, green: 0.13, blue: 0.15)
        )

        static func named(_ name: String) -> Theme {
            name == "light" ? .light : .dark
        }
    }

    private static let keywords: [String: Set<String>] = [
        "swift": ["actor", "any", "as", "async", "await", "break", "case", "catch", "class", "continue",
                  "default", "defer", "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate",
                  "for", "func", "guard", "if", "import", "in", "init", "inout", "internal", "is", "lazy",
                  "let", "nil", "open", "operator", "private", "protocol", "public", "repeat", "return",
                  "self", "static", "struct", "subscript", "super", "switch", "throw", "throws", "true",
                  "try", "typealias", "var", "where", "while", "some", "weak", "unowned", "mutating"],
        "python": ["and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
                   "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
                   "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
                   "while", "with", "yield", "self"],
        "javascript": ["async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
                       "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
                       "function", "if", "import", "in", "instanceof", "let", "new", "null", "return",
                       "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var",
                       "void", "while", "yield"],
        "sql": ["select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
                "table", "alter", "drop", "index", "join", "inner", "left", "right", "outer", "on", "group",
                "by", "order", "having", "limit", "offset", "and", "or", "not", "null", "as", "distinct",
                "count", "sum", "avg", "min", "max", "primary", "key", "foreign", "references"],
        "bash": ["if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
                 "return", "exit", "echo", "export", "local", "readonly", "source", "cd", "sudo", "apt",
                 "brew", "git", "npm", "pnpm", "python3", "curl", "wget", "chmod", "mkdir", "rm", "cp", "mv"],
        "java": ["abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
                 "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
                 "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
                 "new", "package", "private", "protected", "public", "return", "short", "static", "super",
                 "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
                 "while", "val", "var", "fun", "when", "object", "data", "sealed", "companion"],
        "kotlin": ["abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
                   "const", "constructor", "continue", "data", "do", "else", "enum", "expect", "external",
                   "false", "final", "finally", "for", "fun", "get", "if", "import", "in", "interface",
                   "internal", "is", "lateinit", "null", "object", "open", "operator", "out", "override",
                   "package", "private", "protected", "public", "return", "sealed", "set", "super", "suspend",
                   "this", "throw", "true", "try", "typealias", "val", "var", "when", "while"],
        "json": ["true", "false", "null"]
    ]

    /// Приводит название языка из блока кода к известному ключу.
    private static func languageKey(_ language: String) -> String {
        let value = language.lowercased()
        if value.hasPrefix("swift") { return "swift" }
        if value.hasPrefix("py") { return "python" }
        if value.hasPrefix("js") || value.hasPrefix("ts") || value.hasPrefix("javascript") || value.hasPrefix("node") {
            return "javascript"
        }
        if value.hasPrefix("sql") { return "sql" }
        if value.hasPrefix("sh") || value.hasPrefix("bash") || value.hasPrefix("zsh") || value.hasPrefix("shell") {
            return "bash"
        }
        if value.hasPrefix("kt") { return "kotlin" }
        if value.hasPrefix("java") { return "java" }
        if value.hasPrefix("json") { return "json" }
        if value.hasPrefix("c") || value.hasPrefix("objc") { return "java" }
        return ""
    }

    /// Возвращает текст с цветами. Разбор идёт по строкам: сначала комментарии,
    /// затем строки, затем числа и ключевые слова — чтобы цвета не перекрывались.
    static func highlight(_ code: String, language: String, theme: Theme = .dark, fontSize: Double) -> Text {
        let key = languageKey(language)
        guard !key.isEmpty, let words = keywords[key] else {
            return Text(code).foregroundColor(theme.plain)
        }
        var output = Text("")
        for (index, rawLine) in code.components(separatedBy: "\n").enumerated() {
            if index > 0 { output = output + Text("\n") }
            output = output + highlightLine(rawLine, language: key, words: words, theme: theme)
        }
        return output
    }

    private static func highlightLine(_ line: String, language: String, words: Set<String>, theme: Theme) -> Text {
        let commentTokens: [String]
        switch language {
        case "python", "bash": commentTokens = ["#"]
        case "sql": commentTokens = ["--"]
        default: commentTokens = ["//", "/*", "*"]
        }

        // Строка целиком является комментарием
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if commentTokens.contains(where: { trimmed.hasPrefix($0) }) {
            return Text(line).foregroundColor(theme.comment).italic()
        }

        var result = Text("")
        var current = ""
        var index = line.startIndex
        var inString: Character? = nil
        var inComment = false
        let characters = Array(line)

        func flushPlain() {
            guard !current.isEmpty else { return }
            var part = Text("")
            for (offset, piece) in tokenizePlain(current, words: words).enumerated() {
                if offset > 0 { part = part + Text(" ") }
                if words.contains(piece) {
                    part = part + Text(piece).foregroundColor(theme.keyword).bold()
                } else if piece.first?.isNumber == true {
                    part = part + Text(piece).foregroundColor(theme.number)
                } else if piece.first?.isUppercase == true && piece.count > 1 {
                    part = part + Text(piece).foregroundColor(theme.type)
                } else {
                    part = part + Text(piece).foregroundColor(theme.plain)
                }
            }
            result = result + part
            current = ""
        }

        while index < characters.count {
            let character = characters[index]

            if inComment {
                current.append(character)
                index += 1
                continue
            }

            if let quote = inString {
                current.append(character)
                if character == quote && (index == 0 || characters[index - 1] != "\\") {
                    flushString(&result, &current, theme: theme)
                    inString = nil
                }
                index += 1
                continue
            }

            // Начало строки
            if character == "\"" || character == "'" || (character == "`" && language == "javascript") {
                flushPlain()
                inString = character
                current.append(character)
                index += 1
                continue
            }

            // Начало комментария
            if character == "#" && (language == "python" || language == "bash") {
                flushPlain()
                result = result + Text(String(characters[index...])).foregroundColor(theme.comment).italic()
                return result
            }
            if character == "/" && index + 1 < characters.count && characters[index + 1] == "/" {
                flushPlain()
                result = result + Text(String(characters[index...])).foregroundColor(theme.comment).italic()
                return result
            }

            current.append(character)
            index += 1
        }

        if inString != nil {
            flushString(&result, &current, theme: theme)
        } else {
            flushPlain()
        }
        return result
    }

    private static func flushString(_ result: inout Text, _ buffer: inout String, theme: Theme) {
        guard !buffer.isEmpty else { return }
        result = result + Text(buffer).foregroundColor(theme.string)
        buffer = ""
    }

    /// Делит фрагмент без строк и комментариев на слова и разделители.
    private static func tokenizePlain(_ value: String, words: Set<String>) -> [String] {
        var tokens: [String] = []
        var current = ""
        let separators = CharacterSet(charactersIn: " \t(){}[]<>,;:+-*/%=!&|^~?.")
        for character in value {
            if let scalar = character.unicodeScalars.first, separators.contains(scalar) {
                if !current.isEmpty { tokens.append(current); current = "" }
                tokens.append(String(character))
            } else {
                current.append(character)
            }
        }
        if !current.isEmpty { tokens.append(current) }
        return tokens.filter { $0 != " " }
    }
}
