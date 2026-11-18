package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What mpv reconnects by itself when the network returns, and what it leaves alone. */
class NetworkBackResumeTest {

    @Test
    fun `a live channel that played and lost its connection is reconnected`() {
        assertTrue(OwnTVPlayer.resumesOnNetworkBack(isLive = true, hadPlayed = true, error = PlaybackFailure.NoInternet))
        assertTrue(OwnTVPlayer.resumesOnNetworkBack(isLive = true, hadPlayed = true, error = PlaybackFailure.LostConnection))
    }

    @Test
    fun `a channel that never opened is left on its error`() {
        assertFalse(OwnTVPlayer.resumesOnNetworkBack(isLive = true, hadPlayed = false, error = PlaybackFailure.NoInternet))
    }

    @Test
    fun `a film keeps its own resume`() {
        assertFalse(OwnTVPlayer.resumesOnNetworkBack(isLive = false, hadPlayed = true, error = PlaybackFailure.LostConnection))
    }

    @Test
    fun `a failure the network cannot fix, or none at all, is left alone`() {
        assertFalse(OwnTVPlayer.resumesOnNetworkBack(isLive = true, hadPlayed = true, error = PlaybackFailure.AudioNoVideo))
        assertFalse(OwnTVPlayer.resumesOnNetworkBack(isLive = true, hadPlayed = true, error = null))
    }
}
