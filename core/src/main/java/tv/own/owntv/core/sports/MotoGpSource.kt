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
import tv.own.owntv.core.sports.model.isRace
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
        CompetitionLogos.refresh(::get) // glcn: MotoGP's logo, among others
        val date = day ?: LocalDate.now(zone)
        val near = eventsOf(date.year).filter { e ->
            val start = dayOf(e.optString("date_start")) ?: return@filter false
            val end = dayOf(e.optString("date_end")) ?: start
            !date.isBefore(start.minusDays(1)) && !date.isAfter(end.plusDays(1))
        }
        val all = coroutineScope { near.map { e -> async { sessionsOf(league, e) } }.awaitAll() }.flatten()
        return all.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }
    }

    /**
     * glcn: a session's page. Once it has run, its classification — who finished where, and how far behind — comes
     * with the match as its competitors, in order. Read from the championship's results feed (a different one from
     * the calendar's); a session that feed does not know, or has not classified yet, simply has none.
     */
    suspend fun summary(match: Match): MatchSummary {
        val order = runCatching { classificationOf(match) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
        return MatchSummary(if (order.isEmpty()) match else match.copy(competitors = order), emptyList(), emptyList(), emptyList())
    }

    /** glcn: the riders' championship. */
    suspend fun rankings(league: League): Ranking {
        val now = System.currentTimeMillis()
        standings?.takeIf { now - it.first < STANDINGS_CACHE_MS }?.let { return Ranking(league.key, it.second) }
        val (season, category) = ids() ?: throw IllegalArgumentException("no ranking")
        val rows = objects(JSONObject(get("$BASE/results/standings?seasonUuid=$season&categoryUuid=$category")).optJSONArray("classification"))
            .mapNotNull { r ->
                val rider = rider(r.optJSONObject("rider"), r.optJSONObject("team")) ?: return@mapNotNull null
                tv.own.owntv.core.sports.model.RankingRow(r.optInt("position", 0), rider, r.optDouble("points", 0.0).toInt())
            }.sortedBy { if (it.rank > 0) it.rank else Int.MAX_VALUE }
        if (rows.isEmpty()) throw IllegalArgumentException("no ranking")
        val groups = listOf(tv.own.owntv.core.sports.model.RankingGroup(tv.own.owntv.core.sports.model.RankingKind.DRIVERS, rows))
        standings = now to groups
        return Ranking(league.key, groups)
    }

    private var standings: Pair<Long, List<tv.own.owntv.core.sports.model.RankingGroup>>? = null
    private var seasonIds: Pair<String, String>? = null
    private var resultEvents: Pair<Long, List<JSONObject>>? = null
    private val classified = HashMap<String, Pair<Long, List<tv.own.owntv.core.sports.model.Competitor>>>()

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /** The current season's id and the MotoGP class's, in the results feed. */
    private suspend fun ids(): Pair<String, String>? {
        seasonIds?.let { return it }
        val seasons = objects(runCatching { JSONArray(get("$BASE/results/seasons")) }.getOrNull())
        val season = (seasons.firstOrNull { it.optBoolean("current") } ?: seasons.firstOrNull())?.optString("id")?.takeIf { it.isNotEmpty() } ?: return null
        val category = objects(runCatching { JSONArray(get("$BASE/results/categories?seasonUuid=$season")) }.getOrNull())
            .firstOrNull { it.optString("name").startsWith("MotoGP") }?.optString("id")?.takeIf { it.isNotEmpty() } ?: return null
        return (season to category).also { seasonIds = it }
    }

    /** A rider as a side: the name, the number as its short mark, the country's code, the team as its place. */
    private fun rider(r: JSONObject?, team: JSONObject?): Team? {
        if (r == null) return null
        val name = r.optString("full_name").trim().takeIf { it.isNotEmpty() } ?: return null
        return Team(
            id = r.optString("id").ifEmpty { name },
            name = name,
            shortName = name.substringAfterLast(' '),
            abbreviation = r.opt("number")?.toString().orEmpty(),
            location = team?.optString("name")?.takeIf { it.isNotEmpty() },
            isAthlete = true,
            country = r.optJSONObject("country")?.optString("iso")?.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun classificationOf(match: Match): List<tv.own.owntv.core.sports.model.Competitor> {
        val now = System.currentTimeMillis()
        // Nothing to read before the session has had time to end.
        if (match.state == MatchState.PRE) return emptyList()
        classified[match.id]?.takeIf { now - it.first < (if (it.second.isEmpty()) EMPTY_RESULT_MS else CACHE_MS) }?.let { return it.second }
        val (season, category) = ids() ?: return emptyList()
        val events = resultEvents?.takeIf { now - it.first < CACHE_MS }?.second
            ?: objects(runCatching { JSONArray(get("$BASE/results/events?seasonUuid=$season")) }.getOrNull()).also { if (it.isNotEmpty()) resultEvents = now to it }
        val day = Instant.ofEpochMilli(match.startMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val event = events.firstOrNull { e ->
            if (e.optBoolean("test")) return@firstOrNull false
            val start = runCatching { LocalDate.parse(e.optString("date_start").take(DAY_LENGTH)) }.getOrNull() ?: return@firstOrNull false
            val end = runCatching { LocalDate.parse(e.optString("date_end").take(DAY_LENGTH)) }.getOrNull() ?: start
            !day.isBefore(start.minusDays(1)) && !day.isAfter(end.plusDays(1))
        }?.optString("id")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        // The session that began when this one did (the two feeds agree on the hour, not on the names).
        val session = objects(runCatching { JSONArray(get("$BASE/results/sessions?eventUuid=$event&categoryUuid=$category")) }.getOrNull())
            .mapNotNull { s -> time(s.optString("date"))?.let { at -> s to kotlin.math.abs(at - match.startMs) } }
            .filter { it.second <= SAME_SESSION_MS }.minByOrNull { it.second }?.first?.optString("id")?.takeIf { it.isNotEmpty() }
        val rows = if (session == null) emptyList() else objects(
            (runCatching { JSONObject(get("$BASE/results/session/$session/classification?test=false")) }.getOrNull()
                ?: runCatching { JSONObject(get("$BASE/results/session/$session/classification")) }.getOrNull())?.optJSONArray("classification"),
        )
        val out = rows.mapNotNull { r ->
            val rider = rider(r.optJSONObject("rider"), r.optJSONObject("team")) ?: return@mapNotNull null
            val place = r.optInt("position", 0)
            // The winner's own time (a race) or best lap (practice, qualifying); everyone else's gap to it.
            val gap = r.optJSONObject("gap")?.optString("first")?.trim().orEmpty()
            val own = r.optString("time").trim().ifEmpty { r.optJSONObject("best_lap")?.optString("time")?.trim().orEmpty() }
            val text = when {
                place == 1 || gap.isEmpty() || gap.trim('0', '.', ':').isEmpty() -> own
                gap.startsWith("+") || gap.startsWith("-") -> gap
                else -> "+$gap"
            }
            (if (place > 0) place else Int.MAX_VALUE) to tv.own.owntv.core.sports.model.Competitor(rider, home = false, winner = if (place == 1) true else null, score = text.takeIf { it.isNotEmpty() }?.let { tv.own.owntv.core.sports.model.Score(it, null) })
        }.sortedBy { it.first }.map { it.second }
        classified[match.id] = now to out
        return out
    }

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
        const val STANDINGS_CACHE_MS = 30 * 60_000L
        const val EMPTY_RESULT_MS = 3 * 60_000L
        const val SAME_SESSION_MS = 20 * 60_000L
        const val DAY_LENGTH = 10
    }
}

/** ESPN for everything it carries, the MotoGP feed for MotoGP. */
class CompositeSportsSource(
    private val espn: SportsSource,
    private val motoGp: MotoGpSource,
    private val tsdb: BasketDataSource? = null,
    private val openF1: OpenF1Source? = null,
    private val cycling: CyclingSource? = null,
) : SportsSource {
    private fun League.isCycling() = cycling != null && sport == Sport.CYCLING
    private fun League.isMotoGp() = slug == MOTOGP
    private fun League.isTsdb() = tsdb != null && slug in BasketDataSource.SLUGS

    override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> =
        when {
            league.isCycling() -> cycling!!.scoreboard(league, day)
            league.isMotoGp() -> motoGp.scoreboard(league, day)
            league.isTsdb() -> tsdb!!.scoreboard(league, day)
            else -> espn.scoreboard(league, day)
        }

    override suspend fun summary(match: Match): MatchSummary =
        when {
            match.sport == Sport.CYCLING && cycling != null -> cycling.summary(match)
            match.leagueSlug == MOTOGP -> motoGp.summary(match)
            match.leagueSlug in BasketDataSource.SLUGS -> tsdb!!.summary(match)
            else -> espn.summary(match).let { base ->
                // glcn: a finished Formula 1 session also gets its pit stops and race-director notes (free history).
                if (match.leagueSlug == "f1" && match.state == tv.own.owntv.core.sports.model.MatchState.POST && openF1 != null && base.timeline.isEmpty()) {
                    val extra = openF1.timeline(match)
                    if (extra.isEmpty()) base else base.copy(timeline = extra)
                } else base
            }
        }

    override suspend fun standings(league: League): StandingsTable =
        when {
            league.isTsdb() -> tsdb!!.standings(league)
            league.isMotoGp() || league.isCycling() -> throw IllegalArgumentException("no table")
            else -> espn.standings(league)
        }

    override suspend fun teams(league: League): List<Team> =
        when {
            // glcn: where people compete, not teams, the ones to find and follow are those of its ranking: a tour's
            // players, a series' drivers or riders (a constructors' table is left out).
            league.sport == Sport.TENNIS || league.sport.isRace ->
                rankings(league).groups.filter { it.kind != tv.own.owntv.core.sports.model.RankingKind.CONSTRUCTORS }
                    .flatMap { g -> g.rows.map { it.team } }.distinctBy { it.id }
            league.isTsdb() -> tsdb!!.teams(league)
            else -> espn.teams(league)
        }

    // glcn: the interface grew a month, rankings and a team's schedule; MotoGP and the ACB feed have neither,
    // so they answer with what their scoreboard holds, no ranking, and no team schedule.
    override suspend fun month(league: League, month: java.time.YearMonth): MonthBoard =
        when {
            league.isCycling() -> cycling!!.month(league, month)
            league.isTsdb() -> tsdb!!.month(league, month)
            league.isMotoGp() -> MonthBoard(scoreboard(league, null), null)
            else -> espn.month(league, month)
        }

    override suspend fun rankings(league: League): Ranking =
        when {
            league.isCycling() -> cycling!!.rankings(league)
            league.isTsdb() && league.slug == BasketDataSource.SNOOKER -> tsdb!!.rankings(league)
            league.isMotoGp() -> motoGp.rankings(league)
            league.isTsdb() -> throw IllegalArgumentException("no ranking")
            else -> espn.rankings(league)
        }

    override suspend fun warm() = espn.warm()

    override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> =
        when {
            league.isTsdb() -> tsdb!!.teamSchedule(league, teamId, fixtures)
            league.isMotoGp() || league.isCycling() -> emptyList()
            else -> espn.teamSchedule(league, teamId, fixtures)
        }

    companion object { const val MOTOGP = "motogp" }
}
