package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider lessons belong to the core version that wrote them. */
class ProviderLessonStoreTest {

    @Test
    fun `the same core version reads its lessons`() {
        assertEquals("lessons", ProviderLessonStore.textFor("lessons", "1.0.72", "1.0.72"))
    }

    @Test
    fun `a new core version starts with none`() {
        assertEquals("", ProviderLessonStore.textFor("lessons", "1.0.71", "1.0.72"))
        assertEquals("", ProviderLessonStore.textFor("lessons", null, "1.0.72"))
    }

    @Test
    fun `nothing stored reads as nothing`() {
        assertEquals("", ProviderLessonStore.textFor(null, "1.0.72", "1.0.72"))
    }
}
