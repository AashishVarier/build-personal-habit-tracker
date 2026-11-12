package com.variableduck.build.habittracker.ui.settings

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.backup.BackupFolder
import com.variableduck.build.habittracker.backup.BackupManager
import com.variableduck.build.habittracker.backup.BackupResult
import com.variableduck.build.habittracker.backup.RestorePreview
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.settings.AppSettings
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.reminders.AlarmScheduler
import com.variableduck.build.habittracker.reminders.ReminderNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val backupFolderName: String? = null,
    val backupFolderAccessible: Boolean = false,
    val exactAlarmsAllowed: Boolean = true,
    val busy: Boolean = false,
    val restorePreview: RestorePreview? = null,
    val today: LocalDate = LocalDate.MIN,
)

private data class Transient(
    val busy: Boolean = false,
    val restorePreview: RestorePreview? = null,
    val exactAlarmsAllowed: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cycles: CycleRepository,
    private val backups: BackupManager,
    private val folder: BackupFolder,
    private val alarms: AlarmScheduler,
    private val reminder: ReminderNotifier,
    private val time: TimeProvider,
) : ViewModel() {
    private val transient = MutableStateFlow(Transient(exactAlarmsAllowed = alarms.canScheduleExactAlarms()))

    /** One-shot messages for the snackbar, consumed by the screen. */
    val messages = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> = combine(settings.settings, transient) { s, t ->
        val uri = s.backupFolderUri?.toUri()
        val accessible = uri != null && folder.hasAccess(uri)
        SettingsUiState(
            loading = false,
            settings = s,
            backupFolderName = uri?.let { if (accessible) folder.displayName(it) ?: "Selected folder" else null },
            backupFolderAccessible = accessible,
            exactAlarmsAllowed = t.exactAlarmsAllowed,
            busy = t.busy,
            restorePreview = t.restorePreview,
            today = time.today(),
        )
    }.flowOn(Dispatchers.IO) // folder checks talk to the storage provider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun exactAlarmIntent() = alarms.exactAlarmSettingsIntent()

    /** Shows the daily reminder now, to check that notifications work. */
    fun testReminder() = perform {
        reminder.show(test = true)
        alarms.scheduleAll()
    }

    fun refreshPermissions() {
        transient.update { it.copy(exactAlarmsAllowed = alarms.canScheduleExactAlarms()) }
        viewModelScope.launch { alarms.scheduleAll() }
    }

    fun setReminder(minute: Int) = perform {
        settings.setReminderMinute(minute)
        alarms.scheduleReminder(minute)
    }

    fun setCycleStart(date: LocalDate) = perform {
        settings.setCycleStart(date)
        cycles.onCycleStartChanged(date)
        messages.value = "Cycles now start on $date. Past cycles are not re-evaluated."
    }

    fun setThresholds(levelUp: Int, stepDown: Int) = perform { settings.setThresholds(levelUp, stepDown) }

    fun setGoal(goal: String) = perform { settings.setOverallGoal(goal) }

    fun setRetentionMonths(months: Int) = perform { settings.setRetentionMonths(months) }

    fun setBackupFolder(uri: Uri) = perform {
        val previous = settings.current().backupFolderUri?.toUri()
        folder.persistAccess(uri)
        settings.setBackupFolder(uri.toString())
        if (previous != null && previous != uri) folder.releaseAccess(previous)
        messages.value = "Backup folder set."
    }

    fun backupNow() = busy {
        messages.value = when (val result = backups.backupNow()) {
            is BackupResult.Written -> "Saved ${result.fileName}"
            BackupResult.NoFolder -> "Choose a backup folder first."
            BackupResult.NotDue -> "Already backed up today."
            is BackupResult.Failed -> "Backup failed: ${result.reason}"
        }
    }

    fun exportXlsx(uri: Uri) = busy {
        backups.exportXlsx(uri)
        messages.value = "Exported to Excel."
    }

    fun exportCsv(uri: Uri) = busy {
        backups.exportCsv(uri)
        messages.value = "Exported CSV."
    }

    fun previewRestore(uri: Uri) = busy {
        val preview = backups.preview(uri)
        transient.update { it.copy(restorePreview = preview) }
    }

    fun cancelRestore() = transient.update { it.copy(restorePreview = null) }

    fun confirmRestore() {
        val preview = transient.value.restorePreview ?: return
        transient.update { it.copy(restorePreview = null) }
        busy {
            val safetyCopy = backups.restore(preview)
            alarms.scheduleAll()
            messages.value = "Restored ${preview.dayCount} days." +
                (safetyCopy?.let { " Previous data saved as $it." } ?: "")
        }
    }

    fun suggestedExportName(extension: String) = "habit-tracker_export_${time.today()}.$extension"

    private fun perform(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun busy(block: suspend () -> Unit) {
        transient.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.value = e.message ?: "Something went wrong."
            } finally {
                transient.update { it.copy(busy = false) }
            }
        }
    }
}
