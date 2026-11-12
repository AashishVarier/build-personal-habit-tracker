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
    fun `habit details are editable for 7 days, then locked until graduation`() {
        val created = habit(createdOn = today.minusDays(6))
        assertTrue(EditWindow.canEditHabitDetails(created, today, graduated = false))
        val week = habit(createdOn = today.minusDays(7))
        assertFalse(EditWindow.canEditHabitDetails(week, today, graduated = false))
        assertTrue(EditWindow.canEditHabitDetails(week, today, graduated = true))
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
