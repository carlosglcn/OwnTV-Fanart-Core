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
    }
}
