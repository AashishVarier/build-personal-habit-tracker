package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.testing.habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardRuleTest {
    private val a = habit(id = 1, name = "Habit A", mandatory = true, slot = 0)
    private val b = habit(id = 2, name = "Habit B", slot = 1)
    private val c = habit(id = 3, name = "Habit C", type = HabitType.BOOLEAN, slot = 2)
    private val d = habit(id = 4, name = "Habit D", slot = 3)
    private val four = listOf(a, b, c, d)

    @Test
    fun `unlocks with all mandatory and at least three yes`() {
        val status = RewardRule.evaluate(four, setOf(1L, 2L, 3L))
        assertTrue(status.unlocked)
        assertTrue(RewardRule.missingMessages(status).isEmpty())
    }

    @Test
    fun `three yes without the mandatory habit stays locked and names it`() {
        val status = RewardRule.evaluate(four, setOf(2L, 3L, 4L))
        assertFalse(status.unlocked)
        assertEquals(listOf(a), status.missingRequired)
        assertEquals(listOf("Habit A still needed."), RewardRule.missingMessages(status))
    }

    @Test
    fun `mandatory done but only two yes asks for one more of the rest`() {
        val status = RewardRule.evaluate(four, setOf(1L, 2L))
        assertFalse(status.unlocked)
        assertEquals(1, status.extraNeeded)
        assertEquals(listOf("One more of Habit C or Habit D still needed."), RewardRule.missingMessages(status))
    }

    @Test
    fun `all four yes unlocks`() {
        assertTrue(RewardRule.evaluate(four, setOf(1L, 2L, 3L, 4L)).unlocked)
    }

    @Test
    fun `with fewer than three habits all must be yes`() {
        val two = listOf(b, d)
        assertFalse(RewardRule.evaluate(two, setOf(2L)).unlocked)
        assertEquals(listOf("Habit D still needed."), RewardRule.missingMessages(RewardRule.evaluate(two, setOf(2L))))
        assertTrue(RewardRule.evaluate(two, setOf(2L, 4L)).unlocked)

        val one = listOf(b)
        assertFalse(RewardRule.evaluate(one, emptySet()).unlocked)
        assertTrue(RewardRule.evaluate(one, setOf(2L)).unlocked)
    }

    @Test
    fun `with exactly three habits all three are required`() {
        val three = listOf(b, c, d)
        val status = RewardRule.evaluate(three, setOf(2L, 3L))
        assertFalse(status.unlocked)
        assertEquals(listOf(d), status.missingRequired)
        assertTrue(RewardRule.evaluate(three, setOf(2L, 3L, 4L)).unlocked)
    }

    @Test
    fun `several mandatory habits are all listed`() {
        val habits = listOf(a, b.copy(mandatory = true), c, d)
        val status = RewardRule.evaluate(habits, setOf(3L, 4L))
        assertFalse(status.unlocked)
        assertEquals(
            listOf("Habit A still needed.", "Habit B still needed."),
            RewardRule.missingMessages(status),
        )
    }

    @Test
    fun `no habits never unlocks`() {
        assertFalse(RewardRule.evaluate(emptyList(), emptySet()).unlocked)
    }
}
