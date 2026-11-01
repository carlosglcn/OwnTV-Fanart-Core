package tv.own.owntv.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * glcn: an Emby (or Jellyfin) server added as an ordinary M3U playlist.
 *
 * The user adds `http://user:password@nas:8096` as an M3U link. Instead of downloading a playlist,
 * the sync signs in to the server, reads the user's films and episodes and turns them into the M3U
 * the existing pipeline already understands — films as `type="movie"`, episodes as
 * `type="series"` named "Show S01E02 Title" — so they land in Movies and Series with their pictures,
 * and resume, favourites and Keep watching work as for any other playlist.
 *
 * Playback is the original file, untouched (`/Videos/{id}/stream?static=true`), which is what the
 * player on a TV box wants on the home network.
 */
internal object EmbyBridge {

    /** Server root (no trailing slash) and the account to sign in with. */
    data class Target(val root: String, val user: String, val password: String)

    /** Signed in: [api] is the root plus the server's API prefix ("/emby" on Emby, none on Jellyfin). */
    data class Session(val api: String, val token: String, val userId: String)

    /** The server refused the user name or password. */
    class AuthFailed : IOException("Emby: usuario o contraseña incorrectos")

    private val client by lazy {
        tv.own.owntv.core.network.SharedHttp.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private const val AUTH_FIELDS =
        "Client=\"OwnTV\", Device=\"OwnTV\", DeviceId=\"owntv-glcn\", Version=\"1.0\""

    private const val FIELDS =
        "ProductionYear,ParentIndexNumber,IndexNumber,SeriesName,SeriesId," +
            "Container,Genres,Overview,CommunityRating,BackdropImageTags," +
            "DateCreated,ProviderIds,RunTimeTicks,UserData,MediaStreams,Studios"

    /**
     * `http(s)://user[:password]@host[:port][/path]` with no file name in the path → a possible server.
     * Anything else (a `.m3u` file, `get.php`, a link without a user) is an ordinary playlist.
     */
    fun parse(url: String): Target? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val info = uri.rawUserInfo?.takeIf { it.isNotEmpty() } ?: return null
        val host = uri.host ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.substringAfterLast('/').contains('.')) return null
        if (!uri.rawQuery.isNullOrEmpty()) return null
        val user = percentDecode(info.substringBefore(':'))
        val password = if (':' in info) percentDecode(info.substringAfter(':')) else ""
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return Target("$scheme://$hostPart$port$path", user, password)
    }

    /** "%40" → "@", leaving "+" as it is (it is a real character in a password). */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { out.write(b); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }

    /**
     * The API prefix this server answers on, or null when it is not Emby/Jellyfin at all (then the
     * link is treated as an ordinary playlist).
     */
    /** glcn: why the last [detect] found no server — shown under "Test connection" (exception or HTTP status). */
    @Volatile var lastDetectError: String? = null
        private set

    suspend fun detect(t: Target): String? = withContext(Dispatchers.IO) {
        lastDetectError = null
        for (prefix in listOf("/emby", "")) {
            val api = runCatching {
                client.newCall(Request.Builder().url("${t.root}$prefix/System/Info/Public").get().build()).execute().use { r ->
                    // Where the request really ended up: a reverse proxy often answers http:// with a
                    // redirect to https://, and the sign-in (a POST) must go straight there — a
                    // redirected POST arrives as a GET and the server refuses it.
                    if (r.isSuccessful && JSONObject(r.body?.string().orEmpty()).has("Id")) {
                        r.request.url.toString().substringBefore("?").removeSuffix("/System/Info/Public")
                    } else {
                        lastDetectError = "HTTP ${r.code}"
                        null
                    }
                }
            }.onFailure { e ->
                val cause = generateSequence(e) { it.cause }.last()
                lastDetectError = "${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(160)}"
            }.getOrNull()
            if (api != null) return@withContext api
        }
        null
    }

    /** Signs in; throws [AuthFailed] on a wrong user or password. */
    suspend fun login(t: Target, api: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("Username", t.user).put("Pw", t.password).toString()
            .toRequestBody("application/json".toMediaType())
        // Emby reads X-Emby-Authorization ("Emby …"), Jellyfin reads Authorization ("MediaBrowser …");
        // the same client fields also go in the query string, which older Emby builds read instead.
        val query = "X-Emby-Client=OwnTV&X-Emby-Device-Name=OwnTV&X-Emby-Device-Id=owntv-glcn&X-Emby-Client-Version=1.0"
        val req = Request.Builder().url("$api/Users/AuthenticateByName?$query")
            .header("X-Emby-Authorization", "Emby $AUTH_FIELDS")
            .header("Authorization", "MediaBrowser $AUTH_FIELDS")
            .post(body)
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw AuthFailed()
            if (!r.isSuccessful) throw IOException("Emby: HTTP ${r.code}")
            val j = JSONObject(r.body?.string().orEmpty())
            Session(api, j.getString("AccessToken"), j.getJSONObject("User").getString("Id"))
        }
    }

    private fun items(s: Session, kind: String, filters: String? = null): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        forEachItem(s, kind, filters) { out += it }
        return out
    }

    /**
     * glcn: the items one page at a time, each handed to [each] and let go — a library's thousands of episodes, each
     * with its media streams, used to be held in memory all at once (with the playlist being written beside them),
     * and a big library filled the heap while syncing.
     */
    private inline fun forEachItem(s: Session, kind: String, filters: String? = null, each: (JSONObject) -> Unit) {
        var start = 0
        while (true) {
            val sort = if (kind == "Movie") "SortName" else "SeriesSortName,ParentIndexNumber,IndexNumber"
            val url = "${s.api}/Users/${s.userId}/Items?Recursive=true&IncludeItemTypes=$kind&Fields=$FIELDS" +
                "&SortBy=$sort&IsMissing=false&StartIndex=$start&Limit=$PAGE" + (filters?.let { f -> "&Filters=$f" } ?: "")
            val req = Request.Builder().url(url).header("X-Emby-Token", s.token).get().build()
            val page = client.newCall(req).execute().use { r ->
                if (r.code == 401) throw TokenGone()
                if (!r.isSuccessful) throw IOException("Emby: HTTP ${r.code}")
                JSONObject(r.body?.string().orEmpty())
            }
            val arr = page.optJSONArray("Items") ?: break
            val total = page.optInt("TotalRecordCount", 0)
            val n = arr.length()
            for (i in 0 until n) each(arr.getJSONObject(i))
            start += n
            if (n == 0 || start >= total) break
        }
    }

    private fun attr(v: String) = v.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()
    private fun line(v: String) = v.replace('\n', ' ').replace('\r', ' ').trim()

    /** The whole library as M3U text. */
    suspend fun playlist(s: Session): String = withContext(Dispatchers.IO) {
        fun image(id: String) = "${s.api}/Items/$id/Images/Primary?maxWidth=500&api_key=${s.token}"
        fun backdrop(it: JSONObject): String? =
            if ((it.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0) {
                "${s.api}/Items/${it.getString("Id")}/Images/Backdrop?maxWidth=1280&api_key=${s.token}"
            } else null
        fun stream(it: JSONObject): String = streamOf(s, it)
        /** ` key="value"` for the glcn-* details; nothing when the server has no value. */
        fun StringBuilder.extra(key: String, value: String?): StringBuilder {
            if (!value.isNullOrBlank()) append(' ').append(key).append("=\"").append(attr(value)).append('"')
            return this
        }
        /** glcn: the file's real quality from its streams — resolution, HDR format, Atmos / surround. */
        fun StringBuilder.quality(it: JSONObject): StringBuilder {
            val q = qualityOf(it) ?: return this
            extra("glcn-rank", q.first.toString())
            return extra("glcn-quality", q.second)
        }
        /** When the item reached the library, as epoch ms — what "Recently added" sorts by. */
        fun added(it: JSONObject): String? {
            val raw = it.optString("DateCreated").takeIf { d -> d.length >= 19 } ?: return null
            return runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(raw.substring(0, 19))?.time?.toString()
            }.getOrNull()
        }
        val sb = StringBuilder("#EXTM3U\n")
        val filmPlatforms = HashSet<String>()
        forEachItem(s, "Movie") { m ->
            // A file the library could not identify — no year and no IMDb/TMDB id — is not a film:
            // calibration clips ("100% Blanco", "5% Gris"), samples, home videos. Left out.
            val ids = m.optJSONObject("ProviderIds")
            if (!m.has("ProductionYear") && (ids == null || ids.length() == 0)) return@forEachItem
            val title = m.optString("Name")
            val year = m.optInt("ProductionYear", 0)
            val label = if (year > 0) "$title ($year)" else title
            // glcn: a film's "studios" are mostly its makers (Warner, Universal), not where it streams: films keep their
            // genre — unless one of them is a streaming platform's own label (a Netflix or Apple film), by platform.
            val group = (if (EmbyGroups.mode == EmbyGroups.Mode.PLATFORM) PlatformNames.filmPlatform(m.optJSONArray("Studios")) else null)
                ?.also { filmPlatforms += it }
                ?: groupOf(m, MOVIES_GROUP, films = true)
            val rating = m.optDouble("CommunityRating", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            tv.own.owntv.core.emby.EmbyWatchState.record(stream(m), m)
            sb.append("#EXTINF:-1 type=\"movie\" tvg-logo=\"").append(attr(image(m.getString("Id")))).append('"')
                .extra("glcn-plot", m.optString("Overview"))
                .extra("glcn-backdrop", backdrop(m))
                .extra("glcn-year", year.takeIf { it > 0 }?.toString())
                .extra("glcn-rating", rating?.let { String.format(java.util.Locale.ROOT, "%.1f", it) })
                .extra("glcn-added", added(m))
                .quality(m)
                .append(" group-title=\"").append(attr(group)).append("\",").append(line(label)).append('\n')
                .append(stream(m)).append('\n')
        }
        // The shows themselves, for their synopsis and backdrop.
        val shows = runCatching { items(s, "Series") }.getOrDefault(emptyList()).associateBy { it.optString("Id") }
        // glcn: by platform — each show's network, variants joined (HBO Nordic → HBO), and the ones with only a show or
        // two gathered in "Other networks"; then each platform's logo, found once through TMDB.
        val platformOf: Map<String, String> = if (EmbyGroups.mode != EmbyGroups.Mode.PLATFORM) emptyMap() else {
            val raw = shows.mapValues { (_, show) ->
                show.optJSONArray("Studios")?.optJSONObject(0)?.optString("Name")?.takeIf { it.isNotBlank() }?.let(PlatformNames::canonical)
            }
            val counts = raw.values.filterNotNull().groupingBy { it }.eachCount()
            val other = PlatformNames.other()
            raw.mapNotNull { (id, name) -> name?.let { id to (if ((counts[it] ?: 0) >= PLATFORM_MIN_SHOWS) it else other) } }.toMap()
                .also { map ->
                    // The films' platforms find their logo through any show of the same platform, however few.
                    val forLogos = map + raw.mapNotNull { (id, name) -> name?.takeIf { it in filmPlatforms && it !in map.values }?.let { id to it } }
                    runCatching { PlatformLogos.findMissing(forLogos, shows, other, extra = filmPlatforms) }
                }
        }
        forEachItem(s, "Episode") { e ->
            if (!e.has("ParentIndexNumber") || !e.has("IndexNumber")) return@forEachItem
            val show = e.optString("SeriesName").ifBlank { SERIES_GROUP }
            val label = String.format(
                java.util.Locale.ROOT, "%s S%02dE%02d %s",
                show, e.getInt("ParentIndexNumber"), e.getInt("IndexNumber"), e.optString("Name"),
            ).trim()
            val seriesId = e.optString("SeriesId").takeIf { it.isNotBlank() }
            val series = seriesId?.let { shows[it] }
            val logo = seriesId?.let(::image) ?: image(e.getString("Id"))
            tv.own.owntv.core.emby.EmbyWatchState.record(stream(e), e)
            sb.append("#EXTINF:-1 type=\"series\" tvg-logo=\"").append(attr(logo)).append('"')
                .extra("glcn-plot", e.optString("Overview"))
                .extra("glcn-series-plot", series?.optString("Overview"))
                .extra("glcn-series-backdrop", series?.let(::backdrop))
                .extra("glcn-added", added(e))
                .quality(e)
                .append(" group-title=\"").append(attr(seriesId?.let { platformOf[it] } ?: series?.let { groupOf(it, SERIES_GROUP) } ?: SERIES_GROUP)).append("\",").append(line(label)).append('\n')
                .append(stream(e)).append('\n')
        }
        sb.toString()
    }

    /**
     * glcn: a server whose metadata is in English names its genres in English ("Science Fiction", "Mini-Series"): in a
     * Spanish app they are given their Spanish names, so "Fantasy" and "Fantasía" also become one category.
     */
    private fun localGenre(name: String): String {
        if (java.util.Locale.getDefault().language != "es") return name
        return GENRES_ES[name.trim().lowercase(java.util.Locale.ROOT)] ?: name
    }

    private val GENRES_ES: Map<String, String> = GENRE_TABLE_ES.lines().filter { '=' in it }.associate { l ->
        l.substringBefore('=').trim() to l.substringAfter('=').trim()
    }

    /** glcn: the category a film or a show goes in, as Settings asks ([EmbyGroups]): its first genre, its platform, or one list. */
    private fun groupOf(item: JSONObject, whole: String, films: Boolean = false): String = when (EmbyGroups.mode) {
        EmbyGroups.Mode.LIBRARY -> whole
        EmbyGroups.Mode.PLATFORM -> if (films) genreOf(item, whole) else whole // shows: see platformOf
        EmbyGroups.Mode.GENRE -> genreOf(item, whole)
    }

    private fun genreOf(item: JSONObject, whole: String): String =
        item.optJSONArray("Genres")?.optString(0)?.takeIf { it.isNotBlank() }?.let(::localGenre) ?: whole

    /**
     * glcn: (resolution rank, capability marks) from an item's MediaStreams — the same rank and marks a name like
     * "4K DV Atmos" gives (see ProviderCatalogMetadataParser): 4 = 4K, 3 = 1080p, 2 = 720p, 1 = SD.
     */
    private fun qualityOf(item: JSONObject): Pair<Int, String>? {
        val streams = item.optJSONArray("MediaStreams") ?: return null
        var rank = 0
        var picture: String? = null
        var audio: String? = null
        var audioScore = 0
        for (i in 0 until streams.length()) {
            val st = streams.optJSONObject(i) ?: continue
            when (st.optString("Type")) {
                "Video" -> {
                    val w = st.optInt("Width", 0)
                    val h = st.optInt("Height", 0)
                    val r = when { w >= 7000 || h >= 4000 -> 5; w >= 3200 || h >= 1800 -> 4; w >= 1800 || h >= 1000 -> 3; w >= 1200 || h >= 700 -> 2; w > 0 -> 1; else -> 0 }
                    if (r > rank) rank = r
                    val range = (st.optString("VideoRange") + " " + st.optString("VideoRangeType") + " " + st.optString("ExtendedVideoType") +
                        " " + st.optString("DisplayTitle")).lowercase()
                    val p = when {
                        "dolby vision" in range || "dovi" in range || "dolbyvision" in range -> "Dolby Vision"
                        "hdr10+" in range || "hdr10plus" in range -> "HDR10+"
                        "hdr10" in range || "hdr 10" in range -> "HDR10"
                        "hdr" in range || "hlg" in range -> "HDR"
                        else -> null
                    }
                    if (p != null && picture == null) picture = p
                }
                "Audio" -> {
                    val title = (st.optString("DisplayTitle") + " " + st.optString("Profile") + " " + st.optString("Codec")).lowercase()
                    val ch = st.optInt("Channels", 0)
                    val (score, mark) = when {
                        "atmos" in title || ("eac3" in title && "joc" in title) -> 4 to "Dolby Atmos"
                        ch >= 8 -> 3 to "7.1 Audio"
                        ch >= 6 -> 2 to "5.1 Audio"
                        ch == 2 -> 1 to "2.0 Audio"
                        else -> 0 to null
                    }
                    if (score > audioScore && mark != null) { audioScore = score; audio = mark }
                }
            }
        }
        val marks = listOfNotNull(picture, audio)
        if (rank == 0 && marks.isEmpty()) return null
        return rank to marks.joinToString(" • ")
    }

    /** The stream URL the playlist gives an item — the key the watch state is matched on. */
    private fun streamOf(s: Session, it: JSONObject): String {
        val container = it.optString("Container").substringBefore(',').takeIf { c -> c.isNotBlank() }
        return "${s.api}/Videos/${it.getString("Id")}/stream" +
            (container?.let { c -> ".$c" } ?: "") + "?static=true&api_key=${s.token}"
    }

    /**
     * glcn: only what the user has watched or half-watched — a few requests, no playlist — recorded in
     * [tv.own.owntv.core.emby.EmbyWatchState]. Lets the app catch up with Emby between full syncs.
     */
    suspend fun readWatchState(t: Target): Boolean = withContext(Dispatchers.IO) {
        // glcn: one sign-in is kept and reused — signing in again on every refresh made the server announce
        // "signed in" each time. Only a refused token (it expired) signs in again.
        val key = keyOf(t)
        suspend fun read(s: Session) {
            for (filter in listOf("IsPlayed", "IsResumable")) for (kind in listOf("Movie", "Episode")) {
                for (item in items(s, kind, filter)) {
                    tv.own.owntv.core.emby.EmbyWatchState.record(streamOf(s, item), item)
                }
            }
        }
        suspend fun fresh(): Session? {
            val api = detect(t) ?: return null
            return login(t, api).also { sessions[key] = it }
        }
        val kept = sessions[key]
        val s = kept ?: fresh() ?: return@withContext false
        try {
            read(s)
        } catch (e: TokenGone) {
            sessions.remove(key)
            read(fresh() ?: return@withContext false)
        }
        true
    }

    private fun keyOf(t: Target): String = t.root + "|" + t.user + "|" + t.password.hashCode()

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

    /**
     * glcn: the whole library as M3U text, signing in only when no kept sign-in works. The sync and the
     * watch-state refresh share one sign-in (each used to sign in on its own, so the server announced two
     * logins at every sync); a refused token signs in again once.
     */
    suspend fun playlistFor(t: Target, api: String): String {
        val key = keyOf(t)
        sessions[key]?.let { kept ->
            try { return playlist(kept) } catch (e: TokenGone) { sessions.remove(key) }
        }
        val s = login(t, api).also { sessions[key] = it }
        return playlist(s)
    }

    /** The kept token was refused: it expired or the server forgot it. */
    private class TokenGone : IOException("Emby: token refused")

    /** glcn: smaller pages — each holds the items' media streams, read and let go one page at a time. */
    private const val PAGE = 250
    /** glcn: a platform with fewer shows than this goes in "Other networks". */
    private const val PLATFORM_MIN_SHOWS = 3
    private const val MOVIES_GROUP = "Películas"
    private const val SERIES_GROUP = "Series"
}

