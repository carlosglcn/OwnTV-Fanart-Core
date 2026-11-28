package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity

class EpisodeStillsTest {

    private fun ep(n: Int, image: String?) =
        EpisodeEntity(seriesId = 1, seasonNumber = 1, episodeNumber = n, name = "", streamUrl = "u$n", imageUrl = image)

    @Test
    fun `pictures of their own are kept`() {
        val out = EpisodeStills.keepOwn(listOf(ep(1, "a"), ep(2, "b"), ep(3, null)))
        assertEquals(listOf("a", "b", null), out.map { it.imageUrl })
    }

    @Test
    fun `a picture every episode shares is no still`() {
        val out = EpisodeStills.keepOwn(listOf(ep(1, "poster"), ep(2, "poster"), ep(3, "poster")))
        assertEquals(listOf(null, null, null), out.map { it.imageUrl })
    }

    @Test
    fun `only the shared picture goes when some episodes have their own`() {
        val out = EpisodeStills.keepOwn(listOf(ep(1, "poster"), ep(2, "e2"), ep(3, "poster"), ep(4, "e4")))
        assertEquals(listOf(null, "e2", null, "e4"), out.map { it.imageUrl })
    }

    @Test
    fun `the show's own art is never an episode picture`() {
        val out = EpisodeStills.keepOwn(listOf(ep(1, "cover"), ep(2, "e2")), showArt = setOf("cover", "backdrop"))
        assertEquals(listOf(null, "e2"), out.map { it.imageUrl })
        assertEquals("e2", EpisodeStills.keepOwn(listOf(ep(1, "e2")), setOf("cover")).single().imageUrl)
    }

    @Test
    fun `only an http address counts as a picture url`() {
        assertEquals("https://x/y.jpg", EpisodeStills.urlOrNull("  https://x/y.jpg "))
        assertEquals("HTTP://x/y.jpg", EpisodeStills.urlOrNull("HTTP://x/y.jpg"))
        assertNull(EpisodeStills.urlOrNull(""))
        assertNull(EpisodeStills.urlOrNull("/images/y.jpg"))
        assertNull(EpisodeStills.urlOrNull("0"))
        assertNull(EpisodeStills.urlOrNull(null))
    }
}
