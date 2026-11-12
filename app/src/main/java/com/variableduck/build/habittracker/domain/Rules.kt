package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.isActive
import com.variableduck.build.habittracker.model.isActiveOn
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A habit graduates with at least 26 YES in the last 30 days (today included). */
object Graduation {
    const val WINDOW_DAYS = 30
    const val REQUIRED_YES = 26

    fun windowStart(today: LocalDate): LocalDate = today.minusDays(WINDOW_DAYS - 1L)

    data class Progress(val yes: Int) {
        val window: Int get() = WINDOW_DAYS
        val required: Int get() = REQUIRED_YES
        val graduated: Boolean get() = yes >= REQUIRED_YES
        val remaining: Int get() = maxOf(0, REQUIRED_YES - yes)
        val label: String get() = "$yes/$WINDOW_DAYS"
    }

    fun progress(yesDates: Collection<LocalDate>, today: LocalDate): Progress {
        val start = windowStart(today)
        return Progress(yesDates.count { !it.isBefore(start) && !it.isAfter(today) })
    }
}

object EditWindow {
    /**
     * A new habit can be edited (and deleted) on the day it was added and for this many days
     * after, so there are always at least 24 hours to fix a mistake. Then it is locked.
     */
    const val NEW_HABIT_GRACE_DAYS = 1

    /** Only today and yesterday are editable, unless history-import mode is on. */
    fun canEditEntry(date: LocalDate, today: LocalDate, importMode: Boolean = false): Boolean =
        if (importMode) {
            !date.isAfter(today)
        } else {
            date == today || date == today.minusDays(1)
        }

    fun daysSinceCreated(habit: Habit, today: LocalDate): Long =
        ChronoUnit.DAYS.between(habit.createdOn, today)

    fun isInHabitEditWindow(habit: Habit, today: LocalDate): Boolean =
        daysSinceCreated(habit, today) <= NEW_HABIT_GRACE_DAYS

    /** The last day on which a new habit can still be edited or deleted freely. */
    fun lastFreeEditDay(habit: Habit): LocalDate = habit.createdOn.plusDays(NEW_HABIT_GRACE_DAYS.toLong())

    /** Locked after the grace period until the habit graduates. */
    fun canEditHabitDetails(habit: Habit, today: LocalDate, graduated: Boolean): Boolean =
        isInHabitEditWindow(habit, today) || graduated
}

enum class HabitDeletion {
    /** Still in its first day: removed together with its entries. */
    DELETE_COMPLETELY,

    /** Locked habit at a cycle boundary: archived, so its history is kept. */
    ARCHIVE,

    /** Locked habit in the middle of a cycle. */
    NOT_ALLOWED,
}

/**
 * When an active habit may be deleted: freely during its first day, and after that only when a
 * cycle ends (on the last day of a cycle or the first day of the next one).
 */
object HabitDeletionRule {
    fun mode(habit: Habit, today: LocalDate, cycleAnchor: LocalDate?): HabitDeletion = when {
        !habit.isActive() -> HabitDeletion.NOT_ALLOWED
        EditWindow.isInHabitEditWindow(habit, today) -> HabitDeletion.DELETE_COMPLETELY
        cycleAnchor != null && isCycleBoundary(cycleAnchor, today) -> HabitDeletion.ARCHIVE
        else -> HabitDeletion.NOT_ALLOWED
    }

    fun isCycleBoundary(anchor: LocalDate, today: LocalDate): Boolean {
        val cycle = CycleCalendar.cycleFor(anchor, today)
        if (cycle.index < 0) return false
        val day = CycleCalendar.dayInCycle(anchor, today)
        return day == CycleCalendar.LENGTH || (day == 1 && cycle.index >= 1)
    }

    /** The next day on which a locked habit can be deleted: the last day of the current cycle. */
    fun nextDeletionDay(anchor: LocalDate, today: LocalDate): LocalDate {
        if (isCycleBoundary(anchor, today)) return today
        val cycle = CycleCalendar.cycleFor(anchor, today)
        return if (cycle.index < 0) CycleCalendar.cycleAt(anchor, 0).end else cycle.end
    }
}

/** Missed days are recorded as NO: there is no blank state. */
object DayFiller {
    /**
     * Dates that need a row: everything after the last recorded date up to [today]. With no rows
     * yet, tracking starts at [trackingStart].
     */
    fun datesToFill(lastRecorded: LocalDate?, trackingStart: LocalDate, today: LocalDate): List<LocalDate> {
        val start = lastRecorded?.plusDays(1) ?: trackingStart
        if (start.isAfter(today)) return emptyList()
        val days = ChronoUnit.DAYS.between(start, today)
        return (0..days).map { start.plusDays(it) }
    }

    fun habitsForDate(habits: List<Habit>, date: LocalDate): List<Habit> = habits.filter { it.isActiveOn(date) }
}

object RetentionPolicy {
    const val DEFAULT_MONTHS = 6
    const val MIN_MONTHS = 2
    const val MAX_MONTHS = 60
    const val CHECK_INTERVAL_DAYS = 7L

    /**
     * Entries dated strictly before the returned cutoff may be deleted. The cutoff is clamped so
     * that the 30-day graduation window and every cycle that still needs evaluating are always kept.
     */
    fun cutoff(today: LocalDate, retentionMonths: Int, protectFrom: LocalDate?): LocalDate {
        var cutoff = today.minusMonths(retentionMonths.coerceIn(MIN_MONTHS, MAX_MONTHS).toLong())
        val graduationStart = Graduation.windowStart(today)
        if (cutoff.isAfter(graduationStart)) cutoff = graduationStart
        if (protectFrom != null && cutoff.isAfter(protectFrom)) cutoff = protectFrom
        return cutoff
    }

    fun isCheckDue(lastCheck: LocalDate?, today: LocalDate): Boolean =
        lastCheck == null || ChronoUnit.DAYS.between(lastCheck, today) >= CHECK_INTERVAL_DAYS
}
