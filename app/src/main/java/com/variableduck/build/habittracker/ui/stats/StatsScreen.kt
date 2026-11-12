package com.variableduck.build.habittracker.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.domain.Graduation
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.ui.common.ScreenHeader
import com.variableduck.build.habittracker.ui.common.SectionTitle
import com.variableduck.build.habittracker.ui.common.rememberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Completion over the last 14 days. [days] counts only days on which the habit existed. */
data class HabitStats(
    val habit: Habit,
    val yes: Int,
    val days: Int,
    val graduation: Graduation.Progress,
    val changes: List<TargetChange>,
) {
    val percent: Int get() = if (days == 0) 0 else (yes * 100f / days).roundToInt()
}

data class StatsUiState(val loading: Boolean = true, val habits: List<HabitStats> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(tracker: TrackerRepository, time: TimeProvider) : ViewModel() {
    val state: StateFlow<StatsUiState> = time.todayFlow()
        .flatMapLatest { today ->
            val from = today.minusDays(WINDOW - 1L)
            combine(
                tracker.observeActiveHabits(),
                tracker.observeLogs(from, today),
                tracker.observeGraduation(today),
                tracker.observeTargetChanges(),
            ) { habits, logs, graduation, changes ->
                val logsByHabit = logs.groupBy { it.habitId }
                StatsUiState(
                    loading = false,
                    habits = habits.map { habit ->
                        val habitLogs = logsByHabit[habit.id].orEmpty()
                        HabitStats(
                            habit = habit,
                            yes = habitLogs.count { it.done },
                            days = habitLogs.size,
                            graduation = graduation[habit.id] ?: Graduation.Progress(0),
                            changes = changes.filter { it.habitId == habit.id },
                        )
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private companion object {
        const val WINDOW = 14
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatter = rememberFormatter()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Stats", "Completion, targets and graduation. No streaks.")

        SectionTitle("Last 14 days")
        state.habits.forEach { stats ->
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(stats.habit.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${stats.percent}%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { stats.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${stats.yes} of ${stats.days} days YES",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("Graduation")
        state.habits.forEach { stats ->
            val g = stats.graduation
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(stats.habit.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(g.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = { (g.yes.toFloat() / Graduation.REQUIRED_YES).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (g.graduated) {
                        "Graduated. It can be replaced on the Habits tab."
                    } else {
                        "${g.remaining} more YES in the last ${Graduation.WINDOW_DAYS} days to graduate."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("Target changes")
        state.habits.filter { it.habit.type.hasTarget }.forEach { stats ->
            val habit = stats.habit
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(habit.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TimelineLine(
                        habit.createdOn,
                        "Started at ${formatter.target(habit.type, habit.startingTarget)}",
                        formatter::date,
                    )
                    stats.changes.forEach { change ->
                        val what = when (change.reason) {
                            TargetChangeReason.LEVEL_UP -> "Levelled up"
                            TargetChangeReason.STEP_DOWN -> "Stepped down"
                            TargetChangeReason.EDIT -> "Edited"
                        }
                        TimelineLine(
                            change.date,
                            "$what: ${formatter.change(habit.type, change.oldTarget, change.newTarget)}",
                            formatter::date,
                        )
                    }
                    Text(
                        "Now: ${formatter.target(habit.type, habit.currentTarget)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineLine(date: LocalDate, text: String, formatDate: (LocalDate) -> String) {
    Row {
        Text(
            formatDate(date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
