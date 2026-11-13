package com.variableduck.build.habittracker.reminders

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.variableduck.build.habittracker.backup.BackupManager
import com.variableduck.build.habittracker.backup.BackupResult
import com.variableduck.build.habittracker.backup.RetentionManager
import com.variableduck.build.habittracker.core.MaintenanceLock
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.DataChangeNotifier
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.di.ReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Daily upkeep shared by app start, the midnight alarm and the WorkManager worker:
 * fill missed days with NO, evaluate ended cycles, take the automatic backup and run the weekly
 * retention check (which only ever prompts; it never deletes).
 */
@Singleton
class Housekeeping @Inject constructor(
    private val tracker: TrackerRepository,
    private val cycles: CycleRepository,
    private val backups: BackupManager,
    private val retention: RetentionManager,
    private val settings: SettingsRepository,
    private val notifications: AppNotifications,
    private val changeNotifier: DataChangeNotifier,
    private val time: TimeProvider,
    private val lock: MaintenanceLock,
) {
    /** Light work for the day change: new rows, cycle evaluation, widget refresh. */
    suspend fun rollOver() {
        lock.withLock {
            if (!settings.current().onboardingComplete) return
            val today = time.today()
            tracker.fillMissingDays(today)
            val reviews = cycles.evaluateEndedCycles(today)
            if (reviews > 0) notifications.showCycleReviewReady()
            changeNotifier.notifyChanged()
        }
    }

    /** Full daily run. [fromBackground] posts notifications instead of relying on in-app prompts. */
    suspend fun runDaily(fromBackground: Boolean) {
        lock.withLock {
            if (!settings.current().onboardingComplete) return
            val today = time.today()
            tracker.fillMissingDays(today)
            val reviews = cycles.evaluateEndedCycles(today)
            if (fromBackground && reviews > 0) notifications.showCycleReviewReady()

            val backup = backups.autoBackupIfDue(today)
            if (backup is BackupResult.Failed) {
                Log.w(TAG, "Automatic backup failed: ${backup.reason}")
                if (fromBackground) notifications.showBackupFailed(backup.reason)
            }

            if (fromBackground) {
                retention.dueCandidates(today)?.let { notifications.showRetentionPrompt(it.dayCount, it.cutoff) }
            }
        }
    }

    private companion object {
        const val TAG = "Housekeeping"
    }
}

/** Daily upkeep in the background. Dependencies come from the Hilt graph via an entry point. */
class DailyWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(applicationContext, ReceiverEntryPoint::class.java)
            .housekeeping()
            .runDaily(fromBackground = true)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("DailyWorker", "Daily housekeeping failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

@Singleton
class WorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "daily-housekeeping"
    }
}
