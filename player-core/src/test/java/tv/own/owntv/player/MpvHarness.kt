package tv.own.owntv.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.metadata.MetadataOverrideStore
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.OriginalLanguageLookup
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.ProxyConfig
import tv.own.owntv.core.network.ProxyConfigHolder
import tv.own.owntv.core.network.StreamingHttpClient
import tv.own.owntv.core.player.PlaybackPrefsStore
import tv.own.owntv.core.player.VodEngineStore
import tv.own.owntv.core.settings.SettingsRepository
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An mpv core with no library behind it: it records the commands and property writes the player
 * makes, answers reads from [props], and lets a test deliver mpv's events the way the library's event
 * thread does ([fileLoaded], [endFile], [property]).
 */
internal class FakeMpvCore : MpvCore {
    val commands = CopyOnWriteArrayList<List<String>>()
    val props = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val observers = CopyOnWriteArrayList<MPVLib.EventObserver>()
    @Volatile var destroyed = false
    @Volatile var surfaceDetached = false

    /** Every `loadfile` URL, in order. */
    val loads: List<String> get() = commands.filter { it.firstOrNull() == "loadfile" }.map { it[1] }
    val stops: Int get() = commands.count { it.firstOrNull() == "stop" }

    override fun init() {}
    override fun destroy() { destroyed = true }
    override fun attachSurface(surface: Surface) { surfaceDetached = false }
    override fun detachSurface() { surfaceDetached = true }
    override fun command(cmd: Array<String>) { commands += cmd.toList() }
    override fun setOptionString(name: String, value: String): Int { props[name] = value; return 0 }
    override fun getPropertyInt(property: String): Int? = (props[property] as? Number)?.toInt()
    override fun setPropertyInt(property: String, value: Int) { props[property] = value }
    override fun getPropertyDouble(property: String): Double? = (props[property] as? Number)?.toDouble()
    override fun setPropertyDouble(property: String, value: Double) { props[property] = value }
    override fun getPropertyBoolean(property: String): Boolean? = props[property] as? Boolean
    override fun setPropertyBoolean(property: String, value: Boolean) { props[property] = value }
    override fun getPropertyString(property: String): String? = props[property]?.toString()
    override fun setPropertyString(property: String, value: String) { props[property] = value }
    override fun observeProperty(property: String, format: Int) {}
    override fun addObserver(o: MPVLib.EventObserver) { observers += o }
    override fun removeObserver(o: MPVLib.EventObserver) { observers -= o }
    override fun addLogObserver(o: MPVLib.LogObserver) {}
    override fun removeLogObserver(o: MPVLib.LogObserver) {}

    // --- mpv's event thread ---------------------------------------------------------------------

    private fun onEventThread(block: () -> Unit) {
        val t = Thread(block, "fake-mpv-events")
        t.start()
        t.join()
    }

    fun fileLoaded() = onEventThread { observers.forEach { it.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) } }

    fun endFile(reason: Int, error: Int = 0) = onEventThread {
        observers.forEach { it.endFile(reason, error) }
        observers.forEach { it.event(MPVLib.MpvEvent.MPV_EVENT_END_FILE) }
    }

    fun property(name: String, value: Long) = onEventThread { observers.forEach { it.eventProperty(name, value) } }

    fun property(name: String, value: String) = onEventThread { observers.forEach { it.eventProperty(name, value) } }
}

/**
 * The real [OwnTVPlayer] on Robolectric, with [FakeMpvCore] cores. Time is Robolectric's paused main
 * looper: [advance] moves it, and with it every `delay` the player's main-thread coroutines wait on.
 */
internal class MpvHarness {
    val app: Context = RuntimeEnvironment.getApplication()
    val cores = CopyOnWriteArrayList<FakeMpvCore>()
    val core: FakeMpvCore get() = cores.last()
    val diagnostics = PlayerDiagnostics()
    val player: OwnTVPlayer
    val settings: SettingsRepository
    private var surface = Surface(SurfaceTexture(0))

