package tv.own.owntv.player

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Prefer SDR" on mpv, end to end: a master playlist with HDR and SDR variants, mpv listing them as video
 * tracks with their bandwidth, and the player moving the picture to the SDR one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MpvPreferSdrTest {
    private lateinit var h: MpvHarness
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = AtomicInteger()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                requests.incrementAndGet()
                val body = MASTER.toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        url = "http://127.0.0.1:${server.address.port}/live/master.m3u8"
        h = MpvHarness()
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun preferSdrMovesThePictureToTheSdrVariant() {
        prefer(true)
        h.playLive(url)
        openVariants()
        waitFor { h.core.props["vid"] != null }
        assertEquals("the 2160p SDR variant", 2, h.core.props["vid"])
    }

    @Test
    fun withPreferSdrOffNothingIsFetchedAndMpvKeepsItsChoice() {
        prefer(false)
        h.playLive(url)
        openVariants()
        h.advance(1_000)
        assertEquals(0, requests.get())
        assertNull(h.core.props["vid"])
    }

    private fun prefer(on: Boolean) {
        runBlocking { h.settings.setPreferSdr(on) }
        h.settle()
    }

    /** mpv opened the master: HDR 2160 (id 1), SDR 2160 (id 2), SDR 1080 (id 3), then the audio. */
    private fun openVariants() {
        val p = h.core.props
        p["track-list/count"] = 4
        listOf(Triple(1, 2160, 20_000_000), Triple(2, 2160, 12_000_000), Triple(3, 1080, 5_000_000)).forEachIndexed { i, (id, height, bw) ->
            p["track-list/$i/type"] = "video"; p["track-list/$i/id"] = id
            p["track-list/$i/demux-h"] = height; p["track-list/$i/hls-bitrate"] = bw.toString()
        }
        p["track-list/3/type"] = "audio"; p["track-list/3/id"] = 1
        h.core.fileLoaded()
        h.settle()
    }

    /** The playlist is read on an IO thread: let real time pass while the main looper runs. */
    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < until) h.settle()
    }

    private companion object {
        val MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160,VIDEO-RANGE=PQ
            hdr/2160.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=11000000,BANDWIDTH=12000000,RESOLUTION=3840x2160,VIDEO-RANGE=SDR
            sdr/2160.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            sdr/1080.m3u8
        """.trimIndent()
    }
}
