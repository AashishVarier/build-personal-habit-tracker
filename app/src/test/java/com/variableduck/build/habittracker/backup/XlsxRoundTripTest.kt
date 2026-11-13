package com.variableduck.build.habittracker.backup

import com.variableduck.build.habittracker.backup.xlsx.XlsxReader
import com.variableduck.build.habittracker.backup.xlsx.XlsxSheet
import com.variableduck.build.habittracker.backup.xlsx.XlsxWriter
import com.variableduck.build.habittracker.data.settings.PortableSettings
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.testing.habit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM: snapshot -> xlsx bytes -> snapshot. */
class XlsxRoundTripTest {
    private val d0 = LocalDate.of(2026, 2, 1)

    /** The day the backup is restored. */
    private val today = d0.plusDays(30)

    private val snapshot = BackupSnapshot(
        habits = listOf(
            habit(id = 1, name = "Walk", type = HabitType.DURATION, current = 15, starting = 10, step = 5, slot = 0, createdOn = d0),
            habit(id = 2, name = "Wake up", type = HabitType.DEADLINE, current = 7 * 60 + 15, starting = 7 * 60 + 30, step = 15, slot = 1, mandatory = true, createdOn = d0),
            habit(id = 3, name = "Habit C", type = HabitType.BOOLEAN, slot = 2, createdOn = d0, archivedOn = d0.plusDays(2)),
            habit(id = 4, name = "Habit D", type = HabitType.BOOLEAN, slot = 2, createdOn = d0.plusDays(2)),
        ),
        days = listOf(
            DayRecord(d0, "Tricky <remark> & \"quotes\" 'here'", rewardUnlocked = false),
            DayRecord(d0.plusDays(1), "  leading and trailing  ", rewardUnlocked = true),
            DayRecord(d0.plusDays(2), "Emoji 🙂 ok", rewardUnlocked = false),
            DayRecord(d0.plusDays(3), "", rewardUnlocked = false),
        ),
        logs = listOf(
            HabitLog(d0, 1, done = true),
            HabitLog(d0, 2, done = false, actualTime = 7 * 60 + 42),
            HabitLog(d0, 3, done = true),
            HabitLog(d0.plusDays(1), 1, done = true),
            HabitLog(d0.plusDays(1), 2, done = true, actualTime = 7 * 60 + 10),
            HabitLog(d0.plusDays(1), 3, done = true),
            HabitLog(d0.plusDays(2), 1, done = false),
            HabitLog(d0.plusDays(2), 2, done = false),
            HabitLog(d0.plusDays(2), 4, done = true),
        ),
        targetChanges = listOf(
            TargetChange(1, 1, d0.plusDays(1), 10, 15, TargetChangeReason.LEVEL_UP),
            TargetChange(2, 2, d0.plusDays(1), 7 * 60 + 30, 7 * 60 + 15, TargetChangeReason.LEVEL_UP),
        ),
        settings = PortableSettings(
            reminderMinuteOfDay = 21 * 60 + 15,
            cycleStart = d0,
            levelUpThreshold = 11,
            stepDownThreshold = 4,
            overallGoal = "Feel better, one day at a time",
            retentionMonths = 6,
        ),
        lastEvaluatedCycleEnd = d0.minusDays(1),
        exportedOn = d0.plusDays(3),
    )

    private fun roundTrip(input: BackupSnapshot): BackupSnapshot {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(BackupCodec.toSheets(input), it) }.toByteArray()
        return BackupCodec.fromSheets(XlsxReader.read(ByteArrayInputStream(bytes)), today)
    }

    @Test
    fun `export then restore gives back identical data`() {
        assertEquals(snapshot, roundTrip(snapshot))
    }

    @Test
    fun `workbook has the four expected sheets`() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(BackupCodec.toSheets(snapshot), it) }.toByteArray()
        val sheets = XlsxReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("Entries", "Habits", "TargetHistory", "Settings"), sheets.keys.toList())
        assertEquals(listOf("Date", "Habit ID", "Habit", "Status", "Actual time", "Remark", "Reward"), sheets["Entries"]!![0])
    }

    @Test
    fun `a day without entries still round-trips`() {
        val withEmptyDay = snapshot.copy(days = snapshot.days + DayRecord(d0.plusDays(4), "Only a remark"))
        assertEquals(withEmptyDay, roundTrip(withEmptyDay))
    }

    @Test
    fun `reads shared strings and excel date serials written by spreadsheet apps`() {
        val sheets = BackupCodec.toSheets(snapshot).associateBy { it.name }
        val reread = XlsxReader.read(ByteArrayInputStream(sharedStringWorkbook(sheets.getValue("Settings"))))
        assertEquals(sheets.getValue("Settings").rows.map { row -> row.map { it.toString() } }, reread["Settings"])
        assertEquals(LocalDate.of(2026, 2, 1), BackupCodec.parseDate("46054"))
        assertEquals(7 * 60 + 30, BackupCodec.parseTime("0.3125"))
    }

    @Test
    fun `rejects files that are not backups`() {
        assertThrows(Exception::class.java) { XlsxReader.read(ByteArrayInputStream("not a zip".toByteArray())) }
        val noEntries = BackupCodec.toSheets(snapshot).filter { it.name != "Entries" }
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(noEntries, it) }.toByteArray()
        val error = assertThrows(BackupFormatException::class.java) {
            BackupCodec.fromSheets(XlsxReader.read(ByteArrayInputStream(bytes)), today)
        }
        assertTrue(error.message!!.contains("Entries"))
    }

    @Test
    fun `csv export quotes and neutralises formulas`() {
        val csv = BackupCodec.toCsv(snapshot.copy(days = listOf(DayRecord(d0, "=1+1, \"hi\"")), logs = listOf(HabitLog(d0, 1, true))))
        assertTrue(csv.contains("\"'=1+1, \"\"hi\"\"\""))
    }

    /** Builds a workbook the way Excel stores text: in a shared-strings table. */
    private fun sharedStringWorkbook(sheet: XlsxSheet): ByteArray {
        val strings = sheet.rows.flatten().map { it.toString() }.distinct()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            put(
                "xl/workbook.xml",
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                    """<sheets><sheet name="Settings" sheetId="1" r:id="rId7"/></sheets></workbook>""",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/data.xml"/>""" +
                    """<Relationship Id="rId8" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>""" +
                    "</Relationships>",
            )
            put(
                "xl/sharedStrings.xml",
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                    strings.joinToString("") { "<si><r><t>${XlsxWriter.escape(it)}</t></r></si>" } + "</sst>",
            )
            put(
                "xl/worksheets/data.xml",
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
                    sheet.rows.mapIndexed { r, row ->
                        "<row r=\"${r + 1}\">" + row.mapIndexed { c, v ->
                            "<c r=\"${XlsxWriter.columnName(c)}${r + 1}\" t=\"s\"><v>${strings.indexOf(v.toString())}</v></c>"
                        }.joinToString("") + "</row>"
                    }.joinToString("") + "</sheetData></worksheet>",
            )
        }
        return out.toByteArray()
    }
}
