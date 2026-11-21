package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the database is being upgraded to this version's format right now — what both apps' "Performing
 * database migration" screen follows. On a large catalogue an upgrade rewrites every row and rebuilds the
 * search indexes, a minute or more on a TV, and until it ends the app has nothing to show.
 *
 * Every migration is wrapped by [watch], so a future version needs nothing here: its migration turns this on
 * when it starts, and the open that follows (or a failure) turns it off.
 */
object DatabaseUpgrade {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** [migration], reporting while it runs. */
    fun watch(migration: Migration): Migration = object : Migration(migration.startVersion, migration.endVersion) {
        override fun migrate(connection: SQLiteConnection) {
            _running.value = true
            try {
                migration.migrate(connection)
            } catch (e: Throwable) {
                _running.value = false
                throw e
            }
        }
    }

    /** The database is open: whatever ran before it has finished. */
    internal fun opened() {
        _running.value = false
    }
}