    /** What the player screen does when [OwnTVPlayer.surfaceResetToken] moves: a new SurfaceView. */
    fun recreateSurface() {
        player.detachSurface()
        surface = Surface(SurfaceTexture(0))
        player.attachSurface(surface)
        settle()
    }

    init {
        online()
        // The diagnostics tail runs `logcat`, which a JVM has not got. Its restarts sleep through
        // SystemClock, and Robolectric's sleep moves the clock every watchdog reads — so let it give up
        // before the player starts.
        diagnostics.start()
        Thread.getAllStackTraces().keys.filter { it.name == "owntv-logcat" }.forEach { it.join(30_000) }

        val localeStore = LocaleStore.from(app)
        settings = SettingsRepository(app, localeStore)
        val metadata = MetadataRepository(inert(), inert(), settings, MetadataOverrideStore(app), inert())
        player = OwnTVPlayer(
            context = app,
            settings = settings,
            connectivity = ConnectivityObserver(app),
            streamingHttp = StreamingHttpClient(OkHttpClient()),
            diagnostics = diagnostics,
            proxyHolder = ProxyConfigHolder(flowOf(ProxyConfig())),
            vodEngineStore = VodEngineStore(app, inert()),
            localeStore = localeStore,
            playbackPrefs = PlaybackPrefsStore(inert(), settings, inert()),
            originalLanguage = OriginalLanguageLookup(inert(), inert(), metadata),
        )
        player.mpvFactory = { FakeMpvCore().also { cores += it } }
        idle()
        player.attachSurface(surface)
        settle()
    }

    /** The active network has validated internet, as [ConnectivityObserver.isOnlineNow] requires. */
    private fun online() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the player's mpv worker drain and the main looper run what it posted, without moving time. */
    fun settle() {
        repeat(5) {
            idle()
            Thread.sleep(30)
        }
        idle()
    }

    /** Moves the clock by [ms] in small steps, letting the worker run between them. */
    fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 100L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
            Thread.sleep(2)
        }
        settle()
    }

    fun playLive(url: String) { player.play(url, isLive = true); settle() }
    fun playFilm(url: String) { player.play(url, isLive = false); settle() }

    /** mpv opened the file and is showing its first picture at [positionSecs]. */
    fun opened(positionSecs: Long = 1, durationSecs: Long = 0) {
        // One video and one audio track, as a normal channel lists them.
        core.props["track-list/count"] = 2
        core.props["track-list/0/type"] = "video"; core.props["track-list/0/id"] = 1
        core.props["track-list/1/type"] = "audio"; core.props["track-list/1/id"] = 1
        core.props["track-list/1/codec"] = "aac"; core.props["track-list/1/selected"] = true
        core.props["height"] = 1080
        core.props["width"] = 1920
        core.props["hwdec-current"] = "mediacodec"
        core.fileLoaded()
        if (durationSecs > 0) core.property("duration", durationSecs)
        core.property("width", 1920)
        core.property("height", 1080)
        core.property("time-pos", positionSecs)
        settle()
    }
}

/**
 * A DAO, or any interface, that holds nothing: every read is empty and every write is dropped. A
 * suspend function answers null (nothing stored); a Flow is empty or one empty list.
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <reified T> inert(): T = Proxy.newProxyInstance(
    T::class.java.classLoader, arrayOf(T::class.java),
) { proxy, method, _ ->
    when {
        method.name == "toString" -> "inert ${T::class.java.simpleName}"
        method.name == "hashCode" -> System.identityHashCode(proxy)
        method.name == "equals" -> false
        method.returnType == kotlinx.coroutines.flow.Flow::class.java -> flowOf(emptyList<Any>())
        method.returnType == java.util.List::class.java -> emptyList<Any>()
        method.returnType == Int::class.javaPrimitiveType -> 0
        method.returnType == Long::class.javaPrimitiveType -> 0L
        method.returnType == Boolean::class.javaPrimitiveType -> false
        else -> null
    }
} as T
