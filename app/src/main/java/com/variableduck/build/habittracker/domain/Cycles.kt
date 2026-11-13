package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.model.CycleOutcome
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A 14-day tracking cycle. Index 0 starts on the anchor date chosen during onboarding. */
data class Cycle(val index: Long, val start: LocalDate) {
    val end: LocalDate get() = start.plusDays(CycleCalendar.LENGTH - 1L)

    /** 1-based number for display. Days before the anchor fall into cycle 0 or below. */
    val number: Long get() = index + 1

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun days(): List<LocalDate> = (0 until CycleCalendar.LENGTH).map { start.plusDays(it.toLong()) }
}

object CycleCalendar {
    const val LENGTH = 14

    fun cycleAt(anchor: LocalDate, index: Long): Cycle = Cycle(index, anchor.plusDays(index * LENGTH))

    fun cycleFor(anchor: LocalDate, date: LocalDate): Cycle =
        cycleAt(anchor, Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), LENGTH.toLong()))

    /** 1..14 */
    fun dayInCycle(anchor: LocalDate, date: LocalDate): Int =
        Math.floorMod(ChronoUnit.DAYS.between(anchor, date), LENGTH.toLong()).toInt() + 1

    /**
     * Cycles that have fully ended before [today] and have not been evaluated yet, oldest first.
     * Only cycles with index >= 0 count; days before the anchor are never evaluated.
     */
    fun cyclesToEvaluate(anchor: LocalDate, today: LocalDate, lastEvaluatedEnd: LocalDate?): List<Cycle> {
        val lastEnded = cycleFor(anchor, today).index - 1
        val result = ArrayList<Cycle>()
        var index = lastEnded
        while (index >= 0) {
            val cycle = cycleAt(anchor, index)
            if (lastEvaluatedEnd != null && !cycle.end.isAfter(lastEvaluatedEnd)) break
            result += cycle
            index--
        }
        return result.reversed()
    }

    /** The most recent cycle that has fully ended before [today], if any. */
    fun lastEndedCycle(anchor: LocalDate, today: LocalDate): Cycle? {
        val index = cycleFor(anchor, today).index - 1
        return if (index >= 0) cycleAt(anchor, index) else null
    }

    /** Start of the oldest cycle whose data is still needed (unevaluated, or the current one). */
    fun firstUnevaluatedStart(anchor: LocalDate, today: LocalDate, lastEvaluatedEnd: LocalDate?): LocalDate =
        cyclesToEvaluate(anchor, today, lastEvaluatedEnd).firstOrNull()?.start
            ?: cycleFor(anchor, today).start
}

/** Thresholds out of 14 days. Defaults: level-up prompt at 10+, automatic step-down below 5. */
data class Thresholds(val levelUp: Int = DEFAULT_LEVEL_UP, val stepDown: Int = DEFAULT_STEP_DOWN) {
    companion object {
        const val DEFAULT_LEVEL_UP = 10
        const val DEFAULT_STEP_DOWN = 5

        /** Keeps values in range: level-up 1..14, step-down 0..level-up. */
        fun sanitized(levelUp: Int, stepDown: Int): Thresholds {
            val up = levelUp.coerceIn(1, CycleCalendar.LENGTH)
            return Thresholds(up, stepDown.coerceIn(0, up))
        }
    }
}

/** Target arithmetic. DURATION gets harder by adding minutes; DEADLINE by moving earlier. */
object Targets {
    const val MAX_DURATION_MINUTES = 24 * 60
    const val MINUTES_PER_DAY = 24 * 60

    /** True if [value] is a target the app can store and show: 1..1440 minutes, or a time of day. */
    fun isValid(type: HabitType, value: Int): Boolean = when (type) {
        HabitType.DURATION -> value in 1..MAX_DURATION_MINUTES
        HabitType.DEADLINE -> value in 0 until MINUTES_PER_DAY
        HabitType.BOOLEAN -> true
    }

    /** The next harder target, or null when there is none. */
    fun harder(habit: Habit): Int? {
        val current = habit.currentTarget ?: return null
        val step = habit.step?.takeIf { it > 0 } ?: return null
        val next = when (habit.type) {
            HabitType.DURATION -> minOf(MAX_DURATION_MINUTES, current + step)
            HabitType.DEADLINE -> maxOf(0, current - step)
            HabitType.BOOLEAN -> return null
        }
        return next.takeIf { it != current }
    }

    /** One step easier, but never easier than the starting target. */
    fun easier(habit: Habit): Int? {
        val current = habit.currentTarget ?: return null
        val step = habit.step?.takeIf { it > 0 } ?: return current
        val start = habit.startingTarget ?: current
        return when (habit.type) {
            HabitType.DURATION -> if (current <= start) current else maxOf(start, current - step)
            HabitType.DEADLINE -> if (current >= start) current else minOf(start, current + step)
            HabitType.BOOLEAN -> null
        }
    }

    /** True if [candidate] is an easier target than [reference] for [type]. */
    fun isEasier(type: HabitType, candidate: Int, reference: Int): Boolean = when (type) {
        HabitType.DURATION -> candidate < reference
        HabitType.DEADLINE -> candidate > reference
        HabitType.BOOLEAN -> false
    }
}

data class CycleDecision(val outcome: CycleOutcome, val oldTarget: Int?, val newTarget: Int?)

/**
 * End-of-cycle rule for DURATION and DEADLINE habits:
 *  - YES >= level-up threshold (10): prompt to level up; never level up without confirmation.
 *  - YES <  step-down threshold (5): step down automatically, never below the starting target.
 *  - otherwise: no change.
 */
object CycleEvaluator {
    /** BOOLEAN habits have no target; habits added mid-cycle are not judged on a partial cycle. */
    fun isEligible(habit: Habit, cycle: Cycle): Boolean =
        habit.type.hasTarget && habit.currentTarget != null && !habit.createdOn.isAfter(cycle.start)

    fun decide(habit: Habit, yesCount: Int, thresholds: Thresholds): CycleDecision {
        val current = habit.currentTarget
        if (!habit.type.hasTarget || current == null) {
            return CycleDecision(CycleOutcome.NO_CHANGE, current, current)
        }
        return when {
            yesCount >= thresholds.levelUp -> {
                val next = Targets.harder(habit)
                if (next == null) {
                    CycleDecision(CycleOutcome.NO_CHANGE, current, current)
                } else {
                    CycleDecision(CycleOutcome.LEVEL_UP_PROMPT, current, next)
                }
            }
            yesCount < thresholds.stepDown -> {
                val next = Targets.easier(habit) ?: current
                if (next == current) {
                    CycleDecision(CycleOutcome.AT_STARTING_TARGET, current, current)
                } else {
                    CycleDecision(CycleOutcome.STEPPED_DOWN, current, next)
                }
            }
            else -> CycleDecision(CycleOutcome.NO_CHANGE, current, current)
        }
    }
}
