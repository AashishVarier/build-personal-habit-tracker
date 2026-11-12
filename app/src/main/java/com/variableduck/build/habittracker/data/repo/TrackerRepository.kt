package com.variableduck.build.habittracker.data.repo

import androidx.room.withTransaction
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.domain.DayFiller
import com.variableduck.build.habittracker.domain.EditWindow
import com.variableduck.build.habittracker.domain.Graduation
import com.variableduck.build.habittracker.domain.HabitDeletion
import com.variableduck.build.habittracker.domain.HabitDeletionRule
import com.variableduck.build.habittracker.domain.RewardRule
import com.variableduck.build.habittracker.domain.RewardStatus
import com.variableduck.build.habittracker.domain.Targets
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.model.isActive
import com.variableduck.build.habittracker.model.isActiveOn
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Called after data that the widget or reminder show has changed. */
fun interface DataChangeNotifier {
    fun notifyChanged()
}

data class HabitEntry(val habit: Habit, val done: Boolean, val actualTime: Int?)

data class DayView(
    val date: LocalDate,
    val entries: List<HabitEntry>,
    val remark: String,
    val reward: RewardStatus,
)

/** What the user typed in a habit form. Targets are minutes (DURATION) or minute-of-day (DEADLINE). */
data class HabitDraft(
    val name: String,
    val type: HabitType,
    val target: Int?,
    val step: Int?,
    val mandatory: Boolean,
)

sealed interface HabitResult {
    data class Ok(val habitId: Long) : HabitResult
    data class Error(val message: String) : HabitResult
}

