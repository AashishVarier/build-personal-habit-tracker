package com.variableduck.build.habittracker.backup.xlsx

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A worksheet: the first row is treated as a bold, frozen header. Cells are String, Number or null. */
data class XlsxSheet(val name: String, val rows: List<List<Any?>>)

/**
 * Minimal OOXML (.xlsx) writer. It writes inline strings and numbers only, which is all a backup
 * needs, and avoids pulling Apache POI into an Android app.
 */
object XlsxWriter {
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    /** Writes the workbook to [out]. The stream is finished but not closed. */
    fun write(sheets: List<XlsxSheet>, out: OutputStream) {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet" }
        val names = sheets.map { sanitizeSheetName(it.name) }
        require(names.toSet().size == names.size) { "Sheet names must be unique" }

        val zip = ZipOutputStream(out)
        zip.entry("[Content_Types].xml", contentTypes(sheets.size))
        zip.entry("_rels/.rels", rootRels())
        zip.entry("xl/workbook.xml", workbook(names))
        zip.entry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
        zip.entry("xl/styles.xml", styles())
        sheets.forEachIndexed { index, sheet ->
            zip.entry("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
        }
        zip.finish()
        zip.flush()
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int) = buildString {
        append(XML_HEADER)
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append(
            """<Override PartName="/xl/workbook.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""",
        )
        append(
            """<Override PartName="/xl/styles.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""",
        )
        for (i in 1..sheetCount) {
            append(
                """<Override PartName="/xl/worksheets/sheet$i.xml" """ +
                    """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""",
            )
        }
        append("</Types>")
    }

    private fun rootRels() = XML_HEADER +
        """<Relationships xmlns="$PKG_REL_NS">""" +
        """<Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="xl/workbook.xml"/>""" +
        "</Relationships>"

    private fun workbook(names: List<String>) = buildString {
        append(XML_HEADER)
        append("""<workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS"><sheets>""")
        names.forEachIndexed { index, name ->
            append("""<sheet name="${escape(name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int) = buildString {
        append(XML_HEADER)
        append("""<Relationships xmlns="$PKG_REL_NS">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="$REL_NS/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" Type="$REL_NS/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun styles() = XML_HEADER +
        """<styleSheet xmlns="$MAIN_NS">""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
        """<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill>""" +
        """<fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        "</styleSheet>"

    private fun worksheet(sheet: XlsxSheet) = buildString {
        append(XML_HEADER)
        append("""<worksheet xmlns="$MAIN_NS">""")
        if (sheet.rows.isNotEmpty()) {
            append(
                """<sheetViews><sheetView workbookViewId="0">""" +
                    """<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""" +
                    """</sheetView></sheetViews>""",
            )
        }
        append("""<sheetFormatPr defaultRowHeight="15"/>""")
        val widths = columnWidths(sheet.rows)
        if (widths.isNotEmpty()) {
            append("<cols>")
            widths.forEachIndexed { index, width ->
                append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            append("""<row r="$rowNumber">""")
            row.forEachIndexed { colIndex, value ->
                if (value != null) appendCell(columnName(colIndex) + rowNumber, value, header = rowIndex == 0)
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun StringBuilder.appendCell(ref: String, value: Any, header: Boolean) {
        val style = if (header) """ s="1"""" else ""
        when (value) {
            is Int, is Long, is Short, is Byte -> append("""<c r="$ref"$style><v>$value</v></c>""")
            is Double, is Float -> {
                val number = (value as Number).toDouble()
                if (number.isFinite()) {
                    append("""<c r="$ref"$style><v>$number</v></c>""")
                } else {
                    appendInlineString(ref, style, number.toString())
                }
            }
            else -> appendInlineString(ref, style, value.toString())
        }
    }

    private fun StringBuilder.appendInlineString(ref: String, style: String, text: String) {
        val preserve = if (text.isNotEmpty() && (text.first().isWhitespace() || text.last().isWhitespace())) {
            """ xml:space="preserve""""
        } else {
            ""
        }
        append("""<c r="$ref" t="inlineStr"$style><is><t$preserve>${escape(text)}</t></is></c>""")
    }

    private fun columnWidths(rows: List<List<Any?>>): List<Int> {
        val count = rows.maxOfOrNull { it.size } ?: 0
        return (0 until count).map { col ->
            val longest = rows.maxOfOrNull { row -> row.getOrNull(col)?.toString()?.length ?: 0 } ?: 0
            (longest + 2).coerceIn(8, 60)
        }
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun sanitizeSheetName(name: String): String =
        name.replace(Regex("""[\[\]:*?/\\]"""), "_").take(31).ifBlank { "Sheet" }

    /** Escapes XML special characters and drops characters that XML 1.0 forbids. */
    internal fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' -> Unit
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        sb.append(c).append(text[i + 1])
                        i++
                    }
                }
                Character.isLowSurrogate(c) -> Unit
                c == '￾' || c == '￿' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
