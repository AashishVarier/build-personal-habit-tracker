package com.variableduck.build.habittracker.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.variableduck.build.habittracker.di.ReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "HabitReceivers"

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background work failed", e)
        } finally {
            pending.finish()
        }
    }
}

private fun entryPoint(context: Context): ReceiverEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)

/** Daily reminder and the after-midnight day rollover. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deps = entryPoint(context)
        when (intent.action) {
            ACTION_REMINDER -> runAsync {
                deps.alarmScheduler().scheduleAll()
                deps.tracker().fillMissingDays()
                deps.reminderNotifier().show()
            }
            ACTION_DAY_ROLLOVER -> runAsync {
                deps.alarmScheduler().scheduleDayRollover()
                deps.housekeeping().rollOver()
            }
        }
    }

    companion object {
        const val ACTION_REMINDER = "com.variableduck.build.habittracker.action.REMINDER"
        const val ACTION_DAY_ROLLOVER = "com.variableduck.build.habittracker.action.DAY_ROLLOVER"
    }
}

/** "✓ Habit" buttons on the reminder notification. */
class HabitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_DONE) return
        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L).takeIf { it >= 0 } ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val deps = entryPoint(context)
        runAsync {
            // The edit window still applies: a reminder from two days ago can't change old data.
            deps.tracker().setDone(date, habitId, done = true)
            if (date == deps.timeProvider().today()) {
                deps.reminderNotifier().show(date = date, silent = true)
            } else {
                deps.notifications().cancel(AppNotifications.ID_REMINDER)
            }
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "com.variableduck.build.habittracker.action.MARK_DONE"
        const val EXTRA_HABIT_ID = "habit_id"
        const val EXTRA_DATE = "date"
    }
}

/** Re-creates alarms after a reboot, an app update, a clock change or a permission change. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            -> {
                val deps = entryPoint(context)
                runAsync {
                    deps.alarmScheduler().scheduleAll()
                    deps.workScheduler().ensureScheduled()
                    deps.housekeeping().rollOver()
                }
            }
        }
    }

    private companion object {
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
