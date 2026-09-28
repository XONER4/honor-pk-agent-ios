package com.honerai.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Порт DocumentReaderTests.swift: ZIP, Word, Excel, PowerPoint, OpenDocument, EPUB, CSV, HTML, код, RTF. */
class DocumentReaderTest {

    private fun expectError(expected: DocumentReaderError, block: () -> Unit) {
        try {
            block()
            fail("Ожидалась ошибка $expected")
        } catch (e: DocumentReaderException) {
            assertEquals(expected, e.error)
        }
    }

    // MARK: ZIP

    @Test
    fun zipReaderReadsStoredAndDeflatedEntries() {
        val repeated = "abc ".repeat(300)
        val zip = TestZip.make(listOf(
            TestZipEntry("a.txt", "Привет".toByteArray()),
            TestZipEntry("dir/", ByteArray(0)),
            TestZipEntry("dir/b.txt", repeated.toByteArray(), deflate = true, localExtra = byteArrayOf(1, 2, 3, 4, 5, 6)),
        ))
        assertEquals(listOf("a.txt", "dir/b.txt"), ZipArchiveReader.names(zip))
        assertEquals("Привет", String(ZipArchiveReader.read("a.txt", zip)!!))
        assertEquals(repeated, String(ZipArchiveReader.read("dir\\B.TXT", zip)!!))
        assertNull(ZipArchiveReader.read("missing.txt", zip))
        val all = ZipArchiveReader.entries(zip)
        assertEquals(2, all.size)
        assertEquals(repeated, String(all["dir/b.txt"]!!))
    }

    @Test
    fun declaredOversizedEntryIsRejected() {
        val zip = TestZip.make(listOf(
            TestZipEntry("word/document.xml", ByteArray(1_000) { 0x41 }, deflate = true, declaredSize = 61 * 1024 * 1024),
        ))
        expectError(DocumentReaderError.ARCHIVE_TOO_LARGE) { ZipArchiveReader.read("word/document.xml", zip) }
        expectError(DocumentReaderError.ARCHIVE_TOO_LARGE) { DocumentReader.extractText(zip, "docx") }
    }

    @Test
    fun tooManyEntriesAreRejected() {
        val entries = (0..ZipArchiveReader.MAXIMUM_ENTRIES).map { TestZipEntry("f$it.txt", ByteArray(0)) }
        val zip = TestZip.make(entries)
        expectError(DocumentReaderError.ARCHIVE_TOO_LARGE) { ZipArchiveReader.names(zip) }
    }

    @Test
    fun corruptDataThrows() {
        expectError(DocumentReaderError.INVALID_ARCHIVE) { DocumentReader.extractText(ByteArray(200) { 0x78 }, "docx") }
        val valid = TestZip.make(listOf(TestZipEntry("word/document.xml", "<w:document/>".toByteArray())))
        val truncated = valid.copyOf(valid.size / 2)
        expectError(DocumentReaderError.INVALID_ARCHIVE) { DocumentReader.extractText(truncated, "xlsx") }
        val legacy = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(512)
        expectError(DocumentReaderError.LEGACY_OR_ENCRYPTED) { DocumentReader.extractText(legacy, "docx") }
        expectError(DocumentReaderError.UNSUPPORTED_FORMAT) { DocumentReader.extractText("x".toByteArray(), "exe") }
    }

    // MARK: Word

