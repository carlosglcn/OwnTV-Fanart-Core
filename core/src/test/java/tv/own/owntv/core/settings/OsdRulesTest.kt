package tv.own.owntv.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsdRulesTest {
    @Test fun standardKeepsTheOldTimings() {
        assertEquals(4_500L, OsdRules.controlsMs(0))
        assertEquals(3_000L, OsdRules.bannerMs(0))
    }

    @Test fun secondsAndUntilBack() {
        assertEquals(12_000L, OsdRules.controlsMs(12))
        assertEquals(8_000L, OsdRules.bannerMs(12))
        assertNull(OsdRules.controlsMs(OsdRules.UNTIL_BACK))
        assertEquals(20_000L, OsdRules.bannerMs(OsdRules.UNTIL_BACK))
    }

    @Test fun cyclesThroughEveryChoiceAndWraps() {
        var v = 0
        val seen = mutableListOf(v)
        repeat(OsdRules.CHOICES.size) { v = OsdRules.next(v); seen += v }
        assertEquals(listOf(0, 8, 12, 20, -1, 0), seen)
    }
}
