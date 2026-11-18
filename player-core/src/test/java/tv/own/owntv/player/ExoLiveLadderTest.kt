package tv.own.owntv.player

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.StreamingHttpClient
import tv.own.owntv.core.player.PlaybackPrefsStore
import tv.own.owntv.core.settings.SettingsRepository
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live ExoPlayer engine's reconnect ladder, on a real ExoPlayer over Robolectric's codecs: a channel
 * that played and then lost its provider reconnects a bounded number of times, then shows the error.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExoLiveLadderTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val media = requireNotNull(javaClass.getResourceAsStream("/media/sample_h264.ts")).readBytes()
    private val serving = AtomicBoolean(true)
    private val requests = AtomicInteger()
    private lateinit var server: HttpServer
    private lateinit var url: String
    private lateinit var engine: LivePreviewEngine
    private var builds = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                requests.incrementAndGet()
                if (serving.get()) {
                    ex.responseHeaders.add("Content-Type", "video/mp2t")
                    ex.sendResponseHeaders(200, media.size.toLong())
                    ex.responseBody.use { it.write(media) }
                } else {
                    ex.sendResponseHeaders(503, -1)
                    ex.close()
                }
            }
            start()
        }
        url = "http://127.0.0.1:${server.address.port}/live/u/p/1.ts"
        val app = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(app, LocaleStore.from(app))
        engine = LivePreviewEngine(
            app, StreamingHttpClient(OkHttpClient()), PlayerDiagnostics(), settings,
            ConnectivityObserver(app), PlaybackPrefsStore(inert(), settings, inert()),
        )
        engine.playerFactory = { builder -> builds++; builder.build() }
        engine.setSurface(Surface(SurfaceTexture(0)))
        idle()
    }

    @After
    fun tearDown() {
        engine.release()
        server.stop(0)
    }

    @Test
    fun channelThatPlayedThenLostItsProviderGivesUpAfterTheLadder() {
        engine.play(url, muted = true)
        runUntil(10_000) { engine.state.value == LivePreviewEngine.State.PLAYING }
        assertEquals("built through the factory", 1, builds)
        val before = requests.get()

        serving.set(false)
        runUntil(10 * 60_000) { engine.state.value == LivePreviewEngine.State.ERROR }

        assertTrue("the provider was asked again", requests.get() > before)
        assertTrue(engine.error.value != null)
        // The ladder's own record: every attempt spent, then the honest end state — not a spinner.
        val raw = engine.errorInfo.value?.raw.orEmpty()
        assertTrue(raw, raw.contains("gave up after 8 reconnects"))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Moves the clock in small steps until [done], failing after [limitMs] of player time. */
    private fun runUntil(limitMs: Long, done: () -> Boolean) {
        var spent = 0L
        while (!done()) {
            check(spent < limitMs) { "not reached after ${spent}ms: state=${engine.state.value} error=${engine.error.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(1)
            spent += 20
        }
    }
}
