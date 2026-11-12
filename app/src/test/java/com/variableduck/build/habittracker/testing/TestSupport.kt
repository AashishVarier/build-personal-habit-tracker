package com.variableduck.build.habittracker.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitType
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A clock that only moves when a test moves it. */
class FakeTimeProvider(start: LocalDate, private val clock: LocalTime = LocalTime.NOON) : TimeProvider {
    private val flow = MutableStateFlow(start)

    var date: LocalDate
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override fun today(): LocalDate = date

    override fun now(): LocalDateTime = date.atTime(clock)

    override fun todayFlow(): Flow<LocalDate> = flow
}

object TestDatabase {
    fun create(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).build()
}

fun testSettings(dir: File): SettingsRepository =
    SettingsRepository(PreferenceDataStoreFactory.create(produceFile = { File(dir, "test.preferences_pb") }))

/** Generic fixtures only. */
fun habit(
    id: Long = 1,
    name: String = "Habit A",
    type: HabitType = HabitType.DURATION,
    current: Int? = if (type == HabitType.BOOLEAN) null else 10,
    starting: Int? = current,
    step: Int? = type.defaultStep,
    mandatory: Boolean = false,
    createdOn: LocalDate = LocalDate.of(2026, 1, 1),
    slot: Int = 0,
    archivedOn: LocalDate? = null,
) = Habit(
    id = id,
    slot = slot,
    name = name,
    type = type,
    currentTarget = current,
    startingTarget = starting,
    step = step,
    mandatory = mandatory,
    createdOn = createdOn,
    archivedOn = archivedOn,
)
