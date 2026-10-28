package tv.own.owntv.core.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.MonthBoard
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.RankingGroup
import tv.own.owntv.core.sports.model.RankingKind
import tv.own.owntv.core.sports.model.RankingRow
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * glcn: road cycling — the UCI WorldTour — from carlosglcn/owntv-sports-data (cycling.json, built from
 * procyclingstats.com). Every stage of a stage race, and every one-day race, is one entry on the board: the race
 * as its title, the stage and its route under it, the first three once it is over. The ranking is the general
 * classification of the stage race on now, or of the last one run.
 */
class CyclingSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val zone get() = ZoneId.systemDefault()
    private var cache: Pair<Long, JSONObject>? = null

    suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        val date = day ?: LocalDate.now(zone)
        return stages(league).filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }
    }

    suspend fun month(league: League, month: YearMonth): MonthBoard =
        MonthBoard(stages(league).filter { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) == month }, null)

    fun summary(match: Match) = MatchSummary(match, emptyList(), emptyList(), emptyList())

    suspend fun rankings(league: League): Ranking {
        val races = races()
        val now = System.currentTimeMillis()
        // The stage race on now, else the latest one that has a classification.
        val race = races.filter { !it.optBoolean("oneday") && (it.optJSONArray("gc")?.length() ?: 0) > 0 && it.optLong("start") <= now }
            .maxByOrNull { it.optLong("start") } ?: return Ranking(league.key, emptyList())
        val gc = race.optJSONArray("gc") ?: JSONArray()
        val rows = (0 until gc.length()).mapNotNull { i ->
            val r = gc.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("name").ifEmpty { return@mapNotNull null }
            // A rider's team goes where a tennis player's country shows; the time gap where the points would.
            RankingRow(
                rank = r.optInt("pos", i + 1),
                team = Team(id = name, name = name, shortName = name, isAthlete = true, country = r.optString("team").ifEmpty { null }),
                points = 0,
                note = r.optString("gap").ifEmpty { null },
            )
        }
        return Ranking(league.key, if (rows.isEmpty()) emptyList() else listOf(RankingGroup(RankingKind.PLAYERS, rows, title = race.optString("name").ifEmpty { null })))
    }

    private suspend fun races(): List<JSONObject> {
        val root = feed()
        val list = root.optJSONArray("races") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    }

    private suspend fun stages(league: League): List<Match> {
        val now = System.currentTimeMillis()
        return races().flatMap { race ->
            val stages = race.optJSONArray("stages") ?: return@flatMap emptyList()
            val name = race.optString("name")
            val oneDay = race.optBoolean("oneday")
            (0 until stages.length()).mapNotNull { i ->
                val s = stages.optJSONObject(i) ?: return@mapNotNull null
                val start = s.optLong("start").takeIf { it > 0 } ?: return@mapNotNull null
                val state = when (s.optString("status")) {
                    "final" -> MatchState.POST
                    "live" -> MatchState.IN
                    else -> if (now >= start + LIVE_MS) MatchState.POST else if (now >= start) MatchState.IN else MatchState.PRE
                }
                val route = listOfNotNull(s.optString("frm").takeIf { it.isNotEmpty() && !s.isNull("frm") },
                    s.optString("to").takeIf { it.isNotEmpty() && !s.isNull("to") }).joinToString(" – ")
                val label = s.optString("label").takeIf { it.isNotEmpty() }
                val tt = s.optString("tt").takeIf { it.isNotEmpty() && !s.isNull("tt") }
                val km = s.optDouble("km").takeIf { !it.isNaN() && it > 0 }
                val top = s.optJSONArray("top") ?: JSONArray()
                val podium = (0 until top.length()).mapNotNull { k ->
                    val p = top.optJSONObject(k) ?: return@mapNotNull null
                    val rider = p.optString("name").ifEmpty { return@mapNotNull null }
                    Competitor(
                        team = Team(id = rider, name = rider, shortName = rider, isAthlete = true, country = p.optString("team").ifEmpty { null }),
                        home = k == 0, winner = if (k == 0) true else null, score = null,
                    )
                }
                Match(
                    id = s.optString("id"),
                    sport = Sport.CYCLING,
                    feed = league.key,
                    leagueSlug = league.slug,
                    leagueName = name,
                    startMs = start,
                    state = state,
                    statusText = if (state == MatchState.POST) podium.firstOrNull()?.team?.name.orEmpty() else "",
                    title = name,
                    subtitle = listOfNotNull(if (oneDay) null else label, tt, route.ifEmpty { null }).joinToString(" · ").ifEmpty { null },
                    venue = listOfNotNull(km?.let { String.format(java.util.Locale.getDefault(), "%.1f km", it) }, profileText(s.optString("profile"))).joinToString(" · ").ifEmpty { null },
                    competitors = podium,
                )
            }
        }
    }

    private fun profileText(p: String): String? = when (p) {
        "flat" -> "▁"
        "hills-flat", "hills-uphill" -> "▂▄"
        "mountains-flat", "mountains-uphill" -> "▃▆█"
        else -> null
    }

    private suspend fun feed(): JSONObject {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) throw SportsHttpException(r.code)
                r.body.string()
            }
        }
        val root = JSONObject(body)
        cache = now to root
        return root
    }

    companion object {
        const val WORLD_TOUR = "uwt"
        private const val URL = "https://raw.githubusercontent.com/carlosglcn/owntv-sports-data/main/cycling.json"
        private const val TIMEOUT_SECONDS = 10L
        private const val CACHE_MS = 10 * 60_000L
        /** A stage with no result this long after its start is taken as over. */
        private const val LIVE_MS = 7 * 3_600_000L

        /** Names Spanish guides use for the big races (the feed has their own names). */
        private val ALIASES = mapOf(
            "tour de france" to listOf("Tour de Francia"),
            "giro d'italia" to listOf("Giro de Italia"),
            "vuelta" to listOf("La Vuelta", "Vuelta a España"),
            "paris-roubaix" to listOf("París-Roubaix"),
            "ronde van vlaanderen" to listOf("Tour de Flandes"),
            "milano-sanremo" to listOf("Milán-San Remo"),
            "il lombardia" to listOf("Giro de Lombardía"),
            "liège-bastogne-liège" to listOf("Lieja-Bastoña-Lieja"),
            "catalunya" to listOf("Volta a Cataluña", "Volta a Catalunya"),
            "itzulia" to listOf("Itzulia", "Vuelta al País Vasco"),
            "paris-nice" to listOf("París-Niza"),
            "tour de suisse" to listOf("Vuelta a Suiza"),
            "tirreno" to listOf("Tirreno-Adriático"),
            "strade bianche" to listOf("Strade Bianche"),
            "amstel" to listOf("Amstel Gold Race"),
            "flèche wallonne" to listOf("Flecha Valona"),
        )

        /** Other names a guide may give [match]'s race. */
        fun aliases(match: Match): List<String> {
            if (match.sport != Sport.CYCLING) return emptyList()
            val n = match.leagueName.lowercase()
            return ALIASES.filterKeys { it in n }.values.flatten()
        }
    }
}
