import Compression
import XCTest
@testable import HonorPKAgent

final class DocumentReaderTests: XCTestCase {
    // MARK: ZIP

    func testZipReaderReadsStoredAndDeflatedEntries() throws {
        let repeated = String(repeating: "abc ", count: 300)
        let zip = try TestZip.make([
            TestZipEntry(name: "a.txt", content: Data("Привет".utf8)),
            TestZipEntry(name: "dir/", content: Data()),
            TestZipEntry(name: "dir/b.txt", content: Data(repeated.utf8), deflate: true, localExtra: Data([1, 2, 3, 4, 5, 6]))
        ])

        XCTAssertEqual(try ZipArchiveReader.names(in: zip), ["a.txt", "dir/b.txt"])
        XCTAssertEqual(try ZipArchiveReader.read("a.txt", from: zip), Data("Привет".utf8))
        XCTAssertEqual(try ZipArchiveReader.read("dir\\B.TXT", from: zip), Data(repeated.utf8))
        XCTAssertNil(try ZipArchiveReader.read("missing.txt", from: zip))
        let all = try ZipArchiveReader.entries(in: zip)
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(all["dir/b.txt"].map { String(decoding: $0, as: UTF8.self) }, repeated)
    }

    func testDeclaredOversizedEntryIsRejected() throws {
        let zip = try TestZip.make([
            TestZipEntry(name: "word/document.xml", content: Data(repeating: 0x41, count: 1_000),
                         deflate: true, declaredSize: 61 * 1024 * 1024)
        ])
        XCTAssertThrowsError(try ZipArchiveReader.read("word/document.xml", from: zip)) { error in
            XCTAssertEqual(error as? DocumentReaderError, .archiveTooLarge)
        }
        XCTAssertThrowsError(try DocumentReader.extractText(from: zip, fileExtension: "docx")) { error in
            XCTAssertEqual(error as? DocumentReaderError, .archiveTooLarge)
        }
    }

    func testTooManyEntriesAreRejected() throws {
        let entries = (0...ZipArchiveReader.maximumEntries).map { TestZipEntry(name: "f\($0).txt", content: Data()) }
        let zip = try TestZip.make(entries)
        XCTAssertThrowsError(try ZipArchiveReader.names(in: zip)) { error in
            XCTAssertEqual(error as? DocumentReaderError, .archiveTooLarge)
        }
    }

    func testCorruptDataThrows() throws {
        XCTAssertThrowsError(try DocumentReader.extractText(from: Data(repeating: 0x78, count: 200), fileExtension: "docx")) { error in
            XCTAssertEqual(error as? DocumentReaderError, .invalidArchive)
        }
        let valid = try TestZip.make([TestZipEntry(name: "word/document.xml", content: Data("<w:document/>".utf8))])
        let truncated = valid.prefix(valid.count / 2)
        XCTAssertThrowsError(try DocumentReader.extractText(from: truncated, fileExtension: "xlsx")) { error in
            XCTAssertEqual(error as? DocumentReaderError, .invalidArchive)
        }
        var legacy = Data([0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1])
        legacy.append(Data(repeating: 0, count: 512))
        XCTAssertThrowsError(try DocumentReader.extractText(from: legacy, fileExtension: "docx")) { error in
            XCTAssertEqual(error as? DocumentReaderError, .legacyOrEncrypted)
        }
        XCTAssertThrowsError(try DocumentReader.extractText(from: Data("x".utf8), fileExtension: "exe")) { error in
            XCTAssertEqual(error as? DocumentReaderError, .unsupportedFormat)
        }
    }

    // MARK: Word

