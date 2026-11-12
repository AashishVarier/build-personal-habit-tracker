package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.model.CycleOutcome
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.testing.habit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleEvaluatorTest {
    private val defaults = Thresholds()
    private val walk = habit(name = "Walk", type = HabitType.DURATION, current = 10, starting = 10, step = 5)

    @Test
    fun `10 or more yes prompts a level up and does not change the target`() {
        for (yes in listOf(10, 12, 14)) {
            val decision = CycleEvaluator.decide(walk, yes, defaults)
            assertEquals(CycleOutcome.LEVEL_UP_PROMPT, decision.outcome)
            assertEquals(10, decision.oldTarget)
            assertEquals(15, decision.newTarget)
        }
    }

    @Test
    fun `5 to 9 yes means no change`() {
        for (yes in 5..9) {
            val decision = CycleEvaluator.decide(walk, yes, defaults)
            assertEquals("yes=$yes", CycleOutcome.NO_CHANGE, decision.outcome)
            assertEquals(10, decision.newTarget)
        }
    }

    @Test
    fun `fewer than 5 yes steps down automatically`() {
        val levelled = walk.copy(currentTarget = 20)
        for (yes in 0..4) {
            val decision = CycleEvaluator.decide(levelled, yes, defaults)
            assertEquals("yes=$yes", CycleOutcome.STEPPED_DOWN, decision.outcome)
            assertEquals(20, decision.oldTarget)
            assertEquals(15, decision.newTarget)
        }
    }

    @Test
    fun `step down never goes below the starting target`() {
        val atStart = CycleEvaluator.decide(walk, 2, defaults)
        assertEquals(CycleOutcome.AT_STARTING_TARGET, atStart.outcome)
        assertEquals(10, atStart.newTarget)

        // One step would overshoot the starting target, so it stops exactly there.
        val nearStart = walk.copy(currentTarget = 12)
        assertEquals(10, CycleEvaluator.decide(nearStart, 0, defaults).newTarget)
    }

    @Test
    fun `deadline levels up by moving earlier and steps down by moving later`() {
        val wake = habit(name = "Wake up", type = HabitType.DEADLINE, current = 7 * 60 + 15, starting = 7 * 60 + 30, step = 15)

        val up = CycleEvaluator.decide(wake, 11, defaults)
        assertEquals(CycleOutcome.LEVEL_UP_PROMPT, up.outcome)
        assertEquals(7 * 60, up.newTarget)

        val down = CycleEvaluator.decide(wake, 3, defaults)
        assertEquals(CycleOutcome.STEPPED_DOWN, down.outcome)
        assertEquals(7 * 60 + 30, down.newTarget)

        // Never later (easier) than the starting deadline.
        val atStart = CycleEvaluator.decide(wake.copy(currentTarget = 7 * 60 + 30), 0, defaults)
        assertEquals(CycleOutcome.AT_STARTING_TARGET, atStart.outcome)
        assertEquals(7 * 60 + 30, atStart.newTarget)
    }

    @Test
    fun `thresholds are configurable`() {
        val strict = Thresholds(levelUp = 12, stepDown = 7)
        assertEquals(CycleOutcome.NO_CHANGE, CycleEvaluator.decide(walk, 11, strict).outcome)
        assertEquals(CycleOutcome.LEVEL_UP_PROMPT, CycleEvaluator.decide(walk, 12, strict).outcome)
        assertEquals(CycleOutcome.STEPPED_DOWN, CycleEvaluator.decide(walk.copy(currentTarget = 20), 6, strict).outcome)
    }

    @Test
    fun `sanitized thresholds stay in range`() {
        assertEquals(Thresholds(14, 14), Thresholds.sanitized(20, 30))
        assertEquals(Thresholds(1, 0), Thresholds.sanitized(0, -3))
        assertEquals(Thresholds(8, 8), Thresholds.sanitized(8, 9))
    }

    @Test
    fun `boolean habits and habits added mid-cycle are not evaluated`() {
        val cycle = Cycle(0, LocalDate.of(2026, 3, 1))
        assertFalse(CycleEvaluator.isEligible(habit(type = HabitType.BOOLEAN), cycle))
        assertTrue(CycleEvaluator.isEligible(walk.copy(createdOn = cycle.start), cycle))
        assertFalse(CycleEvaluator.isEligible(walk.copy(createdOn = cycle.start.plusDays(3)), cycle))
        assertEquals(CycleOutcome.NO_CHANGE, CycleEvaluator.decide(habit(type = HabitType.BOOLEAN), 0, defaults).outcome)
    }

    @Test
    fun `cycle calendar splits time into 14-day cycles`() {
        val anchor = LocalDate.of(2026, 3, 1)
        assertEquals(0L, CycleCalendar.cycleFor(anchor, anchor).index)
        assertEquals(0L, CycleCalendar.cycleFor(anchor, anchor.plusDays(13)).index)
        assertEquals(1L, CycleCalendar.cycleFor(anchor, anchor.plusDays(14)).index)
        assertEquals(-1L, CycleCalendar.cycleFor(anchor, anchor.minusDays(1)).index)
        assertEquals(14, CycleCalendar.dayInCycle(anchor, anchor.plusDays(13)))
        assertEquals(1, CycleCalendar.dayInCycle(anchor, anchor.plusDays(14)))
    }

    @Test
    fun `only fully ended cycles not yet evaluated are returned`() {
        val anchor = LocalDate.of(2026, 3, 1)
        // Day 14 of cycle 0: nothing has ended yet.
        assertTrue(CycleCalendar.cyclesToEvaluate(anchor, anchor.plusDays(13), null).isEmpty())
        // First day of cycle 1: cycle 0 has ended.
        assertEquals(listOf(0L), CycleCalendar.cyclesToEvaluate(anchor, anchor.plusDays(14), null).map { it.index })
        // Three cycles later with cycle 0 already evaluated.
        val evaluated = CycleCalendar.cycleAt(anchor, 0).end
        assertEquals(
            listOf(1L, 2L),
            CycleCalendar.cyclesToEvaluate(anchor, anchor.plusDays(42), evaluated).map { it.index },
        )
        // Anchor in the future: nothing to evaluate.
        assertTrue(CycleCalendar.cyclesToEvaluate(anchor, anchor.minusDays(5), null).isEmpty())
    }
}
