package com.variableduck.build.habittracker.backup

import com.variableduck.build.habittracker.backup.xlsx.XlsxSheet
import com.variableduck.build.habittracker.core.FileFormats
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.PortableSettings
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.domain.Targets
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.model.TargetChange
import com.variableduck.build.habittracker.model.TargetChangeReason
import java.io.IOException
import java.time.LocalDate

/** Everything needed to rebuild the app's data. */
data class BackupSnapshot(
    val habits: List<Habit>,
    val days: List<DayRecord>,
    val logs: List<HabitLog>,
    val targetChanges: List<TargetChange>,
    val settings: PortableSettings?,
    /** End of the last evaluated cycle, so a restore never re-judges old cycles. */
    val lastEvaluatedCycleEnd: LocalDate?,
    val exportedOn: LocalDate?,
) {
    val firstDate: LocalDate? get() = days.minOfOrNull { it.date }
    val lastDate: LocalDate? get() = days.maxOfOrNull { it.date }
    val isEmpty: Boolean get() = days.isEmpty() && logs.isEmpty() && targetChanges.isEmpty()
}

class BackupFormatException(message: String) : IOException(message)

/**
 * Converts a [BackupSnapshot] to and from four sheets: Entries, Habits, TargetHistory, Settings.
 * Columns are looked up by header name, so a file re-saved by a spreadsheet app still restores.
 */
object BackupCodec {
    const val FORMAT_VERSION = 1

    const val SHEET_ENTRIES = "Entries"
    const val SHEET_HABITS = "Habits"
    const val SHEET_TARGETS = "TargetHistory"
    const val SHEET_SETTINGS = "Settings"

    private val ENTRY_HEADERS = listOf("Date", "Habit ID", "Habit", "Status", "Actual time", "Remark", "Reward")
    private val HABIT_HEADERS = listOf(
        "ID", "Slot", "Name", "Type", "Current target", "Starting target", "Step",
        "Mandatory", "Created on", "Archived on", "Target (readable)",
    )
    private val TARGET_HEADERS = listOf("ID", "Habit ID", "Habit", "Date", "Old target", "New target", "Reason", "Change")
    private val SETTINGS_HEADERS = listOf("Key", "Value")

    private const val YES = "YES"
    private const val NO = "NO"

    private object SettingKeys {
        const val FORMAT_VERSION = "format_version"
        const val EXPORTED_ON = "exported_on"
        const val REMINDER_TIME = "reminder_time"
        const val CYCLE_START = "cycle_start"
        const val LEVEL_UP = "level_up_threshold"
        const val STEP_DOWN = "step_down_threshold"
        const val OVERALL_GOAL = "overall_goal"
        const val RETENTION_MONTHS = "retention_months"
        const val LAST_EVALUATED_CYCLE_END = "last_evaluated_cycle_end"
    }

    fun toSheets(snapshot: BackupSnapshot): List<XlsxSheet> {
        val habitsById = snapshot.habits.associateBy { it.id }
        return listOf(
            XlsxSheet(SHEET_ENTRIES, listOf(ENTRY_HEADERS) + entryRows(snapshot, habitsById)),
            XlsxSheet(SHEET_HABITS, listOf(HABIT_HEADERS) + snapshot.habits.sortedBy { it.id }.map(::habitRow)),
            XlsxSheet(
                SHEET_TARGETS,
                listOf(TARGET_HEADERS) + snapshot.targetChanges.sortedWith(compareBy({ it.date }, { it.id })).map {
                    targetRow(it, habitsById[it.habitId])
                },
            ),
            XlsxSheet(SHEET_SETTINGS, listOf(SETTINGS_HEADERS) + settingsRows(snapshot)),
        )
    }

    /** Entries as CSV (same columns as the Entries sheet). */
    fun toCsv(snapshot: BackupSnapshot): String {
        val habitsById = snapshot.habits.associateBy { it.id }
        val rows = listOf(ENTRY_HEADERS) + entryRows(snapshot, habitsById)
        return rows.joinToString(separator = "\r\n", postfix = "\r\n") { row ->
            row.joinToString(",") { csvField(it?.toString().orEmpty()) }
        }
    }

