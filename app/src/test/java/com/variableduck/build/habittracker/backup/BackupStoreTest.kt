package com.variableduck.build.habittracker.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.variableduck.build.habittracker.backup.xlsx.XlsxReader
import com.variableduck.build.habittracker.backup.xlsx.XlsxWriter
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.domain.Graduation
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.testing.FakeTimeProvider
import com.variableduck.build.habittracker.testing.TestDatabase
import com.variableduck.build.habittracker.testing.testSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Retention purge and export -> restore against a real (in-memory) Room database. */
@RunWith(AndroidJUnit4::class)
class BackupStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val start = LocalDate.of(2025, 9, 1)
    private val today = start.plusDays(250)
    private lateinit var db: AppDatabase
    private lateinit var time: FakeTimeProvider
    private lateinit var tracker: TrackerRepository
    private lateinit var store: BackupStore

    @Before
    fun setUp() = runTest {
        db = TestDatabase.create()
        time = FakeTimeProvider(start)
        tracker = TrackerRepository(db, time) {}
        store = BackupStore(db)
        tracker.createInitialHabits(
            listOf(
                HabitDraft("Walk", HabitType.DURATION, 10, 5, false),
                HabitDraft("Habit A", HabitType.BOOLEAN, null, null, true),
                HabitDraft("Habit B", HabitType.DEADLINE, 7 * 60, 15, false),
            ),
            start,
        )
        // An old habit that was replaced long ago: it only has old entries.
        val habitB = db.trackerDao().activeHabits().first { it.name == "Habit B" }
        time.date = start.plusDays(40)
        tracker.fillMissingDays()
        db.trackerDao().updateHabit(habitB.copy(archivedOn = time.date))
        db.trackerDao().deleteLogsFrom(habitB.id, time.date)
        tracker.addHabit(HabitDraft("Habit C", HabitType.BOOLEAN, null, null, false))

        time.date = today
        tracker.fillMissingDays()
        val walk = db.trackerDao().activeHabits().first { it.name == "Walk" }
        var date = start
        while (!date.isAfter(today)) {
            if (date.dayOfMonth % 4 != 0) tracker.setDone(date, walk.id, true, importMode = true)
            date = date.plusDays(1)
        }
        tracker.setRemark(today, "Felt good", importMode = true)
        db.trackerDao().insertTargetChange(TargetChange(0, walk.id, start.plusDays(14), 10, 15, TargetChangeReason.LEVEL_UP))
        db.trackerDao().insertTargetChange(TargetChange(0, walk.id, today.minusDays(10), 15, 20, TargetChangeReason.LEVEL_UP))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `purge deletes old entries, old target changes and empty archived habits only`() = runTest {
        val cutoff = today.minusMonths(6)
        val walkId = db.trackerDao().activeHabits().first { it.name == "Walk" }.id
        val graduationBefore = tracker.graduationProgress(walkId, today)
        val cycle = CycleCalendar.cycleFor(start, today)
        val cycleYesBefore = db.trackerDao().yesCount(walkId, cycle.start, cycle.end)
        val oldDays = store.countDaysBefore(cutoff)
        assertTrue(oldDays > 0)

        val counts = store.deleteBefore(cutoff)

        assertEquals(oldDays, counts.days)
        assertEquals(0, store.countDaysBefore(cutoff))
        assertEquals(1, counts.targetChanges)
        assertEquals(1, counts.archivedHabits)
        assertNull(db.trackerDao().allHabits().firstOrNull { it.name == "Habit B" })
        // Active habits survive even though their old entries are gone.
        assertEquals(setOf("Walk", "Habit A", "Habit C"), db.trackerDao().activeHabits().map { it.name }.toSet())
        // 14-day and 30-day calculations are unaffected.
        assertEquals(graduationBefore, tracker.graduationProgress(walkId, today))
        assertEquals(cycleYesBefore, db.trackerDao().yesCount(walkId, cycle.start, cycle.end))
        assertTrue(Graduation.windowStart(today).isAfter(cutoff))
    }

    @Test
    fun `purge keeps an archived habit that still has target changes newer than the cutoff`() = runTest {
        val cutoff = today.minusMonths(6)
        val habitB = db.trackerDao().allHabits().first { it.name == "Habit B" }
        // All of Habit B's entries are older than the cutoff, but this target change isn't, so it
        // is not in the archive. Deleting the habit would cascade to it and lose it.
        db.trackerDao().insertTargetChange(TargetChange(0, habitB.id, cutoff, 7 * 60, 6 * 60 + 45, TargetChangeReason.EDIT))

        val counts = store.deleteBefore(cutoff)

        assertEquals(0, counts.archivedHabits)
        assertNotNull(db.trackerDao().allHabits().firstOrNull { it.name == "Habit B" })
        assertEquals(1, db.backupDao().targetChanges().count { it.habitId == habitB.id })
    }

    @Test
    fun `purge with a failing archive export leaves the database untouched`() = runTest {
        val cutoff = today.minusMonths(6)
        val before = store.snapshot(null, null)
        val result = RetentionPurger(store) { _, _ -> throw IOException("No backup folder") }.purge(cutoff)
        assertTrue(result is PurgeResult.ExportFailed)
        assertEquals(before, store.snapshot(null, null))
    }

    @Test
    fun `xlsx export then restore into an empty database gives identical data`() = runTest {
        val settings = testSettings(tmp.newFolder())
        settings.completeOnboarding(start, 20 * 60 + 30, "Generic goal", null)
        val original = store.snapshot(settings.portable(), today)

        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(BackupCodec.toSheets(original), it) }.toByteArray()
        val parsed = BackupCodec.fromSheets(XlsxReader.read(ByteArrayInputStream(bytes)), today)

        val target = TestDatabase.create()
        try {
            val targetStore = BackupStore(target)
            targetStore.replaceAll(parsed, parsed.lastEvaluatedCycleEnd, today)
            val restored = targetStore.snapshot(original.settings, today)
            assertEquals(original.habits, restored.habits)
            assertEquals(original.days, restored.days)
            assertEquals(original.logs, restored.logs)
            assertEquals(original.targetChanges, restored.targetChanges)
            assertEquals(original.settings, parsed.settings)
            assertNotNull(restored.days.firstOrNull { it.remark == "Felt good" })
        } finally {
            target.close()
        }
    }

    @Test
    fun `restore marks old cycles as evaluated`() = runTest {
        val snapshot = store.snapshot(null, today)
        val marker = CycleCalendar.lastEndedCycle(start, today)!!.end
        store.replaceAll(snapshot, marker, today)
        val settings = testSettings(tmp.newFolder())
        settings.completeOnboarding(start, 21 * 60, "", null)
        val cycles = CycleRepository(db, settings, time) {}
        assertEquals(0, cycles.evaluateEndedCycles(today))
    }
}
