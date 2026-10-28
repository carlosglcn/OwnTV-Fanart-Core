package tv.own.owntv.core.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Broadcast
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchClock
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Score
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsGroup
import tv.own.owntv.core.sports.model.StandingsRow
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The Spanish Liga ACB, the EuroLeague and the handball Liga ASOBAL. None is on ESPN, so a small scheduled job
 * (carlosglcn/owntv-sports-data) reads the leagues' own sites and publishes one JSON file per league:
 * every match of the season, with the score and the quarter of a live game, and the table.
 * A file is kept for five minutes, so opening a screen costs at most one small download. The ASOBAL's own
 * round page is read every half minute while one of its matches is on, so a live score is never ten minutes old.
 * Every read is defensive: a surprise in the answer gives an empty board, never a crash.
 */
class BasketDataSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val zone get() = ZoneId.systemDefault()
    private val cache = HashMap<String, Pair<Long, Feed>>()

    private class Feed(val teams: List<Team>, val matches: List<Match>, val groups: List<StandingsGroup>)

    suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        val date = day ?: LocalDate.now(zone)
        return matches(league).filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }
    }

    suspend fun month(league: League, month: YearMonth): MonthBoard =
        MonthBoard(matches(league).filter { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) == month }, null)

    suspend fun teams(league: League): List<Team> = feed(league).teams

    suspend fun standings(league: League): StandingsTable = StandingsTable(league.key, feed(league).groups)

    suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> {
        val mine = matches(league).filter { m -> m.competitors.any { it.team.id == teamId } }
        return if (fixtures) mine.filter { it.state != MatchState.POST } else mine.filter { it.state == MatchState.POST }.reversed()
    }

    fun summary(match: Match) = MatchSummary(match, emptyList(), emptyList(), emptyList())

    /** The league's matches, with a live ASOBAL match read from the league's own round page. */
    private suspend fun matches(league: League): List<Match> {
        val base = feed(league).matches
        if (league.slug in EHF_SLUGS) return ehfLive(base)
        if (league.slug != ASOBAL) return base
        val now = System.currentTimeMillis()
        val rounds = base.filter { it.state != MatchState.POST && now in (it.startMs - LIVE_LEAD_MS)..(it.startMs + LIVE_WINDOW_MS) }
            .mapNotNull { it.subtitle?.removePrefix("Round ")?.toIntOrNull() }.toSet()
        if (rounds.isEmpty()) return base
        val live = rounds.flatMap { roundLive(it) }.associateBy { it.id }
        if (live.isEmpty()) return base
        return base.map { m ->
            val l = live[m.id] ?: return@map m
            val state = when (l.status) { "final" -> MatchState.POST; "live" -> MatchState.IN; else -> m.state }
            if (state == MatchState.PRE) return@map m
            val decided = state == MatchState.POST && l.home != l.away
            m.copy(
                state = state,
                statusText = if (state == MatchState.POST) "FT" else l.label,
                clock = if (state == MatchState.IN && l.label.isNotEmpty()) MatchClock(display = l.label, period = 0) else null,
                competitors = m.competitors.map { c ->
                    val goals = if (c.home) l.home else l.away
                    c.copy(score = Score(goals.toString(), goals), winner = if (decided) (if (c.home) l.home > l.away else l.away > l.home) else null)
                },
            )
        }
    }

    /**
     * glcn: the EHF's live board (every competition at once): while one of [base]'s matches is on, its score and
     * clock come from there, read at most every half minute, instead of from the ten-minute file.
     */
    private suspend fun ehfLive(base: List<Match>): List<Match> {
        val now = System.currentTimeMillis()
        if (base.none { it.state != MatchState.POST && now in (it.startMs - LIVE_LEAD_MS)..(it.startMs + LIVE_WINDOW_MS) }) return base
        val live = ehfBoard()
        if (live.isEmpty()) return base
        return base.map { m ->
            val l = live[m.id] ?: return@map m
            if (!l.live && m.state != MatchState.IN) return@map m
            val state = if (l.live) MatchState.IN else MatchState.POST
            val homeGoals = l.goals[m.competitors.firstOrNull { it.home }?.team?.id] ?: return@map m
            val awayGoals = l.goals[m.competitors.firstOrNull { !it.home }?.team?.id] ?: return@map m
            val decided = state == MatchState.POST && homeGoals != awayGoals
            m.copy(
                state = state,
                statusText = if (state == MatchState.POST) "FT" else l.clock,
                clock = if (state == MatchState.IN && l.clock.isNotEmpty()) MatchClock(display = l.clock, period = 0) else null,
                competitors = m.competitors.map { c ->
                    val goals = if (c.home) homeGoals else awayGoals
                    c.copy(score = Score(goals.toString(), goals), winner = if (decided) (if (c.home) homeGoals > awayGoals else awayGoals > homeGoals) else null)
                },
            )
        }
    }

    private class EhfLine(val live: Boolean, val goals: Map<String, Int>, val clock: String)

    private var ehfCache: Pair<Long, Map<String, EhfLine>>? = null

    private suspend fun ehfBoard(): Map<String, EhfLine> {
        val now = System.currentTimeMillis()
        ehfCache?.takeIf { now - it.first < LIVE_CACHE_MS }?.let { return it.second }
        val lines = runCatching {
            val body = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(EHF_LIVE).build()).execute().use { r ->
                    if (!r.isSuccessful) throw SportsHttpException(r.code)
                    r.body.string()
                }
            }
            val out = HashMap<String, EhfLine>()
            val days = JSONObject(body).optJSONArray("days")
            for (d in 0 until (days?.length() ?: 0)) {
                val list = days!!.optJSONObject(d)?.optJSONArray("liveScoreMatches") ?: continue
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val id = e.optJSONObject("match")?.optString("matchID")?.takeIf { it.isNotEmpty() } ?: continue
                    // The two stats blocks name their own team: they are not always in home/away order.
                    val goals = listOfNotNull(e.optJSONObject("homeStats"), e.optJSONObject("guestStats"))
                        .mapNotNull { s -> s.optString("id").takeIf { it.isNotEmpty() }?.let { it to s.optInt("totalGoals") } }.toMap()
                    val stats = e.optJSONObject("matchStats")
                    val time = stats?.optString("time").orEmpty().takeIf { it != "-" }.orEmpty()
                    out[id] = EhfLine(stats?.optBoolean("isLive") == true, goals, time)
                }
            }
            out
        }.getOrDefault(emptyMap())
        ehfCache = now to lines
        return lines
    }

    private class LiveLine(val id: String, val status: String, val home: Int, val away: Int, val label: String)

    private val roundCache = HashMap<Int, Pair<Long, List<LiveLine>>>()

    private suspend fun roundLive(round: Int): List<LiveLine> {
        val now = System.currentTimeMillis()
        roundCache[round]?.takeIf { now - it.first < LIVE_CACHE_MS }?.let { return it.second }
        val lines = runCatching {
            val html = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$ASOBAL_SITE/liga/calendario/$round/").build()).execute().use { r ->
                    if (!r.isSuccessful) throw SportsHttpException(r.code)
                    r.body.string()
                }
            }
            parseRound(html)
        }.getOrDefault(emptyList())
        roundCache[round] = now to lines
        return lines
    }

    /** One `<li>` per match on the league's round page: its id, score and the text under it ("Partido finalizado"). */
    private fun parseRound(html: String): List<LiveLine> {
        val start = html.indexOf("info-results").takeIf { it >= 0 } ?: return emptyList()
        return ROUND_ITEM.findAll(html.substring(start)).mapNotNull { li ->
            val item = li.groupValues[1]
            val id = STATS_ID.find(item)?.groupValues?.get(1) ?: return@mapNotNull null
            val goals = GOALS.find(item) ?: return@mapNotNull null
            val label = ENLACES.find(item)?.groupValues?.get(1)?.replace(TAGS, SPACE)?.replace(SPACES, SPACE)?.trim().orEmpty()
            val status = when {
                label.contains("finalizado", ignoreCase = true) -> "final"
                label.isEmpty() || label.contains("no ha comenzado", ignoreCase = true) -> "scheduled"
                else -> "live"
            }
            LiveLine(id, status, goals.groupValues[1].toInt(), goals.groupValues[2].toInt(), label)
        }.toList()
    }

    private suspend fun feed(league: League): Feed {
        val now = System.currentTimeMillis()
        cache[league.slug]?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$BASE/${league.slug}.json").build()).execute().use { r ->
                if (!r.isSuccessful) throw SportsHttpException(r.code)
                r.body.string()
            }
        }
        val feed = parse(league, body, now)
        cache[league.slug] = now to feed
        return feed
    }

    private fun parse(league: League, body: String, now: Long): Feed {
        val root = JSONObject(body)
        val teamsArray = root.optJSONArray("teams")
        val teams = (0 until (teamsArray?.length() ?: 0)).mapNotNull { i ->
            val t = teamsArray!!.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").ifEmpty { return@mapNotNull null }
            val name = t.optString("name").ifEmpty { return@mapNotNull null }
            Team(
                id = id, name = name,
                shortName = t.optString("short").ifEmpty { name },
                abbreviation = t.optString("abbr").takeIf { it.length in 2..5 }.orEmpty(),
                logoUrl = t.optString("logo").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
        val byId = teams.associateBy { it.id }
        val matchesArray = root.optJSONArray("matches")
        val matches = (0 until (matchesArray?.length() ?: 0)).mapNotNull { i ->
            matchesArray!!.optJSONObject(i)?.let { toMatch(league, it, byId, now) }
        }
        val tableArray = root.optJSONArray("standings")
        val standings = (0 until (tableArray?.length() ?: 0)).mapNotNull { i ->
            val r = tableArray!!.optJSONObject(i) ?: return@mapNotNull null
            val team = byId[r.optString("team")] ?: return@mapNotNull null
            val diff = r.optInt("diff")
            r.optString("group").takeUnless { r.isNull("group") }.orEmpty() to StandingsRow(
                rank = r.optInt("pos", i + 1), team = team, played = r.optInt("p"), won = r.optInt("w"), drawn = r.optInt("d"),
                lost = r.optInt("l"), scored = r.optInt("pf"), conceded = r.optInt("pa"),
                difference = if (diff > 0) "+$diff" else diff.toString(), points = if (r.has("pts")) r.optInt("pts") else r.optInt("w"),
            )
        }
        // glcn: a competition played in groups (the EHF's) has one table per group, in the file's order.
        val groups = standings.groupBy({ it.first }, { it.second }).map { (name, rows) -> StandingsGroup(name.ifEmpty { league.name }, rows) }
        return Feed(teams, matches, groups)
    }

    private fun toMatch(league: League, m: JSONObject, byId: Map<String, Team>, now: Long): Match? {
        val id = m.optString("id").ifEmpty { return null }
        val home = byId[m.optString("home")] ?: return null
        val away = byId[m.optString("away")] ?: return null
        val start = m.optLong("start", -1L).takeIf { it > 0 } ?: return null
        val hs = if (m.isNull("hs")) null else m.optInt("hs")
        val aw = if (m.isNull("as")) null else m.optInt("as")
        val periodText = m.optString("period").takeUnless { m.isNull("period") }.orEmpty()
        val period = if (league.sport == Sport.HANDBALL) 0 else periodText.toIntOrNull() ?: 0
        val state = when {
            m.optString("status") == "final" -> MatchState.POST
            m.optString("status") == "live" -> MatchState.IN
            now > start + STALE_MS -> MatchState.POST
            else -> MatchState.PRE
        }
        val decided = state == MatchState.POST && hs != null && aw != null && hs != aw
        fun side(team: Team, isHome: Boolean, goals: Int?, winner: Boolean?) = Competitor(
            team = team, home = isHome, winner = winner,
            score = goals?.takeIf { state != MatchState.PRE }?.let { Score(it.toString(), it) },
        )
        val channels = m.optJSONArray("tv")
        return Match(
            id = id,
            sport = league.sport,
            feed = league.key,
            leagueSlug = league.slug,
            leagueName = league.name,
            startMs = start,
            state = state,
            statusText = when (state) {
                MatchState.POST -> "FT"
                MatchState.IN -> if (league.sport == Sport.HANDBALL) periodText else "Q$period".takeIf { period > 0 }.orEmpty()
                else -> null.orEmpty()
            },
            title = home.name + " v " + away.name,
            // glcn: a phase name where the competition has one ("Fase de grupos · Grupo A"), else the round.
            subtitle = m.optString("stage").takeIf { it.isNotEmpty() && !m.isNull("stage") } ?: m.optInt("round").takeIf { it > 0 }?.let { "Round $it" },
            clock = when {
                state != MatchState.IN -> null
                league.sport == Sport.HANDBALL -> periodText.takeIf { it.isNotEmpty() }?.let { MatchClock(display = it, period = 0) }
                period > 0 -> MatchClock(display = "Q$period", period = period)
                else -> null
            },
            competitors = listOf(
                side(home, true, hs, if (decided) hs!! > aw!! else null),
                side(away, false, aw, if (decided) aw!! > hs!! else null),
            ),
            broadcasts = (0 until (channels?.length() ?: 0)).mapNotNull { channels!!.optString(it).takeIf { n -> n.isNotEmpty() } }
                .map { Broadcast(it) },
        )
    }

    companion object {
        const val ACB = "acb"
        const val EUROLEAGUE = "euroleague"
        const val ASOBAL = "asobal"
        const val EHF_CL = "ehfcl"
        const val EHF_EL = "ehfel"
        const val HBL = "hbl"
        const val LNH = "lnh"
        const val HHL = "hhl"
        const val KHL = "khl"
        private val EHF_SLUGS = setOf(EHF_CL, EHF_EL)
        val SLUGS = setOf(ACB, EUROLEAGUE, ASOBAL, EHF_CL, EHF_EL, HBL, LNH, HHL, KHL)
        private const val EHF_LIVE = "https://www.eurohandball.com/umbraco/api/livescoreapi/GetLiveScoreMatches/1069"
        private const val ASOBAL_SITE = "https://www.asobal.es"
        private const val LIVE_CACHE_MS = 30_000L
        private const val LIVE_LEAD_MS = 15 * 60_000L
        private const val LIVE_WINDOW_MS = 3 * 3_600_000L
        private val ROUND_ITEM = Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        private val STATS_ID = Regex("estadisticas/(\\d+)/")
        private val GOALS = Regex("<b>\\s*(\\d+)\\s*</b>\\s*-\\s*<b>\\s*(\\d+)\\s*</b>")
        private val ENLACES = Regex("class=\"enlaces\">(.*?)(?:\\||</span>)", RegexOption.DOT_MATCHES_ALL)
        private val TAGS = Regex("<[^>]+>")
        private val SPACE = ' '.toString()
        private val SPACES = Regex("\\s+")
        private const val BASE = "https://raw.githubusercontent.com/carlosglcn/owntv-sports-data/main"
        private const val TIMEOUT_SECONDS = 10L
        private const val CACHE_MS = 5 * 60_000L

        /** A game with no result this long after its start is over as far as the board is concerned. */
        private const val STALE_MS = 4 * 3_600_000L
    }
}
