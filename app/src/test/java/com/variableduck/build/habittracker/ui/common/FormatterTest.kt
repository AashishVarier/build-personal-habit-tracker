package com.variableduck.build.habittracker.ui.common

import com.variableduck.build.habittracker.model.HabitType
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatterTest {
    private val formatter = Formatter(is24Hour = true)

    @Test
    fun `formats a time of day`() {
        assertEquals("07:30", formatter.clock(7 * 60 + 30))
        assertEquals("By 07:30", formatter.target(HabitType.DEADLINE, 7 * 60 + 30))
    }

    @Test
    fun `clamps damaged values instead of crashing the screen`() {
        // A deadline of 1930 used to throw DateTimeException on every launch.
        assertEquals("23:59", formatter.clock(1930))
        assertEquals("By 23:59", formatter.target(HabitType.DEADLINE, 1930))
        assertEquals("00:00", formatter.clock(-30))
    }
}
