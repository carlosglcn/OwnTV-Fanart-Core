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

    private class Feed(val teams: List<Team>, val matches: List<Match>, val groups: List<StandingsGroup>, val ranking: List<tv.own.owntv.core.sports.model.RankingRow> = emptyList())

    suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        val date = day ?: LocalDate.now(zone)
        return matches(league).filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }
    }

    suspend fun month(league: League, month: YearMonth): MonthBoard =
        MonthBoard(matches(league).filter { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) == month }, null)

    suspend fun teams(league: League): List<Team> = feed(league).teams

    suspend fun standings(league: League): StandingsTable = StandingsTable(league.key, feed(league).groups)

    /** glcn: a tour's world ranking (snooker), when the file carries one. */
    suspend fun rankings(league: League): tv.own.owntv.core.sports.model.Ranking {
        val rows = feed(league).ranking
        return tv.own.owntv.core.sports.model.Ranking(league.key,
            if (rows.isEmpty()) emptyList() else listOf(tv.own.owntv.core.sports.model.RankingGroup(tv.own.owntv.core.sports.model.RankingKind.PLAYERS, rows)))
    }

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
                // glcn: a player (snooker) rather than a club.
                isAthlete = t.optBoolean("athlete"),
                country = t.optString("country").takeIf { it.isNotEmpty() && !t.isNull("country") },
            )
        }
        // glcn: a nation (the Davis Cup) is named in the viewer's language, from its ISO code.
        val named = teams.map { t ->
            val iso = teamsArray?.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it) }.firstOrNull { it?.optString("id") == t.id } }
                ?.optString("nation").orEmpty()
            if (iso.length != 2) t else {
                val local = java.util.Locale(String(), iso).getDisplayCountry(java.util.Locale.getDefault()).takeIf { it.isNotBlank() && it != iso } ?: t.name
                t.copy(name = local, shortName = local)
            }
        }
        val byId = named.associateBy { it.id }
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
        val rankArray = root.optJSONArray("ranking")
        val ranking = (0 until (rankArray?.length() ?: 0)).mapNotNull { i ->
            val r = rankArray!!.optJSONObject(i) ?: return@mapNotNull null
            val team = byId[r.optString("team")] ?: return@mapNotNull null
            tv.own.owntv.core.sports.model.RankingRow(r.optInt("pos", i + 1), team, r.optInt("points"))
        }
        if (league.slug == DAVIS) for (i in 0 until (matchesArray?.length() ?: 0)) {
            val m = matchesArray!!.optJSONObject(i) ?: continue
            val tie = matches.firstOrNull { it.id == m.optString("id") } ?: continue
            rubberCache[tie.id] = rubbersOf(tie, m)
            squadCache[tie.id] = listOfNotNull(tie.home, tie.away).mapNotNull { side -> squadOf(tie, side, teamsArray, root) }
        }
        return Feed(named, matches, groups, ranking)
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
            subtitle = stageOf(m) ?: m.optInt("round").takeIf { it > 0 }?.let { "Round $it" },
            venue = m.optString("venue").takeIf { it.isNotEmpty() && !m.isNull("venue") },
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

    /** glcn: the phase in the viewer's language when the file gives it ("stage_es"). */
    private fun stageOf(m: JSONObject): String? {
        val es = m.optString("stage_es").takeIf { it.isNotEmpty() && !m.isNull("stage_es") }
        val en = m.optString("stage").takeIf { it.isNotEmpty() && !m.isNull("stage") }
        return if (es != null && java.util.Locale.getDefault().language == SPANISH) es else en
    }

    /** glcn: one tie's rubbers as tennis matches: each side a player (or a doubles pair) of its nation, sets as periods. */
    /** glcn: a nation's nominated team for a tie not yet drawn up: its players (singles ranking beside each), captain in the title. */
    private fun squadOf(tie: Match, side: Competitor, teamsArray: org.json.JSONArray?, root: JSONObject): DrawRound? {
        val t = (0 until (teamsArray?.length() ?: 0)).map { teamsArray!!.optJSONObject(it) }.firstOrNull { it?.optString("id") == side.team.id } ?: return null
        val squad = t.optJSONArray("squad") ?: return null
        val players = (0 until squad.length()).mapNotNull { i ->
            val p = squad.optJSONObject(i) ?: return@mapNotNull null
            val name = p.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sr = if (p.isNull("sr")) null else p.optInt("sr")
            tie.copy(
                id = tie.id + "-" + side.team.id + "-" + i, state = MatchState.POST, statusText = String(), clock = null, subtitle = null,
                title = name,
                competitors = listOf(Competitor(
                    team = Team(id = name, name = name, shortName = name.substringAfterLast(' '), isAthlete = true, country = side.team.name),
                    home = true, winner = null,
                    score = sr?.let { Score(it.toString(), it, listOf(tv.own.owntv.core.sports.model.PeriodScore(it))) },
                )),
            )
        }
        if (players.isEmpty()) return null
        val spanish = java.util.Locale.getDefault().language == SPANISH
        val label = root.optJSONObject("captain_label")?.optString(if (spanish) SPANISH else ENGLISH).orEmpty()
        val captain = t.optString("captain").takeIf { it.isNotEmpty() && !t.isNull("captain") }
        val title = side.team.name + (if (captain != null && label.isNotEmpty()) " · $label: $captain" else String())
        return DrawRound(title, players)
    }

    private fun rubbersOf(tie: Match, m: JSONObject): List<Match> {
        val list = m.optJSONArray("rubbers") ?: return emptyList()
        val spanish = java.util.Locale.getDefault().language == SPANISH
        val rubbers = (0 until list.length()).mapNotNull { i ->
            val r = list.optJSONObject(i) ?: return@mapNotNull null
            fun names(key: String) = r.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { n -> n.isNotEmpty() } } }.orEmpty()
            fun sets(key: String) = r.optJSONArray(key)?.let { a -> (0 until a.length()).map { tv.own.owntv.core.sports.model.PeriodScore(a.optInt(it)) } }.orEmpty()
            val p1 = names("p1"); val p2 = names("p2")
            if (p1.isEmpty() || p2.isEmpty()) return@mapNotNull null
            val s1 = sets("s1"); val s2 = sets("s2")
            // Sets won: a set counts once its games are decided (6–4, 7–6), not while it is being played.
            val pairs = s1.zip(s2).map { (a, b) -> a.value to b.value }
            val won1 = pairs.count { (a, b) -> a > b && maxOf(a, b) >= SET_GAMES }
            val won2 = pairs.count { (a, b) -> b > a && maxOf(a, b) >= SET_GAMES }
            val winner = if (r.isNull("winner")) 0 else r.optInt("winner")
            val played = s1.isNotEmpty() || s2.isNotEmpty()
            val state = when {
                winner != 0 -> MatchState.POST
                played -> MatchState.IN
                tie.state == MatchState.POST -> MatchState.POST
                else -> MatchState.PRE
            }
            fun side(names: List<String>, nation: Competitor?, sets: List<tv.own.owntv.core.sports.model.PeriodScore>, setsWon: Int, home: Boolean, won: Boolean) = Competitor(
                team = Team(id = names.joinToString(PAIR), name = names.joinToString(PAIR), shortName = names.joinToString(PAIR) { it.substringAfterLast(' ') },
                    isAthlete = true, country = nation?.team?.name),
                home = home, winner = if (winner != 0) won else null,
                score = if (played) Score(setsWon.toString(), setsWon, sets) else null,
            )
            val label = r.optString(if (spanish) "label_es" else "label").ifEmpty { r.optString("label") }
            tie.copy(
                id = tie.id + "-r" + i, state = state, statusText = if (state == MatchState.POST) "FT" else null.orEmpty(), clock = null,
                title = p1.joinToString(PAIR) + " v " + p2.joinToString(PAIR), subtitle = label,
                competitors = listOf(side(p1, tie.home, s1, won1, true, winner == 1), side(p2, tie.away, s2, won2, false, winner == 2)),
            )
        }
        // A tie under way: the first rubber not yet won is the one on court, even before the article has its first set.
        if (tie.state != MatchState.IN) return rubbers
        val now = rubbers.indexOfFirst { it.state != MatchState.POST }
        return if (now < 0) rubbers else rubbers.mapIndexed { i, r -> if (i == now) r.copy(state = MatchState.IN) else r }
    }

    companion object {
        const val ACB = "acb"
        const val EUROLEAGUE = "euroleague"
        const val EUROCUP = "eurocup"
        const val PRIMERA_FEB = "primerafeb"
        const val LEGA = "lega"
        const val ASOBAL = "asobal"
        const val EHF_CL = "ehfcl"
        const val EHF_EL = "ehfel"
        const val HBL = "hbl"
        const val LNH = "lnh"
        const val HHL = "hhl"
        const val KHL = "khl"
        const val SNOOKER = "snooker"
        const val DAVIS = "davis"
        private val rubberCache = java.util.concurrent.ConcurrentHashMap<String, List<Match>>()
        private val squadCache = java.util.concurrent.ConcurrentHashMap<String, List<DrawRound>>()

        /** glcn: the two nations' nominated teams, for a tie whose rubbers are not drawn up yet. */
        fun squads(match: Match): List<DrawRound> = squadCache[match.id].orEmpty()

        /** glcn: a Davis Cup tie's rubbers (singles and doubles), each a tennis match between players, as last read. */
        fun rubbers(match: Match): List<Match> = rubberCache[match.id].orEmpty()
        private const val SPANISH = "es"
        private const val ENGLISH = "en"
        private const val PAIR = " / "
        private const val SET_GAMES = 6
        private val EHF_SLUGS = setOf(EHF_CL, EHF_EL)
        val SLUGS = setOf(ACB, EUROLEAGUE, EUROCUP, PRIMERA_FEB, LEGA, ASOBAL, EHF_CL, EHF_EL, HBL, LNH, HHL, KHL, SNOOKER, DAVIS)
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
