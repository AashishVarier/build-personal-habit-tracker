package com.variableduck.build.habittracker.backup

import android.content.Context
import android.net.Uri
import com.variableduck.build.habittracker.backup.xlsx.XlsxReader
import com.variableduck.build.habittracker.backup.xlsx.XlsxWriter
import com.variableduck.build.habittracker.core.MaintenanceLock
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.DataChangeNotifier
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.model.isActive
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface BackupResult {
    data class Written(val fileName: String) : BackupResult
    data object NotDue : BackupResult
    data object NoFolder : BackupResult
    data class Failed(val reason: String) : BackupResult
}

/** Summary shown before a restore is confirmed. */
data class RestorePreview(
    val snapshot: BackupSnapshot,
    val dayCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val activeHabitNames: List<String>,
    val archivedHabitCount: Int,
    val targetChangeCount: Int,
    val exportedOn: LocalDate?,
)

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: BackupStore,
    private val folder: BackupFolder,
    private val settings: SettingsRepository,
    private val tracker: TrackerRepository,
    private val cycles: CycleRepository,
    private val time: TimeProvider,
    private val changeNotifier: DataChangeNotifier,
    private val lock: MaintenanceLock,
) {
    suspend fun snapshot(): BackupSnapshot = store.snapshot(settings.portable(), time.today())

    /** Daily automatic backup into the chosen folder. Keeps the newest [KEEP_AUTO_BACKUPS] files. */
    suspend fun autoBackupIfDue(today: LocalDate = time.today()): BackupResult {
        val current = settings.current()
        if (current.backupFolderUri == null) return BackupResult.NoFolder
        if (current.lastAutoBackupDate == today) return BackupResult.NotDue
        return backupNow(today)
    }

    suspend fun backupNow(today: LocalDate = time.today()): BackupResult {
        if (folder.folder() == null) return BackupResult.NoFolder
        return try {
            val snapshot = snapshot()
            val name = folder.write(autoBackupName(today), XLSX_MIME) { out ->
                XlsxWriter.write(BackupCodec.toSheets(snapshot), out)
            }
            settings.markAutoBackup(today)
            folder.prune(AUTO_BACKUP_PATTERN, KEEP_AUTO_BACKUPS)
            BackupResult.Written(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Manual export to a user-chosen file. */
    suspend fun exportXlsx(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = snapshot()
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            XlsxWriter.write(BackupCodec.toSheets(snapshot), out)
        } ?: throw IOException("Could not open the file for writing.")
    }

    suspend fun exportCsv(uri: Uri) = withContext(Dispatchers.IO) {
        val csv = BackupCodec.toCsv(snapshot())
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(csv.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Could not open the file for writing.")
    }

    /** Writes a retention archive to the backup folder. Throws if it could not be written. */
    suspend fun writeArchive(fileName: String, snapshot: BackupSnapshot): String =
        folder.write(fileName, XLSX_MIME) { out -> XlsxWriter.write(BackupCodec.toSheets(snapshot), out) }

    /** Reads and validates a backup without changing anything. */
    suspend fun preview(uri: Uri): RestorePreview = withContext(Dispatchers.IO) {
        val sheets = context.contentResolver.openInputStream(uri)?.use(XlsxReader::read)
            ?: throw IOException("Could not open the file.")
        val snapshot = BackupCodec.fromSheets(sheets, time.today())
        RestorePreview(
            snapshot = snapshot,
            dayCount = snapshot.days.size,
            firstDate = snapshot.firstDate,
            lastDate = snapshot.lastDate,
            activeHabitNames = snapshot.habits.filter { it.isActive() }.sortedBy { it.slot }.map { it.name },
            archivedHabitCount = snapshot.habits.count { !it.isActive() },
            targetChangeCount = snapshot.targetChanges.size,
            exportedOn = snapshot.exportedOn,
        )
    }

    /**
     * Replaces all data with the previewed backup. If a backup folder is set, the current data is
     * saved there first; if that safety copy fails, nothing is restored. Holds the
     * [MaintenanceLock], so the daily housekeeping can't run between replacing the data and
     * applying the backup's settings.
     */
    suspend fun restore(preview: RestorePreview): String? = lock.withLock {
        val today = time.today()
        var safetyCopy: String? = null
        if (folder.folder() != null) {
            val current = snapshot()
            val name = "habit-tracker_pre-restore_${time.now().format(STAMP)}.xlsx"
            safetyCopy = folder.write(name, XLSX_MIME) { out -> XlsxWriter.write(BackupCodec.toSheets(current), out) }
        }
        val snapshot = preview.snapshot
        val portable = snapshot.settings ?: settings.portable()
        val anchor = portable.cycleStart ?: settings.current().cycleStart
        // Never re-judge cycles that ended before the backup was made.
        val marker = snapshot.lastEvaluatedCycleEnd
            ?: anchor?.let { CycleCalendar.lastEndedCycle(it, snapshot.exportedOn ?: today)?.end }
        store.replaceAll(snapshot, marker, today)
        settings.applyPortable(portable.copy(cycleStart = anchor))
        tracker.fillMissingDays(today)
        cycles.evaluateEndedCycles(today)
        changeNotifier.notifyChanged()
        safetyCopy
    }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val CSV_MIME = "text/csv"
        const val KEEP_AUTO_BACKUPS = 14
        private const val AUTO_PREFIX = "habit-tracker_backup_"
        val AUTO_BACKUP_PATTERN = Regex("""habit-tracker_backup_\d{4}-\d{2}-\d{2}\.xlsx""")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        fun autoBackupName(date: LocalDate) = "$AUTO_PREFIX$date.xlsx"
    }
}