/**
 * glcn: how an Emby or Jellyfin library is split into categories: by each title's first genre (the default), by its
 * platform or network (Netflix, HBO… — Emby's Studios, filled from TMDB), or as one list for films and one for series.
 * Set by the app from Settings; read at the next sync of the library.
 */
object EmbyGroups {
    enum class Mode { GENRE, PLATFORM, LIBRARY }
    @Volatile var mode: Mode = Mode.GENRE
}

/** glcn: the English genre names Emby and Jellyfin take from TMDB, lower case = their Spanish name. */
private const val GENRE_TABLE_ES = """
action=Acción
adventure=Aventura
action & adventure=Acción y aventura
animation=Animación
anime=Anime
biography=Biografía
children=Infantil
kids=Infantil
comedy=Comedia
crime=Crimen
documentary=Documental
drama=Drama
family=Familia
fantasy=Fantasía
food=Cocina
game show=Concursos
history=Historia
horror=Terror
mini-series=Miniseries
miniseries=Miniseries
music=Música
musical=Musical
mystery=Misterio
news=Noticias
reality=Reality
romance=Romance
science fiction=Ciencia ficción
sci-fi=Ciencia ficción
sci-fi & fantasy=Ciencia ficción y fantasía
soap=Telenovela
sport=Deporte
sports=Deporte
suspense=Suspense
talk=Tertulia
talk show=Tertulia
thriller=Thriller
tv movie=Telefilm
war=Bélica
war & politics=Bélica y política
western=Western
"""

