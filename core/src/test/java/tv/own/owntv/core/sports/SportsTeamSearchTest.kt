package tv.own.owntv.core.sports

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

class SportsTeamSearchTest {

    private class Source : SportsSource {
        val calls = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        override suspend fun teams(league: League): List<Team> {
            calls += league.key
            if (league.key in failing) throw IOException("down")
            return when (league.key) {
                "football/college-football" -> listOf(Team("194", "Ohio State Buckeyes", abbreviation = "OSU"))
                "football/nfl" -> listOf(Team("21", "Philadelphia Eagles", abbreviation = "PHI"))
                else -> emptyList()
            }
        }
        override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> = emptyList()
        override suspend fun summary(match: Match): MatchSummary = error("unused")
        override suspend fun standings(league: League): StandingsTable = error("unused")
        override suspend fun month(league: League, month: YearMonth) = MonthBoard(emptyList(), null)
        override suspend fun rankings(league: League): Ranking = error("unused")
        override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> = emptyList()
    }

    private val nflOnly = SportsConfig(leagues = listOf("football/nfl"))

    @Test
    fun `following one league searches every league of its sport`() = runTest {
        val source = Source()
        val found = SportsTeamSearch(source).search(nflOnly, "ohio")
        assertEquals(listOf("Ohio State Buckeyes"), found.map { it.name })
        assertEquals(SportsCatalog.leaguesOf(Sport.FOOTBALL).map { it.key }.toSet(), source.calls.toSet())
        // The league it was found in rides along, so it is not shown or opened as the followed league's.
        assertEquals("football/college-football", found.single().league)
    }

    @Test
    fun `a league is fetched once, a short query fetches nothing`() = runTest {
        val source = Source()
        val search = SportsTeamSearch(source)
        assertTrue(search.search(nflOnly, "o").isEmpty())
        assertTrue(source.calls.isEmpty())
        search.search(nflOnly, "ohio")
        val first = source.calls.size
        search.search(nflOnly, "eagles")
        assertEquals(first, source.calls.size)
    }

    @Test
    fun `a failed league is tried again after the pause, not on every key`() = runTest {
        val source = Source().apply { failing += "football/college-football" }
        var now = 0L
        val search = SportsTeamSearch(source) { now }
        assertTrue(search.search(nflOnly, "ohio").isEmpty())
        source.failing.clear()
        now = 10_000
        assertTrue(search.search(nflOnly, "ohio").isEmpty())
        now = 31_000
        assertEquals(listOf("Ohio State Buckeyes"), search.search(nflOnly, "ohio").map { it.name })
    }

    @Test
    fun `a league added later is fetched on the next search`() = runTest {
        val source = Source()
        val search = SportsTeamSearch(source)
        search.search(nflOnly, "ohio")
        source.calls.clear()
        search.search(SportsConfig(leagues = listOf("football/nfl", "hockey/nhl")), "ohio")
        assertEquals(SportsCatalog.leaguesOf(Sport.HOCKEY).map { it.key }.toSet(), source.calls.toSet())
    }

    @Test
    fun `college scoreboards ask for the whole division, the team list for every team`() {
        val ncaab = SportsCatalog.league("basketball/mens-college-basketball")!!
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard?dates=20261121&groups=50&limit=1000",
            EspnSportsSource.scoreboardUrl(ncaab, "20261121"),
        )
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?groups=80&limit=1000",
            EspnSportsSource.scoreboardUrl(SportsCatalog.league("football/college-football")!!, null),
        )
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard?limit=1000",
            EspnSportsSource.scoreboardUrl(SportsCatalog.league("hockey/nhl")!!, null),
        )
        assertEquals(50, SportsCatalog.league("basketball/womens-college-basketball")!!.group)
        assertEquals(null, SportsCatalog.league("volleyball/womens-college-volleyball")!!.group)
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/basketball/mens-college-basketball/teams?limit=1000",
            EspnSportsSource.teamsUrl(ncaab),
        )
    }
}
