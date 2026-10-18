package tv.own.owntv.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitRulesTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day

    private fun v(ch: Long, title: String, startDay: Int, ago: Long = 0L, profile: Long = 1L) =
        HabitRules.Viewing(profile, ch, "Ch$ch", "e$ch", title, HabitRules.titleKey(title), startDay * day, now - ago)

    @Test fun titleKeyStripsEpisodeNoise() {
        assertEquals(HabitRules.titleKey("Telediario 2"), HabitRules.titleKey("Telediario 2 (T3 E5)"))
        assertEquals("el hormiguero", HabitRules.titleKey("El Hormiguero  - Ep. 120"))
        assertEquals("", HabitRules.titleKey("--"))
    }

    @Test fun threeDifferentDaysMakeAHabit() {
        val log = listOf(v(1, "Telediario", 90), v(1, "Telediario", 91), v(1, "Telediario", 92))
        val habits = HabitRules.habits(log, 1L, now)
        assertEquals(1, habits.size)
        assertEquals(3, habits[0].count)
        assertTrue(HabitRules.isHabit(habits, 1, "Telediario (T1 E9)"))
        assertFalse(HabitRules.isHabit(habits, 2, "Telediario"))
    }

    @Test fun sameShowingTwiceOrTooOldDoesNotCount() {
        val twice = listOf(v(1, "X Show", 90), v(1, "X Show", 90), v(1, "X Show", 91))
        assertTrue(HabitRules.habits(twice, 1L, now).isEmpty())
        val old = listOf(v(1, "Old Show", 10, 40 * day), v(1, "Old Show", 11, 39 * day), v(1, "Old Show", 12, 38 * day))
        assertTrue(HabitRules.habits(old, 1L, now).isEmpty())
    }

    @Test fun otherProfileIsIgnored() {
        val log = listOf(v(1, "Show", 1, profile = 2), v(1, "Show", 2, profile = 2), v(1, "Show", 3, profile = 2))
        assertTrue(HabitRules.habits(log, 1L, now).isEmpty())
    }

    @Test fun encodeDecodeRoundTrips() {
        val log = listOf(v(1, "Tab\tand\nline", 90), v(2, "Other", 91))
        val back = HabitRules.decode(HabitRules.encode(log))
        assertEquals(2, back.size)
        assertEquals("Tab and line", back[0].title)
        assertEquals(91 * day, back[1].startMs)
    }
}
