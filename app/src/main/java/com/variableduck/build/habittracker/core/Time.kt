package com.variableduck.build.habittracker.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Source of "now" so that date logic can be tested with a fixed clock. */
interface TimeProvider {
    fun today(): LocalDate
    fun now(): LocalDateTime

    /** Emits today's date, and again after each midnight while collected. */
    fun todayFlow(): Flow<LocalDate>
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun today(): LocalDate = LocalDate.now()

    override fun now(): LocalDateTime = LocalDateTime.now()

    override fun todayFlow(): Flow<LocalDate> = flow {
        while (true) {
            emit(today())
            val now = now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
        }
    }.distinctUntilChanged()
}

/** Locale-neutral formats used in files (xlsx, csv) and settings. */
object FileFormats {
    fun hhmm(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    /** Parses "7:30", "07:30" or "07:30:00". Returns minutes after midnight or null. */
    fun parseHhmm(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size !in 2..3) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }
}
