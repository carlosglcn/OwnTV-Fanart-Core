package tv.own.owntv.core.emby

import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: the TMDB ids an Emby/Jellyfin server already knows (its items' `ProviderIds`), kept from the library read so
 * the app's metadata lookup uses them outright instead of searching TMDB by name — fewer TMDB calls, no wrong match.
 * Films by stream URL (without its query), shows by name. Kept in a small file, replaced at every library read.
 */
object EmbyTmdb {
    private val films = ConcurrentHashMap<String, Int>()
    private val shows = ConcurrentHashMap<String, Int>()
    /** glcn: films and episodes whose file carries styled subtitles (ASS/SSA), by stream URL. */
    private val styled = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var loaded = false

    fun film(streamUrl: String): Int? { ensure(); return films[EmbyWatchState.key(streamUrl)] }

    fun show(name: String): Int? { ensure(); return shows[showKey(name)] }

    /** Whether the item at [streamUrl] has ASS/SSA subtitles (styled, positioned) — mpv draws them as made. */
    fun styledSubs(streamUrl: String?): Boolean { if (streamUrl == null) return false; ensure(); return EmbyWatchState.key(streamUrl) in styled }

    internal fun replaceStyled(urls: Set<String>) {
        if (urls.isEmpty()) return
        ensure()
        // Added to, not replaced: several servers each write their own (a stale entry only nudges the engine choice).
        urls.mapTo(styled) { EmbyWatchState.key(it) }
        save()
    }

    /** The ids read with the library this time; [filmIds] by stream URL, [showIds] by show name. */
    internal fun replace(filmIds: Map<String, Int>, showIds: Map<String, Int>) {
        if (filmIds.isEmpty() && showIds.isEmpty()) return
        ensure()
        films.putAll(filmIds.mapKeys { EmbyWatchState.key(it.key) })
        shows.putAll(showIds.mapKeys { showKey(it.key) })
        save()
    }

    private fun showKey(name: String) = name.trim().lowercase(java.util.Locale.ROOT)

    private fun file(): java.io.File? = runCatching {
        java.io.File(org.koin.core.context.GlobalContext.get().get<android.content.Context>().filesDir, FILE)
    }.getOrNull()

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        val f = file()?.takeIf { it.isFile } ?: return
        runCatching {
            val o = org.json.JSONObject(f.readText())
            o.optJSONObject(FILMS)?.let { j -> j.keys().forEach { k -> films[k] = j.optInt(k) } }
            o.optJSONObject(SHOWS)?.let { j -> j.keys().forEach { k -> shows[k] = j.optInt(k) } }
            o.optJSONArray(STYLED)?.let { a -> for (i in 0 until a.length()) styled += a.optString(i) }
        }
    }

    @Synchronized
    private fun save() {
        val f = file() ?: return
        runCatching {
            val o = org.json.JSONObject()
                .put(FILMS, org.json.JSONObject(films as Map<*, *>))
                .put(SHOWS, org.json.JSONObject(shows as Map<*, *>))
                .put(STYLED, org.json.JSONArray(styled.toList()))
            f.writeText(o.toString())
        }
    }

    private const val FILE = "glcn_emby_tmdb.json"
    private const val FILMS = "f"
    private const val SHOWS = "s"
    private const val STYLED = "a"
}
