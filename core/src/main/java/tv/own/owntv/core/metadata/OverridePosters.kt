package tv.own.owntv.core.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import tv.own.owntv.core.database.OwnTVDatabase

/**
 * glcn: when the user types a TMDB id for a film or series, the provider's own data was wrong — its poster
 * very often too (another film's cover). The TMDB poster then goes on the item's row itself, so every card
 * (rows, grid, Continue watching, the runway) shows it; a re-sync writes the provider's back, and
 * [ImportFinalizer][tv.own.owntv.core.sync.ImportFinalizer] puts the TMDB one back again.
 */
object OverridePosters {

    /** Writes [url] as the poster of the item [localKey] names (`movie:<source>:<id or name>` / `tv:…`). */
    suspend fun write(db: OwnTVDatabase, localKey: String, url: String) {
        val parts = localKey.split(":", limit = 3)
        if (parts.size != 3) return
        val table = when (parts[0]) { "movie" -> "movies"; "tv" -> "series"; else -> return }
        val sourceId = parts[1].toLongOrNull() ?: return
        val id = parts[2]
        withContext(Dispatchers.IO) {
            runCatching {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        usePrepared(
                            "UPDATE `" + table + "` SET `posterUrl` = ? WHERE `sourceId` = ? AND (`remoteId` = ? OR (`remoteId` IS NULL AND `name` = ?))",
                        ) { st ->
                            st.bindText(1, url)
                            st.bindLong(2, sourceId)
                            st.bindText(3, id)
                            st.bindText(4, id)
                            st.step()
                        }
                    }
                }
            }
        }
    }

    /** Puts back, after a sync of [sourceId], every TMDB poster an override had put on that playlist's items. */
    suspend fun restore(db: OwnTVDatabase, store: MetadataOverrideStore, sourceId: Long) {
        store.all().forEach { (key, o) ->
            val url = o.poster ?: return@forEach
            if (o.tmdbId == null || key.split(":", limit = 3).getOrNull(1)?.toLongOrNull() != sourceId) return@forEach
            write(db, key, url)
        }
    }
}
