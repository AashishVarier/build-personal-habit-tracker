package com.variableduck.build.habittracker.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.variableduck.build.habittracker.core.SystemTimeProvider
import com.variableduck.build.habittracker.core.TimeProvider
import com.variableduck.build.habittracker.data.db.AppDatabase
import com.variableduck.build.habittracker.data.repo.DataChangeNotifier
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.reminders.AlarmScheduler
import com.variableduck.build.habittracker.reminders.AppNotifications
import com.variableduck.build.habittracker.reminders.Housekeeping
import com.variableduck.build.habittracker.reminders.ReminderNotifier
import com.variableduck.build.habittracker.reminders.WorkScheduler
import com.variableduck.build.habittracker.widget.AppChangeNotifier
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A process-wide scope for work that must outlive a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = SettingsRepository.corruptionHandler,
            produceFile = { context.preferencesDataStoreFile("settings") },
        )

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    abstract fun bindDataChangeNotifier(impl: AppChangeNotifier): DataChangeNotifier
}

/** Dependencies for components Hilt can't inject directly: broadcast receivers and the widget. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun tracker(): TrackerRepository
    fun reminderNotifier(): ReminderNotifier
    fun alarmScheduler(): AlarmScheduler
    fun workScheduler(): WorkScheduler
    fun housekeeping(): Housekeeping
    fun timeProvider(): TimeProvider
    fun notifications(): AppNotifications
}
