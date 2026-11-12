package com.variableduck.build.habittracker.backup

import androidx.room.withTransaction
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.settings.PortableSettings
import com.variableduck.build.habittracker.model.CycleReview
import com.variableduck.build.habittracker.model.DayRecord
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class PurgeCounts(val days: Int, val targetChanges: Int, val archivedHabits: Int)

/** Data the retention purge works on. Split out so the purge rule can be tested with fakes. */
interface PurgeStore {
    suspend fun countDaysBefore(cutoff: LocalDate): Int
    suspend fun snapshotBefore(cutoff: LocalDate): BackupSnapshot
    suspend fun deleteBefore(cutoff: LocalDate): PurgeCounts
}

/** Room access for full snapshots, restore and the retention purge. */
@Singleton
class BackupStore @Inject constructor(private val db: AppDatabase) : PurgeStore {
    private val dao = db.backupDao()
    private val cycleDao = db.cycleDao()

    suspend fun snapshot(settings: PortableSettings?, exportedOn: LocalDate?): BackupSnapshot = db.withTransaction {
        BackupSnapshot(
            habits = dao.habits(),
            days = dao.days(),
            logs = dao.logs(),
            targetChanges = dao.targetChanges(),
            settings = settings,
            lastEvaluatedCycleEnd = cycleDao.lastEvaluatedEnd(),
            exportedOn = exportedOn,
        )
    }

    override suspend fun countDaysBefore(cutoff: LocalDate): Int = dao.countDaysBefore(cutoff)

    suspend fun firstDayDate(): LocalDate? = dao.firstDayDate()

    override suspend fun snapshotBefore(cutoff: LocalDate): BackupSnapshot = db.withTransaction {
        val logs = dao.logsBefore(cutoff)
        val changes = dao.targetChangesBefore(cutoff)
        val referenced = (logs.map { it.habitId } + changes.map { it.habitId }).toSet()
        BackupSnapshot(
            habits = dao.habits().filter { it.id in referenced },
            days = dao.daysBefore(cutoff),
            logs = logs,
            targetChanges = changes,
            settings = null,
            lastEvaluatedCycleEnd = null,
            exportedOn = null,
        )
    }

    /**
     * Deletes entries and target changes dated before [cutoff], then archived habits that have no
     * entries left. Active habits and settings are never deleted.
     */
    override suspend fun deleteBefore(cutoff: LocalDate): PurgeCounts = db.withTransaction {
        dao.deleteLogsBefore(cutoff)
        val days = dao.deleteDaysBefore(cutoff)
        val changes = dao.deleteTargetChangesBefore(cutoff)
        val habits = dao.deleteArchivedHabitsWithoutEntries()
        PurgeCounts(days, changes, habits)
    }

    /** Replaces all data with [snapshot] in one transaction. */
    suspend fun replaceAll(snapshot: BackupSnapshot, markerEnd: LocalDate?, today: LocalDate) = db.withTransaction {
        cycleDao.deleteAllReviews()
        dao.deleteAllLogs()
        dao.deleteAllTargetChanges()
        dao.deleteAllDays()
        dao.deleteAllHabits()

        dao.insertHabits(snapshot.habits)
        val dayDates = snapshot.days.map { it.date }.toSet()
        val missingDays = snapshot.logs.map { it.date }.filter { it !in dayDates }.distinct()
            .map { DayRecord(it) }
        dao.insertDays(snapshot.days + missingDays)
        dao.insertLogs(snapshot.logs)
        dao.insertTargetChanges(snapshot.targetChanges)
        if (markerEnd != null) {
            cycleDao.insertReview(
                CycleReview(
                    cycleStart = markerEnd.minusDays(13),
                    cycleEnd = markerEnd,
                    evaluatedOn = today,
                    acknowledged = true,
                    skipped = true,
                ),
            )
        }
    }
}
