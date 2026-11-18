package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import tv.own.owntv.core.player.mediaTypeOfPinKey
import tv.own.owntv.core.player.sourceIdOfPinKey

/**
 * glcn v54 → v55 (upstream v53 → v54): drop `playback_prefs.audioDelayMs`. Since v44 an audio delay belongs to the stream and lives
 * in `playback_quirks`; this column was only ever emptied into it, by a pass that ran on first use in each
 * process. That pass is now this migration:
 *
 * - every row from before v44 learns its playlist from its key;
 * - each delay still in the column moves to the stream's shared row, oldest first so the newest one per
 *   item wins, and never over a shared delay set more recently;
 * - rows that remember nothing else go, and the column is dropped (SQLite ≥ 3.35; the bundled engine is
 *   3.50).
 *
 * Room already runs the chain inside one transaction, so this opens none of its own. Last hop, so it
 * carries [OwnTVDatabase.healSchema] (standing rule).
 */
val GLCN_MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "UPDATE `playback_prefs` SET `sourceId` = CAST(substr(`contentKey`, 1, instr(`contentKey`, ':') - 1) AS INTEGER) " +
                "WHERE `sourceId` = -1 AND `contentKey` GLOB '[0-9]*:*'",
        )
        if (OwnTVDatabase.hasColumn(connection, "playback_prefs", "audioDelayMs")) {
            moveLegacyAudioDelays(connection)
            connection.execSQL(
                "DELETE FROM `playback_prefs` WHERE `zoomMode` IS NULL AND `volumeBoost` IS NULL " +
                    "AND `audioLang` IS NULL AND `subtitleLang` IS NULL",
            )
            connection.execSQL("ALTER TABLE `playback_prefs` DROP COLUMN `audioDelayMs`")
        }
        OwnTVDatabase.healSchema(connection)
    }

    private fun moveLegacyAudioDelays(connection: SQLiteConnection) {
        class Legacy(val key: String, val ms: Long, val at: Long)
        val legacy = ArrayList<Legacy>()
        connection.prepare(
            "SELECT `contentKey`, `audioDelayMs`, `updatedAt` FROM `playback_prefs` " +
                "WHERE `audioDelayMs` IS NOT NULL ORDER BY `updatedAt` ASC",
        ).use { s ->
            // Column getters are 0-based; the binds below are 1-based.
            while (s.step()) legacy += Legacy(s.getText(0), s.getLong(1), s.getLong(2))
        }
        if (legacy.isEmpty()) return
        connection.prepare(
            "INSERT OR IGNORE INTO `playback_quirks` (`contentKey`, `sourceId`, `mediaType`, `updatedAt`) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            connection.prepare(
                "UPDATE `playback_quirks` SET `audioDelayMs` = ?, `updatedAt` = ? " +
                    "WHERE `contentKey` = ? AND (`audioDelayMs` IS NULL OR `updatedAt` <= ?)",
            ).use { update ->
                for (row in legacy) {
                    insert.bindText(1, row.key)
                    insert.bindLong(2, sourceIdOfPinKey(row.key))
                    insert.bindText(3, mediaTypeOfPinKey(row.key) ?: "LIVE")
                    insert.bindLong(4, row.at)
                    insert.step()
                    insert.reset()
                    update.bindLong(1, row.ms)
                    update.bindLong(2, row.at)
                    update.bindText(3, row.key)
                    update.bindLong(4, row.at)
                    update.step()
                    update.reset()
                }
            }
        }
    }
}
