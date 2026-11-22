package tv.own.owntv.core.sports

import org.json.JSONObject
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport

/**
 * glcn: where a match is played, for the feeds that do not say: the home club's arena (basketball and handball
 * leagues, by league and team) and a big tennis tournament's ground (by the tournament's name). From
 * carlosglcn/owntv-sports-data (venues.json). Read at most every twelve hours.
 */
object Venues {
    @Volatile private var teams: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var tennis: List<Pair<List<String>, String>> = emptyList()
    @Volatile private var readAt = 0L

    /** Goes up each time the file is read, so a card already drawn can take what came. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** [match]'s own venue when its feed gave one; else the tournament's ground or the home side's arena. */
    fun of(match: Match): String? = match.venue?.takeIf { it.isNotBlank() } ?: when {
        match.sport == Sport.TENNIS -> {
            val plain = java.text.Normalizer.normalize(match.leagueName, java.text.Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
            tennis.firstOrNull { (names, _) -> names.any { it in plain } }?.second
        }
        else -> match.home?.team?.id?.let { teams[match.leagueSlug]?.get(it) }
    }

    suspend fun refresh(fetch: suspend (String) -> String) {
        val now = System.currentTimeMillis()
        if (now - readAt < REFRESH_MS) return
        readAt = now
        runCatching {
            val root = JSONObject(fetch(URL))
            root.optJSONObject("teams")?.let { leagues ->
                teams = leagues.keys().asSequence().associateWith { slug ->
                    val ids = leagues.optJSONObject(slug)
                    ids?.keys()?.asSequence()?.mapNotNull { id -> ids.optString(id).takeIf { it.isNotEmpty() }?.let { id to it } }?.toMap().orEmpty()
                }
            }
            root.optJSONArray("tennis")?.let { list ->
                tennis = (0 until list.length()).mapNotNull { i ->
                    val t = list.optJSONObject(i) ?: return@mapNotNull null
                    val names = t.optJSONArray("names")?.let { n -> (0 until n.length()).map { n.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
                    val venue = t.optString("venue")
                    if (names.isEmpty() || venue.isEmpty()) null else names to venue
                }
            }
            version.value++
        }.onFailure { readAt = now - REFRESH_MS + RETRY_MS }
    }

    private const val URL = "https://raw.githubusercontent.com/carlosglcn/owntv-sports-data/main/venues.json"
    private const val REFRESH_MS = 12 * 3_600_000L
    private const val RETRY_MS = 10 * 60_000L
}
