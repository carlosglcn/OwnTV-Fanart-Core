package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.player.EnginePreference

/** The panel-wide lessons kept across app starts: their stored form, expiry, loading and forgetting. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderLessonsTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private val url = "http://panel.invalid:8080/live/u/p/1.ts"
    private val host = "panel.invalid:8080"

    @Before fun clean() = LiveStreamQuirks.clearForTest()
    @After fun tidy() = LiveStreamQuirks.clearForTest()

    // --- the stored form ---------------------------------------------------------------------------

    @Test
    fun `lessons survive the stored form`() {
        val lessons = mapOf(
            ProviderLessons.Lesson(ProviderLessons.Kind.SINGLE_SESSION, host) to now - day,
            ProviderLessons.Lesson(ProviderLessons.Kind.UNTRUSTED_CERTIFICATE, "other.invalid") to now,
        )
        assertEquals(lessons, ProviderLessons.decode(ProviderLessons.encode(lessons), now))
    }

    @Test
    fun `a lesson expires seven days after it was learned`() {
        val text = "SINGLE_SESSION\t$host\t${now - 7 * day + 1}\nHLS_REDIRECT\t$host\t${now - 7 * day}"
        val kept = ProviderLessons.decode(text, now)
        assertEquals(setOf(ProviderLessons.Kind.SINGLE_SESSION), kept.keys.map { it.kind }.toSet())
    }

    @Test
    fun `a line this build cannot read is skipped`() {
        val text = "NO_SUCH_KIND\t$host\t$now\nSINGLE_SESSION\t$host\nDASH_REDIRECT\t\t$now\nREFUSES_SEGMENTS\t$host\tx\nBLOCKS_DEFAULT_UA\t$host\t$now"
        assertEquals(
            mapOf(ProviderLessons.Lesson(ProviderLessons.Kind.BLOCKS_DEFAULT_UA, host) to now),
            ProviderLessons.decode(text, now),
        )
    }

    // --- loading and writing ------------------------------------------------------------------------

    @Test
    fun `stored lessons are in place once the load is done`() = runTest {
        LiveStreamQuirks.clock = { now }
        val stored = CompletableDeferred<String>()
        LiveStreamQuirks.startPersisting(backgroundScope, read = { stored.await() }, write = {})
        val waiting = backgroundScope.async { LiveStreamQuirks.awaitPersisted() }
        runCurrent()
        assertFalse("a tune waits while the file is read", waiting.isCompleted)
        stored.complete("SINGLE_SESSION\t$host\t${now - day}\nUNTRUSTED_CERTIFICATE\t$host\t$now")
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertTrue(LiveStreamQuirks.isSingleSession(url))
        assertTrue(LiveStreamQuirks.distrustsCertificate(url))
    }

    @Test
    fun `an expired lesson is not loaded`() = runTest {
        LiveStreamQuirks.clock = { now }
        LiveStreamQuirks.startPersisting(backgroundScope, read = { "SINGLE_SESSION\t$host\t${now - 8 * day}" }, write = {})
        runCurrent()
        assertFalse(LiveStreamQuirks.isSingleSession(url))
    }

    @Test
    fun `a lesson learned during the load is kept with the stored ones`() = runTest {
        LiveStreamQuirks.clock = { now }
        val stored = CompletableDeferred<String>()
        val writes = mutableListOf<String>()
        LiveStreamQuirks.startPersisting(backgroundScope, read = { stored.await() }, write = { writes += it })
        LiveStreamQuirks.rememberSegmentRefusal(url)
        runCurrent()
        assertTrue("nothing is written before the load", writes.isEmpty())
        stored.complete("SINGLE_SESSION\t$host\t$now")
        runCurrent()
        val written = ProviderLessons.decode(writes.last(), now).keys.map { it.kind }.toSet()
        assertEquals(setOf(ProviderLessons.Kind.SINGLE_SESSION, ProviderLessons.Kind.REFUSES_SEGMENTS), written)
    }

    @Test
    fun `a new lesson is written, and one already known is not written again`() = runTest {
        val writes = mutableListOf<String>()
        LiveStreamQuirks.startPersisting(backgroundScope, read = { "" }, write = { writes += it })
        LiveStreamQuirks.rememberBlocksDefaultUserAgent(url)
        runCurrent()
        LiveStreamQuirks.rememberBlocksDefaultUserAgent(url)
        runCurrent()
        assertEquals(1, writes.size)
        assertTrue(writes.single().startsWith("BLOCKS_DEFAULT_UA\t$host\t"))
    }

    @Test
    fun `forgetting a panel's HLS redirect removes the kept copy too`() = runTest {
        LiveStreamQuirks.clock = { now }
        val writes = mutableListOf<String>()
        LiveStreamQuirks.startPersisting(backgroundScope, read = { "HLS_REDIRECT\t$host\t$now" }, write = { writes += it })
        runCurrent()
        assertTrue(LiveStreamQuirks.isKnownHls(url))
        LiveStreamQuirks.forgetHlsRedirect(url)
        runCurrent()
        assertFalse(LiveStreamQuirks.isKnownHls(url))
        assertEquals("", writes.last())
    }

    @Test
    fun `per-stream lessons are never stored`() = runTest {
        val writes = mutableListOf<String>()
        LiveStreamQuirks.startPersisting(backgroundScope, read = { "" }, write = { writes += it })
        LiveStreamQuirks.rememberNoHlsVariant(url)
        LiveStreamQuirks.rememberPrerollDefeated(url)
        LiveStreamQuirks.rememberArchiveNeedsSoftware(url)
        runCurrent()
        assertTrue(writes.isEmpty())
    }

    // --- the certificate lesson ---------------------------------------------------------------------

    @Test
    fun `only a refused certificate counts, not any TLS failure`() {
        val refused = javax.net.ssl.SSLHandshakeException("handshake failed").apply {
            initCause(java.security.cert.CertificateException("Trust anchor for certification path not found."))
        }
        assertTrue(LiveStreamQuirks.isUntrustedCertificate(RuntimeException("source error", refused)))
        assertTrue(LiveStreamQuirks.isUntrustedCertificate(javax.net.ssl.SSLPeerUnverifiedException("Hostname not verified")))
        assertFalse(LiveStreamQuirks.isUntrustedCertificate(javax.net.ssl.SSLHandshakeException("Connection reset by peer")))
        assertFalse(LiveStreamQuirks.isUntrustedCertificate(java.io.IOException("timeout")))
    }

    @Test
    fun `an untrusted certificate starts the panel on mpv, unless the user chose otherwise`() {
        val routed = LiveRouting.decide(EnginePreference.EXO_FIRST, pin = null, drmProtected = false, panelRefusesSegments = false, untrustedCertificate = true)
        assertTrue(routed.onMpv)
        assertEquals(EnginePreference.MPV_FIRST, routed.preference)
        assertEquals("mpv (untrusted certificate)", routed.why)
        assertFalse(LiveRouting.decide(EnginePreference.EXO_ONLY, null, false, false, untrustedCertificate = true).onMpv)
        assertFalse(LiveRouting.decide(EnginePreference.EXO_FIRST, pin = false, drmProtected = false, panelRefusesSegments = false, untrustedCertificate = true).onMpv)
    }
}
