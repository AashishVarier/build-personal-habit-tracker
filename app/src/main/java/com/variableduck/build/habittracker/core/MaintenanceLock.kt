package com.variableduck.build.habittracker.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises the jobs that rewrite data in several steps: the daily housekeeping, a restore and
 * the retention purge. Without it, housekeeping could, for example, evaluate restored data with
 * the previous settings before the restore has applied the backup's settings.
 *
 * Not reentrant: code holding the lock must not call another [withLock] user.
 */
@Singleton
class MaintenanceLock @Inject constructor() {
    @PublishedApi internal val mutex = Mutex()

    /** Inline, like [Mutex.withLock], so callers can `return` from inside [action]. */
    suspend inline fun <T> withLock(action: () -> T): T = mutex.withLock(action = action)
}
