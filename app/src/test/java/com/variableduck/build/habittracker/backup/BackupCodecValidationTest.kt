package com.variableduck.build.habittracker.backup

import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.PortableSettings
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.testing.habit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restore rejects values the app can't handle, but still accepts everything the app exports. */
class BackupCodecValidationTest {
    private val d0 = LocalDate.of(2026, 2, 1)
    private val today = LocalDate.of(2026, 3, 1)

    private val snapshot = BackupSnapshot(
        habits = listOf(
            habit(id = 1, name = "Walk", type = HabitType.DURATION, current = 15, starting = 10, step = 5, slot = 0, createdOn = d0),
            habit(id = 2, name = "Wake up", type = HabitType.DEADLINE, current = 7 * 60, starting = 7 * 60 + 30, step = 15, slot = 1, createdOn = d0),
        ),
        days = listOf(DayRecord(d0, "ok"), DayRecord(d0.plusDays(1))),
        logs = listOf(
            HabitLog(d0, 1, done = true),
            HabitLog(d0, 2, done = false, actualTime = 7 * 60 + 5),
            HabitLog(d0.plusDays(1), 1, done = false),
            HabitLog(d0.plusDays(1), 2, done = true),
        ),
        targetChanges = listOf(TargetChange(1, 2, d0.plusDays(1), 7 * 60 + 30, 7 * 60, TargetChangeReason.LEVEL_UP)),
        settings = PortableSettings(21 * 60, d0, 10, 5, "Generic goal", 6),
        lastEvaluatedCycleEnd = d0.minusDays(1),
        exportedOn = d0.plusDays(2),
    )

    /** The backup as the xlsx reader returns it: every cell as text. */
    private val sheets: Map<String, List<List<String>>> = BackupCodec.toSheets(snapshot).associate { sheet ->
        sheet.name to sheet.rows.map { row -> row.map { it?.toString().orEmpty() } }
    }

    /** A copy of the backup with one cell changed in every row of [sheet] whose [keyColumn] is [key]. */
    private fun edited(sheet: String, keyColumn: String, key: String, column: String, value: String): Map<String, List<List<String>>> {
        val rows = sheets.getValue(sheet)
        val keyIndex = rows[0].indexOf(keyColumn)
        val index = rows[0].indexOf(column)
        require(keyIndex >= 0 && index >= 0) { "unknown column" }
        require(rows.drop(1).any { it[keyIndex] == key }) { "no row with $keyColumn = $key" }
        val changed = rows.mapIndexed { r, row ->
            if (r == 0 || row[keyIndex] != key) {
                row
            } else {
                row.toMutableList().also { cells ->
                    while (cells.size <= index) cells += ""
                    cells[index] = value
                }
            }
        }
        return sheets + (sheet to changed)
    }

    private fun assertRejected(backup: Map<String, List<List<String>>>, column: String) {
        val error = assertThrows(BackupFormatException::class.java) { BackupCodec.fromSheets(backup, today) }
        assertTrue(error.message, error.message!!.contains("'$column'"))
    }

    @Test
    fun `the app's own export passes unchanged`() {
        assertEquals(snapshot, BackupCodec.fromSheets(sheets, today))
    }

    @Test
    fun `rejects targets and steps the app can't store or show`() {
        // 19:30 typed as a number instead of minutes after midnight: used to crash the Today screen.
        assertRejected(edited("Habits", "ID", "2", "Current target", "1930"), "Current target")
        assertRejected(edited("Habits", "ID", "2", "Starting target", "-30"), "Starting target")
        assertRejected(edited("Habits", "ID", "1", "Current target", "0"), "Current target")
        assertRejected(edited("Habits", "ID", "1", "Current target", "1441"), "Current target")
        assertRejected(edited("Habits", "ID", "1", "Step", "0"), "Step")
        assertRejected(edited("Habits", "ID", "1", "Step", "${TrackerRepository.MAX_STEP + 1}"), "Step")
        assertRejected(edited("Habits", "ID", "1", "Name", "x".repeat(TrackerRepository.NAME_MAX_LENGTH + 1)), "Name")
        assertRejected(edited("TargetHistory", "ID", "1", "New target", "5000"), "New target")
    }

    @Test
    fun `accepts the extreme targets the app itself can reach`() {
        val midnight = BackupCodec.fromSheets(edited("Habits", "ID", "2", "Current target", "0"), today)
        assertEquals(0, midnight.habits.first { it.id == 2L }.currentTarget)
        val fullDay = BackupCodec.fromSheets(edited("Habits", "ID", "1", "Current target", "1440"), today)
        assertEquals(1440, fullDay.habits.first { it.id == 1L }.currentTarget)
        // Target history can hold either kind of target: a habit's type may have changed since.
        val anyType = BackupCodec.fromSheets(edited("TargetHistory", "ID", "1", "Old target", "1440"), today)
        assertEquals(1440, anyType.targetChanges.single().oldTarget)
    }

    @Test
    fun `rejects dates outside four-digit years`() {
        for (bad in listOf("-999999999-01-01", "+10000-01-01", "0001-01-01", "1899-12-31")) {
            assertRejected(edited("Habits", "ID", "1", "Created on", bad), "Created on")
            assertRejected(edited("Entries", "Date", d0.toString(), "Date", bad), "Date")
        }
        assertNull(BackupCodec.parseDate("-999999999-01-01"))
        assertEquals(LocalDate.of(1900, 1, 1), BackupCodec.parseDate("1900-01-01"))
    }

    @Test
    fun `rejects entries after tomorrow but allows one day of time-zone slack`() {
        assertRejected(edited("Entries", "Date", d0.toString(), "Date", today.plusDays(2).toString()), "Date")
        assertRejected(edited("Habits", "ID", "1", "Created on", today.plusDays(2).toString()), "Created on")
        val tomorrow = BackupCodec.fromSheets(edited("Entries", "Date", d0.toString(), "Date", today.plusDays(1).toString()), today)
        assertEquals(today.plusDays(1), tomorrow.lastDate)
    }

    @Test
    fun `ignores settings dates past their limit instead of disabling cycle reviews`() {
        val farMarker = BackupCodec.fromSheets(edited("Settings", "Key", "last_evaluated_cycle_end", "Value", "9999-12-31"), today)
        assertNull(farMarker.lastEvaluatedCycleEnd)
        val farCycle = BackupCodec.fromSheets(edited("Settings", "Key", "cycle_start", "Value", today.plusDays(14).toString()), today)
        assertNull(farCycle.settings!!.cycleStart)
        // The cycle start date picker allows up to 13 days ahead.
        val nearCycle = BackupCodec.fromSheets(edited("Settings", "Key", "cycle_start", "Value", today.plusDays(13).toString()), today)
        assertEquals(today.plusDays(13), nearCycle.settings!!.cycleStart)
    }

    @Test
    fun `csv export also neutralises a leading tab or carriage return`() {
        for (remark in listOf("\t=1+1", "\r=1+1")) {
            val csv = BackupCodec.toCsv(snapshot.copy(days = listOf(DayRecord(d0, remark)), logs = listOf(HabitLog(d0, 1, true))))
            assertTrue(csv.contains("'$remark"))
        }
    }
}
