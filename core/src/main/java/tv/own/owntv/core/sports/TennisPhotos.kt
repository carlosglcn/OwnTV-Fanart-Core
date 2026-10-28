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

    suspend fun refresh(fetch: suspend (String) -> String) {
        val now = System.currentTimeMillis()
        if (now - readAt < REFRESH_MS) return
        readAt = now
        runCatching {
            val players = JSONObject(fetch(URL)).optJSONObject("players") ?: return
            photos = players.keys().asSequence().mapNotNull { id -> players.optString(id).takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
        }.onFailure { readAt = now - REFRESH_MS + RETRY_MS }
    }

    private const val URL = "https://raw.githubusercontent.com/carlosglcn/owntv-sports-data/main/tennis.json"
    private const val REFRESH_MS = 12 * 3_600_000L
    private const val RETRY_MS = 10 * 60_000L
}
