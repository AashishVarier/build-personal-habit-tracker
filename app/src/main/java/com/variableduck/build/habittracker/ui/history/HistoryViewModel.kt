package com.variableduck.build.habittracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.domain.Cycle
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.isActiveOn
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HistoryUiState(
    val loading: Boolean = true,
    val anchor: LocalDate? = null,
    val firstIndex: Long = 0,
    val currentIndex: Long = 0,
    val today: LocalDate = LocalDate.MIN,
) {
    val pageCount: Int get() = (currentIndex - firstIndex + 1).toInt().coerceAtLeast(1)

    fun indexForPage(page: Int): Long = firstIndex + page
}

enum class CellState { YES, NO, NONE }

data class HistoryRow(
    val date: LocalDate,
    val cells: List<CellState>,
    val remark: String,
    val reward: Boolean,
    val future: Boolean,
)

data class CyclePage(
    val cycle: Cycle,
    val habits: List<Habit>,
    val rows: List<HistoryRow>,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val tracker: TrackerRepository,
    settings: SettingsRepository,
    private val time: TimeProvider,
) : ViewModel() {
    val state: StateFlow<HistoryUiState> = combine(
        settings.settings,
        tracker.observeFirstDayDate(),
        time.todayFlow(),
    ) { appSettings, firstDay, today ->
        val anchor = appSettings.cycleStart ?: firstDay ?: today
        val first = CycleCalendar.cycleFor(anchor, firstDay ?: today).index
        val current = CycleCalendar.cycleFor(anchor, today).index
        HistoryUiState(
            loading = false,
            anchor = anchor,
            firstIndex = minOf(first, current),
            currentIndex = current,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** Rows for one cycle: dates down, habits across. */
    fun cyclePage(anchor: LocalDate, index: Long): Flow<CyclePage> {
        val cycle = CycleCalendar.cycleAt(anchor, index)
        return combine(
            tracker.observeAllHabits(),
            tracker.observeDays(cycle.start, cycle.end),
            tracker.observeLogs(cycle.start, cycle.end),
        ) { habits, days, logs -> buildPage(cycle, habits, days, logs.groupBy { it.date }) }
    }

    private fun buildPage(
        cycle: Cycle,
        allHabits: List<Habit>,
        days: List<DayRecord>,
        logsByDate: Map<LocalDate, List<HabitLog>>,
    ): CyclePage {
        val loggedIds = logsByDate.values.flatten().map { it.habitId }.toSet()
        val habits = allHabits
            .filter { habit -> habit.id in loggedIds || cycle.days().any { habit.isActiveOn(it) } }
            .sortedWith(compareBy({ it.slot }, { it.createdOn }))
        val dayByDate = days.associateBy { it.date }
        val today = time.today()
        val rows = cycle.days().map { date ->
            val logs = logsByDate[date].orEmpty().associateBy { it.habitId }
            HistoryRow(
                date = date,
                cells = habits.map { habit ->
                    when (logs[habit.id]?.done) {
                        true -> CellState.YES
                        false -> CellState.NO
                        null -> CellState.NONE
                    }
                },
                remark = dayByDate[date]?.remark.orEmpty(),
                reward = dayByDate[date]?.rewardUnlocked == true,
                future = date.isAfter(today),
            )
        }
        return CyclePage(cycle, habits, rows)
    }
}
