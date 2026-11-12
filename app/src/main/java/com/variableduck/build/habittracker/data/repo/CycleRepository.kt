package com.variableduck.build.habittracker.data.repo

import androidx.room.withTransaction
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.domain.CycleEvaluator
import com.variableduck.build.habittracker.domain.Targets
import com.variableduck.build.habittracker.model.CycleOutcome
import com.variableduck.build.habittracker.model.CycleReview
import com.variableduck.build.habittracker.model.CycleReviewItem
import com.variableduck.build.habittracker.model.CycleReviewWithItems
import com.variableduck.build.habittracker.model.LevelUpDecision
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import com.variableduck.build.habittracker.model.isActive
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** End-of-cycle evaluation: level-up prompts, automatic step-downs and their history. */
@Singleton
class CycleRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
    private val changeNotifier: DataChangeNotifier,
) {
    private val cycleDao = db.cycleDao()
    private val trackerDao = db.trackerDao()

    fun observePendingReviews(): Flow<List<CycleReviewWithItems>> = cycleDao.observePendingReviews()

    suspend fun lastEvaluatedEnd(): LocalDate? = cycleDao.lastEvaluatedEnd()

    /**
     * Evaluates every cycle that ended since the last evaluation. Step-downs are applied right away;
     * level-ups wait for the user's confirmation. Returns the number of reviews that need attention.
     */
    suspend fun evaluateEndedCycles(today: LocalDate = time.today()): Int {
        val current = settings.current()
        val anchor = current.cycleStart ?: return 0
        val thresholds = current.thresholds
        val (needsAttention, changed) = db.withTransaction {
            val cycles = CycleCalendar.cyclesToEvaluate(anchor, today, cycleDao.lastEvaluatedEnd())
            var attention = 0
            var anyTargetChanged = false
            for (cycle in cycles) {
                val reviewId = cycleDao.insertReview(
                    CycleReview(cycleStart = cycle.start, cycleEnd = cycle.end, evaluatedOn = today, acknowledged = false),
                )
                var items = 0
                for (habit in trackerDao.activeHabits()) {
                    if (!CycleEvaluator.isEligible(habit, cycle)) continue
                    val yes = trackerDao.yesCount(habit.id, cycle.start, cycle.end)
                    val decision = CycleEvaluator.decide(habit, yes, thresholds)
                    when (decision.outcome) {
                        CycleOutcome.STEPPED_DOWN -> {
                            trackerDao.updateHabit(habit.copy(currentTarget = decision.newTarget))
                            trackerDao.insertTargetChange(
                                TargetChange(
                                    habitId = habit.id,
                                    date = cycle.end.plusDays(1),
                                    oldTarget = decision.oldTarget!!,
                                    newTarget = decision.newTarget!!,
                                    reason = TargetChangeReason.STEP_DOWN,
                                ),
                            )
                            anyTargetChanged = true
                        }
                        // A newer prompt replaces an unanswered older one for the same habit.
                        CycleOutcome.LEVEL_UP_PROMPT -> cycleDao.supersedePendingPrompts(habit.id)
                        CycleOutcome.AT_STARTING_TARGET, CycleOutcome.NO_CHANGE -> Unit
                    }
                    cycleDao.insertItem(
                        CycleReviewItem(
                            reviewId = reviewId,
                            habitId = habit.id,
                            yesCount = yes,
                            outcome = decision.outcome,
                            oldTarget = decision.oldTarget,
                            newTarget = decision.newTarget,
                            decision = if (decision.outcome == CycleOutcome.LEVEL_UP_PROMPT) {
                                LevelUpDecision.PENDING
                            } else {
                                null
                            },
                        ),
                    )
                    items++
                }
                if (items == 0) cycleDao.acknowledge(reviewId) else attention++
            }
            attention to anyTargetChanged
        }
        if (changed) changeNotifier.notifyChanged()
        return needsAttention
    }

    /** "Yes" levels the habit up by one step; "Not yet" keeps the target. */
    suspend fun decideLevelUp(reviewId: Long, habitId: Long, accept: Boolean) {
        val today = time.today()
        db.withTransaction {
            val item = cycleDao.item(reviewId, habitId) ?: return@withTransaction
            if (item.decision != LevelUpDecision.PENDING) return@withTransaction
            if (accept) {
                val habit = trackerDao.habit(habitId)
                val newTarget = habit?.let { Targets.harder(it) }
                if (habit != null && habit.isActive() && newTarget != null) {
                    trackerDao.updateHabit(habit.copy(currentTarget = newTarget))
                    trackerDao.insertTargetChange(
                        TargetChange(
                            habitId = habitId,
                            date = today,
                            oldTarget = habit.currentTarget!!,
                            newTarget = newTarget,
                            reason = TargetChangeReason.LEVEL_UP,
                        ),
                    )
                    cycleDao.updateItem(
                        item.copy(decision = LevelUpDecision.ACCEPTED, oldTarget = habit.currentTarget, newTarget = newTarget),
                    )
                } else {
                    cycleDao.updateItem(item.copy(decision = LevelUpDecision.SUPERSEDED))
                }
            } else {
                cycleDao.updateItem(item.copy(decision = LevelUpDecision.DECLINED))
            }
        }
        changeNotifier.notifyChanged()
    }

    /** Closes a review once every level-up prompt in it has an answer. */
    suspend fun acknowledge(reviewId: Long): Boolean = db.withTransaction {
        if (cycleDao.pendingDecisionCount(reviewId) > 0) return@withTransaction false
        cycleDao.acknowledge(reviewId)
        true
    }

    /**
     * After the cycle start date changes, cycles that already ended under the new alignment are
     * marked as evaluated so that old data is never judged retroactively.
     */
    suspend fun onCycleStartChanged(newAnchor: LocalDate, today: LocalDate = time.today()) {
        val lastEnded = CycleCalendar.lastEndedCycle(newAnchor, today) ?: return
        db.withTransaction {
            val marker = cycleDao.lastEvaluatedEnd()
            if (marker == null || lastEnded.end.isAfter(marker)) {
                cycleDao.insertReview(
                    CycleReview(
                        cycleStart = lastEnded.start,
                        cycleEnd = lastEnded.end,
                        evaluatedOn = today,
                        acknowledged = true,
                        skipped = true,
                    ),
                )
            }
        }
    }
}