/** glcn: one name per platform — the variants a library's networks come in are joined. */
object PlatformNames {
    /** Lower-case prefix (or whole name) → the platform it belongs to; the first that matches wins. */
    private val JOIN: List<Pair<String, String>> = listOf(
        "hbo" to "HBO", "max" to "HBO",
        "paramount" to "Paramount+", "showtime" to "Paramount+",
        "apple tv" to "Apple TV+",
        "amazon" to "Prime Video", "prime video" to "Prime Video",
        "disney" to "Disney+",
        "netflix" to "Netflix",
        "movistar" to "Movistar+",
        "sky" to "Sky",
        "bbc" to "BBC",
        "itv" to "ITV",
        "national geographic" to "National Geographic", "nat geo" to "National Geographic",
        "discovery" to "Discovery",
        "atresmedia" to "Atresmedia", "antena 3" to "Atresmedia", "atresplayer" to "Atresmedia", "la sexta" to "Atresmedia", "lasexta" to "Atresmedia",
        "telecinco" to "Mediaset", "cuatro" to "Mediaset", "mitele" to "Mediaset",
        "la 1" to "RTVE", "la 2" to "RTVE", "rtve" to "RTVE", "tve" to "RTVE",
        "hulu" to "Hulu", "peacock" to "Peacock", "starz" to "Starz", "amc" to "AMC",
    )

