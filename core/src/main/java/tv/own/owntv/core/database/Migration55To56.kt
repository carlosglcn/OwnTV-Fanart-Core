package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * glcn v55 → v56 (upstream v54 → v55): the per-playlist "Prefer HLS for catch-up" switch — `sources.catchupPreferHls`, off for
 * every existing playlist, so catch-up keeps asking for MPEG-TS until the user asks. Additive only. Last
 * hop, so it carries [OwnTVDatabase.healSchema] (standing rule).
 */
val GLCN_MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "sources", "catchupPreferHls")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `catchupPreferHls` INTEGER NOT NULL DEFAULT 0")
        }
        OwnTVDatabase.healSchema(connection)
    }
}
