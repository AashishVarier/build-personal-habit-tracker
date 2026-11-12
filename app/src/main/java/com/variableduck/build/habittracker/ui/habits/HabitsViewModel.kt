package com.variableduck.build.habittracker.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.HabitResult
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.domain.EditWindow
import com.variableduck.build.habittracker.domain.Graduation
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.isActive
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActiveHabitUi(
    val habit: Habit,
    val graduation: Graduation.Progress,
    val canEdit: Boolean,
    val editDaysLeft: Int,
)

data class HabitsUiState(
    val loading: Boolean = true,
    val active: List<ActiveHabitUi> = emptyList(),
    val archived: List<Habit> = emptyList(),
    val today: LocalDate = LocalDate.MIN,
    val message: String? = null,
) {
    val canAdd: Boolean get() = active.size < TrackerRepository.MAX_ACTIVE_HABITS
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val tracker: TrackerRepository,
    time: TimeProvider,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<HabitsUiState> = time.todayFlow()
        .flatMapLatest { today ->
            combine(tracker.observeAllHabits(), tracker.observeGraduation(today), message) { habits, graduation, msg ->
                HabitsUiState(
                    loading = false,
                    active = habits.filter { it.isActive() }.sortedBy { it.slot }.map { habit ->
                        val progress = graduation[habit.id] ?: Graduation.Progress(0)
                        val daysLeft = (EditWindow.HABIT_EDIT_DAYS - EditWindow.daysSinceCreated(habit, today))
                            .coerceAtLeast(0).toInt()
                        ActiveHabitUi(
                            habit = habit,
                            graduation = progress,
                            canEdit = EditWindow.canEditHabitDetails(habit, today, progress.graduated),
                            editDaysLeft = daysLeft,
                        )
                    },
                    archived = habits.filter { !it.isActive() }.sortedByDescending { it.archivedOn },
                    today = today,
                    message = msg,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState())

    fun add(draft: HabitDraft, onSuccess: () -> Unit) = run(onSuccess) { tracker.addHabit(draft) }

    fun edit(habitId: Long, draft: HabitDraft, onSuccess: () -> Unit) =
        run(onSuccess) { tracker.updateHabitDetails(habitId, draft) }

    fun replace(habitId: Long, draft: HabitDraft, onSuccess: () -> Unit) =
        run(onSuccess) { tracker.replaceHabit(habitId, draft) }

    fun setMandatory(habitId: Long, mandatory: Boolean) {
        viewModelScope.launch { tracker.setMandatory(habitId, mandatory) }
    }

    fun messageShown() = message.update { null }

    private fun run(onSuccess: () -> Unit, block: suspend () -> HabitResult) {
        viewModelScope.launch {
            when (val result = block()) {
                is HabitResult.Ok -> onSuccess()
                is HabitResult.Error -> message.update { result.message }
            }
        }
    }
}
