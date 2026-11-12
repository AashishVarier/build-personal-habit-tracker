package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.model.Habit

/**
 * Outcome of the daily reward rule.
 *
 * @property missingRequired habits that must still be done (mandatory ones, or every habit when
 *   there are three or fewer).
 * @property extraNeeded how many more of [optionalCandidates] are needed on top of [missingRequired].
 */
data class RewardStatus(
    val unlocked: Boolean,
    val habitCount: Int,
    val yesCount: Int,
    val requiredCount: Int,
    val missingRequired: List<Habit>,
    val extraNeeded: Int,
    val optionalCandidates: List<Habit>,
) {
    companion object {
        val NO_HABITS = RewardStatus(false, 0, 0, 0, emptyList(), 0, emptyList())
    }
}

/**
 * A day's guilt-free reward unlocks when **all mandatory habits are YES** and **at least 3
 * habits are YES**. With fewer than 3 habits, all of them must be YES.
 */
object RewardRule {
    const val MIN_YES = 3

    fun evaluate(habits: List<Habit>, doneHabitIds: Set<Long>): RewardStatus {
        if (habits.isEmpty()) return RewardStatus.NO_HABITS
        val required = minOf(MIN_YES, habits.size)
        val yes = habits.count { it.id in doneHabitIds }
        val notDone = habits.filter { it.id !in doneHabitIds }
        val missingRequired = if (habits.size <= MIN_YES) notDone else notDone.filter { it.mandatory }
        val optional = notDone.filter { it !in missingRequired }
        val extra = maxOf(0, required - yes - missingRequired.size)
        return RewardStatus(
            unlocked = missingRequired.isEmpty() && yes >= required,
            habitCount = habits.size,
            yesCount = yes,
            requiredCount = required,
            missingRequired = missingRequired,
            extraNeeded = extra,
            optionalCandidates = if (extra > 0) optional else emptyList(),
        )
    }

    /** Human readable lines describing what is still missing, e.g. "Walk still needed." */
    fun missingMessages(status: RewardStatus): List<String> {
        if (status.unlocked || status.habitCount == 0) return emptyList()
        val lines = status.missingRequired.map { "${it.name} still needed." }.toMutableList()
        if (status.extraNeeded > 0) {
            val candidates = status.optionalCandidates
            lines += if (candidates.size <= status.extraNeeded) {
                candidates.map { "${it.name} still needed." }
            } else {
                val names = candidates.joinToString(" or ") { it.name }
                listOf(
                    if (status.extraNeeded == 1) {
                        "One more of $names still needed."
                    } else {
                        "${status.extraNeeded} more of $names still needed."
                    },
                )
            }
        }
        return lines
    }
}
