package com.variableduck.build.habittracker.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the daily reminder as an exact alarm when allowed (Android 12+ needs the user to grant
 * "Alarms & reminders"), falling back to an inexact alarm otherwise. Also schedules a light
 * just-after-midnight alarm that rolls the day over and refreshes the widget.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    /** Opens the system screen where the user can allow exact alarms (Android 12+). */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
        } else {
            null
        }

    suspend fun scheduleAll() {
        val current = settings.current()
        if (!current.onboardingComplete) return
        scheduleReminder(current.reminderMinuteOfDay)
        scheduleDayRollover()
    }

    fun scheduleReminder(minuteOfDay: Int) {
        val trigger = nextOccurrence(time.now(), minuteOfDay)
        setAlarm(pendingIntent(AlarmReceiver.ACTION_REMINDER, REQUEST_REMINDER), trigger, exact = true)
    }

    fun scheduleDayRollover() {
        val trigger = time.now().toLocalDate().plusDays(1).atTime(0, 0, 30)
        setAlarm(pendingIntent(AlarmReceiver.ACTION_DAY_ROLLOVER, REQUEST_ROLLOVER), trigger, exact = false)
    }

    private fun setAlarm(intent: PendingIntent, at: LocalDateTime, exact: Boolean) {
        val manager = alarmManager ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (exact && canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
                return
            } catch (e: SecurityException) {
                // Permission was revoked in the meantime; fall back to an inexact alarm below.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val REQUEST_REMINDER = 2001
        private const val REQUEST_ROLLOVER = 2002

        /** The next time [minuteOfDay] occurs strictly after [now]. */
        fun nextOccurrence(now: LocalDateTime, minuteOfDay: Int): LocalDateTime {
            val todayAt = now.toLocalDate().atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
            return if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
        }
    }
}
