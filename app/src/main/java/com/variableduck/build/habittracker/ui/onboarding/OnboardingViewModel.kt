package com.variableduck.build.habittracker.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.backup.BackupFolder
import com.variableduck.build.habittracker.backup.BackupManager
import com.variableduck.build.habittracker.backup.RestorePreview
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.HabitResult
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.AppSettings
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.reminders.AlarmScheduler
import com.variableduck.build.habittracker.ui.common.HabitFormState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DraftItem(val key: Int, val form: HabitFormState)

data class Suggestion(val label: String, val form: HabitFormState)

data class OnboardingUiState(
    val step: Int = 0,
    val habits: List<DraftItem> = emptyList(),
    val cycleStart: LocalDate,
    val today: LocalDate,
    val reminderMinute: Int = AppSettings.DEFAULT_REMINDER_MINUTE,
    val goal: String = "",
    val backupUri: String? = null,
    val backupName: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val restorePreview: RestorePreview? = null,
) {
    val canAddHabit: Boolean get() = habits.size < TrackerRepository.MAX_ACTIVE_HABITS
    val habitsValid: Boolean get() = habits.isNotEmpty() && habits.all { it.form.isValid }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val tracker: TrackerRepository,
    private val settings: SettingsRepository,
    private val backupFolder: BackupFolder,
    private val backups: BackupManager,
    private val alarms: AlarmScheduler,
    time: TimeProvider,
) : ViewModel() {
    private val today = time.today()
    private var nextKey = 0

    private val _state = MutableStateFlow(OnboardingUiState(cycleStart = today, today = today))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Generic examples only; nothing is pre-selected. */
    val suggestions = listOf(
        Suggestion("Wake up by 7:30 AM", HabitFormState(name = "Wake up", type = HabitType.DEADLINE, deadlineTarget = 7 * 60 + 30)),
        Suggestion("10 min walk", HabitFormState(name = "Walk", type = HabitType.DURATION, durationTarget = 10)),
        Suggestion("5 min exercise", HabitFormState(name = "Exercise", type = HabitType.DURATION, durationTarget = 5)),
        Suggestion("5 min focused work", HabitFormState(name = "Focused work", type = HabitType.DURATION, durationTarget = 5)),
    )

    fun addHabit(form: HabitFormState = HabitFormState()) = _state.update {
        if (!it.canAddHabit) it else it.copy(habits = it.habits + DraftItem(nextKey++, form), error = null)
    }

    fun updateHabit(key: Int, form: HabitFormState) = _state.update { s ->
        s.copy(habits = s.habits.map { if (it.key == key) it.copy(form = form) else it }, error = null)
    }

    fun removeHabit(key: Int) = _state.update { s -> s.copy(habits = s.habits.filterNot { it.key == key }) }

    fun setCycleStart(date: LocalDate) = _state.update { it.copy(cycleStart = date) }

    fun setReminder(minute: Int) = _state.update { it.copy(reminderMinute = minute) }

    fun setGoal(goal: String) = _state.update { it.copy(goal = goal.take(AppSettings.GOAL_MAX_LENGTH)) }

    fun setBackupFolder(uri: Uri) {
        runCatching { backupFolder.persistAccess(uri) }
            .onSuccess {
                _state.update { it.copy(backupUri = uri.toString(), backupName = backupFolder.displayName(uri) ?: "Selected folder") }
            }
            .onFailure { e -> _state.update { it.copy(error = "Could not use that folder: ${e.message}") } }
    }

    fun next() = _state.update { s ->
        if (s.step == 0 && !s.habitsValid) s.copy(error = "Add 1 to 4 habits, each with a name.") else s.copy(step = s.step + 1, error = null)
    }

    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0), error = null) }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (!s.habitsValid) {
            _state.update { it.copy(step = 0, error = "Add 1 to 4 habits, each with a name.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // Habits are tracked from today. If the cycle started earlier, that first partial
            // cycle is not used to change their targets.
            when (val result = tracker.createInitialHabits(s.habits.map { it.form.toDraft() }, today)) {
                is HabitResult.Error -> _state.update { it.copy(saving = false, error = result.message) }
                is HabitResult.Ok -> {
                    settings.completeOnboarding(s.cycleStart, s.reminderMinute, s.goal, s.backupUri)
                    alarms.scheduleAll()
                    onDone()
                }
            }
        }
    }

    fun previewRestore(uri: Uri) {
        viewModelScope.launch {
            try {
                val preview = backups.preview(uri)
                _state.update { it.copy(restorePreview = preview, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Can't restore this file: ${e.message}") }
            }
        }
    }

    fun cancelRestore() = _state.update { it.copy(restorePreview = null) }

    fun confirmRestore(onDone: () -> Unit) {
        val preview = _state.value.restorePreview ?: return
        _state.update { it.copy(saving = true, restorePreview = null) }
        viewModelScope.launch {
            try {
                backups.restore(preview)
                _state.value.backupUri?.let { settings.setBackupFolder(it) }
                alarms.scheduleAll()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Restore failed: ${e.message}") }
            }
        }
    }
}
