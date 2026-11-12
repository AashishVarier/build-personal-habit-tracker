package com.variableduck.build.habittracker.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.time.LocalDate

enum class HabitType {
    /** "Do X minutes". Targets are stored in minutes. */
    DURATION,

    /** "By time T". Targets are stored as minutes after midnight; earlier is harder. */
    DEADLINE,

    /** Done or not done, no target. */
    BOOLEAN;

    val hasTarget: Boolean get() = this != BOOLEAN

    val defaultStep: Int?
        get() = when (this) {
            DURATION -> 5
            DEADLINE -> 15
            BOOLEAN -> null
        }

    val defaultTarget: Int?
        get() = when (this) {
            DURATION -> 10
            DEADLINE -> 7 * 60 + 30
            BOOLEAN -> null
        }
}

/**
 * A habit. Active habits have [archivedOn] == null and occupy one of the four [slot]s.
 * [createdOn] is the date the habit was created, or the date it replaced a graduated habit.
 */
@Entity(tableName = "habits", indices = [Index("archivedOn")])
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slot: Int,
    val name: String,
    val type: HabitType,
    val currentTarget: Int?,
    val startingTarget: Int?,
    val step: Int?,
    val mandatory: Boolean,
    val createdOn: LocalDate,
    val archivedOn: LocalDate? = null,
)

fun Habit.isActive(): Boolean = archivedOn == null

/** True if the habit was being tracked on [date]. */
fun Habit.isActiveOn(date: LocalDate): Boolean =
    !createdOn.isAfter(date) && (archivedOn == null || archivedOn.isAfter(date))

/** One row per calendar date. [rewardUnlocked] is stored so history keeps the rule as it was. */
@Entity(tableName = "days")
data class DayRecord(
    @PrimaryKey val date: LocalDate,
    val remark: String = "",
    val rewardUnlocked: Boolean = false,
)

/** YES/NO for one habit on one date. A missing row means NO. */
@Entity(
    tableName = "habit_logs",
    primaryKeys = ["date", "habitId"],
    foreignKeys = [
        ForeignKey(
            entity = DayRecord::class,
            parentColumns = ["date"],
            childColumns = ["date"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("habitId")],
)
data class HabitLog(
    val date: LocalDate,
    val habitId: Long,
    val done: Boolean = false,
    /** For DEADLINE habits: the actual time, in minutes after midnight. */
    val actualTime: Int? = null,
)

enum class TargetChangeReason { LEVEL_UP, STEP_DOWN, EDIT }

@Entity(
    tableName = "target_changes",
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("habitId"), Index("date")],
)
data class TargetChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val date: LocalDate,
    val oldTarget: Int,
    val newTarget: Int,
    val reason: TargetChangeReason,
)

/**
 * One evaluated 14-day cycle. The latest [cycleEnd] marks how far evaluation has progressed.
 * [skipped] rows are markers only (for example after changing the cycle start date).
 */
@Entity(tableName = "cycle_reviews", indices = [Index("cycleEnd")])
data class CycleReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val evaluatedOn: LocalDate,
    val acknowledged: Boolean,
    val skipped: Boolean = false,
)

enum class CycleOutcome {
    /** 10+ of 14 YES: ask before levelling up. */
    LEVEL_UP_PROMPT,

    /** Fewer than 5 of 14 YES: the target was stepped down automatically. */
    STEPPED_DOWN,

    /** Fewer than 5 of 14 YES, but the target is already at its starting value. */
    AT_STARTING_TARGET,

    /** 5-9 of 14 YES. */
    NO_CHANGE,
}

enum class LevelUpDecision { PENDING, ACCEPTED, DECLINED, SUPERSEDED }

@Entity(
    tableName = "cycle_review_items",
    primaryKeys = ["reviewId", "habitId"],
    foreignKeys = [
        ForeignKey(
            entity = CycleReview::class,
            parentColumns = ["id"],
            childColumns = ["reviewId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("habitId")],
)
data class CycleReviewItem(
    val reviewId: Long,
    val habitId: Long,
    val yesCount: Int,
    val outcome: CycleOutcome,
    val oldTarget: Int?,
    val newTarget: Int?,
    val decision: LevelUpDecision?,
)

data class CycleReviewWithItems(
    @Embedded val review: CycleReview,
    @Relation(parentColumn = "id", entityColumn = "reviewId")
    val items: List<CycleReviewItem>,
)

/** Result row for grouped YES counts. */
data class HabitYesCount(
    val habitId: Long,
    val yes: Int,
)
