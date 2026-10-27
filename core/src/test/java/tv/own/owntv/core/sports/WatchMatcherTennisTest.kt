package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Score
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class WatchMatcherTennisTest {
    private val davidovich = Team("1", "Alejandro Davidovich Fokina", "A. Davidovich Fokina", isAthlete = true)
    private val jodar = Team("2", "Rafael Jodar", "R. Jodar", isAthlete = true)

    private fun final(subtitle: String = "Men's Singles · Final", name: String = "Shanghai Masters") = Match(
        id = "m1", sport = Sport.TENNIS, feed = "tennis/atp", leagueSlug = "atp", leagueName = name,
        startMs = 1L, state = MatchState.IN, statusText = "Set 2", title = "A v B", subtitle = subtitle,
        competitors = listOf(
            Competitor(davidovich, true, null, Score("1", 1)),
            Competitor(jodar, false, null, Score("1", 1)),
        ),
    )

    private fun hit(m: Match, title: String) = WatchMatcher.hit(WatchMatcher.Keys(m), TeamNames.tokens(title).toSet())

    @Test fun `players named in the title`() {
        assertEquals(WatchMatcher.Hit.CONFIRMED, hit(final(), "Final: Davidovich – Jodar T2026 · Torneo de Shanghai"))
    }

    @Test fun `no players but the tournament final`() {
        assertEquals(WatchMatcher.Hit.CONFIRMED, hit(final(), "Final T2026 · Torneo de Shanghai"))
        assertEquals(WatchMatcher.Hit.CONFIRMED, hit(final(name = "Rolex Shanghai Masters"), "Final T2026 · Torneo de Shanghai"))
    }

    @Test fun `other finals and other rounds are not matched`() {
        assertEquals(WatchMatcher.Hit.NONE, hit(final(), "Final Masculina T2026 · Premier Padel"))
        assertEquals(WatchMatcher.Hit.NONE, hit(final(), "Semifinal T2026 · Torneo de Shanghai"))
        assertEquals(WatchMatcher.Hit.NONE, hit(final(subtitle = "Men's Singles · Round of 16"), "Final T2026 · Torneo de Shanghai"))
        assertEquals(WatchMatcher.Hit.NONE, hit(final(subtitle = "Men's Doubles · Final"), "Final T2026 · Torneo de Shanghai"))
    }

    @Test fun `first round named by the tournament alone`() {
        val round1 = final(subtitle = "Men's Singles · Round 1", name = "Shanghai Masters")
        assertEquals(WatchMatcher.Hit.CONFIRMED, hit(round1, "TBC T2026 · Torneo de Shanghai"))
        assertEquals(WatchMatcher.Hit.NONE, hit(round1, "Semifinal T2026 · Torneo de Shanghai"))
        assertEquals(WatchMatcher.Hit.NONE, hit(round1, "Final T2026 · Torneo de Shanghai"))
        assertEquals(WatchMatcher.Hit.NONE, hit(round1, "TBC T2026 · Torneo de Madrid"))
    }

    @Test fun `a tournament named for its sponsor is found by its city`() {
        val vienna = final(subtitle = "Men's Singles · Round 1", name = "Erste Bank Open")
        assertEquals(WatchMatcher.Hit.CONFIRMED, hit(vienna, "TBC T2026 · Torneo de Viena"))
        assertEquals(WatchMatcher.Hit.CONFIRMED, hit(vienna, "Vienna Open Tennis"))
        assertEquals(WatchMatcher.Hit.NONE, hit(vienna, "TBC T2026 · Torneo de Basilea"))
    }
}
