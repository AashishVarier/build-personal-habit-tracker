package com.variableduck.build.habittracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.backup.PurgeResult
import com.variableduck.build.habittracker.backup.RetentionCandidates
import com.variableduck.build.habittracker.backup.RetentionManager
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.reminders.AlarmScheduler
import com.variableduck.build.habittracker.reminders.Housekeeping
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val loading: Boolean = true,
    val onboardingComplete: Boolean = false,
    val goalPopup: String? = null,
    val retentionPrompt: RetentionCandidates? = null,
    val retentionBusy: Boolean = false,
    val pendingReviewCount: Int = 0,
    val message: String? = null,
)

private data class LocalState(
    val goalPopup: String? = null,
    val retentionPrompt: RetentionCandidates? = null,
    val retentionBusy: Boolean = false,
    val message: String? = null,
)

/** App-wide state: onboarding gate, daily goal popup, retention prompt and pending reviews. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val housekeeping: Housekeeping,
    private val retention: RetentionManager,
    private val alarms: AlarmScheduler,
    cycles: CycleRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<AppUiState> = combine(
        settings.settings,
        cycles.observePendingReviews(),
        local,
    ) { s, reviews, l ->
        AppUiState(
            loading = false,
            onboardingComplete = s.onboardingComplete,
            goalPopup = l.goalPopup,
            retentionPrompt = l.retentionPrompt,
            retentionBusy = l.retentionBusy,
            pendingReviewCount = reviews.size,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    /** Runs on every resume: catch up on missed days, then show daily prompts if due. */
    fun onResume() {
        viewModelScope.launch {
            val current = settings.current()
            if (!current.onboardingComplete) return@launch
            try {
                housekeeping.runDaily(fromBackground = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(message = "Daily update failed: ${e.message}") }
            }
            val today = time.today()
            val goal = current.overallGoal.trim()
            if (goal.isNotEmpty() && current.lastGoalPopupDate != today) {
                local.update { it.copy(goalPopup = goal) }
            }
            if (local.value.retentionPrompt == null) {
                try {
                    retention.dueCandidates(today)?.let { candidates ->
                        local.update { it.copy(retentionPrompt = candidates) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only a prompt: it is checked again on the next resume.
                }
            }
        }
    }

    fun onOnboardingFinished() = launchReporting("Daily update failed") {
        alarms.scheduleAll()
        settings.markGoalPopupShown(time.today())
        settings.markRetentionChecked(time.today())
        housekeeping.runDaily(fromBackground = false)
    }

    fun dismissGoal() {
        local.update { it.copy(goalPopup = null) }
        launchReporting("Could not save") { settings.markGoalPopupShown(time.today()) }
    }

    fun retentionNotNow() {
        local.update { it.copy(retentionPrompt = null) }
        launchReporting("Could not save") { retention.postpone() }
    }

    fun retentionDelete() {
        local.update { it.copy(retentionBusy = true) }
        viewModelScope.launch {
            val message = try {
                when (val result = retention.purge()) {
                    is PurgeResult.Deleted ->
                        "Deleted ${result.counts.days} days. Archive saved as ${result.archiveFile}."
                    is PurgeResult.ExportFailed ->
                        "Nothing was deleted: the archive could not be saved (${result.reason}). " +
                            "Check the backup folder in Settings."
                    PurgeResult.NothingToDelete -> "Nothing to delete."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The deletion itself is one database transaction: it happened completely or not at all.
                "Deleting old entries didn't finish: ${e.message ?: e.javaClass.simpleName}"
            }
            local.update { it.copy(retentionPrompt = null, retentionBusy = false, message = message) }
        }
    }

    /** Settings → "Check for old data now". */
    fun checkRetentionNow() = launchReporting("Could not check for old data") {
        val candidates = retention.candidates()
        local.update {
            if (candidates.dayCount > 0) {
                it.copy(retentionPrompt = candidates)
            } else {
                it.copy(message = "Nothing is older than the retention limit.")
            }
        }
    }

    /** Runs [block]; a failure becomes a snackbar message ("[failure]: reason") instead of a crash. */
    private fun launchReporting(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(message = "$failure: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun showMessage(message: String) = local.update { it.copy(message = message) }

    fun messageShown() = local.update { it.copy(message = null) }
}
