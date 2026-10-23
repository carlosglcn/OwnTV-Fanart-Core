package tv.own.owntv.core.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * MotoGP's sessions (practice, qualifying, sprint, race) from the championship's own public feed, which
 * ESPN does not carry. Each session of the MotoGP class becomes one entry, like a Formula 1 session.
 * The feed is not documented by its owner, so every read is defensive: an answer that is not what
 * is expected gives an empty board, never a crash.
 */
class MotoGpSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val zone get() = ZoneId.systemDefault()

    private var eventsYear = 0
    private var events: List<JSONObject> = emptyList()
    private var eventsAt = 0L
    private val sessions = HashMap<String, Pair<Long, List<Match>>>()

    /** The MotoGP sessions on [day] (today when null), by the device's calendar. */
    suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        val date = day ?: LocalDate.now(zone)
        val near = eventsOf(date.year).filter { e ->
            val start = dayOf(e.optString("date_start")) ?: return@filter false
            val end = dayOf(e.optString("date_end")) ?: start
            !date.isBefore(start.minusDays(1)) && !date.isAfter(end.plusDays(1))
        }
        val all = coroutineScope { near.map { e -> async { sessionsOf(league, e) } }.awaitAll() }.flatten()
        return all.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }
    }

    fun summary(match: Match): MatchSummary = MatchSummary(match, emptyList(), emptyList(), emptyList())

    private suspend fun eventsOf(year: Int): List<JSONObject> {
        val now = System.currentTimeMillis()
        if (eventsYear == year && now - eventsAt < CACHE_MS) return events
        val body = get("$BASE/events?seasonYear=$year")
        val root = runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body).let { it.optJSONArray("events") ?: it.optJSONArray("data") } }.getOrNull()
            ?: return emptyList()
        events = (0 until root.length()).mapNotNull { root.optJSONObject(it) }
        eventsYear = year
        eventsAt = now
        return events
    }

    private suspend fun sessionsOf(league: League, event: JSONObject): List<Match> {
        val id = event.optString("id").ifEmpty { return emptyList() }
        val now = System.currentTimeMillis()
        sessions[id]?.takeIf { now - it.first < SESSIONS_CACHE_MS }?.let { return it.second }
        val detail = runCatching { JSONObject(get("$BASE/events/$id")) }.getOrNull() ?: return emptyList()
        val list = detail.optJSONArray("broadcasts") ?: return emptyList()
        val name = event.optString("name").trim().ifEmpty { detail.optString("name").trim() }
        val circuit = event.optJSONObject("circuit") ?: detail.optJSONObject("circuit")
        val venue = listOfNotNull(
            circuit?.optString("name")?.takeIf { it.isNotEmpty() },
            circuit?.optString("country")?.takeIf { it.isNotEmpty() },
        ).joinToString(", ").takeIf { it.isNotEmpty() }
        val out = (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            if (s.optJSONObject("category")?.optString("name") != "MotoGP") return@mapNotNull null
            val start = time(s.optString("date_start")) ?: return@mapNotNull null
            val end = (time(s.optString("date_end")) ?: (start + DEFAULT_MS)).coerceAtMost(start + MAX_MS)
            val state = when {
                s.optString("status").equals("FINISHED", true) -> MatchState.POST
                s.optString("status").let { it.contains("LIVE", true) || it.contains("PROGRESS", true) } -> MatchState.IN
                now >= end -> MatchState.POST
                now >= start -> MatchState.IN
                else -> MatchState.PRE
            }
            val short = s.optString("shortname").trim().takeIf { it.isNotEmpty() }
            val full = s.optString("name").trim()
            Match(
                id = "$id-$i",
                sport = Sport.RACING,
                feed = league.key,
                leagueSlug = league.slug,
                leagueName = league.name,
                startMs = start,
                state = state,
                statusText = full.ifEmpty { short.orEmpty() },
                title = name.ifEmpty { league.name },
                subtitle = short ?: full.takeIf { it.isNotEmpty() },
                venue = venue,
            )
        }
        sessions[id] = now to out
        return out
    }

    /** The feed writes offsets as "+0100" in some places and "+01:00" in others. */
    private fun time(s: String): Long? {
        if (s.isBlank()) return null
        return runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx")).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun dayOf(s: String): LocalDate? = time(s)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw SportsHttpException(r.code)
                r.body.string()
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private companion object {
        const val BASE = "https://api.motogp.pulselive.com/motogp/v1"
        const val TIMEOUT_SECONDS = 10L
        const val CACHE_MS = 6 * 3_600_000L
        const val SESSIONS_CACHE_MS = 20 * 60_000L
        const val DEFAULT_MS = 45 * 60_000L
        const val MAX_MS = 150 * 60_000L
    }
}

/** ESPN for everything it carries, the MotoGP feed for MotoGP. */
class CompositeSportsSource(
    private val espn: SportsSource,
    private val motoGp: MotoGpSource,
    private val tsdb: BasketDataSource? = null,
) : SportsSource {
    private fun League.isMotoGp() = slug == MOTOGP
    private fun League.isTsdb() = tsdb != null && slug in BasketDataSource.SLUGS

    override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> =
        when {
            league.isMotoGp() -> motoGp.scoreboard(league, day)
            league.isTsdb() -> tsdb!!.scoreboard(league, day)
            else -> espn.scoreboard(league, day)
        }

    override suspend fun summary(match: Match): MatchSummary =
        when {
            match.leagueSlug == MOTOGP -> motoGp.summary(match)
            match.leagueSlug in BasketDataSource.SLUGS -> tsdb!!.summary(match)
            else -> espn.summary(match)
        }

    override suspend fun standings(league: League): StandingsTable =
        when {
            league.isTsdb() -> tsdb!!.standings(league)
            league.isMotoGp() -> throw IllegalArgumentException("no table")
            else -> espn.standings(league)
        }

    override suspend fun teams(league: League): List<Team> =
        when {
            league.isMotoGp() -> emptyList()
            league.isTsdb() -> tsdb!!.teams(league)
            else -> espn.teams(league)
        }

    // glcn: the interface grew a month, rankings and a team's schedule; MotoGP and the ACB feed have neither,
    // so they answer with what their scoreboard holds, no ranking, and no team schedule.
    override suspend fun month(league: League, month: java.time.YearMonth): MonthBoard =
        when {
            league.isTsdb() -> tsdb!!.month(league, month)
            league.isMotoGp() -> MonthBoard(scoreboard(league, null), null)
            else -> espn.month(league, month)
        }

    override suspend fun rankings(league: League): Ranking =
        if (league.isMotoGp() || league.isTsdb()) throw IllegalArgumentException("no ranking") else espn.rankings(league)

    override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> =
        when {
            league.isTsdb() -> tsdb!!.teamSchedule(league, teamId, fixtures)
            league.isMotoGp() -> emptyList()
            else -> espn.teamSchedule(league, teamId, fixtures)
        }

    companion object { const val MOTOGP = "motogp" }
}