    @Test
    fun wordDocumentBecomesMarkdown() {
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Отчёт</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Первый </w:t></w:r><w:r><w:t>абзац</w:t></w:r><w:r><w:tab/><w:t>&amp; хвост</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Пункт</w:t></w:r></w:p>
<w:tbl>
<w:tr><w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>c</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>d</w:t></w:r></w:p></w:tc></w:tr>
</w:tbl>
<w:p><w:pPr><w:pStyle w:val="2"/></w:pPr><w:r><w:t>Итоги</w:t></w:r></w:p>
</w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:styleId="2"><w:name w:val="heading 2"/><w:basedOn w:val="a"/></w:style>
</w:styles>"""
        val zip = TestZip.make(listOf(
            TestZipEntry("word/document.xml", document.toByteArray(), deflate = true, localExtra = ByteArray(8)),
            TestZipEntry("word/styles.xml", styles.toByteArray()),
        ))
        val result = DocumentReader.extractText(zip, "DOCX")
        assertTrue(result.text, result.text.startsWith("# Отчёт"))
        assertTrue(result.text, result.text.contains("Первый абзац\t& хвост"))
        assertTrue(result.text, result.text.contains("- Пункт"))
        assertTrue(result.text, result.text.contains("| a | b |\n|---|---|\n| c | d |"))
        assertTrue(result.text, result.text.contains("## Итоги"))
        assertTrue(result.summary, result.summary.startsWith("Документ Word"))
        assertTrue(result.summary, result.summary.contains("1 таблица"))
    }

    // MARK: Excel

    @Test
    fun excelWorkbookBecomesMarkdownTables() {
        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
<sheet name="Продажи" sheetId="1" r:id="rId1"/><sheet name="Итоги" sheetId="2" r:id="rId2"/>
</sheets></workbook>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>"""
        val shared = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
<si><t>Имя</t></si><si><r><t>Hel</t></r><r><t>lo</t></r><rPh><t>фонетика</t></rPh></si>
</sst>"""
        val sheet1 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
<row r="2"><c r="A2" t="inlineStr"><is><t>Анна</t></is></c></row>
<row r="3"><c r="C3"><f>0.1+0.2</f><v>0.30000000000000004</v></c></row>
</sheetData></worksheet>"""
        val sheet2 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1"><v>42</v></c><c r="B1" t="b"><v>1</v></c></row>
</sheetData></worksheet>"""
        val zip = TestZip.make(listOf(
            TestZipEntry("xl/workbook.xml", workbook.toByteArray()),
            TestZipEntry("xl/_rels/workbook.xml.rels", rels.toByteArray()),
            TestZipEntry("xl/sharedStrings.xml", shared.toByteArray(), deflate = true),
            TestZipEntry("xl/worksheets/sheet1.xml", sheet1.toByteArray(), deflate = true),
            TestZipEntry("xl/worksheets/sheet2.xml", sheet2.toByteArray()),
        ))
        val result = DocumentReader.extractText(zip, "xlsx")
        assertTrue(result.text, result.text.contains("## Лист «Продажи»"))
        assertTrue(result.text, result.text.contains("| Имя | Hello |  |\n|---|---|---|\n| Анна |  |  |\n|  |  | 0.3 |"))
        assertTrue(result.text, result.text.contains("## Лист «Итоги»\n\n| 42 | TRUE |"))
        assertFalse(result.text, result.text.contains("фонетика"))
        assertTrue(result.text.indexOf("Продажи") < result.text.indexOf("Итоги"))
        assertEquals("Таблица Excel: 2 листа, 4 строки", result.summary)
    }

    @Test
    fun excelHelpers() {
        assertEquals(0, DocumentReader.columnIndex("A1"))
        assertEquals(25, DocumentReader.columnIndex("Z9"))
        assertEquals(26, DocumentReader.columnIndex("AA3"))
        assertEquals(1, DocumentReader.columnIndex("\$B\$12"))
        assertEquals("0.3", DocumentReader.formatNumber("0.30000000000000004"))
        assertEquals("12345", DocumentReader.formatNumber("12345"))
        assertEquals("2024-05-01", DocumentReader.excelDate(45413.0, false))
        assertEquals("12:00", DocumentReader.excelDate(0.5, false))
        assertEquals("2024-05-01 13:30", DocumentReader.excelDate(45413.5625, false))
        assertTrue(DocumentReader.isDateFormat(14, null))
        assertTrue(DocumentReader.isDateFormat(170, "[\$-419]d mmmm yyyy"))
        assertFalse(DocumentReader.isDateFormat(171, "#,##0.00\" дн.\""))
    }

    // MARK: PowerPoint

    @Test
    fun powerPointSlidesFollowNumericOrderWithNotes() {
        fun slide(text: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
        val slideRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide" Target="../notesSlides/notesSlide7.xml"/>
</Relationships>"""
        val notes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree>
<p:sp><p:txBody><a:p><a:r><a:t>Говорить медленно</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:txBody><a:p><a:fld id="{1}" type="slidenum"><a:t>2</a:t></a:fld></a:p></p:txBody></p:sp>
</p:spTree></p:cSld></p:notes>"""
        val zip = TestZip.make(listOf(
            TestZipEntry("ppt/slides/slide10.xml", slide("Десятый").toByteArray(), deflate = true),
            TestZipEntry("ppt/slides/slide2.xml", slide("Второй").toByteArray()),
            TestZipEntry("ppt/slides/_rels/slide2.xml.rels", slideRels.toByteArray()),
            TestZipEntry("ppt/notesSlides/notesSlide7.xml", notes.toByteArray()),
        ))
        val result = DocumentReader.extractText(zip, "pptx")
        assertTrue(result.text, result.text.contains("## Слайд 1\nВторой"))
        assertTrue(result.text, result.text.contains("## Слайд 2\nДесятый"))
        assertTrue(result.text, result.text.contains("Заметки: Говорить медленно"))
        assertFalse(result.text, result.text.contains("медленно\n2"))
        assertEquals("Презентация PowerPoint: 2 слайда", result.summary)
    }

    // MARK: OpenDocument / EPUB

    @Test
    fun openDocumentSpreadsheetRespectsRepeatedCells() {
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:spreadsheet>
<table:table table:name="Бюджет">
<table:table-row><table:table-cell><text:p>Статья</text:p></table:table-cell><table:table-cell><text:p>Сумма</text:p></table:table-cell></table:table-row>
<table:table-row><table:table-cell><text:p>Еда</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="100"><text:p>100</text:p></table:table-cell><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
<table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
</table:table></office:spreadsheet></office:body></office:document-content>"""
        val zip = TestZip.make(listOf(
            TestZipEntry("mimetype", "application/vnd.oasis.opendocument.spreadsheet".toByteArray()),
            TestZipEntry("content.xml", content.toByteArray(), deflate = true),
        ))
        val result = DocumentReader.extractText(zip, "ods")
        assertTrue(result.text, result.text.contains("## Лист «Бюджет»\n\n| Статья | Сумма |\n|---|---|\n| Еда | 100 |"))
        assertEquals("Таблица OpenDocument: 1 лист, 2 строки", result.summary)
    }

