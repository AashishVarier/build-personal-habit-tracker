package com.variableduck.build.habittracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.HabitResult
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.testing.FakeTimeProvider
import com.variableduck.build.habittracker.testing.TestDatabase
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerRepositoryTest {
    private val start = LocalDate.of(2026, 3, 2)
    private lateinit var db: AppDatabase
    private lateinit var time: FakeTimeProvider
    private lateinit var tracker: TrackerRepository

    @Before
    fun setUp() {
        db = TestDatabase.create()
        time = FakeTimeProvider(start)
        tracker = TrackerRepository(db, time) {}
    }

    @After
    fun tearDown() = db.close()

    private suspend fun createHabits(vararg drafts: HabitDraft) {
        assertTrue(tracker.createInitialHabits(drafts.toList(), start) is HabitResult.Ok)
    }

    private val walk = HabitDraft("Walk", HabitType.DURATION, 10, 5, mandatory = false)
    private val habitA = HabitDraft("Habit A", HabitType.BOOLEAN, null, null, mandatory = true)

    @Test
    fun `missed days are filled with NO on the next launch`() = runTest {
        createHabits(walk, habitA)
        val dao = db.trackerDao()
        assertEquals(start, dao.lastDayDate())

        time.date = start.plusDays(5)
        assertEquals(5, tracker.fillMissingDays())

        for (offset in 0..5L) {
            val date = start.plusDays(offset)
            val logs = dao.logsOn(date)
            assertEquals("entries on $date", 2, logs.size)
            assertTrue("all NO on $date", logs.none { it.done })
            assertNotNullDay(date)
        }
        // Running again changes nothing.
        assertEquals(0, tracker.fillMissingDays())
    }

    private suspend fun assertNotNullDay(date: LocalDate) {
        assertTrue("day row for $date", db.trackerDao().day(date) != null)
    }

    @Test
    fun `only today and yesterday can be edited outside import mode`() = runTest {
        createHabits(walk)
        time.date = start.plusDays(3)
        tracker.fillMissingDays()
        val id = db.trackerDao().activeHabits().single().id

        assertTrue(tracker.setDone(time.date, id, true))
        assertTrue(tracker.setDone(time.date.minusDays(1), id, true))
        assertFalse(tracker.setDone(time.date.minusDays(2), id, true))
        assertFalse(db.trackerDao().log(time.date.minusDays(2), id)!!.done)
        assertFalse(tracker.setRemark(time.date.minusDays(2), "Too late"))

        // History import mode bypasses the window.
        assertTrue(tracker.setDone(time.date.minusDays(2), id, true, importMode = true))
        assertTrue(db.trackerDao().log(time.date.minusDays(2), id)!!.done)
        assertFalse(tracker.setDone(time.date.plusDays(1), id, true, importMode = true))
    }

    @Test
    fun `toggling updates the stored reward for the day`() = runTest {
        createHabits(walk, habitA)
        val habits = db.trackerDao().activeHabits().associateBy { it.name }
        tracker.toggle(start, habits.getValue("Walk").id)
        assertFalse(db.trackerDao().day(start)!!.rewardUnlocked)
        tracker.toggle(start, habits.getValue("Habit A").id)
        assertTrue(db.trackerDao().day(start)!!.rewardUnlocked)
        val view = tracker.observeDay(start).first()
        assertTrue(view.reward.unlocked)
    }

    @Test
    fun `remarks are capped at 50 characters`() = runTest {
        createHabits(walk)
        tracker.setRemark(start, "x".repeat(80))
        assertEquals(50, db.trackerDao().day(start)!!.remark.length)
    }

    @Test
    fun `at most four active habits`() = runTest {
        createHabits(walk, habitA, walk.copy(name = "Habit C"), walk.copy(name = "Habit D"))
        val result = tracker.addHabit(walk.copy(name = "Habit E"))
        assertTrue(result is HabitResult.Error)
        assertEquals(4, db.trackerDao().activeHabits().size)
    }

    @Test
    fun `habits can be edited for 7 days, then are locked until graduation`() = runTest {
        createHabits(walk)
        val id = db.trackerDao().activeHabits().single().id
        time.date = start.plusDays(6)
        assertTrue(tracker.updateHabitDetails(id, walk.copy(name = "Long walk", target = 20)) is HabitResult.Ok)
        val edited = db.trackerDao().habit(id)!!
        assertEquals("Long walk", edited.name)
        assertEquals(20, edited.currentTarget)
        assertEquals(20, edited.startingTarget)

        time.date = start.plusDays(7)
        assertTrue(tracker.updateHabitDetails(id, walk.copy(name = "Renamed")) is HabitResult.Error)
        assertEquals("Long walk", db.trackerDao().habit(id)!!.name)
    }

    @Test
    fun `a habit can only be replaced after 26 YES in the last 30 days`() = runTest {
        createHabits(walk, habitA)
        val walkId = db.trackerDao().activeHabits().first { it.name == "Walk" }.id
        time.date = start.plusDays(29)
        tracker.fillMissingDays()
        for (offset in 0 until 25L) tracker.setDone(start.plusDays(offset), walkId, true, importMode = true)
        assertEquals(25, tracker.graduationProgress(walkId).yes)
        assertTrue(tracker.replaceHabit(walkId, HabitDraft("Habit B", HabitType.BOOLEAN, null, null, false)) is HabitResult.Error)

        tracker.setDone(time.date, walkId, true)
        assertEquals(26, tracker.graduationProgress(walkId).yes)
        val result = tracker.replaceHabit(walkId, HabitDraft("Habit B", HabitType.BOOLEAN, null, null, false))
        assertTrue(result is HabitResult.Ok)

        val old = db.trackerDao().habit(walkId)!!
        assertEquals(time.date, old.archivedOn)
        val active = db.trackerDao().activeHabits()
        assertEquals(listOf("Habit B", "Habit A").sorted(), active.map { it.name }.sorted())
        assertEquals(old.slot, active.first { it.name == "Habit B" }.slot)
        // History of the archived habit is kept (yesterday and before).
        assertTrue(db.trackerDao().log(time.date.minusDays(1), walkId) != null)
        assertNull(db.trackerDao().log(time.date, walkId))
    }
}
