package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PanelIdentityTest {

    private val url = "http://Panel.example:8080/live/u/p/1.ts"

    @Before
    fun clean() = PanelIdentity.clearForTest()

    @Test
    fun `the list ends so a refused channel cannot retry forever`() {
        val second = PanelIdentity.next(HttpClient.DEFAULT_USER_AGENT)
        assertEquals(HttpClient.FALLBACK_USER_AGENT, second)
        assertEquals(HttpClient.COMPATIBLE_USER_AGENT, PanelIdentity.next(second!!))
        assertNull(PanelIdentity.next(HttpClient.COMPATIBLE_USER_AGENT))
        assertNull(PanelIdentity.next("something the user typed"))
    }

    @Test
    fun `a proven identity applies to the whole panel until it is forgotten`() {
        assertEquals(HttpClient.DEFAULT_USER_AGENT, PanelIdentity.startFor(url))
        PanelIdentity.remember(url, HttpClient.COMPATIBLE_USER_AGENT)
        assertEquals(HttpClient.COMPATIBLE_USER_AGENT, PanelIdentity.startFor("http://panel.example:8080/movie/u/p/2.mkv"))
        assertEquals(HttpClient.DEFAULT_USER_AGENT, PanelIdentity.startFor("http://other.example/live/u/p/1.ts"))
        PanelIdentity.forget(url)
        assertEquals(HttpClient.DEFAULT_USER_AGENT, PanelIdentity.startFor(url))
    }

    @Test
    fun `an identity outside the list is never saved`() {
        PanelIdentity.remember(url, "something the user typed")
        assertEquals(HttpClient.DEFAULT_USER_AGENT, PanelIdentity.startFor(url))
    }
}
