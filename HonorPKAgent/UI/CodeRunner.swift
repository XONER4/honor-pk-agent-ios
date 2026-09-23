import Foundation
import JavaScriptCore

/// Выполнение кода прямо в приложении — для кнопки «Запустить» в блоке кода.
///
/// JavaScript исполняется настоящим движком JavaScriptCore (он встроен в iOS).
/// Для Python и других языков интерпретатора в системе нет, поэтому там честно
/// выводится пояснение, а не выдуманный результат.
enum CodeRunner {
    struct Result {
        let output: String
        let isError: Bool
    }

    static func canRun(_ language: String) -> Bool {
        let value = language.lowercased()
        return value.hasPrefix("js") || value.hasPrefix("javascript") || value.hasPrefix("ts")
            || value.hasPrefix("node") || value.hasPrefix("json")
    }

    /// Запускает JavaScript и возвращает всё, что код вывел через console.log,
    /// либо последнее вычисленное выражение.
    static func runJavaScript(_ code: String) -> Result {
        guard let context = JSContext() else {
            return Result(output: "Не удалось создать движок JavaScript.", isError: true)
        }

        var lines: [String] = []

        let log: @convention(block) (JSValue) -> Void = { value in
            lines.append(describe(value))
        }
        context.setObject(log, forKeyedSubscript: "nativeLog" as NSString)

        // console.log / console.error
        context.evaluateScript("""
        var console = {
            log: function() {
                var parts = [];
                for (var i = 0; i < arguments.length; i++) { parts.push(arguments[i]); }
                nativeLog(parts.join(' '));
            },
            error: function() {
                var parts = [];
                for (var i = 0; i < arguments.length; i++) { parts.push(arguments[i]); }
                nativeLog('✗ ' + parts.join(' '));
            },
            warn: function() {
                var parts = [];
                for (var i = 0; i < arguments.length; i++) { parts.push(arguments[i]); }
                nativeLog('⚠ ' + parts.join(' '));
            }
        };
        """)

        var errorText: String?
        context.exceptionHandler = { _, exception in
            errorText = exception?.toString() ?? "Неизвестная ошибка"
        }

        // Ограничение по времени: JavaScriptCore исполняет код синхронно,
        // поэтому защищаемся лимитом размера и понятным сообщением об ошибке.
        let started = Date()
        let result = context.evaluateScript(code)

        if let errorText {
            return Result(output: "Ошибка: \(errorText)", isError: true)
        }
        if Date().timeIntervalSince(started) > 3 {
            return Result(output: "Код выполнялся слишком долго и был остановлен.", isError: true)
        }

        if !lines.isEmpty {
            return Result(output: lines.joined(separator: "\n"), isError: false)
        }
        if let result, !result.isUndefined, !result.isNull {
            return Result(output: describe(result), isError: false)
        }
        return Result(output: "Код выполнен без вывода.", isError: false)
    }

    /// Ограничение по длине: не даём коду-бесконечности забить память.
    static func run(_ code: String, language: String) -> Result {
        guard canRun(language) else {
            let name = language.isEmpty ? "этот язык" : language
            return Result(output: """
            Запуск \(name) в приложении недоступен: в iOS нет интерпретатора для этого языка.
            Кнопка «Запустить» работает для JavaScript — движок JavaScriptCore встроен в систему.
            """, isError: false)
        }
        guard code.count < 200_000 else {
            return Result(output: "Слишком большой фрагмент для запуска.", isError: true)
        }
        return runJavaScript(code)
    }

    private static func describe(_ value: JSValue) -> String {
        if value.isUndefined { return "undefined" }
        if value.isNull { return "null" }
        if value.isBoolean { return value.toBool() ? "true" : "false" }
        if value.isNumber {
            let number = value.toDouble()
            if number == number.rounded() && abs(number) < 1e15 {
                return String(Int(number))
            }
            return String(number)
        }
        if value.isString { return value.toString() ?? "" }
        if value.isArray {
            let items = (0..<value.forProperty("length")?.toInt() ?? 0).map { index -> String in
                guard let item = value.atIndex(index) else { return "undefined" }
                return describe(item)
            }
            return "[" + items.joined(separator: ", ") + "]"
        }
        if value.isObject {
            if let json = try? JSONSerialization.data(withJSONObject: value.toObject() as Any,
                                                      options: [.prettyPrinted, .sortedKeys]),
               let text = String(data: json, encoding: .utf8) {
                return text
            }
            return value.toString() ?? "object"
        }
        return value.toString() ?? ""
    }
}