    func testWordDocumentBecomesMarkdown() throws {
        let document = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
        <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Отчёт</w:t></w:r></w:p>
        <w:p><w:r><w:t xml:space="preserve">Первый </w:t></w:r><w:r><w:t>абзац</w:t></w:r><w:r><w:tab/><w:t>&amp; хвост</w:t></w:r></w:p>
        <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Пункт</w:t></w:r></w:p>
        <w:tbl>
        <w:tr><w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:p><w:r><w:t>c</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>d</w:t></w:r></w:p></w:tc></w:tr>
        </w:tbl>
        <w:p><w:pPr><w:pStyle w:val="2"/></w:pPr><w:r><w:t>Итоги</w:t></w:r></w:p>
        </w:body></w:document>
        """
        let styles = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:style w:type="paragraph" w:styleId="2"><w:name w:val="heading 2"/><w:basedOn w:val="a"/></w:style>
        </w:styles>
        """
        let zip = try TestZip.make([
            TestZipEntry(name: "word/document.xml", content: Data(document.utf8), deflate: true, localExtra: Data(repeating: 0, count: 8)),
            TestZipEntry(name: "word/styles.xml", content: Data(styles.utf8))
        ])

        let result = try DocumentReader.extractText(from: zip, fileExtension: "DOCX")
        XCTAssertTrue(result.text.hasPrefix("# Отчёт"), result.text)
        XCTAssertTrue(result.text.contains("Первый абзац\t& хвост"), result.text)
        XCTAssertTrue(result.text.contains("- Пункт"), result.text)
        XCTAssertTrue(result.text.contains("| a | b |\n|---|---|\n| c | d |"), result.text)
        XCTAssertTrue(result.text.contains("## Итоги"), result.text)
        XCTAssertTrue(result.summary.hasPrefix("Документ Word"), result.summary)
        XCTAssertTrue(result.summary.contains("1 таблица"), result.summary)
    }

    // MARK: Excel

    func testExcelWorkbookBecomesMarkdownTables() throws {
        let workbook = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
        <sheet name="Продажи" sheetId="1" r:id="rId1"/><sheet name="Итоги" sheetId="2" r:id="rId2"/>
        </sheets></workbook>
        """
        let rels = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet2.xml"/>
        <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
        </Relationships>
        """
        let shared = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
        <si><t>Имя</t></si><si><r><t>Hel</t></r><r><t>lo</t></r><rPh><t>фонетика</t></rPh></si>
        </sst>
        """
        let sheet1 = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>Анна</t></is></c></row>
        <row r="3"><c r="C3"><f>0.1+0.2</f><v>0.30000000000000004</v></c></row>
        </sheetData></worksheet>
        """
        let sheet2 = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        <row r="1"><c r="A1"><v>42</v></c><c r="B1" t="b"><v>1</v></c></row>
        </sheetData></worksheet>
        """
        let zip = try TestZip.make([
            TestZipEntry(name: "xl/workbook.xml", content: Data(workbook.utf8)),
            TestZipEntry(name: "xl/_rels/workbook.xml.rels", content: Data(rels.utf8)),
            TestZipEntry(name: "xl/sharedStrings.xml", content: Data(shared.utf8), deflate: true),
            TestZipEntry(name: "xl/worksheets/sheet1.xml", content: Data(sheet1.utf8), deflate: true),
            TestZipEntry(name: "xl/worksheets/sheet2.xml", content: Data(sheet2.utf8))
        ])

        let result = try DocumentReader.extractText(from: zip, fileExtension: "xlsx")
        XCTAssertTrue(result.text.contains("## Лист «Продажи»"), result.text)
        XCTAssertTrue(result.text.contains("| Имя | Hello |  |\n|---|---|---|\n| Анна |  |  |\n|  |  | 0.3 |"), result.text)
        XCTAssertTrue(result.text.contains("## Лист «Итоги»\n\n| 42 | TRUE |"), result.text)
        XCTAssertFalse(result.text.contains("фонетика"), result.text)
        let first = try XCTUnwrap(result.text.range(of: "Продажи"))
        let second = try XCTUnwrap(result.text.range(of: "Итоги"))
        XCTAssertTrue(first.lowerBound < second.lowerBound)
        XCTAssertEqual(result.summary, "Таблица Excel: 2 листа, 4 строки")
    }

    func testExcelHelpers() {
        XCTAssertEqual(DocumentReader.columnIndex(fromReference: "A1"), 0)
        XCTAssertEqual(DocumentReader.columnIndex(fromReference: "Z9"), 25)
        XCTAssertEqual(DocumentReader.columnIndex(fromReference: "AA3"), 26)
        XCTAssertEqual(DocumentReader.columnIndex(fromReference: "$B$12"), 1)
        XCTAssertEqual(DocumentReader.formatNumber("0.30000000000000004"), "0.3")
        XCTAssertEqual(DocumentReader.formatNumber("12345"), "12345")
        XCTAssertEqual(DocumentReader.excelDate(45413, date1904: false), "2024-05-01")
        XCTAssertEqual(DocumentReader.excelDate(0.5, date1904: false), "12:00")
        XCTAssertTrue(DocumentReader.isDateFormat(id: 14, code: nil))
        XCTAssertTrue(DocumentReader.isDateFormat(id: 170, code: "[$-419]d mmmm yyyy"))
        XCTAssertFalse(DocumentReader.isDateFormat(id: 171, code: "#,##0.00\" дн.\""))
    }

    // MARK: PowerPoint

    func testPowerPointSlidesFollowNumericOrderWithNotes() throws {
        func slide(_ text: String) -> String {
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>\(text)</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>
            """
        }
        let slideRels = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide" Target="../notesSlides/notesSlide7.xml"/>
        </Relationships>
        """
        let notes = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree>
        <p:sp><p:txBody><a:p><a:r><a:t>Говорить медленно</a:t></a:r></a:p></p:txBody></p:sp>
        <p:sp><p:txBody><a:p><a:fld id="{1}" type="slidenum"><a:t>2</a:t></a:fld></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld></p:notes>
        """
        let zip = try TestZip.make([
            TestZipEntry(name: "ppt/slides/slide10.xml", content: Data(slide("Десятый").utf8), deflate: true),
            TestZipEntry(name: "ppt/slides/slide2.xml", content: Data(slide("Второй").utf8)),
            TestZipEntry(name: "ppt/slides/_rels/slide2.xml.rels", content: Data(slideRels.utf8)),
            TestZipEntry(name: "ppt/notesSlides/notesSlide7.xml", content: Data(notes.utf8))
        ])

