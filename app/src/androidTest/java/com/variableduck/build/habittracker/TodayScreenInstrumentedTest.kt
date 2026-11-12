package com.variableduck.build.habittracker

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.ui.theme.HabitTrackerTheme
import com.variableduck.build.habittracker.ui.today.TodayScreen
import com.variableduck.build.habittracker.ui.today.TodayViewModel
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device version of the Today screen test (the same flow also runs on the JVM with Robolectric
 * in `src/test`). Run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class TodayScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 3, 10)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var settingsDir: File
    private lateinit var viewModel: TodayViewModel
    private var walkId = 0L

    private val fixedTime = object : TimeProvider {
        override fun today(): LocalDate = today
        override fun now(): LocalDateTime = today.atTime(12, 0)
        override fun todayFlow(): Flow<LocalDate> = flowOf(today)
    }

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settingsDir = File(context.cacheDir, "ui-test-${System.nanoTime()}").apply { mkdirs() }
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(produceFile = { File(settingsDir, "test.preferences_pb") }),
        )
        settings.completeOnboarding(today, 21 * 60, "", null)
        val tracker = TrackerRepository(db, fixedTime) {}
        tracker.createInitialHabits(listOf(HabitDraft("Walk", HabitType.DURATION, 10, 5, false)), today)
        walkId = db.trackerDao().activeHabits().single().id
        viewModel = TodayViewModel(tracker, settings, CycleRepository(db, settings, fixedTime) {}, fixedTime)
    }

    @After
    fun tearDown() {
        db.close()
        settingsDir.deleteRecursively()
    }

    @Test
    fun tappingTileLogsYes() {
        compose.setContent { HabitTrackerTheme { TodayScreen(onOpenReview = {}, viewModel = viewModel) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("habit-tile-$walkId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("habit-tile-$walkId").assertIsOff().performClick()
        compose.waitUntil(10_000) { runBlocking { db.trackerDao().log(today, walkId)?.done == true } }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("habit-tile-$walkId").assertIsOn() }.isSuccess
        }
    }
}
