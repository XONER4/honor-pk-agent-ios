package com.honerai.app.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Выполнение кода прямо в приложении — кнопка «Запустить» в блоке кода (порт CodeRunner).
 * На iPhone JavaScript исполняет JavaScriptCore; здесь — движок WebView без страницы
 * и без сети. Для других языков интерпретатора нет: честно выводится пояснение.
 */
object CodeRunner {
    data class Result(val output: String, val isError: Boolean)

    fun canRun(language: String): Boolean {
        val value = language.lowercase()
        return value.startsWith("js") || value.startsWith("javascript") || value.startsWith("ts") ||
            value.startsWith("node") || value.startsWith("json")
    }

    /** Код оборачивается так, чтобы вернуть вывод console.log, результат или ошибку одной строкой JSON. */
    internal fun script(code: String): String = """
        (function(){
          var __logs = [];
          function __fmt(v){
            if (v === undefined) return 'undefined';
            if (v === null) return 'null';
            if (typeof v === 'string') return v;
            if (typeof v === 'number' || typeof v === 'boolean') return String(v);
            if (Array.isArray(v)) return '[' + v.map(__fmt).join(', ') + ']';
            if (typeof v === 'function') return String(v);
            try { return JSON.stringify(v, Object.keys(v).sort(), 2); } catch (e) { return String(v); }
          }
          function __join(args, prefix){ var p = []; for (var i = 0; i < args.length; i++) p.push(__fmt(args[i])); __logs.push(prefix + p.join(' ')); }
          var console = {
            log: function(){ __join(arguments, ''); },
            info: function(){ __join(arguments, ''); },
            error: function(){ __join(arguments, '✗ '); },
            warn: function(){ __join(arguments, '⚠ '); }
          };
          try {
            var __r = eval(${JsonPrimitive(code)});
            var __has = !(__r === undefined || __r === null);
            return JSON.stringify({ logs: __logs, result: __has ? __fmt(__r) : null, error: null });
          } catch (e) {
            return JSON.stringify({ logs: __logs, result: null, error: String(e) });
          }
        })()
    """.trimIndent()

    suspend fun run(context: Context, code: String, language: String, english: Boolean): Result {
        if (!canRun(language)) {
            val name = language.ifEmpty { if (english) "this language" else "этот язык" }
            return Result(
                if (english) "Running $name in the app isn't available: Android has no interpreter for it.\nThe Run button works for JavaScript."
                else "Запуск $name в приложении недоступен: в Android нет интерпретатора для этого языка.\nКнопка «Запустить» работает для JavaScript.",
                isError = false,
            )
        }
        if (code.length >= 200_000) {
            return Result(if (english) "The fragment is too large to run." else "Слишком большой фрагмент для запуска.", true)
        }
        var view: WebView? = null
        val raw = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { continuation ->
                val web = createWebView(context)
                view = web
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(page: WebView?, url: String?) {
                        web.evaluateJavascript(script(code)) { value ->
                            if (continuation.isActive) continuation.resume(value)
                        }
                    }
                }
                web.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
            }
        }
        view?.destroy()
        if (raw == null) {
            return Result(
                if (english) "The code ran too long and was stopped." else "Код выполнялся слишком долго и был остановлен.",
                isError = true,
            )
        }
        return interpret(raw, english)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
    }

    /** Ответ evaluateJavascript — строка JSON внутри строки JSON. */
    internal fun interpret(raw: String, english: Boolean): Result {
        return try {
            val inner = Json.parseToJsonElement(raw).jsonPrimitive.content
            val obj: JsonObject = Json.parseToJsonElement(inner).jsonObject
            val logs = obj["logs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val error = obj["error"]?.jsonPrimitive?.contentOrNull
            val result = obj["result"]?.jsonPrimitive?.contentOrNull
            when {
                error != null -> Result((if (english) "Error: " else "Ошибка: ") + error, true)
                logs.isNotEmpty() -> Result(logs.joinToString("\n"), false)
                result != null -> Result(result, false)
                else -> Result(if (english) "The code ran without output." else "Код выполнен без вывода.", false)
            }
        } catch (_: Exception) {
            Result(if (english) "Couldn't run the code." else "Не удалось выполнить код.", true)
        }
    }
}
