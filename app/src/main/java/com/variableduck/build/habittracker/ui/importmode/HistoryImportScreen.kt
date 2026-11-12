package com.variableduck.build.habittracker.ui.importmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.DayView
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.ui.common.DatePickerDialogFor
import com.variableduck.build.habittracker.ui.common.ScreenHeader
import com.variableduck.build.habittracker.ui.common.TimePickerDialog
import com.variableduck.build.habittracker.ui.common.rememberFormatter
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

data class ImportUiState(
    val date: LocalDate,
    val today: LocalDate,
    val day: DayView? = null,
    val remark: String = "",
)

/** History import: edit any past date. Every write passes importMode = true. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryImportViewModel @Inject constructor(
    private val tracker: TrackerRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val selected = MutableStateFlow(time.today().minusDays(2))
    private val remarkDrafts = MutableStateFlow<Map<LocalDate, String>>(emptyMap())

    val state: StateFlow<ImportUiState> = selected
        .flatMapLatest { date ->
            combine(tracker.observeDay(date, fallbackToActive = true), remarkDrafts) { day, drafts ->
                ImportUiState(date = date, today = time.today(), day = day, remark = drafts[date] ?: day.remark)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ImportUiState(date = selected.value, today = time.today()),
        )

    fun select(date: LocalDate) = selected.update { if (date.isAfter(time.today())) it else date }

    fun shift(days: Long) = select(selected.value.plusDays(days))

    fun setDone(habitId: Long, done: Boolean) {
        val date = selected.value
        viewModelScope.launch { tracker.setDone(date, habitId, done, importMode = true) }
    }

    fun setActualTime(habitId: Long, minute: Int?) {
        val date = selected.value
        viewModelScope.launch { tracker.setActualTime(date, habitId, minute, importMode = true) }
    }

    fun setRemark(text: String) {
        val date = selected.value
        val trimmed = text.take(TrackerRepository.REMARK_MAX_LENGTH)
        remarkDrafts.update { it + (date to trimmed) }
        viewModelScope.launch { tracker.setRemark(date, trimmed, importMode = true) }
    }
}

@Composable
fun HistoryImportScreen(onClose: () -> Unit, viewModel: HistoryImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatter = rememberFormatter()
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var timeFor by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("History import", onBack = onClose)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("HISTORY IMPORT MODE", fontWeight = FontWeight.Bold)
                    Text(
                        "Changes here bypass the today-and-yesterday edit window. " +
                            "Use it only to enter past data.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.shift(-1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
            }
            FilledTonalButton(
                onClick = { pickDate = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) { Text(formatter.dayHeader(state.date)) }
            IconButton(onClick = { viewModel.shift(1) }, enabled = state.date.isBefore(state.today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
            }
        }

        val day = state.day
        if (day != null) {
            day.entries.forEach { entry ->
                val habit = entry.habit
                Column {
                    ListItem(
                        headlineContent = { Text(habit.name) },
                        supportingContent = { Text(formatter.target(habit.type, habit.currentTarget)) },
                        trailingContent = {
                            Switch(checked = entry.done, onCheckedChange = { viewModel.setDone(habit.id, it) })
                        },
                    )
                    if (habit.type == HabitType.DEADLINE) {
                        TextButton(onClick = { timeFor = habit.id }, modifier = Modifier.padding(start = 8.dp)) {
                            Text(entry.actualTime?.let { "Actual time: ${formatter.clock(it)}" } ?: "Set actual time")
                        }
                    }
                }
            }
            Text(
                if (day.reward.unlocked) "Reward unlocked on this day." else "Reward locked on this day.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.remark,
                onValueChange = viewModel::setRemark,
                label = { Text("Remark") },
                singleLine = true,
                supportingText = { Text("${state.remark.length}/${TrackerRepository.REMARK_MAX_LENGTH}") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (pickDate) {
        DatePickerDialogFor(
            initial = state.date,
            max = state.today,
            onDismiss = { pickDate = false },
            onConfirm = {
                viewModel.select(it)
                pickDate = false
            },
        )
    }
    timeFor?.let { habitId ->
        val entry = state.day?.entries?.firstOrNull { it.habit.id == habitId }
        TimePickerDialog(
            title = "Actual time",
            initialMinuteOfDay = entry?.actualTime ?: entry?.habit?.currentTarget ?: (7 * 60),
            onDismiss = { timeFor = null },
            onConfirm = {
                viewModel.setActualTime(habitId, it)
                timeFor = null
            },
            onClear = {
                viewModel.setActualTime(habitId, null)
                timeFor = null
            },
        )
    }
}
