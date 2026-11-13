package com.variableduck.build.habittracker.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.variableduck.build.habittracker.backup.xlsx.XlsxFormatException
import com.variableduck.build.habittracker.backup.xlsx.XlsxReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device: Android's XML parser ignores the JVM parser features, so the DOCTYPE
 * rejection (the guard against entity-expansion files) has to be checked here.
 */
@RunWith(AndroidJUnit4::class)
class XlsxReaderInstrumentedTest {
    private val mainNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private fun read(sheetXml: String): List<List<String>> {
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
        return XlsxReader.read(ByteArrayInputStream(out.toByteArray())).getValue("S")
    }

    @Test
    fun readsAnOrdinarySheet() {
        val rows = read("""<worksheet xmlns="$mainNs"><sheetData><row><c t="inlineStr"><is><t>a</t></is></c></row></sheetData></worksheet>""")
        assertEquals(listOf(listOf("a")), rows)
    }

    @Test
    fun rejectsADoctypeWithEntityDeclarations() {
        val billionLaughs = """<?xml version="1.0"?>""" +
            """<!DOCTYPE worksheet [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
            """<worksheet xmlns="$mainNs"><sheetData><row><c t="inlineStr"><is><t>&b;</t></is></c></row></sheetData></worksheet>"""
        assertThrows(XlsxFormatException::class.java) { read(billionLaughs) }
    }
}
