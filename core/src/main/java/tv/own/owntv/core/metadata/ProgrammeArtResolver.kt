package tv.own.owntv.core.metadata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
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
    data class Info(val backdropUrl: String?, val rating: Double?, val overview: String? = null, val year: Int? = null)

    private val prefs = context.getSharedPreferences("glcn_programme_info_v3", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, Info>()
    private val mutex = Mutex()
    private val lookups = ArrayDeque<Long>()

    /** A wide picture for the programme whose (portrait) picture is [imageUrl]; null for none. */
    suspend fun landscapeFor(imageUrl: String): String? = withContext(Dispatchers.IO) {
        val programme = runCatching { epgDao.programmeForImage(imageUrl) }.getOrNull() ?: return@withContext null
        // The guide's own picture is normally the provider's real one, so TMDB's replaces it only when
        // the guide says what the programme is (a film or a series) and, if it gives a year, the year agrees.
        val hint = Hint(programme.categories, programme.year, programme.lengthMin, programme.episode)
        if (hint.kind(programme.title) == Kind.UNKNOWN) return@withContext null
        infoFor(programme.title, hint)?.backdropUrl
    }

    /**
     * glcn: a wide picture for a guide programme that has none of its own — only what is already known (memory or
     * the saved answers), so a card is never held up. A film or series not asked about yet is looked up in the
     * background by [warm]; the next time it is drawn it has its picture.
     */
    fun knownBackdrop(p: tv.own.owntv.core.database.entity.EpgProgrammeEntity): String? {
        val hint = Hint(p.categories, p.year, p.lengthMin, p.episode)
        if (hint.kind(p.title) == Kind.UNKNOWN) return null
        val key = keyFor(p.title, hint, loose = false) ?: return null
        memory[key]?.let { return it.backdropUrl }
        val stored = runCatching { prefs.getString(key, null) }.getOrNull() ?: return null
        return decode(stored).also { memory[key] = it }.backdropUrl
    }

    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val warming = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** glcn: asks TMDB, in the background and within the hourly allowance, for a film or series with no picture. */
    fun warm(p: tv.own.owntv.core.database.entity.EpgProgrammeEntity) {
        val hint = Hint(p.categories, p.year, p.lengthMin, p.episode)
        if (hint.kind(p.title) == Kind.UNKNOWN) return
        val key = keyFor(p.title, hint, loose = false) ?: return
        if (memory.containsKey(key) || !warming.add(key)) return
        warmScope.launch { try { infoFor(p.title, hint) } finally { warming.remove(key) } }
    }

    private fun keyFor(title: String, hint: Hint?, loose: Boolean): String? {
        val query = clean(title)
        if (query.length < 2 || fold(query).isEmpty()) return null
        val kind = hint?.kind(title) ?: Kind.UNKNOWN
        return fold(query) + "|" + kind.name.first() + listOfNotNull(hint?.year, '~'.takeIf { loose }).joinToString(separator = "")
    }

    /** TMDB's average rating for the programme called [title]; null for none. */
    suspend fun ratingFor(title: String): Double? = infoFor(title)?.rating

    /**
     * The rating for a guide programme, asked only when it may be a film or a series: sport, news,
     * talk and quiz shows are never looked up, so the hourly allowance goes to what TMDB can rate.
     */
    suspend fun ratingFor(title: String, categories: String?): Double? =
        if (worthRating(title, categories)) infoFor(title, Hint(categories, null, null, null))?.rating else null

    /**
     * Everything TMDB knows for a guide programme that may be a film or a series: rating, year and a
     * synopsis, for a programme whose guide entry says nothing. Same allowance and memory as [ratingFor].
     */
    suspend fun detailsFor(title: String, categories: String?): Info? =
        if (worthRating(title, categories)) infoFor(title, Hint(categories, null, null, null), loose = true) else null

    private val logoPrefs = context.getSharedPreferences("glcn_programme_logo_v1", Context.MODE_PRIVATE)
    private val logoMemory = ConcurrentHashMap<String, String>()

    /**
     * The title's own logo (the lettering a film is known by), for a programme that is a film — never for a
     * series or a bulletin. Asked once per title and remembered, a miss too ("" = none). Null when there is
     * none or TMDB could not be asked.
     */
    suspend fun logoFor(title: String, categories: String?): String? = withContext(Dispatchers.IO) {
        if (!worthRating(title, categories)) return@withContext null
        if (!runCatching { settings.metadataConfig().enabled }.getOrDefault(false)) return@withContext null
        val hint = Hint(categories, null, null, null)
        if (hint.kind(title) == Kind.SERIES) return@withContext null
        val query = clean(title)
        val want = fold(query)
        if (want.isEmpty()) return@withContext null
        val key = want + "|" + hint.kind(title).name.first()
        (logoMemory[key] ?: logoPrefs.getString(key, null))?.let { logoMemory[key] = it; return@withContext it.ifBlank { null } }
        mutex.withLock {
            if (!allowLookup()) return@withLock null
            val hits = runCatching { provider.searchMovie(query, null, false) }.getOrNull() ?: return@withLock null
            val hit = hits.filter { r -> fold(r.title) == want || r.originalTitle?.let(::fold) == want }.maxByOrNull { it.popularity }
            val url = hit?.let { runCatching { provider.movieDetails(it.tmdbId) }.getOrNull() }?.logoPath?.let { MetadataImages.logo(it) }
            if (hit != null && url == null) {
                // A film found, no lettering for it: remember that. A failed call is asked again later.
                logoMemory[key] = ""; logoPrefs.edit().putString(key, "").apply(); return@withLock null
            }
            if (hit == null) { logoMemory[key] = ""; logoPrefs.edit().putString(key, "").apply(); return@withLock null }
            logoMemory[key] = url!!
            if (logoPrefs.all.size > MAX_STORED) logoPrefs.edit().clear().apply()
            logoPrefs.edit().putString(key, url).apply()
            url
        }
    }

    private val filmPrefs = context.getSharedPreferences("glcn_programme_film_v1", Context.MODE_PRIVATE)
    private val filmMemory = ConcurrentHashMap<String, MovieDetails>()

    /**
     * glcn: a film on a TV channel, in full — TMDB's details (lettering, year, genres, rating, synopsis, cast, the wide
     * picture) for the programme's info screen. The match (the TMDB id, or none) is kept on the device; the details
     * are kept in memory, so a film already looked at — or warmed when its channel was tuned — opens at once.
     */
    suspend fun filmFor(title: String, categories: String?): MovieDetails? = withContext(Dispatchers.IO) {
        if (!worthRating(title, categories)) return@withContext null
        if (!runCatching { settings.metadataConfig().enabled }.getOrDefault(false)) return@withContext null
        if (Hint(categories, null, null, null).kind(title) == Kind.SERIES) return@withContext null
        val query = clean(title)
        val want = fold(query)
        if (want.isEmpty()) return@withContext null
        filmMemory[want]?.let { return@withContext it }
        var id = filmPrefs.getInt(want, -1)
        if (id == 0) return@withContext null
        if (id < 0) {
            val found = mutex.withLock {
                if (!allowLookup()) return@withLock null
                val hits = runCatching { provider.searchMovie(query, null, false) }.getOrNull() ?: return@withLock null
                hits.filter { r -> fold(r.title) == want || r.originalTitle?.let(::fold) == want }.maxByOrNull { it.popularity }?.tmdbId ?: 0
            } ?: return@withContext null
            if (filmPrefs.all.size > MAX_STORED) filmPrefs.edit().clear().apply()
            filmPrefs.edit().putInt(want, found).apply()
            id = found
        }
        if (id <= 0) return@withContext null
        val details = runCatching { provider.movieDetails(id) }.getOrNull() ?: return@withContext null
        filmMemory[want] = details
        details
    }

    /** What tells a film from a series in the guide: the feed's categories, year, length and episode mark. */
    data class Hint(val categories: String?, val year: Int?, val lengthMin: Int?, val episode: String?) {
        /** FILM / SERIES when the feed says so, else UNKNOWN. */
        fun kind(title: String): Kind {
            val cats = categories?.let(::fold).orEmpty()
            val episodic = !episode.isNullOrBlank() || EPISODE.containsMatchIn(title) || title.contains(" · ")
            return when {
                episodic -> Kind.SERIES
                Regex("""\b(cine|pelicula|peliculas|film|movie|movies)\b""").containsMatchIn(cats) -> Kind.FILM
                Regex("""\b(serie|series|telenovela|miniserie)\b""").containsMatchIn(cats) -> Kind.SERIES
                (lengthMin ?: 0) >= 80 -> Kind.FILM
                else -> Kind.UNKNOWN
            }
        }
    }

    enum class Kind { FILM, SERIES, UNKNOWN }

    suspend fun infoFor(p: tv.own.owntv.core.database.entity.EpgProgrammeEntity): Info? =
        infoFor(p.title, Hint(p.categories, p.year, p.lengthMin, p.episode))

    suspend fun infoFor(title: String): Info? = infoFor(title, null)

    suspend fun infoFor(title: String, hint: Hint?, loose: Boolean = false): Info? = withContext(Dispatchers.IO) {
        if (!runCatching { settings.metadataConfig().enabled }.getOrDefault(false)) return@withContext null
        val query = clean(title)
        if (query.length < 2) return@withContext null
        val kind = hint?.kind(title) ?: Kind.UNKNOWN
        val key = fold(query) + "|" + kind.name.first() + listOfNotNull(hint?.year, '~'.takeIf { loose }).joinToString(separator = "")
        if (fold(query).isEmpty()) return@withContext null
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
            val found = runCatching { search(query, kind, hint?.year, loose) }.getOrNull() ?: return@withLock null
            memory[key] = found
            if (prefs.all.size > MAX_STORED) prefs.edit().clear().apply()
            prefs.edit().putString(key, encode(found)).apply()
            found.orNull()
        }
    }

    /**
     * NONE when TMDB answered with nothing that matches; null when it could not be asked. A film in the
     * guide is looked up among films only, a series among series — the same title ("Aquaman") is both a
     * 2018 film and a 1967 cartoon, and the wrong one's picture is worse than none. With no hint, both
     * are asked and the better known wins.
     */
    private suspend fun search(query: String, kind: Kind, year: Int?, loose: Boolean = false): Info? {
        val want = fold(query)
        fun best(results: List<MetadataSearchResult>?): MetadataSearchResult? {
            val inYear = results?.filter { r -> year == null || r.year == null || kotlin.math.abs(r.year - year) <= 1 }
            inYear?.filter { r -> fold(r.title) == want || r.originalTitle?.let(::fold) == want }
                ?.maxByOrNull { it.popularity }?.let { return it }
            // glcn: for the synopsis only — a guide title that is the film's name plus a tag or subtitle
            // ("Directoras sin piedad - estreno"), kept to long titles so a short one never matches by chance.
            if (!loose || want.length < 10) return null
            return inYear?.filter { r -> val t = fold(r.title); t.length >= 10 && (t.startsWith(want) || want.startsWith(t)) }
                ?.maxByOrNull { it.popularity }
        }
        val hit = when (kind) {
            Kind.FILM -> best(provider.searchMovie(query, year, false) ?: return null)
            Kind.SERIES -> best(provider.searchTv(query, null, false) ?: return null)
            Kind.UNKNOWN -> {
                val tv = best(provider.searchTv(query, null, false) ?: return null)
                val movie = best(provider.searchMovie(query, year, false) ?: return null)
                listOfNotNull(tv, movie).maxByOrNull { it.popularity }
            }
        } ?: return NONE
        return Info(
            backdropUrl = hit.backdropPath?.let { MetadataImages.backdrop(it, "w780") },
            rating = hit.voteAverage,
            overview = hit.overview?.takeIf { it.isNotBlank() },
            year = hit.year,
        )
    }

    private fun allowLookup(): Boolean {
        val now = System.currentTimeMillis()
        while (lookups.isNotEmpty() && now - lookups.first() > 3_600_000L) lookups.removeFirst()
        if (lookups.size >= MAX_PER_HOUR) return false
        lookups.addLast(now)
        return true
    }

    private fun Info.orNull(): Info? = takeIf { backdropUrl != null || rating != null || overview != null }

    companion object {
        private const val MAX_PER_HOUR = 40
        private const val MAX_STORED = 3_000
        private val NONE = Info(null, null)

        private fun encode(i: Info) = listOf(
            i.backdropUrl.orEmpty(), i.rating?.toString().orEmpty(), i.year?.toString().orEmpty(),
            i.overview.orEmpty().filterNot { it == '\t' || it == '\n' || it == '\r' },
        ).joinToString("\t")

        private fun decode(s: String): Info {
            val parts = s.split('\t')
            return Info(
                backdropUrl = parts.getOrNull(0)?.ifBlank { null },
                rating = parts.getOrNull(1)?.toDoubleOrNull(),
                overview = parts.getOrNull(3)?.ifBlank { null },
                year = parts.getOrNull(2)?.toIntOrNull(),
            )
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

        private val FILM_CATS = Regex("""\b(cine|pelicula|peliculas|film|movie|movies|serie|series|drama|comedia|comedy|thriller|terror|horror|accion|action|animacion|animation|ciencia ficcion|sci fi|fantasia|fantasy|western|suspense|romance|aventura|adventure|crimen|crime|policiaca|misterio|mystery|documental|documentary)\b""")
        private val NOT_FILM = Regex("""\b(deporte|deportes|sport|sports|futbol|football|soccer|baloncesto|basket|tenis|tennis|motor|ciclismo|golf|noticias|news|informativo|informativos|telediario|magazine|tertulia|debate|talk|concurso|quiz|game show|reality|entretenimiento|tiempo|weather|tarot|teletienda|infocomercial|musica|music)\b""")

        /** Whether a guide programme may be a film or a series TMDB can rate. */
        fun worthRating(title: String, categories: String?): Boolean {
            val cats = categories?.let(::fold).orEmpty()
            val t = fold(title)
            if (cats.isNotEmpty()) {
                if (FILM_CATS.containsMatchIn(cats)) return !NOT_FILM.containsMatchIn(t)
                if (NOT_FILM.containsMatchIn(cats)) return false
            }
            if (NOT_FILM.containsMatchIn(t)) return false
            if (t.startsWith("directo") || t.contains(" en directo")) return false
            return true
        }

        /** "7,4" in Spanish, "7.4" in English. */
        fun format(rating: Double): String = String.format(java.util.Locale.getDefault(), "%.1f", rating)
    }
}
