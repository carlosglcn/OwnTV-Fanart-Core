package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import tv.own.owntv.core.database.entity.nameKeyOf

/**
 * glcn v56 → v57 (upstream v55 → v56): names sorted and searched in any alphabet's letter case.
 *
 * SQLite's `LOWER()`, `LIKE` and `ORDER BY` fold A–Z only, and so does the FTS `simple` tokenizer, so a
 * lowercase Cyrillic query never found a capitalised name and A–Z put "arte" after "ZDFneo". So:
 *  - channels, movies and series gain `nameKey`, the Unicode lowercase of `name`. SQL cannot compute it
 *    (the bundled SQLite has no ICU), so existing rows are filled here from Kotlin, in batches;
 *  - their `name` A–Z indexes become `nameKey` indexes (re-created by [OwnTVDatabase.healSchema]);
 *  - their FTS tables are dropped and come back from [OwnTVDatabase.healSchema] with the `unicode61`
 *    tokenizer, rebuilt from the content tables.
 *
 * Room drops the FTS sync triggers before migrating and re-creates them after, so the backfill's updates
 * touch no FTS table. No transaction is opened here — Room already runs the chain inside one. Last hop,
 * so it carries [OwnTVDatabase.healSchema] (standing rule).
 */
val GLCN_MIGRATION_56_57 = object : Migration(56, 57) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in NAME_KEY_TABLES) {
            if (!OwnTVDatabase.hasColumn(connection, table, "nameKey")) {
                connection.execSQL("ALTER TABLE `$table` ADD COLUMN `nameKey` TEXT NOT NULL DEFAULT ''")
            }
            backfillNameKeys(connection, table)
        }
        OLD_NAME_INDEXES.forEach { connection.execSQL("DROP INDEX IF EXISTS `$it`") }
        NAME_KEY_TABLES.forEach { connection.execSQL("DROP TABLE IF EXISTS `${it}_fts`") }
        OwnTVDatabase.healSchema(connection)
    }
}

private val NAME_KEY_TABLES = listOf("channels", "movies", "series")

private val OLD_NAME_INDEXES = listOf(
    "index_channels_sourceId_name",
    "index_channels_categoryId_name",
    "index_movies_name",
    "index_movies_sourceId_name",
    "index_movies_categoryId_name",
    "index_series_name",
    "index_series_sourceId_name",
    "index_series_categoryId_name",
)

private const val NAME_KEY_BATCH = 1000

/** A name holding any character outside printable ASCII. */
private const val NON_ASCII = "*[^ -~]*"

/**
 * Fills `nameKey` for every row of [table]. A name of printable ASCII only — four in five on a real
 * catalogue — is lowercased by SQLite in one statement, where `lower()` gives exactly [nameKeyOf]'s
 * answer. Only the rest goes through Kotlin, one id-ordered batch at a time, one prepared update per batch.
 * Measured on a TCL G10 with 396k rows: 21 s row by row for every row.
 */
private fun backfillNameKeys(db: SQLiteConnection, table: String) {
    db.execSQL("UPDATE `$table` SET `nameKey` = lower(`name`) WHERE `name` NOT GLOB '$NON_ASCII'")
    var lastId = 0L
    while (true) {
        val rows = ArrayList<Pair<Long, String>>(NAME_KEY_BATCH)
        db.prepare("SELECT `id`, `name` FROM `$table` WHERE `id` > ? AND `name` GLOB '$NON_ASCII' ORDER BY `id` ASC LIMIT ?").use { statement ->
            statement.bindLong(1, lastId)
            statement.bindInt(2, NAME_KEY_BATCH)
            while (statement.step()) rows.add(statement.getLong(0) to statement.getText(1))
        }
        if (rows.isEmpty()) return
        db.prepare("UPDATE `$table` SET `nameKey` = ? WHERE `id` = ?").use { statement ->
            for ((id, name) in rows) {
                statement.bindText(1, nameKeyOf(name))
                statement.bindLong(2, id)
                statement.step()
                statement.reset()
            }
        }
        lastId = rows.last().first
    }
}
