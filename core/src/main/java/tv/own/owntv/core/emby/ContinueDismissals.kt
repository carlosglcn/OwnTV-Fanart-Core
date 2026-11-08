package tv.own.owntv.core.emby

/**
 * glcn: what the user took out of "Keep watching" by hand, and when. A media server (Emby, Jellyfin) still holds
 * the resume point, so the next read of its watch state put the card straight back. An item taken out stays out
 * until the server has it played again after that moment (its last-played time is later than the mark). Played
 * here, it is back at once from the app's own resume point.
 */
object ContinueDismissals {
    private const val PREFS = "glcn_continue_dismissed"

    private fun prefs() = runCatching {
        org.koin.core.context.GlobalContext.get().get<android.content.Context>()
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    private fun key(profileId: Long, kind: String, id: Long) = "$profileId:$kind:$id"

    fun dismissMovie(profileId: Long, movieId: Long) = put(key(profileId, KIND_MOVIE, movieId))
    fun dismissSeries(profileId: Long, seriesId: Long) = put(key(profileId, KIND_SERIES, seriesId))

    /** When [movieId] was taken out, or null. */
    fun movie(profileId: Long, movieId: Long): Long? = get(key(profileId, KIND_MOVIE, movieId))
    fun series(profileId: Long, seriesId: Long): Long? = get(key(profileId, KIND_SERIES, seriesId))

    private fun put(k: String) { prefs()?.edit()?.putLong(k, System.currentTimeMillis())?.apply() }
    private fun get(k: String): Long? = prefs()?.let { p -> if (p.contains(k)) p.getLong(k, 0L) else null }

    private const val KIND_MOVIE = "m"
    private const val KIND_SERIES = "s"
}