    private fun entryRows(snapshot: BackupSnapshot, habitsById: Map<Long, Habit>): List<List<Any?>> {
        val logsByDate = snapshot.logs.groupBy { it.date }
        return snapshot.days.sortedBy { it.date }.flatMap { day ->
            val reward = if (day.rewardUnlocked) YES else NO
            val logs = logsByDate[day.date].orEmpty()
                .sortedWith(compareBy({ habitsById[it.habitId]?.slot ?: Int.MAX_VALUE }, { it.habitId }))
            if (logs.isEmpty()) {
                listOf(listOf(day.date.toString(), null, null, null, null, day.remark, reward))
            } else {
                logs.map { log ->
                    listOf(
                        day.date.toString(),
                        log.habitId,
                        habitsById[log.habitId]?.name.orEmpty(),
                        if (log.done) YES else NO,
                        log.actualTime?.let(FileFormats::hhmm),
                        day.remark,
                        reward,
                    )
                }
            }
        }
    }

    private fun habitRow(habit: Habit): List<Any?> = listOf(
        habit.id,
        habit.slot,
        habit.name,
        habit.type.name,
        habit.currentTarget,
        habit.startingTarget,
        habit.step,
        if (habit.mandatory) YES else NO,
        habit.createdOn.toString(),
        habit.archivedOn?.toString(),
        habit.currentTarget?.let { readableTarget(habit.type, it) },
    )

    private fun targetRow(change: TargetChange, habit: Habit?): List<Any?> = listOf(
        change.id,
        change.habitId,
        habit?.name.orEmpty(),
        change.date.toString(),
        change.oldTarget,
        change.newTarget,
        change.reason.name,
        habit?.let { "${readableTarget(it.type, change.oldTarget)} -> ${readableTarget(it.type, change.newTarget)}" },
    )

