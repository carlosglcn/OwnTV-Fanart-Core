package tv.own.owntv.core.database.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class NameKeyTest {

    @Test
    fun foldsEveryAlphabetNotOnlyAsciiLetters() {
        assertEquals("первый канал", nameKeyOf("Первый Канал"))
        assertEquals("один дома", nameKeyOf("ОДИН ДОМА"))
        assertEquals("ἰλιάς", nameKeyOf("Ἰλιάς"))
        assertEquals("zdfneo", nameKeyOf("ZDFneo"))
    }

    @Test
    fun aToZByKeyIgnoresLetterCase() {
        val names = listOf("ZDFneo", "arte", "Первый", "BBC One", "ард")
        assertEquals(listOf("arte", "BBC One", "ZDFneo", "ард", "Первый"), names.sortedBy(::nameKeyOf))
    }

    /** "Clean channel names" renames rows with `copy(name = …)` before they are written; the key must follow. */
    @Test
    fun copyWithANewNameRecomputesTheKey() {
        val raw = ChannelEntity(sourceId = 1, name = "DE| Das Erste HD", streamUrl = "http://x/1.ts")
        val cleaned = raw.copy(name = "Das Erste", providerName = raw.name)
        assertEquals("de| das erste hd", raw.nameKey)
        assertEquals("das erste", cleaned.nameKey)
        assertEquals("один", MovieEntity(sourceId = 1, name = "x", streamUrl = "u").copy(name = "Один").nameKey)
        assertEquals("один", SeriesEntity(sourceId = 1, name = "x").copy(name = "ОДИН").nameKey)
    }

    /** The key is derived, so a database written before it existed must hash every row the same as before. */
    @Test
    fun theKeyIsNotPartOfTheContentHash() {
        val a = MovieEntity(sourceId = 1, name = "Один", streamUrl = "u")
        val b = a.copy().also { it.nameKey = "something else" }
        assertEquals(a.computeContentHash(), b.computeContentHash())
    }
}
