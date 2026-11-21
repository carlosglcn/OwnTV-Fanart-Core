package tv.own.owntv.player

import dev.jdtech.mpv.MPVLib.MpvEndFileReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mpv engine driven end to end through [MpvCore]: what [OwnTVPlayer] does with mpv's END_FILE —
 * the stop credits that tell our own stops from failures, the live reconnect budget, and the film
 * paths (mid-stream reload, hand-over to ExoPlayer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MpvEngineTest {
    private lateinit var h: MpvHarness

    @Before
    fun setUp() {
        h = MpvHarness()
    }

    // --- END_FILE classification ------------------------------------------------------------------

    @Test
    fun zapEndFileIsAbsorbedByItsStopCredit() {
        h.playLive(A)
        h.opened()
        h.playLive(B)
        assertEquals(listOf(A, B), h.core.loads)
        // The old channel's END_FILE arrives after the new loadfile, even reading as an error: the
        // credit the loadfile took absorbs it.
        h.core.endFile(MpvEndFileReason.ERROR, -13)
        h.opened()
        h.advance(QUIET_MS)
        assertEquals(listOf(A, B), h.core.loads)
        assertNull(h.player.error.value)
    }

    @Test
    fun stopReasonWithNoCreditIsNotAFailure() {
        h.playLive(A)
        h.opened()
        h.core.endFile(MpvEndFileReason.STOP)
        h.advance(QUIET_MS)
        assertEquals(listOf(A), h.core.loads)
        assertNull(h.player.error.value)
    }

    @Test
    fun fileLoadedOfThePreviousChannelReachingMainAfterAZapIsNotTheNewOnes() {
        h.playLive(A)
        // A's FILE_LOADED leaves mpv's event thread; before the main thread gets to it, the user zaps.
        h.core.props["track-list/count"] = 1
        h.core.props["track-list/0/type"] = "audio"; h.core.props["track-list/0/id"] = 1
        h.core.fileLoaded()
        h.player.play(B, isLive = true)
        h.settle()
        assertEquals("B has listed no track of its own yet", 0, h.player.audioCount.value)
        assertEquals(listOf(A, B), h.core.loads)
    }

    // --- live reconnect budget ----------------------------------------------------------------------

    @Test
    fun liveDroppingMidPlayReconnectsSixTimesThenShowsLostConnection() {
        h.playLive(A)
        h.opened()
        repeat(OwnTVPlayer.MAX_LIVE_RECONNECTS) { attempt ->
            h.core.endFile(MpvEndFileReason.EOF)
            h.settle() // handled on the main thread
            assertTrue(h.player.buffering.value)
            h.advance(OwnTVPlayer.LIVE_RECONNECT_DELAY_MS)
            assertEquals("reconnect ${attempt + 1}", attempt + 2, h.core.loads.size)
            h.opened(positionSecs = 2L + attempt)
        }
        h.core.endFile(MpvEndFileReason.EOF)
        h.advance(OwnTVPlayer.LIVE_RECONNECT_DELAY_MS * 2)
        assertEquals(1 + OwnTVPlayer.MAX_LIVE_RECONNECTS, h.core.loads.size)
        assertEquals(PlaybackFailure.LostConnection, h.player.error.value)
        assertFalse(h.player.buffering.value)
    }

    // --- films ----------------------------------------------------------------------------------------

    @Test
    fun filmCutMidStreamReloadsOnceThenShowsLostConnection() {
        h.playFilm(F1)
        h.opened(positionSecs = 600, durationSecs = 6_000)
        h.core.endFile(MpvEndFileReason.EOF)
        h.advance(OwnTVPlayer.DECODER_RELEASE_MS + 100)
        assertEquals(listOf(F1, F1), h.core.loads)
        h.opened(positionSecs = 601, durationSecs = 6_000)
        h.core.endFile(MpvEndFileReason.EOF)
        h.advance(OwnTVPlayer.DECODER_RELEASE_MS + 100)
        assertEquals(listOf(F1, F1), h.core.loads)
        assertEquals(PlaybackFailure.LostConnection, h.player.error.value)
    }

    @Test
    fun filmMpvCannotOpenGoesToExoPlayer() {
        h.playFilm(F1)
        val first = h.core
        val resets = h.player.surfaceResetToken.value
        // The demuxer refused the file: END_FILE before FILE_LOADED.
        first.endFile(MpvEndFileReason.ERROR, -13)
        h.advance(OwnTVPlayer.CORE_RESET_SETTLE_MS + 100)
        assertTrue("the stuck core is destroyed", first.destroyed)
        // One surface recreate from the reset, one that arms the ExoPlayer start on the fresh surface.
        assertEquals(resets + 2, h.player.surfaceResetToken.value)
        assertNull(h.player.error.value)
    }

    @Test
    fun anotherFilmPickedDuringTheHandOverPlaysAlone() {
        h.playFilm(F1)
        val resets = h.player.surfaceResetToken.value
        h.core.endFile(MpvEndFileReason.ERROR, -13)
        h.settle()
        // Inside the reset's settle wait, the user picks another film.
        h.playFilm(F2)
        h.advance(OwnTVPlayer.CORE_RESET_SETTLE_MS + 100)
        h.recreateSurface()
        assertEquals("a fresh core opens the new film", listOf(F2), h.core.loads)
        // Only the reset's own recreate: the ExoPlayer start for the first film was dropped.
        assertEquals(resets + 1, h.player.surfaceResetToken.value)
    }

    @Test
    fun aCatchUpAfterAHardResetStillDecodesInHardware() {
        h.player.play(C1, isArchive = true)
        h.settle()
        assertEquals("an archive opens on the copy path", "mediacodec-copy", h.core.props["hwdec"])
        // A dead programme (HTTP 404): END_FILE before FILE_LOADED, and mpv is rebuilt.
        h.core.endFile(MpvEndFileReason.ERROR, -13)
        h.settle()
        // The next programme picked inside the reset, as from the catch-up list.
        h.player.play(C2, isArchive = true)
        h.advance(OwnTVPlayer.CORE_RESET_SETTLE_MS + 100)
        h.recreateSurface()
        assertEquals(listOf(C2), h.core.loads)
        assertEquals("the rebuilt core keeps the hardware decoder", "mediacodec-copy", h.core.props["hwdec"])
    }

    @Test
    fun aCatchUpAskedForAsHlsThatWillNotOpenGoesBackToTs() {
        h.player.play(H1, isArchive = true)
        h.settle()
        assertEquals(listOf(H1), h.core.loads)
        // The timeshift server has no HLS for this programme: END_FILE before FILE_LOADED.
        val first = h.core
        h.core.endFile(MpvEndFileReason.ERROR, -13)
        h.advance(OwnTVPlayer.FALLBACK_RETRY_DELAY_MS + 100)
        assertFalse("mpv answered, so it is not rebuilt", first.destroyed)
        assertEquals("the same programme as .ts", listOf(H1, H1.removeSuffix(".m3u8") + ".ts"), h.core.loads)
    }

    @Test
    fun aCatchUpAsHlsIsGivenTimeToOpenThenAskedForAsTs() {
        h.player.play(H1, isArchive = true)
        h.settle()
        // The timeshift server is still building a long programme's playlist: no FILE_LOADED, no END_FILE.
        h.advance(22_000)
        val cores = h.cores.size
        assertEquals("past the usual ten seconds, still waiting on the playlist", listOf(H1), h.core.loads)
        assertEquals("and mpv was not rebuilt meanwhile", cores, h.cores.size)
        h.advance(FilmWatchdog.ARCHIVE_HLS_OPEN_TIMEOUT_MS - 22_000 + 2_000)
        h.advance(OwnTVPlayer.CORE_RESET_SETTLE_MS + 100)
        h.recreateSurface()
        assertEquals("a fresh core asks for the programme as .ts", listOf(H1.removeSuffix(".m3u8") + ".ts"), h.core.loads)
    }

    @Test
    fun theReportSaysWhichDecoderACatchUpUsed() {
        h.player.play(C1, isArchive = true)
        h.settle()
        h.core.props["video-codec"] = "h264"
        h.core.props["vo"] = "gpu"
        h.opened(positionSecs = 0)
        h.core.property("hwdec-current", "mediacodec-copy")
        h.advance(OwnTVPlayer.DECODE_CHECK_MS + 500)
        val report = LiveDiagnosticsLog.snapshot()
        assertTrue(report, report.contains("mpv decoder: hwdec-current='mediacodec-copy'"))
        assertTrue(report, report.contains("mpv decode check: hwdec-current="))
        assertTrue(report, report.contains("vo=gpu direct=false archive=true"))
    }

    @Test
    fun aCatchUpLosingFramesOnTheCopyPathReopensOnceOnTheDirectPath() {
        h.player.play(C1, isArchive = true)
        h.settle()
        h.opened(positionSecs = 0)
        h.core.props["hwdec-current"] = "mediacodec-copy"
        h.core.props["container-fps"] = 25.0
        // Five frames in every twenty-five lost, as the copy path did on an Android 11 TV.
        playFor(seconds = 40, dropsPerSecond = 5) { h.core.loads.size > 1 }
        assertEquals("reopened once, from the start", listOf(C1, C1), h.core.loads)
        assertEquals("on the direct hardware path", "mediacodec", h.core.props["hwdec"])
        assertEquals("mediacodec_embed", h.core.props["vo"])
        // Still losing frames there: nowhere better to go, so it stays.
        h.opened(positionSecs = 0)
        h.core.props["frame-drop-count"] = 0
        h.core.props["container-fps"] = 25.0
        playFor(seconds = 60, dropsPerSecond = 5)
        assertEquals(listOf(C1, C1), h.core.loads)
    }

    @Test
    fun aCatchUpLosingAFewFramesStaysWhereItIs() {
        h.player.play(C1, isArchive = true)
        h.settle()
        h.opened(positionSecs = 0)
        h.core.props["hwdec-current"] = "mediacodec-copy"
        h.core.props["container-fps"] = 50.0
        playFor(seconds = 60, dropsPerSecond = 1)
        assertEquals(listOf(C1), h.core.loads)
        assertEquals("mediacodec-copy", h.core.props["hwdec"])
    }

    @Test
    fun aCatchUpLosingFramesInSoftwareGoesToExoPlayer() {
        h.player.play(C1, isArchive = true)
        h.settle()
        h.opened(positionSecs = 0)
        h.core.props["hwdec-current"] = "no"
        h.core.props["container-fps"] = 25.0
        playFor(seconds = 30, dropsPerSecond = 5) { h.core.stops > 0 }
        assertEquals("mpv let go of the programme", 1, h.core.stops)
        assertTrue("and of the surface, for ExoPlayer", h.core.surfaceDetached)
    }

    /** The programme plays on for [seconds], mpv dropping [dropsPerSecond] frames a second, until [done]. */
    private fun playFor(seconds: Int, dropsPerSecond: Int, done: () -> Boolean = { false }) {
        val pos = (h.core.props["time-pos"] as? Number)?.toLong() ?: 0L
        val drops = (h.core.props["frame-drop-count"] as? Number)?.toInt() ?: 0
        for (s in 1..seconds) {
            h.core.props["frame-drop-count"] = drops + s * dropsPerSecond
            h.core.property("time-pos", pos + s)
            h.advance(1000)
            if (done()) return
        }
    }

    @Test
    fun filmMpvCanOnlySoftwareDecodeIn4kIsStoppedAndHandedToExoPlayer() {
        h.playFilm(F1)
        h.opened(positionSecs = 0)
        h.core.property("height", 2160L)
        // The decoder mpv ended up on is software: a TV cannot keep up with 4K that way.
        h.core.property("hwdec-current", "no")
        h.settle()
        assertEquals("mpv let go of the film", 1, h.core.stops)
        assertTrue("and of the surface, for ExoPlayer", h.core.surfaceDetached)
        assertNull(h.player.error.value)
        assertFalse("the core is kept — it was not stuck", h.core.destroyed)
    }

    private companion object {
        /** Past the EOF grace and the live reconnect delay, short of the frozen-picture watchdog. */
        const val QUIET_MS = 6_000L
        const val A = "http://panel.test/live/u/p/1.ts"
        const val B = "http://panel.test/live/u/p/2.ts"
        const val F1 = "http://panel.test/movie/u/p/10.mkv"
        const val F2 = "http://panel.test/movie/u/p/11.mkv"
        const val C1 = "http://panel.test/timeshift/u/p/30/2026-11-19:09-30/1.ts"
        const val C2 = "http://panel.test/timeshift/u/p/75/2026-11-19:10-30/2.ts"
        const val H1 = "http://panel.test/timeshift/u/p/30/2026-11-19:09-30/1.m3u8"
    }
}
