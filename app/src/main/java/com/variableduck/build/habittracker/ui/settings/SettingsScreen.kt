package com.variableduck.build.habittracker.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.variableduck.build.habittracker.backup.BackupManager
import com.variableduck.build.habittracker.data.settings.AppSettings
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.domain.RetentionPolicy
import com.variableduck.build.habittracker.ui.common.DatePickerDialogFor
import com.variableduck.build.habittracker.ui.common.NumberStepper
import com.variableduck.build.habittracker.ui.common.RestorePreviewDialog
import com.variableduck.build.habittracker.ui.common.ScreenHeader
import com.variableduck.build.habittracker.ui.common.SectionTitle
import com.variableduck.build.habittracker.ui.common.TimePickerDialog
import com.variableduck.build.habittracker.ui.common.rememberFormatter

@Composable
fun SettingsScreen(
    onOpenImport: () -> Unit,
    onCheckRetention: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val formatter = rememberFormatter()
    val context = LocalContext.current
    val s = state.settings

    var pickTime by rememberSaveable { mutableStateOf(false) }
    var pickCycleStart by rememberSaveable { mutableStateOf(false) }
    var editGoal by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            onMessage(it)
            viewModel.messages.value = null
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setBackupFolder)
    }
    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.XLSX_MIME)) { uri ->
        uri?.let(viewModel::exportXlsx)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.CSV_MIME)) { uri ->
        uri?.let(viewModel::exportCsv)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::previewRestore)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ScreenHeader("Settings")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        SectionTitle("Reminder")
        SettingRow("Daily reminder", formatter.clock(s.reminderMinuteOfDay)) { pickTime = true }
        if (!state.exactAlarmsAllowed) {
            SettingRow(
                "Allow exact reminders",
                "Without \"Alarms & reminders\" access, Android may deliver the reminder a little late.",
            ) {
                viewModel.exactAlarmIntent()?.let {
                    try {
                        context.startActivity(it)
                    } catch (e: ActivityNotFoundException) {
                        onMessage("Open system settings → Apps → Habit Tracker → Alarms & reminders.")
                    }
                }
            }
        }
        SettingRow("Overall goal", s.overallGoal.ifBlank { "Not set" }) { editGoal = true }

        SectionTitle("Cycles")
        SettingRow(
            "Cycle start date",
            s.cycleStart?.let { "${formatter.date(it)} (cycles of ${CycleCalendar.LENGTH} days)" } ?: "Not set",
        ) { pickCycleStart = true }
        NumberStepper(
            label = "Level-up prompt at ≥",
            value = s.thresholds.levelUp,
            range = 1..CycleCalendar.LENGTH,
            onChange = { viewModel.setThresholds(it, s.thresholds.stepDown) },
            display = { "$it / 14" },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        NumberStepper(
            label = "Step down below",
            value = s.thresholds.stepDown,
            range = 0..s.thresholds.levelUp,
            onChange = { viewModel.setThresholds(s.thresholds.levelUp, it) },
            display = { "$it / 14" },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        SectionTitle("Backups")
        SettingRow(
            "Backup folder",
            when {
                s.backupFolderUri == null -> "Not set. Daily backups are off."
                state.backupFolderAccessible -> "${state.backupFolderName} · last automatic backup: " +
                    (s.lastAutoBackupDate?.let(formatter::date) ?: "never")
                else -> "Access lost. Choose the folder again."
            },
        ) { folderLauncher.launch(null) }
        SettingRow("Back up now", "Writes today's backup and keeps the newest ${BackupManager.KEEP_AUTO_BACKUPS}.") {
            viewModel.backupNow()
        }
        SettingRow("Export to Excel (.xlsx)", "Sheets: Entries, Habits, TargetHistory, Settings") {
            xlsxLauncher.launch(viewModel.suggestedExportName("xlsx"))
        }
        SettingRow("Export entries to CSV", null) { csvLauncher.launch(viewModel.suggestedExportName("csv")) }
        SettingRow("Restore from an Excel backup", "Shows a preview before anything changes.") {
            restoreLauncher.launch(arrayOf(BackupManager.XLSX_MIME, "application/octet-stream"))
        }
        SettingRow("History import mode", "Enter past data for any date. Bypasses the edit window.", onOpenImport)

        SectionTitle("Data retention")
        NumberStepper(
            label = "Keep daily entries for",
            value = s.retentionMonths,
            range = RetentionPolicy.MIN_MONTHS..RetentionPolicy.MAX_MONTHS,
            onChange = viewModel::setRetentionMonths,
            display = { "$it months" },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SettingRow(
            "Check for old data now",
            "Never deletes without asking. Old data is archived to the backup folder first.",
            onCheckRetention,
        )

        SectionTitle("Privacy")
        Text(
            "This app has no internet permission. All data stays on this device and in the backup folder you choose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (pickTime) {
        TimePickerDialog(
            title = "Reminder time",
            initialMinuteOfDay = s.reminderMinuteOfDay,
            onDismiss = { pickTime = false },
            onConfirm = {
                viewModel.setReminder(it)
                pickTime = false
            },
        )
    }
    if (pickCycleStart) {
        DatePickerDialogFor(
            initial = s.cycleStart ?: state.today,
            max = state.today.plusDays(CycleCalendar.LENGTH - 1L),
            onDismiss = { pickCycleStart = false },
            onConfirm = {
                viewModel.setCycleStart(it)
                pickCycleStart = false
            },
        )
    }
    if (editGoal) {
        GoalDialog(
            initial = s.overallGoal,
            onDismiss = { editGoal = false },
            onSave = {
                viewModel.setGoal(it)
                editGoal = false
            },
        )
    }
    state.restorePreview?.let { preview ->
        RestorePreviewDialog(
            preview = preview,
            hasBackupFolder = state.backupFolderAccessible,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::cancelRestore,
        )
    }
}

@Composable
private fun SettingRow(title: String, summary: String?, onClick: () -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = if (summary != null) {
                { Text(summary) }
            } else {
                null
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
        HorizontalDivider()
    }
}

@Composable
private fun GoalDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overall goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(AppSettings.GOAL_MAX_LENGTH) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Shown in the daily reminder and once a day when you open the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
