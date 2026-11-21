package tv.own.owntv.core.subtitles

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCharsetTest {

    private fun srt(line: (Int) -> String): String =
        (1..150).joinToString("\r\n\r\n") { i -> "$i\r\n00:00:${"%02d".format(i % 60)},000 --> 00:00:${"%02d".format(i % 60)},900\r\n${line(i)}" }

    /** A UTF-8 file whose only non-ASCII letter is a sparse "·" was read as GB18030: "·" became "路". */
    @Test
    fun aValidUtf8FileStaysUtf8EvenWithFewNonAsciiCharacters() {
        val text = srt { "FILE: Guarding Stars 2026  ·  line $it" }
        assertEquals(text, String(SubtitleCharset.toUtf8(text.toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
    }

    @Test
    fun aUtf8ByteOrderMarkIsDropped() {
        val text = srt { "Ça va · line $it" }
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, String(SubtitleCharset.toUtf8(withBom), Charsets.UTF_8))
    }

    @Test
    fun legacyEncodingsAreStillConvertedToUtf8() {
        val french = srt { "Ça été très difficile, numéro $it, déjà vu à la fenêtre" }
        assertEquals(french, String(SubtitleCharset.toUtf8(french.toByteArray(Charset.forName("windows-1252"))), Charsets.UTF_8))
        val russian = srt { "Привет, это строка номер $it из фильма" }
        assertEquals(russian, String(SubtitleCharset.toUtf8(russian.toByteArray(Charset.forName("windows-1251"))), Charsets.UTF_8))
    }
}
