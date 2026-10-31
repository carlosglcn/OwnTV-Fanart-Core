package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeletextTest {

    @Test
    fun `the teletext descriptor lists only subtitle pages, magazine 0 read as 8`() {
        // tag 0x56, 10 bytes: "spa" initial page (type 1, mag 1, 00) and "spa" subtitles (type 2, mag 0 → 8, page 88).
        val d = byteArrayOf(
            0x56, 10,
            's'.code.toByte(), 'p'.code.toByte(), 'a'.code.toByte(), ((1 shl 3) or 1).toByte(), 0x00,
            's'.code.toByte(), 'p'.code.toByte(), 'a'.code.toByte(), ((2 shl 3) or 0).toByte(), 0x88.toByte(),
        )
        val pages = Teletext.subtitlePages(d)
        assertEquals(listOf(Teletext.Page("spa", 8, 0x88, false)), pages)
    }

    @Test
    fun `no teletext descriptor, no pages`() {
        assertTrue(Teletext.subtitlePages(byteArrayOf(0x0A, 4, 's'.code.toByte(), 'p'.code.toByte(), 'a'.code.toByte(), 0)).isEmpty())
    }

    @Test
    fun `hamming 8-4 nibbles decode from the bit-reversed bytes DVB sends`() {
        // Hamming 8/4 code words for 0..15 (teletext order), as in EN 300 706 §8.2.
        val words = intArrayOf(0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F, 0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA)
        words.forEachIndexed { n, w ->
            val sent = Integer.reverse(w).ushr(24).toByte() // as carried in the PES
            assertEquals(n, Teletext.unham(Teletext.rev(sent)))
        }
    }

    @Test
    fun `Spanish pages get the Spanish national characters`() {
        val es = Teletext.subsetFor("spa")
        assertEquals('ñ', Teletext.charFor(0x7C, es))
        assertEquals('¿', Teletext.charFor(0x60, es))
        assertEquals('á', Teletext.charFor(0x5B, es))
        assertEquals('A', Teletext.charFor(0x41, es))
    }
}
