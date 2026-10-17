package tv.own.owntv.core.sports.model

/** One row of a match's Stats tab: [home] and [away] as ESPN shows them ("47.6", "20"). */
data class StatLine(val name: String, val label: String, val home: String, val away: String)

data class LineupPlayer(
    val name: String,
    val jersey: String?,
    val position: String?,
    val subbedIn: Boolean = false,
    val subbedOut: Boolean = false,
)

data class Lineup(val teamId: String, val formation: String?, val starters: List<LineupPlayer>, val bench: List<LineupPlayer>)

/**
 * One of a side's last games (ESPN's `lastFiveGames`): [result] W / D / L, the [opponent], [home] when it
 * was played at home, and the [score] as ESPN writes it, home side first ("3-2").
 */
data class FormGame(val startMs: Long, val result: String, val opponent: String, val home: Boolean, val score: String)

/**
 * Everything the match page shows beyond the board's card: the timeline, the stats, the line-ups, and
 * each side's last games by team id ([form]; ESPN gives no head-to-head).
 */
data class MatchSummary(
    val match: Match,
    val timeline: List<MatchEvent>,
    val stats: List<StatLine>,
    val lineups: List<Lineup>,
    val form: Map<String, List<FormGame>> = emptyMap(),
)

data class StandingsRow(
    val rank: Int,
    val team: Team,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val scored: Int,
    val conceded: Int,
    val difference: String,
    val points: Int,
    val note: String? = null,
)

/** A league table; [name] is a conference or group ("Eastern Conference"), or the league's own name. */
data class StandingsGroup(val name: String, val rows: List<StandingsRow>)

data class StandingsTable(val leagueKey: String, val groups: List<StandingsGroup>)
