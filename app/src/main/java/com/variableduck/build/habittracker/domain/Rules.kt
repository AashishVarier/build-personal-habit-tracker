package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.model.Habit
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
    /** Name, target and step may be edited freely for this many days after creation. */
    const val HABIT_EDIT_DAYS = 7

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
        daysSinceCreated(habit, today) < HABIT_EDIT_DAYS

    /** Locked after 7 days until the habit graduates. */
    fun canEditHabitDetails(habit: Habit, today: LocalDate, graduated: Boolean): Boolean =
        isInHabitEditWindow(habit, today) || graduated
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
