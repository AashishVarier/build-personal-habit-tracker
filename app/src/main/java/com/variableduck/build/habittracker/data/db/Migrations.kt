package com.variableduck.build.habittracker.data.db

import androidx.room.migration.Migration

/**
 * Every schema change must:
 *  1. bump [AppDatabase.VERSION],
 *  2. add a `Migration(old, new)` to [ALL] (or an `@AutoMigration` on [AppDatabase]),
 *  3. commit the newly exported JSON under `app/schemas/`.
 *
 * Destructive migration is never used; user data must survive every upgrade.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
