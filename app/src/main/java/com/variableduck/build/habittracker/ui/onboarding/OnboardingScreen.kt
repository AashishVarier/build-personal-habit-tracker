package com.variableduck.build.habittracker.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.variableduck.build.habittracker.backup.BackupManager
import com.variableduck.build.habittracker.ui.common.DatePickerDialogFor
import com.variableduck.build.habittracker.ui.common.HabitForm
import com.variableduck.build.habittracker.ui.common.RestorePreviewDialog
import com.variableduck.build.habittracker.ui.common.SectionTitle
import com.variableduck.build.habittracker.ui.common.TimePickerDialog
import com.variableduck.build.habittracker.ui.common.notificationsAllowed
import com.variableduck.build.habittracker.ui.common.rememberFormatter

private const val STEP_COUNT = 3

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::previewRestore)
    }
    val context = LocalContext.current
    // Ask for notification permission at the end of onboarding so the daily reminder can appear.
    // Finish either way: the Today screen keeps offering to turn notifications on.
    val finishLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.finish(onFinished)
    }
    val startTracking = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed(context)) {
            finishLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.finish(onFinished)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        LinearProgressIndicator(
            progress = { (state.step + 1) / STEP_COUNT.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                0 -> HabitsStep(state, viewModel, onRestore = {
                    restoreLauncher.launch(arrayOf(BackupManager.XLSX_MIME, "application/octet-stream"))
                })
                1 -> ScheduleStep(state, viewModel)
                else -> GoalAndBackupStep(state, viewModel)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.step > 0) {
                OutlinedButton(onClick = viewModel::back, modifier = Modifier.height(48.dp)) { Text("Back") }
            } else {
                Spacer(Modifier)
            }
            if (state.step < STEP_COUNT - 1) {
                Button(onClick = viewModel::next, modifier = Modifier.height(48.dp)) { Text("Next") }
            } else {
                Button(
                    onClick = startTracking,
                    enabled = !state.saving,
                    modifier = Modifier.height(48.dp),
                ) { Text("Start tracking") }
            }
        }
    }

    state.restorePreview?.let { preview ->
        RestorePreviewDialog(
            preview = preview,
            hasBackupFolder = false,
            onConfirm = { viewModel.confirmRestore(onFinished) },
            onDismiss = viewModel::cancelRestore,
        )
    }
}

@Composable
private fun HabitsStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onRestore: () -> Unit) {
    Text("Welcome", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Pick 1 to 4 habits. Everything stays on this phone: no account, no internet.",
        style = MaterialTheme.typography.bodyLarge,
    )
    TextButton(onClick = onRestore) { Text("Restore from a backup instead") }

    SectionTitle("Suggestions")
    viewModel.suggestions.forEach { suggestion ->
        OutlinedButton(
            onClick = { viewModel.addHabit(suggestion.form) },
            enabled = state.canAddHabit,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(suggestion.label, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
        }
    }

    SectionTitle("Your habits (${state.habits.size}/4)")
    state.habits.forEachIndexed { index, item ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Habit ${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removeHabit(item.key) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove habit ${index + 1}")
                    }
                }
                HabitForm(state = item.form, onChange = { viewModel.updateHabit(item.key, it) })
            }
        }
    }
    if (state.canAddHabit) {
        FilledTonalButton(onClick = { viewModel.addHabit() }, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add a habit")
        }
    }
}

@Composable
private fun ScheduleStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val formatter = rememberFormatter()
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    Text("Cycles and reminders", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    SectionTitle("14-day cycle start")
    Text(
        "Targets are reviewed at the end of every 14 days. Habits are tracked from today, so if " +
            "you pick an earlier date, that first, shorter cycle doesn't change any targets.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FilledTonalButton(onClick = { pickDate = true }, modifier = Modifier.height(48.dp)) {
        Text(formatter.date(state.cycleStart))
    }

    SectionTitle("Daily reminder")
    FilledTonalButton(onClick = { pickTime = true }, modifier = Modifier.height(48.dp)) {
        Text(formatter.clock(state.reminderMinute))
    }
    Text(
        "When you start, Android asks whether the app may send notifications. Allow it, or the " +
            "reminder can't appear.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (pickDate) {
        DatePickerDialogFor(
            initial = state.cycleStart,
            min = state.today.minusDays(13),
            max = state.today.plusDays(13),
            onDismiss = { pickDate = false },
            onConfirm = {
                viewModel.setCycleStart(it)
                pickDate = false
            },
        )
    }
    if (pickTime) {
        TimePickerDialog(
            title = "Reminder time",
            initialMinuteOfDay = state.reminderMinute,
            onDismiss = { pickTime = false },
            onConfirm = {
                viewModel.setReminder(it)
                pickTime = false
            },
        )
    }
}

@Composable
private fun GoalAndBackupStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setBackupFolder)
    }
    Text("Goal and backups", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    SectionTitle("Overall goal")
    OutlinedTextField(
        value = state.goal,
        onValueChange = viewModel::setGoal,
        label = { Text("What are these habits for? (optional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    Text(
        "Shown in the daily reminder and once a day when you open the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionTitle("Backup folder")
    Text(
        "A daily Excel backup is saved to a folder you choose (the last 14 are kept). " +
            "Files there survive uninstalling the app.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FilledTonalButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.height(48.dp)) {
        Text(state.backupName?.let { "Folder: $it" } ?: "Choose backup folder")
    }
    if (state.backupUri == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Recommended. You can also set this later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