    @Test
    fun epubFollowsSpineOrder() {
        val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val pkg = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Сказка</dc:title></metadata>
<manifest><item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>"""
        fun chapter(text: String) =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title><script src=\"a.js\"/></head><body><p>$text</p></body></html>"
        val zip = TestZip.make(listOf(
            TestZipEntry("mimetype", "application/epub+zip".toByteArray()),
            TestZipEntry("META-INF/container.xml", container.toByteArray()),
            TestZipEntry("OEBPS/content.opf", pkg.toByteArray()),
            TestZipEntry("OEBPS/text/ch1.xhtml", chapter("Первая глава").toByteArray(), deflate = true),
            TestZipEntry("OEBPS/text/ch 2.xhtml", chapter("Вторая глава").toByteArray()),
        ))
        val result = DocumentReader.extractText(zip, "epub")
        assertTrue(result.text, result.text.startsWith("# Сказка"))
        val second = result.text.indexOf("Вторая глава")
        val first = result.text.indexOf("Первая глава")
        assertTrue(second >= 0 && first >= 0 && second < first)
        assertEquals("Книга EPUB: 2 главы", result.summary)
    }

    // MARK: Текстовые форматы

    @Test
    fun semicolonCsvWithQuotedNewline() {
        val csv = "Имя;Комментарий\nАня;\"строка1\nстрока2\"\nБорис;\"с \"\"кавычками\"\" | чертой\"\n"
        val result = DocumentReader.extractText(csv.toByteArray(), "csv")
        assertTrue(result.text, result.text.startsWith("| Имя | Комментарий |\n|---|---|"))
        assertTrue(result.text, result.text.contains("| Аня | строка1<br>строка2 |"))
        assertTrue(result.text, result.text.contains("| Борис | с \"кавычками\" \\| чертой |"))
        assertEquals("Таблица CSV: 3 строки, 2 столбца", result.summary)
    }

    @Test
    fun tsvAndCommaDetection() {
        assertEquals('\t', DocumentReader.detectDelimiter("a\tb\tc\n1,2"))
        assertEquals(',', DocumentReader.detectDelimiter("a,b;c,d\n"))
        val result = DocumentReader.extractText("x\ty\n1\t2\n".toByteArray(), "tsv")
        assertTrue(result.text, result.text.startsWith("| x | y |\n|---|---|\n| 1 | 2 |"))
        assertEquals("Таблица TSV: 2 строки, 2 столбца", result.summary)
    }

    @Test
    fun htmlStripsScriptsAndDecodesEntities() {
        val html = """<!DOCTYPE html><html><head><title>Страница</title><style>p { color: red; }</style>
<script>if (a < b) { alert('x'); }</script></head><body><h1>Заголовок</h1>
<p>Tom &amp; Jerry &lt;3 &#x1F600; &#169;&nbsp;ok<br>вторая   строка</p>
<ul><li>один</li><li>два</li></ul><!-- секрет --><noscript>без JS</noscript></body></html>"""
        val result = DocumentReader.extractText(html.toByteArray(), "html")
        assertTrue(result.text, result.text.contains("# Заголовок"))
        assertTrue(result.text, result.text.contains("Tom & Jerry <3 😀 © ok\nвторая строка"))
        assertTrue(result.text, result.text.contains("- один\n- два"))
        for (hidden in listOf("alert", "color", "секрет", "без JS", "<p>")) assertFalse(hidden, result.text.contains(hidden))
        assertTrue(result.summary.startsWith("Веб-страница"))
    }

    @Test
    fun codeFileIsFenced() {
        val code = "def main():\n    print('hi')\n"
        val result = DocumentReader.extractText(code.toByteArray(), "py")
        assertEquals("```python\ndef main():\n    print('hi')\n```", result.text)
        assertEquals("Код Python: 2 строки", result.summary)
        assertEquals("Код", DocumentReader.kind("swift"))
        assertEquals("Таблица Excel", DocumentReader.kind("xlsx"))
        val withFence = DocumentReader.extractText("val a = \"```\"\n".toByteArray(), "kt")
        assertTrue(withFence.text, withFence.text.startsWith("````kotlin\n"))
    }

    @Test
    fun legacyEncodingsAndNotebook() {
        val cp1251 = "Привет, мир".toByteArray(charset("windows-1251"))
        assertEquals("Привет, мир", DocumentReader.extractText(cp1251, "txt").text)
        val utf16 = "Hello, world".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hello, world", DocumentReader.decodeText(utf16))

        val notebook = """{"cells":[{"cell_type":"markdown","source":["# Анализ\n","Описание"]},{"cell_type":"code","source":"print(1)","outputs":[{"output_type":"stream","text":["1\n"]}]}],"metadata":{"kernelspec":{"language":"python"}},"nbformat":4}"""
            .replace("\n", "\\n")
        val result = DocumentReader.extractText(notebook.toByteArray(), "ipynb")
        assertTrue(result.text, result.text.startsWith("# Анализ\nОписание"))
        assertTrue(result.text, result.text.contains("```python\nprint(1)\n```\nВывод:\n```\n1\n```"))
        assertEquals("Блокнот Jupyter: 2 ячейки", result.summary)
    }

    @Test
    fun rtfIsReadable() {
        val rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Helvetica;}}\\f0 Hello \\b World\\b0\\par}"
        val result = DocumentReader.extractText(rtf.toByteArray(), "rtf")
        assertTrue(result.text, result.text.contains("Hello World"))
        assertFalse(result.text, result.text.contains("Helvetica"))

        val russian = "{\\rtf1\\ansi\\ansicpg1251 {\\*\\generator Test;}\\'cf\\'f0\\'e8\\'e2\\'e5\\'f2 \\u1084?\\u1080?\\u1088?\\par}"
        val decoded = DocumentReader.extractText(russian.toByteArray(), "rtf")
        assertTrue(decoded.text, decoded.text.contains("Привет мир"))
        assertTrue(decoded.summary, decoded.summary.startsWith("Документ RTF: 2 слова"))
    }

    @Test
    fun longTextIsTruncatedWithNote() {
        val text = "Строка текста\n\n\n\n".repeat(20_000)
        val result = DocumentReader.extractText(text.toByteArray(), "txt")
        assertTrue(result.text.length <= DocumentReader.MAXIMUM_CHARACTERS)
        assertTrue(result.text.endsWith("(текст обрезан: документ длиннее 160 000 символов)"))
        assertFalse(result.text.contains("\n\n\n"))
    }

    @Test
    fun helpers() {
        assertEquals("1 слово", DocumentReader.plural(1, "слово", "слова", "слов"))
        assertEquals("3 слова", DocumentReader.plural(3, "слово", "слова", "слов"))
        assertEquals("11 слов", DocumentReader.plural(11, "слово", "слова", "слов"))
        assertEquals("22 слова", DocumentReader.plural(22, "слово", "слова", "слов"))
        assertEquals(3, DocumentReader.wordCount("Привет, мир! 42"))
        assertEquals(12, DocumentReader.trailingNumber("ppt/slides/slide12.xml"))
        assertEquals("xl/_rels/workbook.xml.rels", DocumentReader.relationshipsPath("xl/workbook.xml"))
        assertEquals("_rels/.rels", DocumentReader.relationshipsPath(""))
        assertEquals("xl/worksheets/sheet1.xml", DocumentReader.resolve("worksheets/sheet1.xml", "xl/workbook.xml"))
        assertEquals("ppt/notesSlides/n 1.xml", DocumentReader.resolve("../notesSlides/n%201.xml", "ppt/slides/slide1.xml"))
        assertEquals(2, DocumentReader.headingLevel("Heading 2"))
        assertEquals(3, DocumentReader.headingLevel("Заголовок 3"))
        assertNull(DocumentReader.headingLevel("Normal"))
        assertEquals("a\n\nb", DocumentReader.normalize("  \r\na\r\n\r\n\r\n  \nb\n"))
        assertNotNull(DocumentReader.decodeEntity("#x1F600"))
        assertNull(DocumentReader.decodeEntity("nonsense"))
    }
}

// MARK: - Тестовый ZIP-писатель (stored + deflate)

internal class TestZipEntry(
    val name: String,
    val content: ByteArray,
    val deflate: Boolean = false,
    val localExtra: ByteArray = ByteArray(0),
    val declaredSize: Int? = null,
)

internal object TestZip {
    fun make(entries: List<TestZipEntry>): ByteArray {
        val body = ByteArrayOutputStream()
        val directory = ByteArrayOutputStream()
        for (entry in entries) {
            val name = entry.name.toByteArray()
            val payload = if (entry.deflate) deflate(entry.content) else entry.content
            val method = if (entry.deflate) 8 else 0
            val checksum = CRC32().apply { update(entry.content) }.value
            val size = (entry.declaredSize ?: entry.content.size).toLong()
            val offset = body.size().toLong()

            body.le32(0x0403_4b50); body.le16(20); body.le16(0x0800); body.le16(method); body.le16(0); body.le16(0x21)
            body.le32(checksum); body.le32(payload.size.toLong()); body.le32(size)
            body.le16(name.size); body.le16(entry.localExtra.size)
            body.write(name); body.write(entry.localExtra); body.write(payload)

            directory.le32(0x0201_4b50); directory.le16(20); directory.le16(20); directory.le16(0x0800); directory.le16(method)
            directory.le16(0); directory.le16(0x21); directory.le32(checksum); directory.le32(payload.size.toLong()); directory.le32(size)
            directory.le16(name.size); directory.le16(0); directory.le16(0); directory.le16(0); directory.le16(0)
            directory.le32(0); directory.le32(offset); directory.write(name)
        }
        val directoryOffset = body.size().toLong()
        val directoryBytes = directory.toByteArray()
        body.write(directoryBytes)
        body.le32(0x0605_4b50); body.le16(0); body.le16(0); body.le16(entries.size and 0xFFFF); body.le16(entries.size and 0xFFFF)
        body.le32(directoryBytes.size.toLong()); body.le32(directoryOffset); body.le16(0)
        return body.toByteArray()
    }

    /** Сырой DEFLATE без заголовка zlib. */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(value: Long) {
        write((value and 0xFF).toInt()); write(((value shr 8) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt()); write(((value shr 24) and 0xFF).toInt())
    }
}
