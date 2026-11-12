@file:OptIn(ExperimentalMaterial3Api::class)

package com.variableduck.build.habittracker.ui.common

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.model.HabitType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** − value + with large touch targets. */
@Composable
fun NumberStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    display: (Int) -> String = { it.toString() },
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = enabled && value - step >= range.first,
            modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            display(value),
            modifier = Modifier.width(96.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = enabled && value + step <= range.last,
            modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
fun TimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun Long.utcMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
fun DatePickerDialogFor(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    min: LocalDate? = null,
    max: LocalDate? = null,
) {
    val selectable = remember(min, max) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = utcTimeMillis.utcMillisToDate()
                return (min == null || !date.isBefore(min)) && (max == null || !date.isAfter(max))
            }

            override fun isSelectableYear(year: Int): Boolean =
                (min == null || year >= min.year) && (max == null || year <= max.year)
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(it.utcMillisToDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/** Editable state of the habit form. */
data class HabitFormState(
    val name: String = "",
    val type: HabitType = HabitType.DURATION,
    val durationTarget: Int = 10,
    val deadlineTarget: Int = 7 * 60 + 30,
    val durationStep: Int = 5,
    val deadlineStep: Int = 15,
    val mandatory: Boolean = false,
) {
    fun toDraft(): HabitDraft = HabitDraft(
        name = name.trim(),
        type = type,
        target = when (type) {
            HabitType.DURATION -> durationTarget
            HabitType.DEADLINE -> deadlineTarget
            HabitType.BOOLEAN -> null
        },
        step = when (type) {
            HabitType.DURATION -> durationStep
            HabitType.DEADLINE -> deadlineStep
            HabitType.BOOLEAN -> null
        },
        mandatory = mandatory,
    )

    val isValid: Boolean get() = name.isNotBlank() && name.trim().length <= TrackerRepository.NAME_MAX_LENGTH

    companion object {
        fun fromDraft(draft: HabitDraft): HabitFormState {
            val base = HabitFormState(name = draft.name, type = draft.type, mandatory = draft.mandatory)
            return when (draft.type) {
                HabitType.DURATION -> base.copy(
                    durationTarget = draft.target ?: base.durationTarget,
                    durationStep = draft.step ?: base.durationStep,
                )
                HabitType.DEADLINE -> base.copy(
                    deadlineTarget = draft.target ?: base.deadlineTarget,
                    deadlineStep = draft.step ?: base.deadlineStep,
                )
                HabitType.BOOLEAN -> base
            }
        }
    }
}

/** Name, type, target, step and the mandatory flag. */
@Composable
fun HabitForm(
    state: HabitFormState,
    onChange: (HabitFormState) -> Unit,
    modifier: Modifier = Modifier,
    typeEditable: Boolean = true,
    showMandatory: Boolean = true,
) {
    val formatter = rememberFormatter()
    var pickTime by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onChange(state.copy(name = it.take(TrackerRepository.NAME_MAX_LENGTH))) },
            label = { Text("Habit name") },
            singleLine = true,
            supportingText = { Text("${state.name.length}/${TrackerRepository.NAME_MAX_LENGTH}") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (typeEditable) {
            val types = listOf(HabitType.DURATION to "Minutes", HabitType.DEADLINE to "By a time", HabitType.BOOLEAN to "Yes / no")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                types.forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { onChange(state.copy(type = type)) },
                        shape = SegmentedButtonDefaults.itemShape(index, types.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
        when (state.type) {
            HabitType.DURATION -> {
                NumberStepper(
                    label = "Target",
                    value = state.durationTarget,
                    range = 1..600,
                    onChange = { onChange(state.copy(durationTarget = it)) },
                    display = { "$it min" },
                )
                NumberStepper(
                    label = "Level-up step",
                    value = state.durationStep,
                    range = 1..120,
                    onChange = { onChange(state.copy(durationStep = it)) },
                    display = { "+$it min" },
                )
            }
            HabitType.DEADLINE -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Target time", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = { pickTime = true }, modifier = Modifier.height(48.dp)) {
                        Text("by ${formatter.clock(state.deadlineTarget)}")
                    }
                }
                NumberStepper(
                    label = "Level-up step",
                    value = state.deadlineStep,
                    range = 5..120,
                    step = 5,
                    onChange = { onChange(state.copy(deadlineStep = it)) },
                    display = { "$it min earlier" },
                )
            }
            HabitType.BOOLEAN -> Text(
                "Done or not done. No target, so no level-ups.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showMandatory) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Mandatory", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The reward stays locked until this one is YES.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.mandatory, onCheckedChange = { onChange(state.copy(mandatory = it)) })
            }
        }
    }
    if (pickTime) {
        TimePickerDialog(
            title = "Target time",
            initialMinuteOfDay = state.deadlineTarget,
            onDismiss = { pickTime = false },
            onConfirm = {
                onChange(state.copy(deadlineTarget = it))
                pickTime = false
            },
        )
    }
}

/** A plain text field that only accepts digits (kept for numeric settings). */
@Composable
fun DigitsField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** A small bar-chart icon for the Stats tab (the core icon set has none). */
val BarChartIcon: ImageVector by lazy {
    ImageVector.Builder(name = "BarChart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 20f)
            lineTo(4f, 11f)
            lineTo(8f, 11f)
            lineTo(8f, 20f)
            close()
            moveTo(10f, 20f)
            lineTo(10f, 4f)
            lineTo(14f, 4f)
            lineTo(14f, 20f)
            close()
            moveTo(16f, 20f)
            lineTo(16f, 14f)
            lineTo(20f, 14f)
            lineTo(20f, 20f)
            close()
        }
        .build()
}
