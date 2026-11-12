package com.variableduck.build.habittracker.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.HabitEntry
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.domain.Graduation
import com.variableduck.build.habittracker.domain.RewardStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DaySelection { TODAY, YESTERDAY }

data class TileUi(
    val entry: HabitEntry,
    val graduation: Graduation.Progress,
)

data class TodayUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.MIN,
    val selection: DaySelection = DaySelection.TODAY,
    val tiles: List<TileUi> = emptyList(),
    val reward: RewardStatus = RewardStatus.NO_HABITS,
    val remark: String = "",
    val goal: String = "",
    val cycleNumber: Long? = null,
    val dayInCycle: Int? = null,
    val pendingReview: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val tracker: TrackerRepository,
    settings: SettingsRepository,
    cycles: CycleRepository,
    time: TimeProvider,
) : ViewModel() {
    private val selection = MutableStateFlow(DaySelection.TODAY)

    /** What the user is typing; shown instead of the stored remark so the cursor never jumps. */
    private val remarkDrafts = MutableStateFlow<Map<LocalDate, String>>(emptyMap())

    private val today = time.todayFlow().onEach { tracker.fillMissingDays(it) }

    val state: StateFlow<TodayUiState> = combine(today, selection) { day, selected -> day to selected }
        .flatMapLatest { (todayDate, selected) ->
            val date = if (selected == DaySelection.TODAY) todayDate else todayDate.minusDays(1)
            combine(
                tracker.observeDay(date),
                tracker.observeGraduation(todayDate),
                settings.settings,
                cycles.observePendingReviews(),
                remarkDrafts,
            ) { day, graduation, appSettings, reviews, drafts ->
                val anchor = appSettings.cycleStart
                val cycle = anchor?.let { CycleCalendar.cycleFor(it, date) }
                TodayUiState(
                    loading = false,
                    date = date,
                    selection = selected,
                    tiles = day.entries.map { TileUi(it, graduation[it.habit.id] ?: Graduation.Progress(0)) },
                    reward = day.reward,
                    remark = drafts[date] ?: day.remark,
                    goal = appSettings.overallGoal,
                    cycleNumber = cycle?.number?.takeIf { it >= 1 },
                    dayInCycle = if (anchor != null && cycle != null && cycle.index >= 0) {
                        CycleCalendar.dayInCycle(anchor, date)
                    } else {
                        null
                    },
                    pendingReview = reviews.isNotEmpty(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun select(day: DaySelection) = selection.update { day }

    fun toggle(habitId: Long) {
        val date = state.value.date
        viewModelScope.launch { tracker.toggle(date, habitId) }
    }

    fun setActualTime(habitId: Long, minuteOfDay: Int?) {
        val date = state.value.date
        viewModelScope.launch { tracker.setActualTime(date, habitId, minuteOfDay) }
    }

    fun onRemarkChange(text: String) {
        val date = state.value.date
        val trimmed = text.take(TrackerRepository.REMARK_MAX_LENGTH)
        remarkDrafts.update { it + (date to trimmed) }
        viewModelScope.launch { tracker.setRemark(date, trimmed) }
    }
}
