package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.sports.model.Sport

class SportsCatalogTest {

    @Test
    fun `every league key is unique and resolvable`() {
        val keys = SportsCatalog.leagues.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertEquals(it, SportsCatalog.league(it)!!.key) }
    }

    @Test
    fun `sports come in the hub's chip order`() {
        assertEquals(
            listOf(Sport.SOCCER, Sport.BASKETBALL, Sport.TENNIS, Sport.CRICKET, Sport.RACING, Sport.HOCKEY, Sport.FOOTBALL, Sport.MMA),
            SportsCatalog.sports.take(8),
        )
    }
}