@Singleton
class TrackerRepository @Inject constructor(
    private val db: AppDatabase,
    private val time: TimeProvider,
    private val changeNotifier: DataChangeNotifier,
) {
    private val dao = db.trackerDao()

    fun observeActiveHabits(): Flow<List<Habit>> = dao.observeActiveHabits()

    fun observeAllHabits(): Flow<List<Habit>> = dao.observeAllHabits()

    fun observeTargetChanges(): Flow<List<TargetChange>> = dao.observeTargetChanges()

    fun observeFirstDayDate(): Flow<LocalDate?> = dao.observeFirstDayDate()

    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<DayRecord>> = dao.observeDays(from, to)

    fun observeLogs(from: LocalDate, to: LocalDate): Flow<List<HabitLog>> = dao.observeLogs(from, to)

    /**
     * Habits and their YES/NO for [date]. Shows habits that were active that day or have an entry.
     * With [fallbackToActive] (history import), dates before any habit existed show today's habits.
     */
    fun observeDay(date: LocalDate, fallbackToActive: Boolean = false): Flow<DayView> =
        combine(dao.observeAllHabits(), dao.observeLogsOn(date), dao.observeDay(date)) { habits, logs, day ->
            buildDayView(date, habits, logs, day, fallbackToActive)
        }

    /** YES counts in the 30-day graduation window ending [today], per habit id. */
    fun observeGraduation(today: LocalDate): Flow<Map<Long, Graduation.Progress>> =
        dao.observeYesCounts(Graduation.windowStart(today), today).map { counts ->
            counts.associate { it.habitId to Graduation.Progress(it.yes) }
        }

    suspend fun graduationProgress(habitId: Long, today: LocalDate = time.today()): Graduation.Progress =
        Graduation.Progress(dao.yesCount(habitId, Graduation.windowStart(today), today))

    suspend fun dayView(date: LocalDate): DayView =
        buildDayView(date, dao.allHabits(), dao.logsOn(date), dao.day(date), fallbackToActive = false)

    /**
     * Creates rows (all NO) for every date since the last recorded one, up to [today]. Also makes
     * sure today and yesterday have an entry for every habit that was active on them.
     * Returns the number of new dates.
     */
    suspend fun fillMissingDays(today: LocalDate = time.today()): Int {
        val added = db.withTransaction {
            val habits = dao.allHabits()
            if (habits.isEmpty()) return@withTransaction 0
            val trackingStart = habits.minOf { it.createdOn }
            val missing = DayFiller.datesToFill(dao.lastDayDate(), trackingStart, today)
            val toEnsure = (missing + listOf(today.minusDays(1), today))
                .filter { !it.isBefore(trackingStart) }
                .distinct()
            for (date in toEnsure) {
                ensureDay(date, habits)
                recomputeReward(date)
            }
            missing.size
        }
        if (added > 0) changeNotifier.notifyChanged()
        return added
    }

    /** Sets YES/NO. Returns false (and changes nothing) outside the edit window. */
    suspend fun setDone(date: LocalDate, habitId: Long, done: Boolean, importMode: Boolean = false): Boolean =
        mutateLog(date, habitId, importMode) { it.copy(done = done) }

    suspend fun toggle(date: LocalDate, habitId: Long, importMode: Boolean = false): Boolean =
        mutateLog(date, habitId, importMode) { it.copy(done = !it.done) }

    suspend fun setActualTime(date: LocalDate, habitId: Long, minuteOfDay: Int?, importMode: Boolean = false): Boolean =
        mutateLog(date, habitId, importMode) { it.copy(actualTime = minuteOfDay?.coerceIn(0, Targets.MINUTES_PER_DAY - 1)) }

    suspend fun setRemark(date: LocalDate, remark: String, importMode: Boolean = false): Boolean {
        if (!EditWindow.canEditEntry(date, time.today(), importMode)) return false
        db.withTransaction {
            dao.insertDayIfMissing(DayRecord(date))
            dao.updateRemark(date, remark.take(REMARK_MAX_LENGTH))
        }
        return true
    }

    private suspend fun mutateLog(
        date: LocalDate,
        habitId: Long,
        importMode: Boolean,
        change: (HabitLog) -> HabitLog,
    ): Boolean {
        if (!EditWindow.canEditEntry(date, time.today(), importMode)) return false
        val changed = db.withTransaction {
            if (dao.habit(habitId) == null) return@withTransaction false
            dao.insertDayIfMissing(DayRecord(date))
            val current = dao.log(date, habitId) ?: HabitLog(date, habitId)
            dao.upsertLog(change(current))
            recomputeReward(date)
            true
        }
        if (changed) changeNotifier.notifyChanged()
        return changed
    }

    /** Onboarding: creates the first 1-4 habits. */
    suspend fun createInitialHabits(drafts: List<HabitDraft>, createdOn: LocalDate): HabitResult {
        if (drafts.isEmpty() || drafts.size > MAX_ACTIVE_HABITS) {
            return HabitResult.Error("Create between 1 and $MAX_ACTIVE_HABITS habits.")
        }
        drafts.forEach { draft -> validate(draft)?.let { return HabitResult.Error(it) } }
        val result = db.withTransaction {
            val active = dao.activeHabits()
            if (active.size + drafts.size > MAX_ACTIVE_HABITS) {
                return@withTransaction HabitResult.Error(
                    "${active.size} habits already exist. Add the rest on the Habits tab.",
                )
            }
            val freeSlots = (0 until MAX_ACTIVE_HABITS).filter { slot -> active.none { it.slot == slot } }
            val ids = drafts.mapIndexed { index, draft ->
                dao.insertHabit(draft.toHabit(slot = freeSlots[index], createdOn = createdOn))
            }
            HabitResult.Ok(ids.first())
        }
        if (result is HabitResult.Ok) {
            fillMissingDays()
            changeNotifier.notifyChanged()
        }
        return result
    }

    /** Adds a habit when fewer than four are active. */
    suspend fun addHabit(draft: HabitDraft): HabitResult {
        validate(draft)?.let { return HabitResult.Error(it) }
        val today = time.today()
        val result = db.withTransaction {
            val active = dao.activeHabits()
            if (active.size >= MAX_ACTIVE_HABITS) {
                return@withTransaction HabitResult.Error("You can have at most $MAX_ACTIVE_HABITS active habits.")
            }
            val slot = (0 until MAX_ACTIVE_HABITS).first { candidate -> active.none { it.slot == candidate } }
            val id = dao.insertHabit(draft.toHabit(slot, today))
            ensureDay(today, listOf(dao.habit(id)!!))
            recomputeReward(today)
            HabitResult.Ok(id)
        }
        changeNotifier.notifyChanged()
        return result
    }

    /**
     * Edits name, type, target and step. Allowed for 7 days after creation, then locked until the
     * habit graduates. During the first 7 days the starting target moves with the target.
     */
    suspend fun updateHabitDetails(habitId: Long, draft: HabitDraft): HabitResult {
        validate(draft)?.let { return HabitResult.Error(it) }
        val today = time.today()
        val habit = dao.habit(habitId) ?: return HabitResult.Error("Habit not found.")
        if (!habit.isActive()) return HabitResult.Error("Archived habits can't be edited.")
        val inWindow = EditWindow.isInHabitEditWindow(habit, today)
        val graduated = graduationProgress(habitId, today).graduated
        if (!EditWindow.canEditHabitDetails(habit, today, graduated)) {
            return HabitResult.Error("Locked until this habit graduates.")
        }
        if (!inWindow && draft.type != habit.type) {
            return HabitResult.Error("The type can only be changed during the habit's first day.")
        }
        db.withTransaction {
            val newTarget = draft.target
            val oldTarget = habit.currentTarget
            val startingTarget = when {
                newTarget == null -> null
                inWindow || habit.startingTarget == null -> newTarget
                Targets.isEasier(draft.type, newTarget, habit.startingTarget) -> newTarget
                else -> habit.startingTarget
            }
            dao.updateHabit(
                habit.copy(
                    name = draft.name.trim(),
                    type = draft.type,
                    currentTarget = newTarget,
                    startingTarget = startingTarget,
                    step = draft.step,
                    mandatory = draft.mandatory,
                ),
            )
            if (draft.type == habit.type && oldTarget != null && newTarget != null && oldTarget != newTarget) {
                dao.insertTargetChange(TargetChange(0, habitId, today, oldTarget, newTarget, TargetChangeReason.EDIT))
            }
            recomputeReward(today)
            recomputeReward(today.minusDays(1))
        }
        changeNotifier.notifyChanged()
        return HabitResult.Ok(habitId)
    }

    /** The mandatory flag can be changed at any time. It affects today's and yesterday's reward. */
    suspend fun setMandatory(habitId: Long, mandatory: Boolean) {
        val today = time.today()
        db.withTransaction {
            val habit = dao.habit(habitId) ?: return@withTransaction
            dao.updateHabit(habit.copy(mandatory = mandatory))
            recomputeReward(today)
            recomputeReward(today.minusDays(1))
        }
        changeNotifier.notifyChanged()
    }

    /**
     * Replaces a graduated habit. The old habit is archived with its history and the new one takes
     * its slot from today.
     */
    suspend fun replaceHabit(oldHabitId: Long, draft: HabitDraft): HabitResult {
        validate(draft)?.let { return HabitResult.Error(it) }
        val today = time.today()
        val old = dao.habit(oldHabitId) ?: return HabitResult.Error("Habit not found.")
        if (!old.isActive()) return HabitResult.Error("This habit is already archived.")
        val progress = graduationProgress(oldHabitId, today)
        if (!progress.graduated) {
            return HabitResult.Error(
                "Only graduated habits can be replaced (${progress.label}, needs ${Graduation.REQUIRED_YES}).",
            )
        }
        val newId = db.withTransaction {
            dao.updateHabit(old.copy(archivedOn = today))
            dao.deleteLogsFrom(oldHabitId, today)
            val id = dao.insertHabit(draft.toHabit(slot = old.slot, createdOn = today))
            ensureDay(today, listOf(dao.habit(id)!!))
            recomputeReward(today)
            id
        }
        changeNotifier.notifyChanged()
        return HabitResult.Ok(newId)
    }

    /**
     * Deletes an active habit. During its first day it is removed completely (with its entries).
     * After that it can only be deleted when a cycle ends, and is then archived so its history is
     * kept. [cycleAnchor] is the cycle start date from the settings.
     */
    suspend fun deleteHabit(habitId: Long, cycleAnchor: LocalDate?): HabitResult {
        val today = time.today()
        val habit = dao.habit(habitId) ?: return HabitResult.Error("Habit not found.")
        when (HabitDeletionRule.mode(habit, today, cycleAnchor)) {
            HabitDeletion.NOT_ALLOWED -> return HabitResult.Error(
                "This habit is locked. You can delete it when the current cycle ends.",
            )
            HabitDeletion.DELETE_COMPLETELY -> db.withTransaction {
                val affectedDates = dao.logDates(habitId)
                dao.deleteHabit(habit)
                (affectedDates + listOf(today.minusDays(1), today)).distinct().forEach { recomputeReward(it) }
            }
            HabitDeletion.ARCHIVE -> db.withTransaction {
                dao.updateHabit(habit.copy(archivedOn = today))
                dao.deleteLogsFrom(habitId, today)
                db.cycleDao().supersedePendingPrompts(habitId)
                recomputeReward(today)
            }
        }
        changeNotifier.notifyChanged()
        return HabitResult.Ok(habitId)
    }

    private suspend fun ensureDay(date: LocalDate, habits: List<Habit>) {
        dao.insertDayIfMissing(DayRecord(date))
        val logs = DayFiller.habitsForDate(habits, date).map { HabitLog(date, it.id) }
        if (logs.isNotEmpty()) dao.insertLogsIfMissing(logs)
    }

    /** Must run inside a transaction. */
    private suspend fun recomputeReward(date: LocalDate) {
        val habits = dao.habitsLoggedOn(date)
        val done = dao.logsOn(date).filter { it.done }.map { it.habitId }.toSet()
        dao.updateReward(date, RewardRule.evaluate(habits, done).unlocked)
    }

    private fun buildDayView(
        date: LocalDate,
        habits: List<Habit>,
        logs: List<HabitLog>,
        day: DayRecord?,
        fallbackToActive: Boolean,
    ): DayView {
        val logByHabit = logs.associateBy { it.habitId }
        var shown = habits.filter { it.isActiveOn(date) || it.id in logByHabit }
        if (shown.isEmpty() && fallbackToActive) shown = habits.filter { it.isActive() }
        shown = shown.sortedBy { it.slot }
        val entries = shown.map { habit ->
            val log = logByHabit[habit.id]
            HabitEntry(habit, done = log?.done ?: false, actualTime = log?.actualTime)
        }
        val doneIds = entries.filter { it.done }.map { it.habit.id }.toSet()
        return DayView(
            date = date,
            entries = entries,
            remark = day?.remark.orEmpty(),
            reward = RewardRule.evaluate(shown, doneIds),
        )
    }

    private fun validate(draft: HabitDraft): String? {
        val name = draft.name.trim()
        return when {
            name.isEmpty() -> "Give the habit a name."
            name.length > NAME_MAX_LENGTH -> "Keep the name under $NAME_MAX_LENGTH characters."
            draft.type.hasTarget && draft.target == null -> "Set a target."
            draft.type == HabitType.DURATION && draft.target!! !in 1..Targets.MAX_DURATION_MINUTES ->
                "Duration must be between 1 and ${Targets.MAX_DURATION_MINUTES} minutes."
            draft.type == HabitType.DEADLINE && draft.target!! !in 0 until Targets.MINUTES_PER_DAY ->
                "Pick a valid time."
            draft.type.hasTarget && (draft.step == null || draft.step !in 1..MAX_STEP) ->
                "Step must be between 1 and $MAX_STEP minutes."
            else -> null
        }
    }

    private fun HabitDraft.toHabit(slot: Int, createdOn: LocalDate): Habit {
        val target = if (type.hasTarget) target else null
        return Habit(
            slot = slot,
            name = name.trim(),
            type = type,
            currentTarget = target,
            startingTarget = target,
            step = if (type.hasTarget) step else null,
            mandatory = mandatory,
            createdOn = createdOn,
        )
    }

    companion object {
        const val MAX_ACTIVE_HABITS = 4
        const val REMARK_MAX_LENGTH = 50
        const val NAME_MAX_LENGTH = 40
        const val MAX_STEP = 240
    }
}
