package com.variableduck.build.habittracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.variableduck.build.habittracker.model.CycleReview
import com.variableduck.build.habittracker.model.CycleReviewItem
import com.variableduck.build.habittracker.model.DayRecord
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.HabitLog
import com.variableduck.build.habittracker.model.TargetChange
import java.time.LocalDate

@Database(
    entities = [
        Habit::class,
        DayRecord::class,
        HabitLog::class,
        TargetChange::class,
        CycleReview::class,
        CycleReviewItem::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao
    abstract fun cycleDao(): CycleDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val VERSION = 1
        const val NAME = "habit_tracker.db"

        /**
         * Builds the on-device database. WAL journaling is explicit, and only real migrations are
         * registered: there is deliberately no destructive fallback, so a missing migration
         * crashes loudly instead of silently wiping user data.
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/** Dates are stored as ISO-8601 text (yyyy-MM-dd), which sorts correctly and reads well. */
class Converters {
    @TypeConverter
    fun dateToText(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun textToDate(text: String?): LocalDate? = text?.let(LocalDate::parse)
}
