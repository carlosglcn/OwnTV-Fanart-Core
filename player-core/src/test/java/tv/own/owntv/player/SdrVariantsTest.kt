package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which HLS variants "Prefer SDR" keeps: the SDR ones, and only when the stream offers both. */
class SdrVariantsTest {

    private val mixed = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=20000000,CODECS="hvc1.2.4.L153",RESOLUTION=3840x2160,VIDEO-RANGE=PQ,AUDIO="aac"
        hdr/2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=18000000,CODECS="hvc1.2.4.L153",RESOLUTION=3840x2160,VIDEO-RANGE=HLG,AUDIO="aac"
        hlg/2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=12000000,CODECS="hvc1.1.6.L150",RESOLUTION=3840x2160,VIDEO-RANGE=SDR,AUDIO="aac"
        sdr/2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028",RESOLUTION=1920x1080,AUDIO="aac"
        sdr/1080.m3u8
    """.trimIndent()

    @Test
    fun `a stream with both keeps its SDR variants, unlabelled ones included`() {
        assertEquals(setOf("sdr/2160.m3u8", "sdr/1080.m3u8"), SdrVariants.sdrUris(mixed))
    }

    @Test
    fun `an HDR-only stream is left alone`() {
        val hdrOnly = mixed.lines().filterNot { it.startsWith("sdr/") || it.contains("VIDEO-RANGE=SDR") || it.contains("avc1") }
            .joinToString("\n")
        assertNull(SdrVariants.sdrUris(hdrOnly))
    }

    @Test
    fun `an SDR-only stream is left alone`() {
        val sdrOnly = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,VIDEO-RANGE=SDR\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nb.m3u8\n"
        assertNull(SdrVariants.sdrUris(sdrOnly))
    }

    @Test
    fun `a media playlist is left alone`() {
        assertNull(SdrVariants.sdrUris("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"))
    }

    // --- mpv: its video tracks carry the variant's BANDWIDTH as `hls-bitrate` ---------------------

    @Test
    fun `mpv's SDR tracks are the ones whose bitrate is an SDR variant's`() {
        val tracks = mapOf(1 to 20_000_000L, 2 to 18_000_000L, 3 to 12_000_000L, 4 to 5_000_000L)
        assertEquals(setOf(3, 4), SdrVariants.sdrTrackIds(mixed, tracks))
    }

    @Test
    fun `AVERAGE-BANDWIDTH is not read as the variant's bandwidth`() {
        val text = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=7,BANDWIDTH=9,VIDEO-RANGE=PQ\nh.m3u8\n" +
            "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9,BANDWIDTH=3,VIDEO-RANGE=SDR\ns.m3u8\n"
        assertEquals(setOf(2), SdrVariants.sdrTrackIds(text, mapOf(1 to 9L, 2 to 3L)))
    }

    @Test
    fun `no match, or a stream with one kind, leaves mpv's tracks alone`() {
        assertNull(SdrVariants.sdrTrackIds(mixed, mapOf(1 to 1L, 2 to 2L)))
        assertNull(SdrVariants.sdrTrackIds("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n", mapOf(1 to 1L)))
    }

    @Test
    fun `comments and blank lines between the tag and its address are skipped`() {
        val text = "#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=9,VIDEO-RANGE=PQ\r\n\r\n#EXT-X-SOMETHING\r\nh.m3u8\r\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3,VIDEO-RANGE=SDR\r\ns.m3u8\r\n"
        assertEquals(setOf("s.m3u8"), SdrVariants.sdrUris(text))
    }
}
