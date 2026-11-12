package com.variableduck.build.habittracker.ui.habits

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.variableduck.build.habittracker.data.repo.HabitDraft
import com.variableduck.build.habittracker.domain.Graduation
import com.variableduck.build.habittracker.domain.HabitDeletion
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.ui.common.Formatter
import com.variableduck.build.habittracker.ui.common.HabitForm
import com.variableduck.build.habittracker.ui.common.HabitFormState
import com.variableduck.build.habittracker.ui.common.ScreenHeader
import com.variableduck.build.habittracker.ui.common.SectionTitle
import com.variableduck.build.habittracker.ui.common.rememberFormatter

private sealed interface HabitDialog {
    data object Add : HabitDialog
    data class Edit(val habit: Habit, val inEditWindow: Boolean) : HabitDialog
    data class Replace(val habit: Habit) : HabitDialog
    data class Delete(val item: ActiveHabitUi) : HabitDialog
}

@Composable
fun HabitsScreen(viewModel: HabitsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatter = rememberFormatter()
    var dialog by remember { mutableStateOf<HabitDialog?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Habits", "Up to 4 active habits")
        state.active.forEach { item ->
            ActiveHabitCard(
                item = item,
                onMandatory = { viewModel.setMandatory(item.habit.id, it) },
                onEdit = { dialog = HabitDialog.Edit(item.habit, item.inGracePeriod) },
                onReplace = { dialog = HabitDialog.Replace(item.habit) },
                onDelete = { dialog = HabitDialog.Delete(item) },
            )
        }
        if (!state.loading && state.canAdd) {
            FilledTonalButton(onClick = { dialog = HabitDialog.Add }, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add a habit")
            }
        }

        SectionTitle("Archived habits")
        if (state.archived.isEmpty()) {
            Text(
                "Habits you replace or delete at the end of a cycle are kept here with their history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.archived.forEach { habit ->
            Column(Modifier.fillMaxWidth()) {
                Text(habit.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatter.date(habit.createdOn)} – ${habit.archivedOn?.let(formatter::date).orEmpty()} · " +
                        "final target: ${formatter.targetInline(habit.type, habit.currentTarget)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        HabitDialog.Add -> HabitFormDialog(
            title = "New habit",
            initial = HabitFormState(),
            typeEditable = true,
            onDismiss = { dialog = null },
            onSave = { viewModel.add(it) { dialog = null } },
        )
        is HabitDialog.Edit -> HabitFormDialog(
            title = "Edit ${current.habit.name}",
            initial = HabitFormState.fromDraft(current.habit.toDraft()),
            typeEditable = current.inEditWindow,
            onDismiss = { dialog = null },
            onSave = { viewModel.edit(current.habit.id, it) { dialog = null } },
        )
        is HabitDialog.Replace -> HabitFormDialog(
            title = "Replace ${current.habit.name}",
            subtitle = "${current.habit.name} graduated. It will be archived with its history, " +
                "and the new habit takes its place from today.",
            initial = HabitFormState(),
            typeEditable = true,
            onDismiss = { dialog = null },
            onSave = { viewModel.replace(current.habit.id, it) { dialog = null } },
        )
        is HabitDialog.Delete -> {
            val habit = current.item.habit
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Delete ${habit.name}?") },
                text = {
                    Text(
                        if (current.item.deletion == HabitDeletion.DELETE_COMPLETELY) {
                            "It was added recently, so it is removed completely, together with its entries."
                        } else {
                            "It moves to Archived habits with its history kept, and its slot becomes free."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.delete(habit.id) { dialog = null } }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text("OK") } },
        )
    }
}

@Composable
private fun ActiveHabitCard(
    item: ActiveHabitUi,
    onMandatory: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatter = rememberFormatter()
    val habit = item.habit
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(habit.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(formatter.target(habit.type, habit.currentTarget), style = MaterialTheme.typography.bodyLarge)
            if (habit.type.hasTarget) {
                Text(
                    "${formatter.step(habit.type, habit.step)} · started at " +
                        formatter.targetInline(habit.type, habit.startingTarget),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Graduation: ${item.graduation.label} YES in the last ${Graduation.WINDOW_DAYS} days " +
                    if (item.graduation.graduated) "· graduated" else "(needs ${Graduation.REQUIRED_YES})",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (item.graduation.yes.toFloat() / Graduation.REQUIRED_YES).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Mandatory for the reward", modifier = Modifier.weight(1f))
                Switch(checked = habit.mandatory, onCheckedChange = onMandatory)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.canEdit) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Text("Edit")
                    }
                }
                if (item.deletion != HabitDeletion.NOT_ALLOWED) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("Delete")
                    }
                }
                if (item.graduation.graduated) {
                    FilledTonalButton(onClick = onReplace, modifier = Modifier.height(48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("Replace")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.inGracePeriod) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Text(
                    lockMessage(item, formatter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Explains when the habit can be edited or deleted. */
private fun lockMessage(item: ActiveHabitUi, formatter: Formatter): String {
    if (item.inGracePeriod) {
        return "You can edit or delete this habit until the end of ${formatter.date(item.lastFreeEditDay)}. " +
            "After that it is locked in."
    }
    val edits = if (item.graduation.graduated) "Graduated, so it can be edited again." else "Edits unlock when it graduates."
    val delete = when {
        item.deletion == HabitDeletion.ARCHIVE -> "The cycle is ending: you can delete it today."
        item.nextDeletionDay != null -> "You can delete it when this cycle ends (${formatter.date(item.nextDeletionDay)})."
        else -> "You can delete it when the current cycle ends."
    }
    return "Locked in. $edits $delete"
}

@Composable
private fun HabitFormDialog(
    title: String,
    initial: HabitFormState,
    typeEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (HabitDraft) -> Unit,
    subtitle: String? = null,
) {
    var form by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
                HabitForm(state = form, onChange = { form = it }, typeEditable = typeEditable)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(form.toDraft()) }, enabled = form.isValid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Habit.toDraft() = HabitDraft(name, type, currentTarget, step, mandatory)