    private fun settingsRows(snapshot: BackupSnapshot): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>(listOf(SettingKeys.FORMAT_VERSION, FORMAT_VERSION.toString()))
        snapshot.exportedOn?.let { rows += listOf(SettingKeys.EXPORTED_ON, it.toString()) }
        snapshot.settings?.let { s ->
            rows += listOf(SettingKeys.REMINDER_TIME, FileFormats.hhmm(s.reminderMinuteOfDay))
            s.cycleStart?.let { rows += listOf(SettingKeys.CYCLE_START, it.toString()) }
            rows += listOf(SettingKeys.LEVEL_UP, s.levelUpThreshold.toString())
            rows += listOf(SettingKeys.STEP_DOWN, s.stepDownThreshold.toString())
            rows += listOf(SettingKeys.OVERALL_GOAL, s.overallGoal)
            rows += listOf(SettingKeys.RETENTION_MONTHS, s.retentionMonths.toString())
        }
        snapshot.lastEvaluatedCycleEnd?.let { rows += listOf(SettingKeys.LAST_EVALUATED_CYCLE_END, it.toString()) }
        return rows
    }

    fun readableTarget(type: HabitType, value: Int): String = when (type) {
        HabitType.DURATION -> "$value min"
        HabitType.DEADLINE -> "by " + FileFormats.hhmm(value)
        HabitType.BOOLEAN -> ""
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads a backup. Values are checked against the same limits the app itself enforces, so a
     * hand-edited or damaged file is rejected with the row and column instead of storing data the
     * app can't handle. Nothing may be dated more than a day after [today] (one day of slack for
     * time zones).
     */
    fun fromSheets(sheets: Map<String, List<List<String>>>, today: LocalDate): BackupSnapshot {
        val byName = sheets.mapKeys { it.key.trim().lowercase() }
        fun sheet(name: String) = byName[name.lowercase()]
        val latest = today.plusDays(1)

        val settingsTable = sheet(SHEET_SETTINGS)?.let { Table(SHEET_SETTINGS, it, latest) }
        val settingValues = settingsTable?.rows?.associate { row ->
            settingsTable.text(row, "Key") to settingsTable.raw(row, "Value")
        }.orEmpty()
        val version = settingValues[SettingKeys.FORMAT_VERSION]?.trim()?.toIntOrNull()
        if (version != null && version > FORMAT_VERSION) {
            throw BackupFormatException("This backup was made by a newer version of the app.")
        }

        val habitsTable = Table(SHEET_HABITS, sheet(SHEET_HABITS) ?: missing(SHEET_HABITS), latest)
        val habits = habitsTable.rows.map { row -> parseHabit(habitsTable, row) }
        val habitIds = habits.map { it.id }
        if (habitIds.toSet().size != habitIds.size) throw BackupFormatException("Habits: duplicate IDs.")
        val active = habits.filter { it.archivedOn == null }
        if (active.size > MAX_ACTIVE) throw BackupFormatException("Habits: more than $MAX_ACTIVE active habits.")
        if (active.map { it.slot }.toSet().size != active.size) {
            throw BackupFormatException("Habits: two active habits share a slot.")
        }
        val knownIds = habitIds.toSet()

        val entriesTable = Table(SHEET_ENTRIES, sheet(SHEET_ENTRIES) ?: missing(SHEET_ENTRIES), latest)
        val days = LinkedHashMap<LocalDate, DayRecord>()
        val logs = LinkedHashMap<Pair<LocalDate, Long>, HabitLog>()
        for (row in entriesTable.rows) {
            val date = entriesTable.date(row, "Date") ?: continue
            val remark = entriesTable.raw(row, "Remark").take(REMARK_MAX)
            val reward = entriesTable.text(row, "Reward").isYes()
            val existing = days[date]
            days[date] = if (existing == null) {
                DayRecord(date, remark, reward)
            } else {
                existing.copy(
                    remark = existing.remark.ifEmpty { remark },
                    rewardUnlocked = existing.rewardUnlocked || reward,
                )
            }
            val habitId = entriesTable.long(row, "Habit ID") ?: continue
            if (habitId !in knownIds) {
                throw BackupFormatException("Entries row ${row.number}: unknown habit ID $habitId.")
            }
            val key = date to habitId
            if (key in logs) throw BackupFormatException("Entries row ${row.number}: duplicate entry for $date.")
            logs[key] = HabitLog(
                date = date,
                habitId = habitId,
                done = entriesTable.text(row, "Status").isYes(),
                actualTime = entriesTable.time(row, "Actual time"),
            )
        }

        val targetChanges = sheet(SHEET_TARGETS)?.let { rows ->
            val table = Table(SHEET_TARGETS, rows, latest)
            table.rows.mapNotNull { row ->
                val habitId = table.long(row, "Habit ID") ?: return@mapNotNull null
                if (habitId !in knownIds) {
                    throw BackupFormatException("TargetHistory row ${row.number}: unknown habit ID $habitId.")
                }
                // Minutes or a time of day: a habit's type may have changed since the change was recorded.
                fun target(column: String): Int =
                    table.int(row, column)?.takeIf { it in 0..Targets.MAX_DURATION_MINUTES } ?: table.fail(row, column)
                TargetChange(
                    id = table.long(row, "ID") ?: 0,
                    habitId = habitId,
                    date = table.date(row, "Date") ?: table.fail(row, "Date"),
                    oldTarget = target("Old target"),
                    newTarget = target("New target"),
                    reason = table.text(row, "Reason").let { reason ->
                        TargetChangeReason.entries.firstOrNull { it.name.equals(reason, ignoreCase = true) }
                            ?: table.fail(row, "Reason")
                    },
                )
            }
        }.orEmpty()

        // Unreadable settings are ignored rather than rejected, and so are dates past their limit:
        // a far-future "last evaluated" date would otherwise switch off cycle reviews for good.
        fun settingDate(key: String, latestAllowed: LocalDate): LocalDate? =
            settingValues[key]?.let(::parseDate)?.takeIf { !it.isAfter(latestAllowed) }

        val portable = if (settingValues.isEmpty()) {
            null
        } else {
            PortableSettings(
                reminderMinuteOfDay = settingValues[SettingKeys.REMINDER_TIME]?.let(::parseTime) ?: DEFAULT_REMINDER,
                // The cycle start date picker allows up to 13 days ahead.
                cycleStart = settingDate(SettingKeys.CYCLE_START, today.plusDays(CycleCalendar.LENGTH - 1L)),
                levelUpThreshold = settingValues[SettingKeys.LEVEL_UP]?.let(::parseInt) ?: 10,
                stepDownThreshold = settingValues[SettingKeys.STEP_DOWN]?.let(::parseInt) ?: 5,
                overallGoal = settingValues[SettingKeys.OVERALL_GOAL].orEmpty(),
                retentionMonths = settingValues[SettingKeys.RETENTION_MONTHS]?.let(::parseInt) ?: 6,
            )
        }

        return BackupSnapshot(
            habits = habits,
            days = days.values.sortedBy { it.date },
            logs = logs.values.sortedWith(compareBy({ it.date }, { it.habitId })),
            targetChanges = targetChanges,
            settings = portable,
            lastEvaluatedCycleEnd = settingDate(SettingKeys.LAST_EVALUATED_CYCLE_END, today),
            exportedOn = settingDate(SettingKeys.EXPORTED_ON, latest),
        )
    }

    private fun parseHabit(table: Table, row: Table.Row): Habit {
        val type = table.text(row, "Type").let { text ->
            HabitType.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: table.fail(row, "Type")
        }
        val name = table.text(row, "Name")
            .takeIf { it.isNotBlank() && it.length <= TrackerRepository.NAME_MAX_LENGTH }
            ?: table.fail(row, "Name")
        // The same limits as the habit form, so the screens can always display the target.
        fun target(column: String): Int? =
            if (type.hasTarget) table.int(row, column)?.takeIf { Targets.isValid(type, it) } ?: table.fail(row, column) else null
        return Habit(
            id = table.long(row, "ID") ?: table.fail(row, "ID"),
            slot = table.int(row, "Slot")?.takeIf { it in 0 until MAX_ACTIVE } ?: table.fail(row, "Slot"),
            name = name,
            type = type,
            currentTarget = target("Current target"),
            startingTarget = target("Starting target"),
            step = if (type.hasTarget) {
                table.int(row, "Step")?.takeIf { it in 1..TrackerRepository.MAX_STEP } ?: table.fail(row, "Step")
            } else {
                null
            },
            mandatory = table.text(row, "Mandatory").isYes(),
            createdOn = table.date(row, "Created on") ?: table.fail(row, "Created on"),
            archivedOn = table.date(row, "Archived on"),
        )
    }

    private fun missing(sheet: String): Nothing = throw BackupFormatException("The '$sheet' sheet is missing.")

    private fun String.isYes(): Boolean = trim().uppercase().let { it == YES || it == "TRUE" || it == "1" }

    /**
     * ISO dates, or Excel serial numbers if a spreadsheet app converted the text to a date. Only
     * years 1900 to 9999 are accepted: they sort correctly as text in the database, and anything
     * else (ISO also allows years such as -999999999) is a typo or a damaged file.
     */
    internal fun parseDate(text: String): LocalDate? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val date = runCatching { LocalDate.parse(trimmed) }.getOrNull()
            ?: trimmed.toDoubleOrNull()
                ?.takeIf { it >= 1 && it <= 2_958_465 }
                ?.let { EXCEL_EPOCH.plusDays(it.toLong()) }
        return date?.takeIf { it.year in MIN_YEAR..MAX_YEAR }
    }

    /** "07:30", or an Excel time fraction such as 0.3125. */
    internal fun parseTime(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        FileFormats.parseHhmm(trimmed)?.let { return it }
        val fraction = trimmed.toDoubleOrNull() ?: return null
        if (fraction < 0 || fraction >= 1) return null
        return Math.round(fraction * MINUTES_PER_DAY).toInt().coerceIn(0, MINUTES_PER_DAY - 1)
    }

    private fun parseInt(text: String): Int? = text.trim().toDoubleOrNull()?.toInt()

    private fun csvField(value: String): String {
        // Neutralise formulas when the CSV is opened in a spreadsheet app.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    /** Header-addressed view over sheet rows. Dates after [latest] are rejected. */
    private class Table(private val sheetName: String, rows: List<List<String>>, private val latest: LocalDate) {
        class Row(val number: Int, val cells: List<String>)

        private val columns: Map<String, Int>
        val rows: List<Row>

        init {
            val header = rows.firstOrNull() ?: emptyList()
            columns = header.mapIndexed { index, name -> name.trim().lowercase() to index }.toMap()
            this.rows = rows.drop(1)
                .mapIndexed { index, cells -> Row(index + 2, cells) }
                .filter { row -> row.cells.any { it.isNotBlank() } }
        }

        fun text(row: Row, column: String): String = raw(row, column).trim()

        /** Cell text exactly as stored, for free text such as remarks. */
        fun raw(row: Row, column: String): String {
            val index = columns[column.lowercase()] ?: return ""
            return row.cells.getOrNull(index).orEmpty()
        }

        fun long(row: Row, column: String): Long? = text(row, column).toDoubleOrNull()?.toLong()

        fun int(row: Row, column: String): Int? = text(row, column).toDoubleOrNull()?.toInt()

        fun date(row: Row, column: String): LocalDate? {
            val value = text(row, column)
            if (value.isEmpty()) return null
            return parseDate(value)?.takeIf { !it.isAfter(latest) } ?: fail(row, column)
        }

        fun time(row: Row, column: String): Int? {
            val value = text(row, column)
            if (value.isEmpty()) return null
            return parseTime(value) ?: fail(row, column)
        }

        fun fail(row: Row, column: String): Nothing =
            throw BackupFormatException("$sheetName row ${row.number}: invalid or missing '$column'.")
    }

    private const val MAX_ACTIVE = 4
    private const val REMARK_MAX = 50
    private const val MINUTES_PER_DAY = 24 * 60
    private const val DEFAULT_REMINDER = 21 * 60
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 9999
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
}
