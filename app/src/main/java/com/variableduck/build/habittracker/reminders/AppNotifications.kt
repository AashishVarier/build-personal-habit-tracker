package com.variableduck.build.habittracker.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.variableduck.build.habittracker.MainActivity
import com.variableduck.build.habittracker.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_REMINDER, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Daily reminder")
                    .setDescription("Habits still at NO, with buttons to mark them YES")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_REVIEW, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Cycle reviews and old data")
                    .setDescription("End-of-cycle reviews and data-retention prompts")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_BACKUP, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Backups")
                    .setDescription("Problems with the automatic daily backup")
                    .build(),
            ),
        )
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }

    fun cancel(id: Int) = manager.cancel(id)

    fun isShowing(id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == id } == true

    fun openAppIntent(requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun showCycleReviewReady() {
        post(
            ID_REVIEW,
            NotificationCompat.Builder(context, CHANNEL_REVIEW)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Your 14-day cycle is complete")
                .setContentText("Open the app to review level-ups and target changes.")
                .setContentIntent(openAppIntent(ID_REVIEW))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showRetentionPrompt(dayCount: Int, cutoff: LocalDate) {
        val date = cutoff.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        post(
            ID_RETENTION,
            NotificationCompat.Builder(context, CHANNEL_REVIEW)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Old entries can be deleted")
                .setContentText("$dayCount days older than $date. Open the app to decide. Nothing is deleted without asking.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$dayCount days older than $date can be deleted. Open the app to decide. " +
                            "Nothing is deleted without your confirmation.",
                    ),
                )
                .setContentIntent(openAppIntent(ID_RETENTION))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showBackupFailed(reason: String) {
        post(
            ID_BACKUP,
            NotificationCompat.Builder(context, CHANNEL_BACKUP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Automatic backup failed")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\nOpen Settings to check the backup folder."))
                .setContentIntent(openAppIntent(ID_BACKUP))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL_REMINDER = "daily_reminder"
        const val CHANNEL_REVIEW = "cycle_review"
        const val CHANNEL_BACKUP = "backup"

        const val ID_REMINDER = 1001
        const val ID_REVIEW = 1002
        const val ID_RETENTION = 1003
        const val ID_BACKUP = 1004
    }
}
