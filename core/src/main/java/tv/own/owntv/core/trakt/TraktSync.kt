package tv.own.owntv.core.trakt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.emby.EmbyPlayback
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.model.MediaType

/**
 * glcn: the app's side of [Trakt].
 *
 * - [watchedMovie] / [watchedEpisode]: a title finished or marked watched goes to the account's history. Skipped for
 *   Emby/Jellyfin items (the server's own Trakt plugin reports those — sent twice, they would count twice) and for
 *   anything not matched to TMDB.
 * - [refresh]: the account's watched films get their ✓ here (only where nothing is recorded yet — progress of
 *   your own is never overwritten), and its watchlist is kept for Home ([watchlistMovies]).
 *
 * Only films the app has already matched to TMDB with confidence are mapped; an unmatched film is left alone.
 */
object TraktSync {
    private const val TAG = "TraktSync"
    private const val FILE = "glcn_trakt_sync"
    private const val MIN_CONFIDENCE = 0.75
    private const val REFRESH_EVERY_MS = 30 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private inline fun <reified T : Any> dep(): T? = runCatching { GlobalContext.get().get<T>() }.getOrNull()

    private fun prefs(): android.content.SharedPreferences? =
        dep<Context>()?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watchedMovie(profileId: Long, movie: MovieEntity) {
        if (!Trakt.available || !Trakt.connected(profileId) || EmbyPlayback.isEmbyUrl(movie.streamUrl)) return
        scope.launch {
            val tmdb = runCatching { dep<MetadataRepository>()?.resolveMovie(movie)?.tmdbId }.getOrNull()?.takeIf { it > 0 } ?: return@launch
            Trakt.addMovieToHistory(profileId, tmdb)
        }
    }

    fun watchedEpisode(profileId: Long, series: SeriesEntity, episode: EpisodeEntity) {
        if (!Trakt.available || !Trakt.connected(profileId) || EmbyPlayback.isEmbyUrl(episode.streamUrl)) return
        if (episode.seasonNumber <= 0 || episode.episodeNumber <= 0) return
        scope.launch {
            val tmdb = runCatching { dep<MetadataRepository>()?.resolveSeries(series)?.tmdbId }.getOrNull()?.takeIf { it > 0 } ?: return@launch
            Trakt.addEpisodeToHistory(profileId, tmdb, episode.seasonNumber, episode.episodeNumber)
        }
    }

    /** The watchlist films found in the playlists, newest on the list first (empty when not signed in). */
    suspend fun watchlistMovies(profileId: Long, limit: Int): List<MovieEntity> {
        if (!Trakt.available || !Trakt.connected(profileId)) return emptyList()
        val ids = prefs()?.getString("$profileId.watchlist", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        if (ids.isEmpty()) return emptyList()
        val local = moviesFor(ids)
        return ids.mapNotNull { local[it] }.distinctBy { it.id }.take(limit)
    }

    /** [refresh] in the background, without waiting for it. */
    fun refreshLater(profileId: Long) {
        if (!Trakt.available || !Trakt.connected(profileId)) return
        scope.launch { runCatching { refresh(profileId) } }
    }

    /** At most every half hour (or [force]): the account's watched films and watchlist. */
    suspend fun refresh(profileId: Long, force: Boolean = false) {
        if (!Trakt.available || !Trakt.connected(profileId)) return
        val p = prefs() ?: return
        val last = p.getLong("$profileId.at", 0L)
        if (!force && System.currentTimeMillis() - last < REFRESH_EVERY_MS) return
        p.edit().putLong("$profileId.at", System.currentTimeMillis()).apply()
        Trakt.watchlistMovieTmdbIds(profileId)?.let { ids -> p.edit().putString("$profileId.watchlist", ids.joinToString(",")).apply() }
        val watched = Trakt.watchedMovieTmdbIds(profileId) ?: return
        if (watched.isEmpty()) return
        val progressDao = dep<ProgressDao>() ?: return
        var marked = 0
        for (movie in moviesFor(watched.toList()).values) {
            if (EmbyPlayback.isEmbyUrl(movie.streamUrl)) continue
            if (progressDao.get(profileId, MediaType.MOVIE, movie.id) != null) continue
            // The "marked watched" sentinel (1 of 1): the ✓, nothing to resume.
            progressDao.save(PlaybackProgressEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movie.id, positionMs = 1L, durationMs = 1L))
            marked++
        }
        if (marked > 0) Log.i(TAG, "watched on Trakt: $marked films marked")
    }

    /** TMDB id → a local film matched to it (the match table is keyed "movie:<source>:<provider id or name>"). */
    private suspend fun moviesFor(tmdbIds: List<Int>): Map<Int, MovieEntity> {
        val metadataDao = dep<MetadataDao>() ?: return emptyMap()
        val movieDao = dep<MovieDao>() ?: return emptyMap()
        val matches = tmdbIds.distinct().chunked(CHUNK).flatMap { metadataDao.movieMatchesFor(it, MIN_CONFIDENCE) }
        data class Key(val source: Long, val ref: String)
        val tmdbByKey = HashMap<Key, Int>()
        for (m in matches) {
            val parts = m.localKey.removeSuffix(":kids").split(':', limit = 3)
            val source = parts.getOrNull(1)?.toLongOrNull() ?: continue
            val ref = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: continue
            m.tmdbId?.let { tmdbByKey[Key(source, ref)] = it }
        }
        val out = HashMap<Int, MovieEntity>()
        for ((source, keys) in tmdbByKey.keys.groupBy { it.source }) {
            val refs = keys.map { it.ref }
            for (chunk in refs.chunked(CHUNK)) {
                movieDao.findByRemoteIds(source, chunk).forEach { f -> f.remoteId?.let { tmdbByKey[Key(source, it)] }?.let { out.putIfAbsent(it, f) } }
                movieDao.findByNamesWithoutRemote(source, chunk).forEach { f -> tmdbByKey[Key(source, f.name)]?.let { out.putIfAbsent(it, f) } }
            }
        }
        return out
    }

    private const val CHUNK = 500
}
