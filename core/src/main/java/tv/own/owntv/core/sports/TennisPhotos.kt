package tv.own.owntv.core.sports

import org.json.JSONObject

/**
 * glcn: a photo for each ranked ATP and WTA player, from carlosglcn/owntv-sports-data (tennis.json): ESPN's own
 * headshot where it has one, otherwise the player's picture on Wikimedia Commons. ESPN alone had a photo for
 * about a third of them, and the rest showed initials. Read at most every twelve hours.
 */
object TennisPhotos {
    @Volatile private var photos: Map<String, String> = emptyMap()
    @Volatile private var readAt = 0L

    fun of(playerId: String): String? = photos[playerId]

    /**
     * glcn: the logo of the tournament called [name], when the same file lists it ("tournaments": the names a
     * tournament goes by, lower case and without accents, and its logo). The first entry one of whose names is in
     * [name] wins — the file orders them so that the Paris Masters is not taken for Roland Garros.
     */
    @Volatile private var logos: List<Pair<List<String>, String>> = emptyList()

    fun tournamentLogo(name: String?): String? {
        if (name.isNullOrBlank() || logos.isEmpty()) return null
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
        return logos.firstOrNull { (names, _) -> names.any { it in plain } }?.second
    }

    suspend fun refresh(fetch: suspend (String) -> String) {
        val now = System.currentTimeMillis()
        if (now - readAt < REFRESH_MS) return
        readAt = now
        runCatching {
            val root = JSONObject(fetch(URL))
            root.optJSONArray("tournaments")?.let { list ->
                logos = (0 until list.length()).mapNotNull { i ->
                    val t = list.optJSONObject(i) ?: return@mapNotNull null
                    val names = t.optJSONArray("names")?.let { n -> (0 until n.length()).map { n.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
                    val logo = t.optString("logo")
                    if (names.isEmpty() || logo.isEmpty()) null else names to logo
                }
            }
            val players = root.optJSONObject("players") ?: return
            photos = players.keys().asSequence().mapNotNull { id -> players.optString(id).takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
        }.onFailure { readAt = now - REFRESH_MS + RETRY_MS }
    }

    private const val URL = "https://raw.githubusercontent.com/carlosglcn/owntv-sports-data/main/tennis.json"
    private const val REFRESH_MS = 12 * 3_600_000L
    private const val RETRY_MS = 10 * 60_000L
}
