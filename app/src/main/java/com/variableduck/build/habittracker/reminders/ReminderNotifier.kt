package com.variableduck.build.habittracker.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.variableduck.build.habittracker.R
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The daily reminder: lists habits still at NO today, shows the overall goal, and offers a
 * "mark YES" button per habit. Android shows at most three standard action buttons, so with four
 * pending habits an expanded custom layout with four buttons is used instead.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tracker: TrackerRepository,
    private val settings: SettingsRepository,
    private val notifications: AppNotifications,
    private val time: TimeProvider,
) {
    /**
     * Posts (or updates) the reminder for [date]. Cancels it when nothing is pending.
     * [silent] updates without sound, for example after a button was tapped.
     */
    suspend fun show(date: LocalDate = time.today(), silent: Boolean = false, test: Boolean = false) {
        val current = settings.current()
        if (!current.onboardingComplete) return
        val day = tracker.dayView(date)
        val pending = day.entries.filter { !it.done }
        if (pending.isEmpty()) {
            if (test) showAllDone(current.overallGoal.trim()) else notifications.cancel(AppNotifications.ID_REMINDER)
            return
        }
        val names = pending.joinToString(", ") { it.habit.name }
        val title = if (pending.size == 1) "${pending[0].habit.name} is still at NO" else "${pending.size} habits still at NO"
        val goal = current.overallGoal.trim()
        val bigText = buildString {
            append("Still at NO: ").append(names)
            if (goal.isNotEmpty()) append("\n\nGoal: ").append(goal)
        }

        val builder = NotificationCompat.Builder(context, AppNotifications.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (goal.isNotEmpty()) "$names · Goal: $goal" else names)
            .setContentIntent(notifications.openAppIntent(AppNotifications.ID_REMINDER))
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        val actions = pending.map { entry ->
            entry.habit.name to markDoneIntent(date, entry.habit.id)
        }
        if (actions.size <= MAX_STANDARD_ACTIONS) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            actions.forEach { (name, intent) ->
                builder.addAction(NotificationCompat.Action.Builder(R.drawable.ic_check, "✓ $name", intent).build())
            }
        } else {
            val expanded = RemoteViews(context.packageName, R.layout.notification_reminder_expanded)
            expanded.setTextViewText(R.id.reminder_text, bigText)
            BUTTON_IDS.forEachIndexed { index, viewId ->
                val action = actions.getOrNull(index)
                if (action == null) {
                    expanded.setViewVisibility(viewId, android.view.View.GONE)
                } else {
                    expanded.setTextViewText(viewId, "✓ ${action.first}")
                    expanded.setOnClickPendingIntent(viewId, action.second)
                }
            }
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(expanded)
        }
        notifications.post(AppNotifications.ID_REMINDER, builder.build())
    }

    /** Test reminder when everything is already YES, so the user can still see that it works. */
    private fun showAllDone(goal: String) {
        val text = "Test reminder: every habit is YES today." + if (goal.isNotEmpty()) " Goal: $goal" else ""
        notifications.post(
            AppNotifications.ID_REMINDER,
            NotificationCompat.Builder(context, AppNotifications.CHANNEL_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("All habits done today")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(notifications.openAppIntent(AppNotifications.ID_REMINDER))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Re-renders the reminder after a change elsewhere (app or widget), if it is on screen. */
    suspend fun refreshIfShowing() {
        if (notifications.isShowing(AppNotifications.ID_REMINDER)) show(silent = true)
    }

    private fun markDoneIntent(date: LocalDate, habitId: Long): PendingIntent {
        val intent = Intent(context, HabitActionReceiver::class.java)
            .setAction(HabitActionReceiver.ACTION_MARK_DONE)
            .putExtra(HabitActionReceiver.EXTRA_HABIT_ID, habitId)
            .putExtra(HabitActionReceiver.EXTRA_DATE, date.toString())
        return PendingIntent.getBroadcast(
            context,
            habitId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val MAX_STANDARD_ACTIONS = 3
        val BUTTON_IDS = intArrayOf(
            R.id.reminder_button_1,
            R.id.reminder_button_2,
            R.id.reminder_button_3,
            R.id.reminder_button_4,
        )
    }
}