        let result = try DocumentReader.extractText(from: zip, fileExtension: "pptx")
        XCTAssertTrue(result.text.contains("## Слайд 1\nВторой"), result.text)
        XCTAssertTrue(result.text.contains("## Слайд 2\nДесятый"), result.text)
        XCTAssertTrue(result.text.contains("Заметки: Говорить медленно"), result.text)
        XCTAssertFalse(result.text.contains("медленно\n2"), result.text)
        XCTAssertEqual(result.summary, "Презентация PowerPoint: 2 слайда")
    }

    // MARK: OpenDocument / EPUB

    func testOpenDocumentSpreadsheetRespectsRepeatedCells() throws {
        let content = """
        <?xml version="1.0" encoding="UTF-8"?>
        <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:spreadsheet>
        <table:table table:name="Бюджет">
        <table:table-row><table:table-cell><text:p>Статья</text:p></table:table-cell><table:table-cell><text:p>Сумма</text:p></table:table-cell></table:table-row>
        <table:table-row><table:table-cell><text:p>Еда</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="100"><text:p>100</text:p></table:table-cell><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
        <table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
        </table:table></office:spreadsheet></office:body></office:document-content>
        """
        let zip = try TestZip.make([
            TestZipEntry(name: "mimetype", content: Data("application/vnd.oasis.opendocument.spreadsheet".utf8)),
            TestZipEntry(name: "content.xml", content: Data(content.utf8), deflate: true)
        ])

        let result = try DocumentReader.extractText(from: zip, fileExtension: "ods")
        XCTAssertTrue(result.text.contains("## Лист «Бюджет»\n\n| Статья | Сумма |\n|---|---|\n| Еда | 100 |"), result.text)
        XCTAssertEqual(result.summary, "Таблица OpenDocument: 1 лист, 2 строки")
    }

    func testEPUBFollowsSpineOrder() throws {
        let container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
        """
        let package = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Сказка</dc:title></metadata>
        <manifest><item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>
        """
        func chapter(_ text: String) -> String {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title><script src=\"a.js\"/></head><body><p>\(text)</p></body></html>"
        }
        let zip = try TestZip.make([
            TestZipEntry(name: "mimetype", content: Data("application/epub+zip".utf8)),
            TestZipEntry(name: "META-INF/container.xml", content: Data(container.utf8)),
            TestZipEntry(name: "OEBPS/content.opf", content: Data(package.utf8)),
            TestZipEntry(name: "OEBPS/text/ch1.xhtml", content: Data(chapter("Первая глава").utf8), deflate: true),
            TestZipEntry(name: "OEBPS/text/ch 2.xhtml", content: Data(chapter("Вторая глава").utf8))
        ])

        let result = try DocumentReader.extractText(from: zip, fileExtension: "epub")
        XCTAssertTrue(result.text.hasPrefix("# Сказка"), result.text)
        let second = try XCTUnwrap(result.text.range(of: "Вторая глава"))
        let first = try XCTUnwrap(result.text.range(of: "Первая глава"))
        XCTAssertTrue(second.lowerBound < first.lowerBound)
        XCTAssertEqual(result.summary, "Книга EPUB: 2 главы")
    }

    // MARK: Текстовые форматы

    func testSemicolonCSVWithQuotedNewline() throws {
        let csv = "Имя;Комментарий\nАня;\"строка1\nстрока2\"\nБорис;\"с \"\"кавычками\"\" | чертой\"\n"
        let result = try DocumentReader.extractText(from: Data(csv.utf8), fileExtension: "csv")
        XCTAssertTrue(result.text.hasPrefix("| Имя | Комментарий |\n|---|---|"), result.text)
        XCTAssertTrue(result.text.contains("| Аня | строка1<br>строка2 |"), result.text)
        XCTAssertTrue(result.text.contains("| Борис | с \"кавычками\" \\| чертой |"), result.text)
        XCTAssertEqual(result.summary, "Таблица CSV: 3 строки, 2 столбца")
    }

    func testHTMLStripsScriptsAndDecodesEntities() throws {
        let html = """
        <!DOCTYPE html><html><head><title>Страница</title><style>p { color: red; }</style>
        <script>if (a < b) { alert('x'); }</script></head><body><h1>Заголовок</h1>
        <p>Tom &amp; Jerry &lt;3 &#x1F600; &#169;&nbsp;ok<br>вторая   строка</p>
        <ul><li>один</li><li>два</li></ul><!-- секрет --><noscript>без JS</noscript></body></html>
        """
        let result = try DocumentReader.extractText(from: Data(html.utf8), fileExtension: "html")
        XCTAssertTrue(result.text.contains("# Заголовок"), result.text)
        XCTAssertTrue(result.text.contains("Tom & Jerry <3 😀 © ok\nвторая строка"), result.text)
        XCTAssertTrue(result.text.contains("- один\n- два"), result.text)
        for hidden in ["alert", "color", "секрет", "без JS", "<p>"] {
            XCTAssertFalse(result.text.contains(hidden), hidden)
        }
        XCTAssertTrue(result.summary.hasPrefix("Веб-страница"))
    }

    func testCodeFileIsFenced() throws {
        let code = "def main():\n    print('hi')\n"
        let result = try DocumentReader.extractText(from: Data(code.utf8), fileExtension: "py")
        XCTAssertEqual(result.text, "```python\ndef main():\n    print('hi')\n```")
        XCTAssertEqual(result.summary, "Код Python: 2 строки")
        XCTAssertEqual(DocumentReader.kind(forExtension: "swift"), "Код")
        XCTAssertEqual(DocumentReader.symbol(forExtension: "swift"), "chevron.left.forwardslash.chevron.right")
        XCTAssertEqual(DocumentReader.symbol(forExtension: "xlsx"), "tablecells")
    }

    func testLegacyEncodingsAndNotebook() throws {
        let cp1251 = try XCTUnwrap("Привет, мир".data(using: .windowsCP1251))
        XCTAssertEqual(try DocumentReader.extractText(from: cp1251, fileExtension: "txt").text, "Привет, мир")

        let notebook = """
        {"cells":[{"cell_type":"markdown","source":["# Анализ\\n","Описание"]},{"cell_type":"code","source":"print(1)","outputs":[{"output_type":"stream","text":["1\\n"]}]}],"metadata":{"kernelspec":{"language":"python"}},"nbformat":4}
        """
        let result = try DocumentReader.extractText(from: Data(notebook.utf8), fileExtension: "ipynb")
        XCTAssertTrue(result.text.hasPrefix("# Анализ\nОписание"), result.text)
        XCTAssertTrue(result.text.contains("```python\nprint(1)\n```\nВывод:\n```\n1\n```"), result.text)
        XCTAssertEqual(result.summary, "Блокнот Jupyter: 2 ячейки")
    }

    func testRTFIsReadable() throws {
        let rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Helvetica;}}\\f0 Hello \\b World\\b0\\par}"
        let result = try DocumentReader.extractText(from: Data(rtf.utf8), fileExtension: "rtf")
        XCTAssertTrue(result.text.contains("Hello World"), result.text)
    }

    func testLongTextIsTruncatedWithNote() throws {
        let text = String(repeating: "Строка текста\n\n\n\n", count: 20_000)
        let result = try DocumentReader.extractText(from: Data(text.utf8), fileExtension: "txt")
        XCTAssertLessThanOrEqual(result.text.count, DocumentReader.maximumCharacters)
        XCTAssertTrue(result.text.hasSuffix("(текст обрезан: документ длиннее 160 000 символов)"))
        XCTAssertFalse(result.text.contains("\n\n\n"))
    }
}

