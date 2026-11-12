package tv.own.owntv.core.metadata

import org.koin.core.context.GlobalContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.entity.MovieEntity

/**
 * glcn: the films in your playlists with a given actor — found through the TMDB details the app has already cached
 * (their cast) and the films matched to them. Only films whose details were fetched are known; nothing is asked
 * of TMDB here.
 */
object CastLibrary {
    suspend fun filmsWith(name: String, except: Long? = null): List<MovieEntity> {
        val dao = runCatching { GlobalContext.get().get<MetadataDao>() }.getOrNull() ?: return emptyList()
        val ids = runCatching { dao.movieTmdbIdsWithCast(name.trim()) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return emptyList()
        return tv.own.owntv.core.trakt.TraktSync.moviesFor(ids).values
            .filter { it.id != except }
            .distinctBy { it.name.lowercase() }
            .sortedByDescending { it.year ?: 0 }
    }
}
