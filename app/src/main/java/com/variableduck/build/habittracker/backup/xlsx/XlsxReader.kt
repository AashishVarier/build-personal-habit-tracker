package com.variableduck.build.habittracker.backup.xlsx

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import org.xml.sax.helpers.DefaultHandler

class XlsxFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal OOXML (.xlsx) reader. Returns every sheet as rows of cell text, in workbook order.
 * Handles inline strings (what [XlsxWriter] writes) and shared strings (what Excel or LibreOffice
 * write after the file has been edited and saved).
 */
object XlsxReader {
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"

    // A 10-year backup is a few megabytes of XML and about 150,000 cells, far below these limits.
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 2_000

    /** Excel's own limit: column XFD. */
    private const val MAX_COLUMNS = 16_384

    /** Rows, cells and shared strings the whole file may expand into in memory. */
    private const val MAX_CELLS = 1_000_000

    fun read(input: InputStream): LinkedHashMap<String, List<List<String>>> {
        val files = unzip(input)
        val workbook = files["xl/workbook.xml"] ?: throw XlsxFormatException("Not an .xlsx file (no workbook).")
        val sheets = parseWorkbook(workbook)
        val rels = files["xl/_rels/workbook.xml.rels"]?.let(::parseRels).orEmpty()
        val budget = CellBudget(MAX_CELLS)
        val sharedStringsPart = rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }
            ?.let { files[resolve(it.target)] }
            ?: files["xl/sharedStrings.xml"]
        val sharedStrings = sharedStringsPart?.let { parseSharedStrings(it, budget) }.orEmpty()