    fun canonical(name: String): String {
        val n = name.trim().lowercase(java.util.Locale.ROOT)
        return JOIN.firstOrNull { (prefix, _) -> n == prefix || n.startsWith("$prefix ") || n.startsWith("$prefix+") }?.second ?: name.trim()
    }

    /**
     * A film's platform, when one of its studios is a streaming platform's own label ("Netflix", "Apple Original
     * Films", "Amazon Prime Video"…) — never a maker that merely shares the name (Walt Disney Pictures, Amazon MGM).
     */
    fun filmPlatform(studios: org.json.JSONArray?): String? {
        studios ?: return null
        for (i in 0 until studios.length()) {
            val n = studios.optJSONObject(i)?.optString("Name")?.trim()?.lowercase(java.util.Locale.ROOT) ?: continue
            FILM_LABELS.firstOrNull { (prefix, _) -> n == prefix || n.startsWith("$prefix ") }?.let { return it.second }
        }
        return null
    }

    private val FILM_LABELS: List<Pair<String, String>> = listOf(
        "netflix" to "Netflix",
        "apple original films" to "Apple TV+", "apple tv+" to "Apple TV+", "apple tv" to "Apple TV+", "apple studios" to "Apple TV+",
        "amazon prime video" to "Prime Video", "prime video" to "Prime Video",
        "disney+" to "Disney+",
        "hbo max" to "HBO", "max original" to "HBO",
        "movistar+" to "Movistar+", "movistar plus+" to "Movistar+",
        "paramount+" to "Paramount+",
        "hulu" to "Hulu", "peacock" to "Peacock",
    )

