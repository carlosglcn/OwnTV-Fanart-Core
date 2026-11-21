package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDropCheckTest {
    @Test
    fun aFifthOfTheFramesLostIsTooMany() {
        // 25 fps for 20 s is 500 frames; the copy path on an Android 11 TV lost about 107 of them.
        assertTrue(ArchiveDropCheck.tooMany(dropped = 107, fps = 25.0, windowMs = 20_000))
    }

    @Test
    fun aTenthOrLessIsNot() {
        assertFalse(ArchiveDropCheck.tooMany(dropped = 50, fps = 25.0, windowMs = 20_000))
        assertFalse(ArchiveDropCheck.tooMany(dropped = 3, fps = 50.0, windowMs = 20_000))
    }

    @Test
    fun aFewDropsInAShortWindowAreNeverEnough() {
        assertFalse(ArchiveDropCheck.tooMany(dropped = 9, fps = 25.0, windowMs = 1_000))
    }

    @Test
    fun anUnknownFrameRateCountsAs25() {
        assertTrue(ArchiveDropCheck.tooMany(dropped = 51, fps = Double.NaN, windowMs = 20_000))
        assertFalse(ArchiveDropCheck.tooMany(dropped = 50, fps = 0.0, windowMs = 20_000))
    }

    @Test
    fun eachDecodePathHasItsOwnAnswer() {
        assertEquals(ArchiveDropCheck.Action.DIRECT, ArchiveDropCheck.action("mediacodec-copy", triedDirect = false))
        assertEquals(ArchiveDropCheck.Action.NONE, ArchiveDropCheck.action("mediacodec-copy", triedDirect = true))
        assertEquals(ArchiveDropCheck.Action.EXOPLAYER, ArchiveDropCheck.action("no", triedDirect = false))
        assertEquals(ArchiveDropCheck.Action.NONE, ArchiveDropCheck.action("mediacodec", triedDirect = false))
        assertEquals(ArchiveDropCheck.Action.NONE, ArchiveDropCheck.action(null, triedDirect = false))
    }
}