        val result = LinkedHashMap<String, List<List<String>>>()
        sheets.forEachIndexed { index, (name, relId) ->
            val path = rels[relId]?.target?.let(::resolve) ?: "xl/worksheets/sheet${index + 1}.xml"
            val bytes = files[path] ?: throw XlsxFormatException("Sheet '$name' is missing.")
            result[name] = parseSheet(bytes, sharedStrings, budget)
        }
        return result
    }

    /**
     * Counts what the file expands into. A row element of a few bytes can stand for thousands of
     * cells, so without a budget a small crafted file could exhaust memory.
     */
    private class CellBudget(private var remaining: Int) {
        fun spend(amount: Int) {
            remaining -= amount
            if (remaining < 0) throw XlsxFormatException("The file has too many cells.")
        }
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (files.size >= MAX_ENTRIES) throw XlsxFormatException("Too many entries in file.")
                    if (entry.isDirectory) continue
                    val bytes = readLimited(zip)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw XlsxFormatException("File is too large.")
                    files[entry.name.removePrefix("/")] = bytes
                }
            }
        } catch (e: XlsxFormatException) {
            throw e
        } catch (e: IOException) {
            throw XlsxFormatException("Could not read the file. Is it an .xlsx file?", e)
        }
        if (files.isEmpty()) throw XlsxFormatException("Not an .xlsx file.")
        return files
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var size = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            size += read
            if (size > MAX_ENTRY_BYTES) throw XlsxFormatException("A part of the file is too large.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun resolve(target: String): String =
        if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")

    private data class Rel(val target: String, val type: String)

    private fun parseWorkbook(bytes: ByteArray): List<Pair<String, String>> {
        val sheets = ArrayList<Pair<String, String>>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (localName == "sheet") {
                    val name = attributes.getValue("", "name") ?: attributes.getValue("name") ?: return
                    val id = attributes.getValue(REL_NS, "id") ?: ""
                    sheets += name to id
                }
            }
        })
        return sheets
    }

    private fun parseRels(bytes: ByteArray): Map<String, Rel> {
        val rels = HashMap<String, Rel>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (localName == "Relationship") {
                    val id = attributes.getValue("", "Id") ?: attributes.getValue("Id") ?: return
                    val target = attributes.getValue("", "Target") ?: attributes.getValue("Target") ?: return
                    val type = attributes.getValue("", "Type") ?: attributes.getValue("Type") ?: ""
                    rels[id] = Rel(target, type)
                }
            }
        })
        return rels
    }

    private fun parseSharedStrings(bytes: ByteArray, budget: CellBudget): List<String> {
        val strings = ArrayList<String>()
        parse(bytes, object : DefaultHandler() {
            private var current: StringBuilder? = null
            private var inText = false
            private var phoneticDepth = 0

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                when (localName) {
                    "si" -> current = StringBuilder()
                    "rPh" -> phoneticDepth++
                    "t" -> inText = phoneticDepth == 0
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when (localName) {
                    "si" -> {
                        budget.spend(1)
                        strings += current?.toString().orEmpty()
                        current = null
                    }
                    "rPh" -> phoneticDepth--
                    "t" -> inText = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current?.appendRange(ch, start, start + length)
            }
        })
        return strings
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>, budget: CellBudget): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parse(bytes, object : DefaultHandler() {
            private var row: MutableMap<Int, String>? = null
            private var cellRef: String? = null
            private var cellType: String? = null
            private var nextColumn = 0
            private val value = StringBuilder()
            private val inline = StringBuilder()
            private var inValue = false
            private var inInlineText = false
            private var phoneticDepth = 0

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                when (localName) {
                    "row" -> {
                        row = HashMap()
                        nextColumn = 0
                    }
                    "c" -> {
                        cellRef = attributes.getValue("", "r") ?: attributes.getValue("r")
                        cellType = attributes.getValue("", "t") ?: attributes.getValue("t")
                        value.setLength(0)
                        inline.setLength(0)
                    }
                    "v" -> inValue = true
                    "rPh" -> phoneticDepth++
                    "t" -> inInlineText = phoneticDepth == 0
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when (localName) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "rPh" -> phoneticDepth--
                    "c" -> {
                        val column = cellRef?.let(::columnIndex) ?: nextColumn
                        if (column >= MAX_COLUMNS) throw XlsxFormatException("A cell is beyond the last column (XFD).")
                        nextColumn = column + 1
                        val text = when (cellType) {
                            "s" -> value.toString().trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                            "inlineStr" -> inline.toString()
                            "str", "e" -> value.toString()
                            "b" -> if (value.toString().trim() == "1") "TRUE" else "FALSE"
                            else -> normalizeNumber(value.toString().trim())
                        }
                        row?.put(column, text)
                    }
                    "row" -> {
                        val cells = row.orEmpty()
                        val width = (cells.keys.maxOrNull() ?: -1) + 1
                        budget.spend(maxOf(width, 1))
                        rows += List(width) { cells[it].orEmpty() }
                        row = null
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.appendRange(ch, start, start + length)
                if (inInlineText) inline.appendRange(ch, start, start + length)
            }
        })
        return rows
    }

    /** "B12" -> 1. Stops before a long reference could overflow (which used to cause an out-of-memory crash). */
    private fun columnIndex(ref: String): Int? {
        var index = 0
        var letters = 0
        for (c in ref) {
            if (c in 'A'..'Z') {
                index = index * 26 + (c - 'A' + 1)
                letters++
            } else if (c in 'a'..'z') {
                index = index * 26 + (c - 'a' + 1)
                letters++
            } else {
                break
            }
            if (index > MAX_COLUMNS) throw XlsxFormatException("A cell is beyond the last column (XFD).")
        }
        return if (letters == 0) null else index - 1
    }

    /** "12.0" -> "12", keeps other numbers as written. */
    private fun normalizeNumber(text: String): String {
        val number = text.toDoubleOrNull() ?: return text
        return if (number == Math.floor(number) && kotlin.math.abs(number) < 1e15) {
            number.toLong().toString()
        } else {
            text
        }
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            // Never resolve external entities: an exported file must not reach outside itself.
            reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            // .xlsx parts never have a DOCTYPE. Rejecting it also stops entity-expansion files
            // ("billion laughs") on Android's parser, which ignores the features above.
            try {
                reader.setProperty(LEXICAL_HANDLER, RejectDoctype)
            } catch (unsupported: SAXException) {
                // Not recognised by this parser; disallow-doctype-decl above covers the JVM one.
            }
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (e: XlsxFormatException) {
            throw e
        } catch (e: Exception) {
            throw XlsxFormatException("The file is damaged or not an .xlsx file.", e)
        }
    }

    /** Fails on any DOCTYPE, before its entity declarations can be read. */
    private object RejectDoctype : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("A DOCTYPE is not allowed in an .xlsx file.")
        }
    }

    private fun trySetFeature(factory: SAXParserFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (unsupported: Exception) {
            // Not every parser (Android's Expat-based one, for example) supports every feature.
        }
    }
}
