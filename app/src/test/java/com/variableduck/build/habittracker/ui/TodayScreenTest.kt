package com.variableduck.build.habittracker.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.testing.FakeTimeProvider
import com.variableduck.build.habittracker.testing.TestDatabase
import com.variableduck.build.habittracker.testing.testSettings
import com.variableduck.build.habittracker.ui.theme.HabitTrackerTheme
import com.variableduck.build.habittracker.ui.today.TodayScreen
import com.variableduck.build.habittracker.ui.today.TodayViewModel
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** UI test: logging a habit from the Today screen writes YES and updates the reward. */
@RunWith(AndroidJUnit4::class)
class TodayScreenTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 3, 10)
    private lateinit var db: AppDatabase
    private lateinit var viewModel: TodayViewModel
    private var walkId = 0L
    private var habitAId = 0L

    @Before
    fun setUp() = runBlocking {
        db = TestDatabase.create()
        val time = FakeTimeProvider(today)
        val tracker = TrackerRepository(db, time) {}
        val settings = testSettings(tmp.root)
        settings.completeOnboarding(today, 21 * 60, "Generic goal", null)
        tracker.createInitialHabits(
            listOf(
                HabitDraft("Walk", HabitType.DURATION, 10, 5, mandatory = false),
                HabitDraft("Habit A", HabitType.BOOLEAN, null, null, mandatory = true),
            ),
            today,
        )
        val habits = db.trackerDao().activeHabits().associateBy { it.name }
        walkId = habits.getValue("Walk").id
        habitAId = habits.getValue("Habit A").id
        viewModel = TodayViewModel(tracker, settings, CycleRepository(db, settings, time) {}, time)
    }

    @After
    fun tearDown() = db.close()

    private fun waitForTile(id: Long) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("habit-tile-$id").fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun tappingHabitTilesLogsYesAndUnlocksTheReward() {
        compose.setContent {
            HabitTrackerTheme(dynamicColor = false) {
                TodayScreen(onOpenReview = {}, viewModel = viewModel)
            }
        }
        waitForTile(walkId)
        compose.onNodeWithTag("habit-tile-$walkId").assertIsOff()
        waitForText("Walk still needed.")

        compose.onNodeWithTag("habit-tile-$walkId").performClick()
        compose.waitUntil(10_000) { runBlocking { db.trackerDao().log(today, walkId)?.done == true } }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("habit-tile-$walkId").assertIsOn() }.isSuccess
        }
        waitForText("Habit A still needed.")

        compose.onNodeWithTag("habit-tile-$habitAId").performClick()
        waitForText("Guilt-free reward unlocked")
        runBlocking { assertEquals(true, db.trackerDao().day(today)?.rewardUnlocked) }

        // Tapping again turns it back to NO.
        compose.onNodeWithTag("habit-tile-$walkId").performClick()
        compose.waitUntil(10_000) { runBlocking { db.trackerDao().log(today, walkId)?.done == false } }
    }
}
