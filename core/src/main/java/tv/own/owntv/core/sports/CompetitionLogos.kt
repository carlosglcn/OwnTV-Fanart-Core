package tv.own.owntv.core.sports

import org.json.JSONObject

/**
 * glcn: the logo of each competition whose scores come without one (ESPN's carry their own): the Spanish and
 * European basketball and handball leagues, MotoGP, Formula 1, cycling, snooker… From carlosglcn/owntv-sports-data
 * (competitions.json), by the app's league slug. Read at most every twelve hours.
 */
object CompetitionLogos {
    @Volatile private var logos: Map<String, String> = emptyMap()
    @Volatile private var readAt = 0L

    fun of(leagueSlug: String?): String? = leagueSlug?.let { logos[it] }

    suspend fun refresh(fetch: suspend (String) -> String) {
        val now = System.currentTimeMillis()
        if (now - readAt < REFRESH_MS) return
        readAt = now
        runCatching {
            val leagues = JSONObject(fetch(URL)).optJSONObject("leagues") ?: return
            logos = leagues.keys().asSequence().mapNotNull { k -> leagues.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        }.onFailure { readAt = now - REFRESH_MS + RETRY_MS }
    }

    private const val URL = "https://raw.githubusercontent.com/carlosglcn/owntv-sports-data/main/competitions.json"
    private const val REFRESH_MS = 12 * 3_600_000L
    private const val RETRY_MS = 10 * 60_000L
}