// MARK: - Тестовый ZIP-писатель (stored + deflate)

private struct TestZipEntry {
    var name: String
    var content: Data
    var deflate: Bool = false
    var localExtra: Data = Data()
    var declaredSize: Int? = nil
}

private enum TestZip {
    static func make(_ entries: [TestZipEntry]) throws -> Data {
        var body = Data()
        var directory = Data()
        for entry in entries {
            let name = Data(entry.name.utf8)
            let payload: Data
            if entry.deflate {
                payload = try deflate(entry.content)
            } else {
                payload = entry.content
            }
            let method: UInt16 = entry.deflate ? 8 : 0
            let checksum = crc32(entry.content)
            let size = UInt32(entry.declaredSize ?? entry.content.count)
            let offset = UInt32(body.count)

            body.append(le32(0x0403_4b50))
            body.append(le16(20))
            body.append(le16(0x0800))
            body.append(le16(method))
            body.append(le16(0))
            body.append(le16(0x21))
            body.append(le32(checksum))
            body.append(le32(UInt32(payload.count)))
            body.append(le32(size))
            body.append(le16(UInt16(name.count)))
            body.append(le16(UInt16(entry.localExtra.count)))
            body.append(name)
            body.append(entry.localExtra)
            body.append(payload)

            directory.append(le32(0x0201_4b50))
            directory.append(le16(20))
            directory.append(le16(20))
            directory.append(le16(0x0800))
            directory.append(le16(method))
            directory.append(le16(0))
            directory.append(le16(0x21))
            directory.append(le32(checksum))
            directory.append(le32(UInt32(payload.count)))
            directory.append(le32(size))
            directory.append(le16(UInt16(name.count)))
            directory.append(le16(0))
            directory.append(le16(0))
            directory.append(le16(0))
            directory.append(le16(0))
            directory.append(le32(0))
            directory.append(le32(offset))
            directory.append(name)
        }
        let directoryOffset = UInt32(body.count)
        let directorySize = UInt32(directory.count)
        body.append(directory)
        body.append(le32(0x0605_4b50))
        body.append(le16(0))
        body.append(le16(0))
        body.append(le16(UInt16(entries.count)))
        body.append(le16(UInt16(entries.count)))
        body.append(le32(directorySize))
        body.append(le32(directoryOffset))
        body.append(le16(0))
        return body
    }

