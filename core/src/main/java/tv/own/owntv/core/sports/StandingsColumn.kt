package tv.own.owntv.core.sports

import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsRow

/**
 * The number columns of a league table. Each sport ranks by its own figures: football by points with
 * goal difference, hockey by points with overtime losses, the American leagues by win percentage with
 * games behind. Both apps draw [of] and put [main] in bold.
 */
enum class StandingsColumn {
    PLAYED, WON, DRAWN, TIED, LOST, OT_LOSSES, DIFFERENCE, WIN_PERCENT, GAMES_BEHIND, POINTS;

    fun valueOf(row: StandingsRow): String = when (this) {
        PLAYED -> row.played.toString()
        WON -> row.won.toString()
        DRAWN, TIED -> row.drawn.toString()
        LOST -> row.lost.toString()
        OT_LOSSES -> row.otLosses.toString()
        DIFFERENCE -> row.difference
        WIN_PERCENT -> row.winPercent
        GAMES_BEHIND -> row.gamesBehind
        POINTS -> row.points.toString()
    }

    companion object {
        fun of(sport: Sport?): List<StandingsColumn> = when (sport) {
            Sport.HOCKEY -> listOf(PLAYED, WON, LOST, OT_LOSSES, POINTS)
            Sport.BASKETBALL, Sport.BASEBALL -> listOf(WON, LOST, WIN_PERCENT, GAMES_BEHIND)
            Sport.FOOTBALL -> listOf(WON, LOST, TIED, WIN_PERCENT)
            Sport.VOLLEYBALL, Sport.LACROSSE -> listOf(WON, LOST, WIN_PERCENT)
            else -> listOf(PLAYED, WON, DRAWN, LOST, DIFFERENCE, POINTS)
        }

        /** The figure the table is ranked by. */
        fun main(sport: Sport?): StandingsColumn = if (POINTS in of(sport)) POINTS else WIN_PERCENT
    }
}
