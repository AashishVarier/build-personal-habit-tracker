package com.variableduck.build.habittracker.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.variableduck.build.habittracker.domain.RetentionPolicy
import com.variableduck.build.habittracker.domain.Thresholds
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val reminderMinuteOfDay: Int = DEFAULT_REMINDER_MINUTE,
    val cycleStart: LocalDate? = null,
    val levelUpThreshold: Int = Thresholds.DEFAULT_LEVEL_UP,
    val stepDownThreshold: Int = Thresholds.DEFAULT_STEP_DOWN,
    val overallGoal: String = "",
    val retentionMonths: Int = RetentionPolicy.DEFAULT_MONTHS,
    val backupFolderUri: String? = null,
    val lastGoalPopupDate: LocalDate? = null,
    val lastRetentionCheck: LocalDate? = null,
    val lastAutoBackupDate: LocalDate? = null,
) {
    val thresholds: Thresholds get() = Thresholds.sanitized(levelUpThreshold, stepDownThreshold)

    companion object {
        const val DEFAULT_REMINDER_MINUTE = 21 * 60
        const val GOAL_MAX_LENGTH = 200
    }
}

/** The settings that travel with an xlsx backup (device-specific values are excluded). */
data class PortableSettings(
    val reminderMinuteOfDay: Int,
    val cycleStart: LocalDate?,
    val levelUpThreshold: Int,
    val stepDownThreshold: Int,
    val overallGoal: String,
    val retentionMonths: Int,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute_of_day")
        val CYCLE_START = stringPreferencesKey("cycle_start")
        val LEVEL_UP = intPreferencesKey("level_up_threshold")
        val STEP_DOWN = intPreferencesKey("step_down_threshold")
        val OVERALL_GOAL = stringPreferencesKey("overall_goal")
        val RETENTION_MONTHS = intPreferencesKey("retention_months")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder_uri")
        val LAST_GOAL_POPUP = stringPreferencesKey("last_goal_popup")
        val LAST_RETENTION_CHECK = stringPreferencesKey("last_retention_check")
        val LAST_AUTO_BACKUP = stringPreferencesKey("last_auto_backup")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun completeOnboarding(
        cycleStart: LocalDate,
        reminderMinuteOfDay: Int,
        overallGoal: String,
        backupFolderUri: String?,
    ) = dataStore.edit {
        it[Keys.CYCLE_START] = cycleStart.toString()
        it[Keys.REMINDER_MINUTE] = reminderMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        it[Keys.OVERALL_GOAL] = overallGoal.trim().take(AppSettings.GOAL_MAX_LENGTH)
        it.setOrRemove(Keys.BACKUP_FOLDER, backupFolderUri)
        it[Keys.ONBOARDING_COMPLETE] = true
    }

    suspend fun setReminderMinute(minuteOfDay: Int) = dataStore.edit {
        it[Keys.REMINDER_MINUTE] = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    }

    suspend fun setCycleStart(date: LocalDate) = dataStore.edit { it[Keys.CYCLE_START] = date.toString() }

    suspend fun setThresholds(levelUp: Int, stepDown: Int) = dataStore.edit {
        val sanitized = Thresholds.sanitized(levelUp, stepDown)
        it[Keys.LEVEL_UP] = sanitized.levelUp
        it[Keys.STEP_DOWN] = sanitized.stepDown
    }

    suspend fun setOverallGoal(goal: String) = dataStore.edit {
        it[Keys.OVERALL_GOAL] = goal.take(AppSettings.GOAL_MAX_LENGTH)
    }

    suspend fun setRetentionMonths(months: Int) = dataStore.edit {
        it[Keys.RETENTION_MONTHS] = months.coerceIn(RetentionPolicy.MIN_MONTHS, RetentionPolicy.MAX_MONTHS)
    }

    suspend fun setBackupFolder(uri: String?) = dataStore.edit { it.setOrRemove(Keys.BACKUP_FOLDER, uri) }

    suspend fun markGoalPopupShown(date: LocalDate) = dataStore.edit { it[Keys.LAST_GOAL_POPUP] = date.toString() }

    suspend fun markRetentionChecked(date: LocalDate) =
        dataStore.edit { it[Keys.LAST_RETENTION_CHECK] = date.toString() }

    suspend fun markAutoBackup(date: LocalDate) = dataStore.edit { it[Keys.LAST_AUTO_BACKUP] = date.toString() }

    suspend fun portable(): PortableSettings = current().let {
        PortableSettings(
            reminderMinuteOfDay = it.reminderMinuteOfDay,
            cycleStart = it.cycleStart,
            levelUpThreshold = it.thresholds.levelUp,
            stepDownThreshold = it.thresholds.stepDown,
            overallGoal = it.overallGoal,
            retentionMonths = it.retentionMonths,
        )
    }

    /** Applies settings from a restored backup. The backup folder and bookkeeping stay as they are. */
    suspend fun applyPortable(portable: PortableSettings) = dataStore.edit {
        it[Keys.REMINDER_MINUTE] = portable.reminderMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        it.setOrRemove(Keys.CYCLE_START, portable.cycleStart?.toString())
        val thresholds = Thresholds.sanitized(portable.levelUpThreshold, portable.stepDownThreshold)
        it[Keys.LEVEL_UP] = thresholds.levelUp
        it[Keys.STEP_DOWN] = thresholds.stepDown
        it[Keys.OVERALL_GOAL] = portable.overallGoal.take(AppSettings.GOAL_MAX_LENGTH)
        it[Keys.RETENTION_MONTHS] =
            portable.retentionMonths.coerceIn(RetentionPolicy.MIN_MONTHS, RetentionPolicy.MAX_MONTHS)
        it[Keys.ONBOARDING_COMPLETE] = true
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        reminderMinuteOfDay = this[Keys.REMINDER_MINUTE] ?: AppSettings.DEFAULT_REMINDER_MINUTE,
        cycleStart = this[Keys.CYCLE_START].toDateOrNull(),
        levelUpThreshold = this[Keys.LEVEL_UP] ?: Thresholds.DEFAULT_LEVEL_UP,
        stepDownThreshold = this[Keys.STEP_DOWN] ?: Thresholds.DEFAULT_STEP_DOWN,
        overallGoal = this[Keys.OVERALL_GOAL] ?: "",
        retentionMonths = this[Keys.RETENTION_MONTHS] ?: RetentionPolicy.DEFAULT_MONTHS,
        backupFolderUri = this[Keys.BACKUP_FOLDER],
        lastGoalPopupDate = this[Keys.LAST_GOAL_POPUP].toDateOrNull(),
        lastRetentionCheck = this[Keys.LAST_RETENTION_CHECK].toDateOrNull(),
        lastAutoBackupDate = this[Keys.LAST_AUTO_BACKUP].toDateOrNull(),
    )

    private fun String?.toDateOrNull(): LocalDate? = this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
