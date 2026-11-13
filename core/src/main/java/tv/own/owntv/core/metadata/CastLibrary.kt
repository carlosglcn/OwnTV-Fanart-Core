package tv.own.owntv.core.metadata

import org.koin.core.context.GlobalContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.trakt.TraktSync

/**
 * glcn: the films in your playlists (and media servers) with a given actor. Two ways in, merged: the TMDB details the
 * app has already cached (their cast), and — when the caller has it — the person's filmography from elsewhere
 * ([credits]: a film's TMDB id, title(s) and year), matched by TMDB id first, then by title and year.
 */
object CastLibrary {

    class Credit(val tmdb: Int?, val titles: List<String>, val year: Int?)

    suspend fun filmsWith(name: String, except: Long? = null, credits: List<Credit> = emptyList()): List<MovieEntity> {
        val dao = runCatching { GlobalContext.get().get<MetadataDao>() }.getOrNull()
        val cached = dao?.let { d -> runCatching { d.movieTmdbIdsWithCast(name.trim()) }.getOrDefault(emptyList()) }.orEmpty()
        val ids = (cached + credits.mapNotNull { it.tmdb }).distinct()
        val byId = if (ids.isEmpty()) emptyMap() else runCatching { TraktSync.moviesFor(ids) }.getOrDefault(emptyMap())
        val found = byId.values.toMutableList()
        for (c in credits.take(MAX_BY_NAME)) {
            if (c.tmdb != null && byId.containsKey(c.tmdb)) continue
            if (c.year == null) continue
            c.titles.firstNotNullOfOrNull { t -> runCatching { TraktSync.filmByTitle(t, c.year) }.getOrNull() }?.let { found += it }
        }
        return found
            .filter { it.id != except }
            .distinctBy { it.name.lowercase() }
            .sortedByDescending { it.year ?: 0 }
    }

    private const val MAX_BY_NAME = 160
}
