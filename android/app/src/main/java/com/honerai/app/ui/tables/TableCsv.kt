package com.honerai.app.ui.tables

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.honerai.app.data.ChatTable
import java.io.File
import java.text.Collator
import java.time.Instant
import java.util.Locale

/**
 * Помощники экрана таблиц: выравнивание строк, Markdown, CSV, сортировка.
 * Движок чата держит свою копию правил (core/TableEditing); здесь — то, что нужно
 * интерфейсу, в том же формате, что на iPhone (TableEditing.markdown / csv / sort).
 */
object TableCsv {
    /** Строка ровно на [width] ячеек: лишние отрезаются, недостающие — пустые. */
    fun normalized(row: List<String>, width: Int): List<String> = when {
        row.size == width -> row
        row.size > width -> row.subList(0, width)
        else -> row + List(width - row.size) { "" }
    }

    private fun csvField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == ';' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /** CSV без BOM (строки через \n, поля с запятой, кавычкой, «;» или переводом строки — в кавычках). */
    fun csv(columns: List<String>, rows: List<List<String>>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines.add(columns.joinToString(",") { csvField(it) })
        for (row in rows) lines.add(normalized(row, columns.size).joinToString(",") { csvField(it) })
        return lines.joinToString("\n")
    }

    fun csv(table: ChatTable): String = csv(table.columns, table.rows)

    /** CSV с BOM: без него Excel открывает кириллицу крякозябрами. */
    fun csvWithBom(table: ChatTable): String = "\uFEFF" + csv(table)

    /** Таблица текстом Markdown со столбцом «№» (как TableEditing.markdown на iOS). */
    fun markdown(table: ChatTable, limitRows: Int = 300): String {
        fun clean(value: String) = value.replace("|", "\\|").replace("\n", " ")
        val lines = ArrayList<String>()
        lines.add("| № | " + table.columns.joinToString(" | ") { clean(it) } + " |")
        lines.add("|---|" + table.columns.joinToString("|") { "---" } + "|")
        table.rows.take(limitRows).forEachIndexed { index, row ->
            lines.add("| ${index + 1} | " + normalized(row, table.columns.size).joinToString(" | ") { clean(it) } + " |")
        }
        if (table.rows.size > limitRows) lines.add("… ещё ${table.rows.size - limitRows} строк")
        return lines.joinToString("\n")
    }

    /** Число из ячейки: пробелы-разделители тысяч, валюта и процент убираются, запятая — десятичная. */
    fun numericValue(raw: String): Double? {
        val cleaned = raw.replace(" ", "").replace("\u00A0", "").replace("\u202F", "").replace(",", ".")
            .replace("₽", "").replace("$", "").replace("€", "").replace("%", "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()?.takeIf { !it.isNaN() }
    }

    private val collator: Collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }

    /** Сравнение «как в Finder»: числа внутри строк сравниваются как числа, регистр не важен. */
    fun naturalCompare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]
            if (a.isDigit() && b.isDigit()) {
                var endA = i
                while (endA < left.length && left[endA].isDigit()) endA++
                var endB = j
                while (endB < right.length && right[endB].isDigit()) endB++
                val numberA = left.substring(i, endA).trimStart('0')
                val numberB = right.substring(j, endB).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                val order = numberA.compareTo(numberB)
                if (order != 0) return order
                i = endA
                j = endB
                continue
            }
            var endA = i
            while (endA < left.length && !left[endA].isDigit()) endA++
            var endB = j
            while (endB < right.length && !right[endB].isDigit()) endB++
            val order = collator.compare(left.substring(i, endA), right.substring(j, endB))
            if (order != 0) return order
            i = endA
            j = endB
        }
        return (left.length - i) - (right.length - j)
    }

    /** Сравнение ячеек для сортировки: числа — как числа, остальное — «естественно». */
    fun compareCells(left: String, right: String): Int {
        val a = numericValue(left)
        val b = numericValue(right)
        if (a != null && b != null) return a.compareTo(b)
        return naturalCompare(left, right)
    }

    /** Таблица, отсортированная по столбцу (как действие sort в TableEditing на iOS). */
    fun sorted(table: ChatTable, column: Int, descending: Boolean): ChatTable {
        val comparator = Comparator<List<String>> { lhs, rhs ->
            compareCells(lhs.getOrElse(column) { "" }, rhs.getOrElse(column) { "" })
        }
        val rows = table.rows.sortedWith(if (descending) comparator.reversed() else comparator)
        return table.copy(rows = rows, updatedAt = Instant.now())
    }

    /** Имя файла без запрещённых символов. */
    fun fileName(title: String, extension: String): String {
        val base = title.replace(Regex("""[/\\:*?"<>|\n\r\t]"""), "-").trim().take(80).ifEmpty { "Honer-таблица" }
        return "$base.$extension"
    }

    /**
     * Файл во временной папке и системный лист «Поделиться» (FileProvider «${'$'}{applicationId}.files»).
     * Возвращает false, если файл записать не удалось.
     */
    fun shareFile(context: Context, fileName: String, content: String, mime: String, chooserTitle: String): Boolean {
        return try {
            val folder = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(folder, fileName)
            file.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