    /// Сырой DEFLATE (COMPRESSION_ZLIB у Apple — без заголовка zlib).
    static func deflate(_ data: Data) throws -> Data {
        let source = [UInt8](data)
        let capacity = source.count + 1_024
        var destination = [UInt8](repeating: 0, count: capacity)
        let written: Int = source.withUnsafeBufferPointer { (input: UnsafeBufferPointer<UInt8>) -> Int in
            destination.withUnsafeMutableBufferPointer { (output: inout UnsafeMutableBufferPointer<UInt8>) -> Int in
                guard let inputBase = input.baseAddress, let outputBase = output.baseAddress else { return 0 }
                return compression_encode_buffer(outputBase, capacity, inputBase, input.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0 else {
            throw NSError(domain: "TestZip", code: 1, userInfo: [NSLocalizedDescriptionKey: "deflate failed"])
        }
        return Data(destination.prefix(written))
    }

    static let crcTable: [UInt32] = (0..<256).map { (index: Int) -> UInt32 in
        var value = UInt32(index)
        for _ in 0..<8 {
            value = (value & 1) != 0 ? (0xEDB8_8320 ^ (value >> 1)) : (value >> 1)
        }
        return value
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }

    static func le16(_ value: UInt16) -> Data {
        Data([UInt8(value & 0xFF), UInt8(value >> 8)])
    }

    static func le32(_ value: UInt32) -> Data {
        Data([UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8(value >> 24)])
    }
}
