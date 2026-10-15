package tv.own.owntv.core.metadata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.settings.SettingsRepository
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: what TMDB knows about a guide programme — its wide backdrop (for a programme whose own picture
 * is a portrait poster) and its average rating (shown beside films and series on live TV).
 *
 * A programme is looked up by its title, cleaned of episode marks ("A un paso del cielo T5 E17 · …"
 * → "A un paso del cielo"), and only an exact title match counts — a match, a news bulletin or a talk
 * show finds nothing, which is right. Answers are remembered on disk, the misses too, so a title is
 * asked about once; and the lookups are rationed, so the guide never spends the metadata allowance the
 * films and series rely on.
 */
class ProgrammeArtResolver(
    context: Context,
    private val provider: MetadataProvider,
    private val epgDao: EpgDao,
    private val settings: SettingsRepository,
) {
    /** What TMDB knows about one title; both null for "nothing usable". */
    data class Info(val backdropUrl: String?, val rating: Double?)

    private val prefs = context.getSharedPreferences("glcn_programme_info", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, Info>()
    private val mutex = Mutex()
    private val lookups = ArrayDeque<Long>()

    /** A wide picture for the programme whose (portrait) picture is [imageUrl]; null for none. */
    suspend fun landscapeFor(imageUrl: String): String? = withContext(Dispatchers.IO) {
        val title = runCatching { epgDao.titleForImage(imageUrl) }.getOrNull() ?: return@withContext null
        infoFor(title)?.backdropUrl
    }

    /** TMDB's average rating for the programme called [title]; null for none. */
    suspend fun ratingFor(title: String): Double? = infoFor(title)?.rating

    suspend fun infoFor(title: String): Info? = withContext(Dispatchers.IO) {
        if (!runCatching { settings.metadataConfig().enabled }.getOrDefault(false)) return@withContext null
        val query = clean(title)
        if (query.length < 2) return@withContext null
        val key = fold(query)
        if (key.isEmpty()) return@withContext null
        memory[key]?.let { return@withContext it.orNull() }
        prefs.getString(key, null)?.let { stored ->
            val info = decode(stored)
            memory[key] = info
            return@withContext info.orNull()
        }
        mutex.withLock {
            memory[key]?.let { return@withLock it.orNull() }
            if (!allowLookup()) return@withLock null
            // Transport failure: nothing is remembered, so it is asked again later.
            val found = runCatching { search(query) }.getOrNull() ?: return@withLock null
            memory[key] = found
            if (prefs.all.size > MAX_STORED) prefs.edit().clear().apply()
            prefs.edit().putString(key, encode(found)).apply()
            found.orNull()
        }
    }

    /** NONE when TMDB answered with nothing that matches; null when it could not be asked. */
    private suspend fun search(query: String): Info? {
        val want = fold(query)
        fun best(results: List<MetadataSearchResult>?): MetadataSearchResult? = results
            ?.filter { r -> fold(r.title) == want || r.originalTitle?.let(::fold) == want }
            ?.maxByOrNull { it.popularity }
        val tv = provider.searchTv(query, null, false) ?: return null
        val hit = best(tv) ?: run {
            val movies = provider.searchMovie(query, null, false) ?: return null
            best(movies)
        } ?: return NONE
        return Info(
            backdropUrl = hit.backdropPath?.let { MetadataImages.backdrop(it, "w780") },
            rating = hit.voteAverage,
        )
    }

    private fun allowLookup(): Boolean {
        val now = System.currentTimeMillis()
        while (lookups.isNotEmpty() && now - lookups.first() > 3_600_000L) lookups.removeFirst()
        if (lookups.size >= MAX_PER_HOUR) return false
        lookups.addLast(now)
        return true
    }

    private fun Info.orNull(): Info? = takeIf { backdropUrl != null || rating != null }

    companion object {
        private const val MAX_PER_HOUR = 40
        private const val MAX_STORED = 3_000
        private val NONE = Info(null, null)

        private fun encode(i: Info) = (i.backdropUrl ?: "") + "\t" + (i.rating?.toString() ?: "")
        private fun decode(s: String): Info {
            val parts = s.split('\t')
            return Info(parts.getOrNull(0)?.ifBlank { null }, parts.getOrNull(1)?.toDoubleOrNull())
        }

        private val EPISODE = Regex("""\s+(T\d+(\s*E\d+)?|S\d+\s*E\d+|Temporada\s+\d+|Ep\.?\s*\d+|Episodio\s+\d+)\b.*$""", RegexOption.IGNORE_CASE)
        private val LIVE_PREFIX = Regex("""^\s*\(?(EN\s+)?DIRECTO\)?\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)
        private val PARENS = Regex("""\s*[\(\[][^)\]]*[\)\]]""")

        /** "A un paso del cielo T5 E17 · La fuente del odio 1" → "A un paso del cielo". */
        fun clean(title: String): String = title
            .substringBefore(" · ")
            .replace(LIVE_PREFIX, "")
            .replace(EPISODE, "")
            .replace(PARENS, "")
            .trim()

        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        /** "7,4" in Spanish, "7.4" in English. */
        fun format(rating: Double): String = String.format(java.util.Locale.getDefault(), "%.1f", rating)
    }
}
