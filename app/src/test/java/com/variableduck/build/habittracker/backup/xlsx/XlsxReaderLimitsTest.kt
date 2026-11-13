package com.variableduck.build.habittracker.backup.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Crafted files fail with a clear error instead of exhausting memory. */
class XlsxReaderLimitsTest {
    private val mainNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    /** A one-sheet workbook whose sheet XML is [sheetXml]. */
    private fun workbook(sheetXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            put(
                "xl/workbook.xml",
                """<workbook xmlns="$mainNs" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                    """<sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            )
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
        return out.toByteArray()
    }

    private fun sheet(rows: String) = """<worksheet xmlns="$mainNs"><sheetData>$rows</sheetData></worksheet>"""

    private fun read(sheetXml: String) = XlsxReader.read(ByteArrayInputStream(workbook(sheetXml))).getValue("S")

    @Test
    fun `reads an ordinary sheet`() {
        val rows = read(sheet("""<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c><c r="C1"><v>3</v></c></row>"""))
        assertEquals(listOf(listOf("a", "", "3")), rows)
    }

    @Test
    fun `accepts the last column, XFD`() {
        val row = read(sheet("""<row><c r="XFD1"><v>1</v></c></row>""")).single()
        assertEquals(16_384, row.size)
        assertEquals("1", row.last())
    }

    @Test
    fun `rejects cell references beyond the last column`() {
        // This one used to overflow the column number and run out of memory.
        assertThrows(XlsxFormatException::class.java) { read(sheet("""<row><c r="FXSHRXW1"><v>1</v></c></row>""")) }
        assertThrows(XlsxFormatException::class.java) { read(sheet("""<row><c r="XFE1"><v>1</v></c></row>""")) }
    }

    @Test
    fun `rejects a small file that expands into too many cells`() {
        // A few kilobytes of XML standing for 1.6 million cells.
        val rows = (1..100).joinToString("") { """<row><c r="XFD$it"><v>1</v></c></row>""" }
        assertThrows(XlsxFormatException::class.java) { read(sheet(rows)) }
    }

    @Test
    fun `rejects a DOCTYPE with entity declarations`() {
        val billionLaughs = """<?xml version="1.0"?>""" +
            """<!DOCTYPE worksheet [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
            """<worksheet xmlns="$mainNs"><sheetData><row><c t="inlineStr"><is><t>&b;</t></is></c></row></sheetData></worksheet>"""
        assertThrows(XlsxFormatException::class.java) { read(billionLaughs) }
    }
}
