package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * glcn v53 → v54 (upstream v52 → v53): the per-playlist "Ignore connection limit" switch — `sources.ignoreConnectionLimit`, off
 * for every existing playlist, so nothing changes until the user asks. Additive only. Last hop, so it
 * carries [OwnTVDatabase.healSchema] (standing rule).
 */
val GLCN_MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "sources", "ignoreConnectionLimit")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `ignoreConnectionLimit` INTEGER NOT NULL DEFAULT 0")
        }
        OwnTVDatabase.healSchema(connection)
    }
}
