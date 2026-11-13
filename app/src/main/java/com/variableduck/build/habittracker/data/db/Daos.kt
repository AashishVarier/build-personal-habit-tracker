package com.variableduck.build.habittracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.variableduck.build.habittracker.model.CycleReview
import com.variableduck.build.habittracker.model.CycleReviewItem
import com.variableduck.build.habittracker.model.CycleReviewWithItems
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.HabitYesCount
import com.variableduck.build.habittracker.model.TargetChange
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerDao {
    @Query("SELECT * FROM habits WHERE archivedOn IS NULL ORDER BY slot")
    fun observeActiveHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habits WHERE archivedOn IS NULL ORDER BY slot")
    suspend fun activeHabits(): List<Habit>

    @Query("SELECT * FROM habits ORDER BY slot, createdOn")
    fun observeAllHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY slot, createdOn")
    suspend fun allHabits(): List<Habit>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun habit(id: Long): Habit?

    @Insert
    suspend fun insertHabit(habit: Habit): Long

    @Update
    suspend fun updateHabit(habit: Habit)

    /** Entries, target changes and review items of the habit are removed with it (cascade). */
    @Delete
    suspend fun deleteHabit(habit: Habit)

    @Query("SELECT DISTINCT date FROM habit_logs WHERE habitId = :habitId")
    suspend fun logDates(habitId: Long): List<LocalDate>

    @Query("SELECT MAX(date) FROM days")
    suspend fun lastDayDate(): LocalDate?

    @Query("SELECT MIN(date) FROM days")
    fun observeFirstDayDate(): Flow<LocalDate?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDayIfMissing(day: DayRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLogsIfMissing(logs: List<HabitLog>)

    @Upsert
    suspend fun upsertLog(log: HabitLog)

    @Query("SELECT * FROM habit_logs WHERE date = :date AND habitId = :habitId")
    suspend fun log(date: LocalDate, habitId: Long): HabitLog?

    @Query("SELECT * FROM habit_logs WHERE date = :date")
    suspend fun logsOn(date: LocalDate): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE date = :date")
    fun observeLogsOn(date: LocalDate): Flow<List<HabitLog>>

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun day(date: LocalDate): DayRecord?

    @Query("SELECT * FROM days WHERE date = :date")
    fun observeDay(date: LocalDate): Flow<DayRecord?>

    @Query("SELECT * FROM days WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<DayRecord>>

    @Query("SELECT * FROM habit_logs WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeLogs(from: LocalDate, to: LocalDate): Flow<List<HabitLog>>

    @Query(
        "SELECT h.* FROM habits h INNER JOIN habit_logs l ON l.habitId = h.id " +
            "WHERE l.date = :date ORDER BY h.slot",
    )
    suspend fun habitsLoggedOn(date: LocalDate): List<Habit>

    @Query(
        "SELECT COUNT(*) FROM habit_logs " +
            "WHERE habitId = :habitId AND done = 1 AND date BETWEEN :from AND :to",
    )
    suspend fun yesCount(habitId: Long, from: LocalDate, to: LocalDate): Int

    @Query(
        "SELECT habitId, COUNT(*) AS yes FROM habit_logs " +
            "WHERE done = 1 AND date BETWEEN :from AND :to GROUP BY habitId",
    )
    fun observeYesCounts(from: LocalDate, to: LocalDate): Flow<List<HabitYesCount>>

    @Query("UPDATE days SET remark = :remark WHERE date = :date")
    suspend fun updateRemark(date: LocalDate, remark: String)

    @Query("UPDATE days SET rewardUnlocked = :unlocked WHERE date = :date")
    suspend fun updateReward(date: LocalDate, unlocked: Boolean)

    @Query("DELETE FROM habit_logs WHERE habitId = :habitId AND date >= :from")
    suspend fun deleteLogsFrom(habitId: Long, from: LocalDate)

    @Insert
    suspend fun insertTargetChange(change: TargetChange)

    @Query("SELECT * FROM target_changes ORDER BY date, id")
    fun observeTargetChanges(): Flow<List<TargetChange>>
}

@Dao
interface CycleDao {
    @Query("SELECT MAX(cycleEnd) FROM cycle_reviews")
    suspend fun lastEvaluatedEnd(): LocalDate?

    @Insert
    suspend fun insertReview(review: CycleReview): Long

    @Insert
    suspend fun insertItem(item: CycleReviewItem)

    @Update
    suspend fun updateItem(item: CycleReviewItem)

    @Query("SELECT * FROM cycle_review_items WHERE reviewId = :reviewId AND habitId = :habitId")
    suspend fun item(reviewId: Long, habitId: Long): CycleReviewItem?

    @Query(
        "UPDATE cycle_review_items SET decision = 'SUPERSEDED' " +
            "WHERE habitId = :habitId AND decision = 'PENDING'",
    )
    suspend fun supersedePendingPrompts(habitId: Long)

    @Query("SELECT COUNT(*) FROM cycle_review_items WHERE reviewId = :reviewId AND decision = 'PENDING'")
    suspend fun pendingDecisionCount(reviewId: Long): Int

    @Query("UPDATE cycle_reviews SET acknowledged = 1 WHERE id = :reviewId")
    suspend fun acknowledge(reviewId: Long)

    @Transaction
    @Query("SELECT * FROM cycle_reviews WHERE acknowledged = 0 AND skipped = 0 ORDER BY cycleStart")
    fun observePendingReviews(): Flow<List<CycleReviewWithItems>>

    @Query("SELECT * FROM cycle_reviews ORDER BY cycleEnd")
    suspend fun allReviews(): List<CycleReview>

    @Query("DELETE FROM cycle_reviews")
    suspend fun deleteAllReviews()
}

/** Bulk reads and writes for backup, restore and the retention purge. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM habits ORDER BY id")
    suspend fun habits(): List<Habit>

    @Query("SELECT * FROM days ORDER BY date")
    suspend fun days(): List<DayRecord>

    @Query("SELECT * FROM habit_logs ORDER BY date, habitId")
    suspend fun logs(): List<HabitLog>

    @Query("SELECT * FROM target_changes ORDER BY date, id")
    suspend fun targetChanges(): List<TargetChange>

    @Query("SELECT * FROM days WHERE date < :cutoff ORDER BY date")
    suspend fun daysBefore(cutoff: LocalDate): List<DayRecord>

    @Query("SELECT * FROM habit_logs WHERE date < :cutoff ORDER BY date, habitId")
    suspend fun logsBefore(cutoff: LocalDate): List<HabitLog>

    @Query("SELECT * FROM target_changes WHERE date < :cutoff ORDER BY date, id")
    suspend fun targetChangesBefore(cutoff: LocalDate): List<TargetChange>

    @Query("SELECT COUNT(*) FROM days WHERE date < :cutoff")
    suspend fun countDaysBefore(cutoff: LocalDate): Int

    @Query("SELECT MIN(date) FROM days")
    suspend fun firstDayDate(): LocalDate?

    @Query("DELETE FROM habit_logs WHERE date < :cutoff")
    suspend fun deleteLogsBefore(cutoff: LocalDate): Int

    @Query("DELETE FROM days WHERE date < :cutoff")
    suspend fun deleteDaysBefore(cutoff: LocalDate): Int

    @Query("DELETE FROM target_changes WHERE date < :cutoff")
    suspend fun deleteTargetChangesBefore(cutoff: LocalDate): Int

    /**
     * Archived habits with no remaining entries or target changes. Active habits are never
     * touched. Target changes are checked too: deleting the habit would cascade to them, and the
     * ones still left are newer than the purge cutoff, so they were never archived.
     */
    @Query(
        "DELETE FROM habits WHERE archivedOn IS NOT NULL " +
            "AND id NOT IN (SELECT DISTINCT habitId FROM habit_logs) " +
            "AND id NOT IN (SELECT DISTINCT habitId FROM target_changes)",
    )
    suspend fun deleteArchivedHabitsWithoutEntries(): Int

    @Query("DELETE FROM habit_logs")
    suspend fun deleteAllLogs()

    @Query("DELETE FROM days")
    suspend fun deleteAllDays()

    @Query("DELETE FROM target_changes")
    suspend fun deleteAllTargetChanges()

    @Query("DELETE FROM habits")
    suspend fun deleteAllHabits()

    @Insert
    suspend fun insertHabits(habits: List<Habit>)

    @Insert
    suspend fun insertDays(days: List<DayRecord>)

    @Insert
    suspend fun insertLogs(logs: List<HabitLog>)

    @Insert
    suspend fun insertTargetChanges(changes: List<TargetChange>)
}
