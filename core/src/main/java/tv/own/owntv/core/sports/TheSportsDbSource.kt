package tv.own.owntv.core.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Score
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Leagues ESPN does not carry, from TheSportsDB's free feed (today the Spanish Liga ACB). The free
 * feed lists the next and the last fifteen games, which is all a "today and tomorrow" board needs; it
 * has no live clock, so a game that has started is shown as live without a score until it ends.
 * Every read is defensive: a surprise in the answer gives an empty board, never a crash.
 */
class TheSportsDbSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val zone get() = ZoneId.systemDefault()
    private val cache = HashMap<String, Pair<Long, List<JSONObject>>>()

    suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        val date = day ?: LocalDate.now(zone)
        val id = LEAGUE_IDS[league.slug] ?: return emptyList()
        val events = rows("eventsnextleague.php?id=$id", "events") + rows("eventspastleague.php?id=$id", "results")
        val now = System.currentTimeMillis()
        return events.mapNotNull { e -> toMatch(league, e, now) }
            .distinctBy { it.id }
            .filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }
    }

    /**
     * glcn: a league's [month] — the whole season's list from the feed, narrowed to the month, plus the
     * next/last games so a feed that cuts the season short still shows what it has. The season list is
     * kept for six hours: opening the calendar never costs more than one call.
     */
    suspend fun month(league: League, month: java.time.YearMonth): MonthBoard {
        val id = LEAGUE_IDS[league.slug] ?: return MonthBoard(emptyList(), null)
        val season = if (month.monthValue >= 8) "${month.year}-${month.year + 1}" else "${month.year - 1}-${month.year}"
        val events = runCatching { rows("eventsseason.php?id=$id&s=$season", "events", SEASON_CACHE_MS) }.getOrDefault(emptyList()) +
            rows("eventsnextleague.php?id=$id", "events") + rows("eventspastleague.php?id=$id", "results")
        val now = System.currentTimeMillis()
        val matches = events.mapNotNull { e -> toMatch(league, e, now) }
            .distinctBy { it.id }
            .filter { java.time.YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) == month }
            .sortedBy { it.startMs }
        return MonthBoard(matches, null)
    }

    suspend fun teams(league: League): List<Team> {
        val id = LEAGUE_IDS[league.slug] ?: return emptyList()
        return rows("lookup_all_teams.php?id=$id", "teams").mapNotNull { t ->
            val tid = t.optString("idTeam").ifEmpty { return@mapNotNull null }
            val name = t.optString("strTeam").ifEmpty { return@mapNotNull null }
            Team(
                id = tid, name = name,
                abbreviation = t.optString("strTeamShort").takeIf { it.length in 2..5 }.orEmpty(),
                logoUrl = t.optString("strBadge").takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun toMatch(league: League, e: JSONObject, now: Long): Match? {
        val id = e.optString("idEvent").ifEmpty { return null }
        val start = startOf(e) ?: return null
        val homeName = e.optString("strHomeTeam").ifEmpty { return null }
        val awayName = e.optString("strAwayTeam").ifEmpty { return null }
        val hs = e.optString("intHomeScore").toIntOrNull()
        val aw = e.optString("intAwayScore").toIntOrNull()
        val status = e.optString("strStatus")
        val finished = status.equals("Match Finished", true) || status.equals("FT", true) || status.startsWith("Finished", true) ||
            (hs != null && aw != null && now > start + LENGTH_MS)
        val state = when {
            e.optString("strPostponed").equals("yes", true) -> MatchState.POSTPONED
            finished -> MatchState.POST
            now >= start + LENGTH_MS -> MatchState.POST
            now >= start -> MatchState.IN
            else -> MatchState.PRE
        }
        fun side(teamId: String, name: String, badge: String, goals: Int?, home: Boolean, winner: Boolean?) = Competitor(
            team = Team(id = teamId.ifEmpty { name }, name = name, logoUrl = badge.takeIf { it.isNotEmpty() }),
            home = home,
            winner = winner,
            score = goals?.let { Score(it.toString(), it) },
        )
        val decided = state == MatchState.POST && hs != null && aw != null && hs != aw
        return Match(
            id = id,
            sport = Sport.BASKETBALL,
            feed = league.key,
            leagueSlug = league.slug,
            leagueName = league.name,
            startMs = start,
            state = state,
            statusText = if (state == MatchState.POST) "FT" else "",
            title = e.optString("strEvent").ifEmpty { "$homeName v $awayName" },
            subtitle = e.optString("intRound").takeIf { it.isNotEmpty() && it != "0" }?.let { "Round $it" },
            competitors = listOf(
                side(e.optString("idHomeTeam"), homeName, e.optString("strHomeTeamBadge"), hs, true, if (decided) hs!! > aw!! else null),
                side(e.optString("idAwayTeam"), awayName, e.optString("strAwayTeamBadge"), aw, false, if (decided) aw!! > hs!! else null),
            ),
            venue = e.optString("strVenue").takeIf { it.isNotEmpty() },
        )
    }

    /** The feed's timestamp is UTC without an offset ("2026-10-18T18:30:00"); the date and time fields are a fallback. */
    private fun startOf(e: JSONObject): Long? {
        val stamp = e.optString("strTimestamp")
        val local = runCatching { LocalDateTime.parse(stamp.removeSuffix("Z").take(19)) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(e.optString("dateEvent") + "T" + e.optString("strTime").take(8)) }.getOrNull()
        return local?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
    }

    private suspend fun rows(path: String, key: String, keepMs: Long = CACHE_MS): List<JSONObject> {
        val now = System.currentTimeMillis()
        cache[path]?.takeIf { now - it.first < keepMs }?.let { return it.second }
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$BASE/$path").build()).execute().use { r ->
                if (!r.isSuccessful) throw SportsHttpException(r.code)
                r.body.string()
            }
        }
        val array = runCatching { JSONObject(body).optJSONArray(key) }.getOrNull() ?: return emptyList()
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        cache[path] = now to list
        return list
    }

    companion object {
        const val ACB = "acb"
        private val LEAGUE_IDS = mapOf(ACB to 4408)
        private const val BASE = "https://www.thesportsdb.com/api/v1/json/3"
        private const val TIMEOUT_SECONDS = 10L
        private const val CACHE_MS = 10 * 60_000L
        private const val SEASON_CACHE_MS = 6 * 60 * 60_000L
        private const val LENGTH_MS = 150 * 60_000L
    }
}
