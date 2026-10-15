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
 * glcn: many guide programmes carry a portrait poster where a wide picture is wanted (a card, the
 * guide's backdrop). Given that poster's URL, this finds the programme's title, looks the show or film
 * up on TMDB and returns its wide backdrop — or null when TMDB has nothing that matches the title
 * exactly (a match, a news bulletin), in which case the caller crops the poster.
 *
 * Answers are remembered on disk, the misses too, so a title is asked about once; and the lookups are
 * rationed, so the guide never spends the metadata allowance the films and series rely on.
 */
class ProgrammeArtResolver(
    context: Context,
    private val provider: MetadataProvider,
    private val epgDao: EpgDao,
    private val settings: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences("glcn_programme_art", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, String>() // title key → url, "" = none
    private val mutex = Mutex()
    private val lookups = ArrayDeque<Long>()

    suspend fun landscapeFor(imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (!runCatching { settings.metadataConfig().enabled }.getOrDefault(false)) return@withContext null
        val title = runCatching { epgDao.titleForImage(imageUrl) }.getOrNull() ?: return@withContext null
        val query = clean(title)
        if (query.length < 2) return@withContext null
        val key = fold(query)
        memory[key]?.let { return@withContext it.ifEmpty { null } }
        prefs.getString(key, null)?.let { memory[key] = it; return@withContext it.ifEmpty { null } }
        mutex.withLock {
            memory[key]?.let { return@withLock it.ifEmpty { null } }
            if (!allowLookup()) return@withLock null
            val found = runCatching { search(query) }.getOrNull() ?: return@withLock null // transport failure: ask again later
            val url = found.orEmpty()
            memory[key] = url
            if (prefs.all.size > MAX_STORED) prefs.edit().clear().apply()
            prefs.edit().putString(key, url).apply()
            url.ifEmpty { null }
        }
    }

    /** "" when TMDB answered with nothing usable; null when it could not be asked. */
    private suspend fun search(query: String): String? {
        val want = fold(query)
        fun pick(results: List<MetadataSearchResult>?): String? = results
            ?.filter { r -> r.backdropPath != null && (fold(r.title) == want || r.originalTitle?.let(::fold) == want) }
            ?.maxByOrNull { it.popularity }
            ?.backdropPath
        val tv = provider.searchTv(query, null, false) ?: return null
        pick(tv)?.let { return MetadataImages.backdrop(it, "w780") }
        val movies = provider.searchMovie(query, null, false) ?: return null
        return pick(movies)?.let { MetadataImages.backdrop(it, "w780") } ?: ""
    }

    private fun allowLookup(): Boolean {
        val now = System.currentTimeMillis()
        while (lookups.isNotEmpty() && now - lookups.first() > 3_600_000L) lookups.removeFirst()
        if (lookups.size >= MAX_PER_HOUR) return false
        lookups.addLast(now)
        return true
    }

    companion object {
        private const val MAX_PER_HOUR = 30
        private const val MAX_STORED = 2_000

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
    }
}
