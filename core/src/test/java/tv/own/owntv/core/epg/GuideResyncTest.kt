package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test

/** After a guide match: which ids still need a download once the cached guide was read. */
class GuideResyncTest {

    private fun fetch(
        filled: Set<String> = emptySet(),
        withProgrammes: Set<String> = emptySet(),
        keptBefore: Set<String>? = emptySet(),
        keptAfter: Set<String>? = setOf("a"),
    ) = GuideResync.idsToFetch(setOf("a"), filled, withProgrammes, keptBefore, keptAfter)

    @Test
    fun `filled from the cache needs nothing`() {
        assertEquals(emptySet<String>(), fetch(filled = setOf("a")))
        assertEquals(emptySet<String>(), fetch(filled = setOf("a"), withProgrammes = emptySet(), keptBefore = emptySet()))
    }

    @Test
    fun `no cache and nothing stored is fetched`() {
        assertEquals(setOf("a"), fetch())
        // Whether or not a catch-up channel reads it.
        assertEquals(setOf("a"), fetch(keptAfter = emptySet()))
    }

    @Test
    fun `stored programmes of a channel without catch-up are enough`() {
        assertEquals(emptySet<String>(), fetch(withProgrammes = setOf("a"), keptAfter = emptySet()))
    }

    @Test
    fun `a catch-up channel that just gained the id fetches its pruned past`() {
        assertEquals(setOf("a"), fetch(withProgrammes = setOf("a"), keptBefore = emptySet(), keptAfter = setOf("a")))
    }

    @Test
    fun `an id whose past was already kept is not fetched again`() {
        assertEquals(emptySet<String>(), fetch(withProgrammes = setOf("a"), keptBefore = setOf("a"), keptAfter = setOf("a")))
    }

    @Test
    fun `an unknown kept set before the match pruned nothing`() {
        assertEquals(emptySet<String>(), fetch(withProgrammes = setOf("a"), keptBefore = null, keptAfter = setOf("a")))
    }

    @Test
    fun `an unknown kept set after the match assumes the past is needed`() {
        assertEquals(setOf("a"), fetch(withProgrammes = setOf("a"), keptBefore = emptySet(), keptAfter = null))
    }

    @Test
    fun `each id is decided on its own`() {
        val out = GuideResync.idsToFetch(
            ids = setOf("cached", "stored", "empty", "catchup"),
            filledFromCache = setOf("cached"),
            withProgrammes = setOf("stored", "catchup"),
            keptBefore = setOf("stored"),
            keptAfter = setOf("stored", "catchup"),
        )
        assertEquals(setOf("empty", "catchup"), out)
    }
}