    /** The category the small networks share, in the app's language. */
    fun other(): String = if (java.util.Locale.getDefault().language == "es") OTHER_ES else OTHER_EN

    private const val OTHER_ES = "Otras cadenas"
    private const val OTHER_EN = "Other networks"
}

/**
 * glcn: each platform's logo (TMDB's network logo), found once — through the TMDB details of one of its shows — and kept
 * on the device, so the category list can show the platform's own mark. Read by the app with [logoFor].
 */
object PlatformLogos {
    private val logos = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    /** The logo URL kept for [platform], or null. */
    fun logoFor(platform: String): String? { ensure(); return logos[platform] }

    internal suspend fun findMissing(platformOf: Map<String, String>, shows: Map<String, JSONObject>, other: String, extra: Set<String> = emptySet()) {
        ensure()
        val provider = runCatching {
            org.koin.core.context.GlobalContext.get().get<tv.own.owntv.core.metadata.MetadataProvider>()
        }.getOrNull() ?: return
        val wanted = platformOf.values.toSet() + extra - other - logos.keys
        var changed = false
        for (platform in wanted) {
            val tmdbIds = platformOf.filterValues { it == platform }.keys.asSequence()
                .mapNotNull { id -> shows[id]?.optJSONObject("ProviderIds")?.optString("Tmdb")?.toIntOrNull() }
                .take(2).toList()
            for (tmdb in tmdbIds) {
                val nets = runCatching { provider.tvDetails(tmdb) }.getOrNull()?.networks.orEmpty()
                val hit = nets.firstOrNull { PlatformNames.canonical(it.name) == platform && it.logoPath != null }
                    ?: nets.firstOrNull { it.logoPath != null }.takeIf { nets.size == 1 }
                val url = tv.own.owntv.core.metadata.MetadataImages.logo(hit?.logoPath, "w300") ?: continue
                logos[platform] = url
                changed = true
                break
            }
        }
        // What no show of the library gave: the platform's own network page on TMDB, for the well-known ones.
        for (platform in wanted - logos.keys) {
            val id = NETWORK_IDS[platform] ?: continue
            val path = runCatching { provider.networkLogo(id) }.getOrNull()
            val url = tv.own.owntv.core.metadata.MetadataImages.logo(path, "w300") ?: continue
            logos[platform] = url
            changed = true
        }
        if (changed) save()
    }

    /** TMDB network ids of the platforms [PlatformNames] joins to, for a logo when no show carries it. */
    private val NETWORK_IDS: Map<String, Int> = mapOf(
        "Netflix" to 213, "HBO" to 49, "Disney+" to 2739, "Apple TV+" to 2552, "Prime Video" to 1024,
        "Paramount+" to 4330, "Hulu" to 453, "Peacock" to 3353, "AMC" to 174, "Starz" to 318,
        "BBC" to 4, "ITV" to 9, "National Geographic" to 43, "Discovery" to 64,
    )

    private fun prefs() = runCatching {
        org.koin.core.context.GlobalContext.get().get<android.content.Context>()
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    private fun ensure() {
        if (loaded) return
        loaded = true
        prefs()?.all?.forEach { (k, v) -> if (v is String) logos[k] = v }
    }

    private fun save() {
        val p = prefs() ?: return
        p.edit().apply { logos.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    private const val PREFS = "glcn_platform_logos"
}
