package com.variableduck.build.habittracker

import android.app.Application
import com.variableduck.build.habittracker.di.ApplicationScope
import com.variableduck.build.habittracker.reminders.AlarmScheduler
import com.variableduck.build.habittracker.reminders.AppNotifications
import com.variableduck.build.habittracker.reminders.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class HabitTrackerApp : Application() {
    @Inject lateinit var notifications: AppNotifications

    @Inject lateinit var workScheduler: WorkScheduler

    @Inject lateinit var alarmScheduler: AlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        workScheduler.ensureScheduled()
        appScope.launch { alarmScheduler.scheduleAll() }
    }
}
