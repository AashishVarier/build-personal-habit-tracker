package com.variableduck.build.habittracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.model.CycleOutcome
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.model.LevelUpDecision
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.testing.FakeTimeProvider
import com.variableduck.build.habittracker.testing.TestDatabase
import com.variableduck.build.habittracker.testing.testSettings
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CycleRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val anchor = LocalDate.of(2026, 3, 2)
    private lateinit var db: AppDatabase
    private lateinit var time: FakeTimeProvider
    private lateinit var tracker: TrackerRepository
    private lateinit var settings: SettingsRepository
    private lateinit var cycles: CycleRepository

    @Before
    fun setUp() = runTest {
        db = TestDatabase.create()
        time = FakeTimeProvider(anchor)
        tracker = TrackerRepository(db, time) {}
        settings = testSettings(tmp.root)
        settings.completeOnboarding(anchor, 21 * 60, "", null)
        cycles = CycleRepository(db, settings, time) {}
        tracker.createInitialHabits(
            listOf(
                HabitDraft("Walk", HabitType.DURATION, 10, 5, false),
                HabitDraft("Exercise", HabitType.DURATION, 5, 5, false),
                HabitDraft("Wake up", HabitType.DEADLINE, 7 * 60 + 30, 15, false),
                HabitDraft("Habit A", HabitType.BOOLEAN, null, null, false),
            ),
            anchor,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun id(name: String) = db.trackerDao().activeHabits().first { it.name == name }.id

    private suspend fun markYes(name: String, days: Int) {
        val habitId = id(name)
        for (offset in 0 until days.toLong()) tracker.setDone(anchor.plusDays(offset), habitId, true, importMode = true)
    }

    @Test
    fun `end of cycle prompts level up, steps down automatically, and leaves the middle alone`() = runTest {
        // Exercise starts above its starting target so a step-down is visible.
        val exercise = db.trackerDao().habit(id("Exercise"))!!
        db.trackerDao().updateHabit(exercise.copy(currentTarget = 15))

        time.date = anchor.plusDays(13)
        tracker.fillMissingDays()
        markYes("Walk", 11)
        markYes("Exercise", 3)
        markYes("Wake up", 2)

        // Still inside the cycle: nothing happens.
        assertEquals(0, cycles.evaluateEndedCycles())

        time.date = anchor.plusDays(14)
        assertEquals(1, cycles.evaluateEndedCycles())

        val review = cycles.observePendingReviews().first().single()
        val items = review.items.associateBy { it.habitId }
        assertEquals(3, items.size) // BOOLEAN habits have no target and are skipped.

        val walkItem = items.getValue(id("Walk"))
        assertEquals(CycleOutcome.LEVEL_UP_PROMPT, walkItem.outcome)
        assertEquals(LevelUpDecision.PENDING, walkItem.decision)
        assertEquals(10, db.trackerDao().habit(id("Walk"))!!.currentTarget) // not without confirmation

        assertEquals(CycleOutcome.STEPPED_DOWN, items.getValue(id("Exercise")).outcome)
        assertEquals(10, db.trackerDao().habit(id("Exercise"))!!.currentTarget)

        assertEquals(CycleOutcome.AT_STARTING_TARGET, items.getValue(id("Wake up")).outcome)
        assertEquals(7 * 60 + 30, db.trackerDao().habit(id("Wake up"))!!.currentTarget)

        val changes = db.trackerDao().observeTargetChanges().first()
        assertEquals(listOf(TargetChangeReason.STEP_DOWN), changes.map { it.reason })

        // Evaluating again does not repeat anything.
        assertEquals(0, cycles.evaluateEndedCycles())
    }

    @Test
    fun `level up happens only after Yes, and Not yet keeps the target`() = runTest {
        time.date = anchor.plusDays(13)
        tracker.fillMissingDays()
        markYes("Walk", 12)
        markYes("Exercise", 10)
        time.date = anchor.plusDays(14)
        cycles.evaluateEndedCycles()
        val reviewId = cycles.observePendingReviews().first().single().review.id

        assertTrue(!cycles.acknowledge(reviewId)) // unanswered prompts block closing

        cycles.decideLevelUp(reviewId, id("Walk"), accept = true)
        cycles.decideLevelUp(reviewId, id("Exercise"), accept = false)
        assertEquals(15, db.trackerDao().habit(id("Walk"))!!.currentTarget)
        assertEquals(5, db.trackerDao().habit(id("Exercise"))!!.currentTarget)

        assertTrue(cycles.acknowledge(reviewId))
        assertTrue(cycles.observePendingReviews().first().isEmpty())
        val changes = db.trackerDao().observeTargetChanges().first()
        assertEquals(listOf(TargetChangeReason.LEVEL_UP), changes.map { it.reason })
        assertEquals(10 to 15, changes.single().oldTarget to changes.single().newTarget)
    }

    @Test
    fun `changing the cycle start never re-judges old data`() = runTest {
        time.date = anchor.plusDays(40)
        tracker.fillMissingDays()
        // Everything is NO, so evaluating would step down; but first, move the anchor back.
        val newAnchor = anchor.minusDays(10)
        settings.setCycleStart(newAnchor)
        cycles.onCycleStartChanged(newAnchor)
        assertEquals(0, cycles.evaluateEndedCycles())
    }
}
