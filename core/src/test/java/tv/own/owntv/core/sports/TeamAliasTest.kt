package tv.own.owntv.core.sports

import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Team

class TeamAliasTest {
    private fun found(team: String, title: String) =
        TeamNames.keyOf(Team("1", team)).foundIn(TeamNames.tokens(title).toSet())

    @Test fun `spanish nicknames find their clubs`() {
        assertTrue(found("Barcelona", "Barça - Getafe"))
        assertTrue(found("Atlético Madrid", "Atleti - Real Sociedad"))
        assertTrue(found("Real Betis", "Betis – Sevilla"))
        assertTrue(found("Athletic Club", "Athletic - Valencia"))
        assertTrue(found("Celta Vigo", "Celta – Osasuna"))
        assertTrue(found("Manchester United", "Man Utd v Chelsea"))
    }
}
