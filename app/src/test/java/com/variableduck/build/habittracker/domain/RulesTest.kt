package com.variableduck.build.habittracker.domain

import com.variableduck.build.habittracker.testing.habit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraduationTest {
    private val today = LocalDate.of(2026, 6, 30)

    private fun lastDays(count: Int, skip: Set<Int> = emptySet()) =
        (0 until count).filter { it !in skip }.map { today.minusDays(it.toLong()) }

    @Test
    fun `26 yes in the last 30 days graduates`() {
        val progress = Graduation.progress(lastDays(30, skip = setOf(3, 7, 11, 20)), today)
        assertEquals(26, progress.yes)
        assertTrue(progress.graduated)
        assertEquals("26/30", progress.label)
    }

    @Test
    fun `25 yes does not graduate`() {
        val progress = Graduation.progress(lastDays(25), today)
        assertFalse(progress.graduated)
        assertEquals(1, progress.remaining)
    }

    @Test
    fun `days outside the 30-day window do not count`() {
        val old = (30..60).map { today.minusDays(it.toLong()) }
        val progress = Graduation.progress(old + lastDays(22), today)
        assertEquals(22, progress.yes)
        assertEquals("22/30", progress.label)
    }

    @Test
    fun `the window includes today and the 29 days before it`() {
        assertEquals(today.minusDays(29), Graduation.windowStart(today))
        assertEquals(1, Graduation.progress(listOf(today), today).yes)
        assertEquals(0, Graduation.progress(listOf(today.plusDays(1)), today).yes)
    }
}

class EditWindowTest {
    private val today = LocalDate.of(2026, 6, 30)

    @Test
    fun `only today and yesterday are editable`() {
        assertTrue(EditWindow.canEditEntry(today, today))
        assertTrue(EditWindow.canEditEntry(today.minusDays(1), today))
        assertFalse(EditWindow.canEditEntry(today.minusDays(2), today))
        assertFalse(EditWindow.canEditEntry(today.minusDays(100), today))
        assertFalse(EditWindow.canEditEntry(today.plusDays(1), today))
    }

    @Test
    fun `history import mode opens any past date but not the future`() {
        assertTrue(EditWindow.canEditEntry(today.minusDays(2), today, importMode = true))
        assertTrue(EditWindow.canEditEntry(today.minusDays(400), today, importMode = true))
        assertFalse(EditWindow.canEditEntry(today.plusDays(1), today, importMode = true))
    }

    @Test
    fun `a new habit is editable on the day it was added and the day after, then locked until graduation`() {
        assertTrue(EditWindow.canEditHabitDetails(habit(createdOn = today), today, graduated = false))
        val yesterday = habit(createdOn = today.minusDays(1))
        assertTrue(EditWindow.canEditHabitDetails(yesterday, today, graduated = false))
        assertEquals(today, EditWindow.lastFreeEditDay(yesterday))
        val older = habit(createdOn = today.minusDays(2))
        assertFalse(EditWindow.canEditHabitDetails(older, today, graduated = false))
        assertTrue(EditWindow.canEditHabitDetails(older, today, graduated = true))
    }
}

class HabitDeletionRuleTest {
    private val anchor = LocalDate.of(2026, 3, 2)

    @Test
    fun `a habit in its first day can be deleted completely`() {
        val fresh = habit(createdOn = anchor.plusDays(5))
        assertEquals(HabitDeletion.DELETE_COMPLETELY, HabitDeletionRule.mode(fresh, anchor.plusDays(5), anchor))
        assertEquals(HabitDeletion.DELETE_COMPLETELY, HabitDeletionRule.mode(fresh, anchor.plusDays(6), anchor))
    }

    @Test
    fun `a locked habit can only be deleted when the cycle ends`() {
        val locked = habit(createdOn = anchor)
        assertEquals(HabitDeletion.NOT_ALLOWED, HabitDeletionRule.mode(locked, anchor.plusDays(5), anchor))
        // Last day of cycle 1 and first day of cycle 2.
        assertEquals(HabitDeletion.ARCHIVE, HabitDeletionRule.mode(locked, anchor.plusDays(13), anchor))
        assertEquals(HabitDeletion.ARCHIVE, HabitDeletionRule.mode(locked, anchor.plusDays(14), anchor))
        assertEquals(HabitDeletion.NOT_ALLOWED, HabitDeletionRule.mode(locked, anchor.plusDays(15), anchor))
        assertEquals(anchor.plusDays(27), HabitDeletionRule.nextDeletionDay(anchor, anchor.plusDays(15)))
        // Without a cycle start there is no cycle end, so it stays locked.
        assertEquals(HabitDeletion.NOT_ALLOWED, HabitDeletionRule.mode(locked, anchor.plusDays(13), null))
    }

    @Test
    fun `archived habits cannot be deleted again`() {
        val archived = habit(createdOn = anchor, archivedOn = anchor.plusDays(3))
        assertEquals(HabitDeletion.NOT_ALLOWED, HabitDeletionRule.mode(archived, anchor.plusDays(3), anchor))
    }
}

class DayFillerTest {
    private val today = LocalDate.of(2026, 6, 30)

    @Test
    fun `fills every day after the last recorded one up to today`() {
        val dates = DayFiller.datesToFill(today.minusDays(4), today.minusDays(30), today)
        assertEquals((3 downTo 0).map { today.minusDays(it.toLong()) }, dates)
    }

    @Test
    fun `with no rows it starts at the tracking start`() {
        assertEquals(
            listOf(today.minusDays(2), today.minusDays(1), today),
            DayFiller.datesToFill(null, today.minusDays(2), today),
        )
    }

    @Test
    fun `nothing to fill when today is already recorded`() {
        assertTrue(DayFiller.datesToFill(today, today.minusDays(10), today).isEmpty())
    }

    @Test
    fun `only habits active on a date get a row`() {
        val old = habit(id = 1, createdOn = today.minusDays(20), archivedOn = today.minusDays(5))
        val new = habit(id = 2, createdOn = today.minusDays(5))
        assertEquals(listOf(old), DayFiller.habitsForDate(listOf(old, new), today.minusDays(6)))
        assertEquals(listOf(new), DayFiller.habitsForDate(listOf(old, new), today.minusDays(5)))
    }
}

class RetentionPolicyTest {
    private val today = LocalDate.of(2026, 6, 30)

    @Test
    fun `cutoff is the retention limit by default`() {
        assertEquals(today.minusMonths(6), RetentionPolicy.cutoff(today, 6, protectFrom = null))
    }

    @Test
    fun `cutoff never cuts into the 30-day graduation window or an unevaluated cycle`() {
        // The shortest allowed limit still keeps 30 days.
        assertTrue(!RetentionPolicy.cutoff(today, 0, null).isAfter(Graduation.windowStart(today)))
        // An old, unevaluated cycle is protected.
        val protectFrom = today.minusMonths(8)
        assertEquals(protectFrom, RetentionPolicy.cutoff(today, 6, protectFrom))
    }

    @Test
    fun `weekly check`() {
        assertTrue(RetentionPolicy.isCheckDue(null, today))
        assertFalse(RetentionPolicy.isCheckDue(today.minusDays(6), today))
        assertTrue(RetentionPolicy.isCheckDue(today.minusDays(7), today))
    }
}
