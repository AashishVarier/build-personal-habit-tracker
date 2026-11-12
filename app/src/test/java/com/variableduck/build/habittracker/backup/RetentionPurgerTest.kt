package com.variableduck.build.habittracker.backup

import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.testing.habit
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The purge rule with fakes: export first; if the export fails, delete nothing. */
class RetentionPurgerTest {
    private val cutoff = LocalDate.of(2026, 1, 1)

    private class FakeStore(var days: List<DayRecord>) : PurgeStore {
        var deleteCalls = 0

        override suspend fun countDaysBefore(cutoff: LocalDate) = days.count { it.date.isBefore(cutoff) }

        override suspend fun snapshotBefore(cutoff: LocalDate): BackupSnapshot {
            val old = days.filter { it.date.isBefore(cutoff) }
            return BackupSnapshot(
                habits = listOf(habit(id = 1, name = "Walk")),
                days = old,
                logs = old.map { HabitLog(it.date, 1, done = true) },
                targetChanges = emptyList(),
                settings = null,
                lastEvaluatedCycleEnd = null,
                exportedOn = null,
            )
        }

        override suspend fun deleteBefore(cutoff: LocalDate): PurgeCounts {
            deleteCalls++
            val before = days.size
            days = days.filterNot { it.date.isBefore(cutoff) }
            return PurgeCounts(before - days.size, 0, 0)
        }
    }

    private val allDays = (1..40L).map { DayRecord(cutoff.minusDays(30).plusDays(it)) }

    @Test
    fun `refuses to delete anything when the archive export fails`() = runTest {
        val store = FakeStore(allDays)
        val result = RetentionPurger(store) { _, _ -> throw IOException("Backup folder unavailable") }.purge(cutoff)

        assertTrue(result is PurgeResult.ExportFailed)
        assertEquals("Backup folder unavailable", (result as PurgeResult.ExportFailed).reason)
        assertEquals(0, store.deleteCalls)
        assertEquals(allDays, store.days)
    }

    @Test
    fun `exports to an archive named after the date range, then deletes`() = runTest {
        val store = FakeStore(allDays)
        var exportedName: String? = null
        var exportedDays = 0
        val result = RetentionPurger(store) { name, snapshot ->
            exportedName = name
            exportedDays = snapshot.days.size
            name
        }.purge(cutoff)

        val oldDays = allDays.filter { it.date.isBefore(cutoff) }
        assertEquals("archive_${oldDays.first().date}_${oldDays.last().date}.xlsx", exportedName)
        assertEquals(oldDays.size, exportedDays)
        assertTrue(result is PurgeResult.Deleted)
        assertEquals(oldDays.size, (result as PurgeResult.Deleted).counts.days)
        assertTrue(store.days.none { it.date.isBefore(cutoff) })
    }

    @Test
    fun `does nothing when no data is old enough`() = runTest {
        val store = FakeStore(allDays.filterNot { it.date.isBefore(cutoff) })
        var exported = false
        val result = RetentionPurger(store) { name, _ ->
            exported = true
            name
        }.purge(cutoff)
        assertEquals(PurgeResult.NothingToDelete, result)
        assertEquals(false, exported)
        assertEquals(0, store.deleteCalls)
    }
}
