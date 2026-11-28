package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * glcn v57 → v58 (upstream v56 → v57): `episodes.imageUrl`, the provider's own picture of an episode. Null for
 * every existing row: episodes are catalogue, filled again the next time a show is opened or its playlist synced.
 * Additive only — an `ALTER TABLE … ADD COLUMN` touches no row, so it is instant on any catalogue size.
 * No transaction is opened here — Room already runs the chain inside one. Last hop, so it carries
 * [OwnTVDatabase.healSchema] (standing rule).
 */
val GLCN_MIGRATION_57_58 = object : Migration(57, 58) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "episodes", "imageUrl")) {
            connection.execSQL("ALTER TABLE `episodes` ADD COLUMN `imageUrl` TEXT")
        }
        OwnTVDatabase.healSchema(connection)
    }
}
