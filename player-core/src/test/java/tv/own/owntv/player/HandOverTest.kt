package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the live ladder learns from a hand-over is decided by its status, never by the log sentence. */
class HandOverTest {

    @Test fun `a refusal named in the engine's own error is a refusal`() {
        val h = HandOver.engineError("ExoPlayer error before first frame", "Response code: 458")
        assertEquals(458, h.httpStatus)
        assertTrue(h.refused)
        assertEquals("ExoPlayer error before first frame: Response code: 458", h.text)
    }

    @Test fun `mpv's raw failure text is read the same way`() {
        val detail = PlaybackFailure.Raw("http: HTTP error 403 Forbidden").toString()
        assertTrue(HandOver.engineError("mpv couldn't play it", detail).refused)
    }

    @Test fun `a missing stream is a format failure, not a refusal`() {
        val h = HandOver.engineError("ExoPlayer gave up mid-stream", "HTTP 404")
        assertEquals(404, h.httpStatus)
        assertFalse(h.refused)
    }

    @Test fun `a number in the sentence itself is never read as a status`() {
        val h = HandOver("played, then stalled for status 403 without recovering")
        assertNull(h.httpStatus)
        assertFalse(h.refused)
    }

    @Test fun `no detail means no status`() {
        val h = HandOver.engineError("mpv couldn't play it", null)
        assertNull(h.httpStatus)
        assertEquals("mpv couldn't play it: null", h.text)
    }
}
