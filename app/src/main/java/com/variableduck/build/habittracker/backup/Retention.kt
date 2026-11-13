package com.variableduck.build.habittracker.backup

import com.variableduck.build.habittracker.core.MaintenanceLock
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.DataChangeNotifier
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.domain.RetentionPolicy
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Writes the soon-to-be-deleted data somewhere safe. Must throw if the file was not written. */
fun interface ArchiveWriter {
    suspend fun write(fileName: String, snapshot: BackupSnapshot): String
}

sealed interface PurgeResult {
    data object NothingToDelete : PurgeResult
    data class Deleted(val counts: PurgeCounts, val archiveFile: String) : PurgeResult
    data class ExportFailed(val reason: String) : PurgeResult
}

/**
 * The retention purge. Data is exported to `archive_<from>_<to>.xlsx` first; if that export fails,
 * nothing is deleted.
 */
class RetentionPurger(private val store: PurgeStore, private val archive: ArchiveWriter) {
    suspend fun purge(cutoff: LocalDate): PurgeResult {
        val snapshot = store.snapshotBefore(cutoff)
        if (snapshot.isEmpty) return PurgeResult.NothingToDelete
        val from = snapshot.firstDate ?: snapshot.targetChanges.minOf { it.date }
        val to = snapshot.lastDate ?: snapshot.targetChanges.maxOf { it.date }
        val fileName = archiveFileName(from, to)
        val written = try {
            archive.write(fileName, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PurgeResult.ExportFailed(e.message ?: e.javaClass.simpleName)
        }
        return PurgeResult.Deleted(store.deleteBefore(cutoff), written)
    }

    companion object {
        fun archiveFileName(from: LocalDate, to: LocalDate) = "archive_${from}_$to.xlsx"
    }
}

data class RetentionCandidates(val dayCount: Int, val cutoff: LocalDate)

@Singleton
class RetentionManager @Inject constructor(
    private val store: BackupStore,
    private val backups: BackupManager,
    private val settings: SettingsRepository,
    private val cycles: CycleRepository,
    private val time: TimeProvider,
    private val changeNotifier: DataChangeNotifier,
    private val lock: MaintenanceLock,
) {
    /** Entries before this date may be deleted (clamped to keep the 30-day and cycle windows). */
    suspend fun cutoff(today: LocalDate = time.today()): LocalDate {
        val current = settings.current()
        val protectFrom = current.cycleStart?.let { anchor ->
            CycleCalendar.firstUnevaluatedStart(anchor, today, cycles.lastEvaluatedEnd())
        }
        return RetentionPolicy.cutoff(today, current.retentionMonths, protectFrom)
    }

    suspend fun candidates(today: LocalDate = time.today()): RetentionCandidates {
        val cutoff = cutoff(today)
        return RetentionCandidates(store.countDaysBefore(cutoff), cutoff)
    }

    /** Weekly check. Returns candidates only when a prompt should be shown. */
    suspend fun dueCandidates(today: LocalDate = time.today()): RetentionCandidates? {
        if (!RetentionPolicy.isCheckDue(settings.current().lastRetentionCheck, today)) return null
        val candidates = candidates(today)
        if (candidates.dayCount == 0) {
            settings.markRetentionChecked(today)
            return null
        }
        return candidates
    }

    suspend fun postpone(today: LocalDate = time.today()) = settings.markRetentionChecked(today)

    /** Only ever called after the user tapped Delete. Never runs at the same time as a restore or housekeeping. */
    suspend fun purge(today: LocalDate = time.today()): PurgeResult = lock.withLock {
        val purger = RetentionPurger(store) { fileName, snapshot -> backups.writeArchive(fileName, snapshot) }
        val result = purger.purge(cutoff(today))
        if (result !is PurgeResult.ExportFailed) settings.markRetentionChecked(today)
        if (result is PurgeResult.Deleted) changeNotifier.notifyChanged()
        result
    }
}
