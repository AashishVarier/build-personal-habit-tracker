package com.variableduck.build.habittracker.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.variableduck.build.habittracker.domain.Targets
import com.variableduck.build.habittracker.model.HabitType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Display formatting that follows the device's 12/24-hour preference. */
class Formatter(private val is24Hour: Boolean) {
    private val clock12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val clock24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val mediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dayHeader: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
    private val shortDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    /** An out-of-range value (for example from a damaged backup) is clamped instead of crashing the screen. */
    fun clock(minuteOfDay: Int): String {
        val minute = minuteOfDay.coerceIn(0, Targets.MINUTES_PER_DAY - 1)
        return LocalTime.of(minute / 60, minute % 60).format(if (is24Hour) clock24 else clock12)
    }

    /** Stand-alone label, e.g. "10 min", "By 7:30 AM", "Done or not done". */
    fun target(type: HabitType, value: Int?): String = when {
        value == null || type == HabitType.BOOLEAN -> "Done or not done"
        type == HabitType.DURATION -> "$value min"
        else -> "By ${clock(value)}"
    }

    /** For use inside a sentence, e.g. "Started at 10 min" or "Started at 7:30 AM". */
    fun targetInline(type: HabitType, value: Int?): String = when {
        value == null || type == HabitType.BOOLEAN -> "done or not done"
        else -> targetValue(type, value)
    }

    fun targetValue(type: HabitType, value: Int): String =
        if (type == HabitType.DEADLINE) clock(value) else "$value min"

    fun change(type: HabitType, from: Int?, to: Int?): String =
        if (from == null || to == null) "" else "${targetValue(type, from)} → ${targetValue(type, to)}"

    fun step(type: HabitType, step: Int?): String = when (type) {
        HabitType.BOOLEAN -> ""
        HabitType.DURATION -> "+${step ?: 0} min per level"
        HabitType.DEADLINE -> "${step ?: 0} min earlier per level"
    }

    fun date(date: LocalDate): String = date.format(mediumDate)

    fun dayHeader(date: LocalDate): String = date.format(dayHeader)

    fun shortDay(date: LocalDate): String = date.format(shortDay)

    /** "5 min early", "on time", "12 min late" for a deadline habit. */
    fun deadlineDelta(target: Int, actual: Int): String {
        val diff = actual - target
        return when {
            diff == 0 -> "on time"
            diff < 0 -> "${abs(diff)} min early"
            else -> "$diff min late"
        }
    }
}

@Composable
fun rememberFormatter(): Formatter = Formatter(DateFormat.is24HourFormat(LocalContext.current))
