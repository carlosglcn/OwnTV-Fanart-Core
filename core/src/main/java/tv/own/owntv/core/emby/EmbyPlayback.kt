package tv.own.owntv.core.emby

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * glcn: tells an Emby/Jellyfin server what is being watched from OwnTV.
 *
 * Every Emby item OwnTV plays has a URL of the form `{api}/Videos/{id}/stream…?…api_key={token}`
 * (see the M3U bridge), so the server, the item and the session token are all in the URL — nothing
 * else needs storing. The player calls [onUrlChanged] whenever its current stream changes; for an
 * Emby URL this reports start, progress every 15 s, and stop with the last position. Emby then keeps
 * its own resume point, marks the item watched past ~90%, and anything listening to its sessions —
 * the Trakt plugin, for one — sees the playback too.
 *
 * [subtitles] lists the item's external subtitle files on the server, as URLs the player can attach.
 */
object EmbyPlayback {

    private const val TAG = "EmbyPlayback"
    private val URL_RE = Regex("""^(https?://.+?)/Videos/([0-9A-Za-z]+)/stream[^?]*\?(?:.*&)?api_key=([^&]+)""")

    private data class Ref(val api: String, val itemId: String, val token: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        tv.own.owntv.core.network.SharedHttp.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    private var current: Ref? = null
    private var ticker: Job? = null
    @Volatile private var lastPositionMs: Long = 0L

    private fun parse(url: String?): Ref? {
        val m = url?.let { URL_RE.find(it) } ?: return null
        return Ref(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** glcn: the item's own picture on the server (an episode's still), from its stream URL; null otherwise. */
    fun imageFor(streamUrl: String?, maxWidth: Int = 480): String? {
        val ref = parse(streamUrl) ?: return null
        return "${ref.api}/Items/${ref.itemId}/Images/Primary?maxWidth=$maxWidth&api_key=${ref.token}"
    }

    /** True for a stream served by an Emby/Jellyfin server through the M3U bridge. */
    fun isEmbyUrl(url: String?): Boolean = parse(url) != null

    /**
     * The player's current stream changed (null = stopped). [position] and [playing] are read while
     * the stream plays; both must be cheap and safe from any thread.
     */
    @Synchronized
    fun onUrlChanged(url: String?, position: () -> Long, playing: () -> Boolean) {
        runCatching { EmbyTrickplay.onUrlChanged(url) } // glcn: the seek-bar thumbnails follow the stream
        val next = parse(url)
        // glcn: a stream that looks like a server's but is not read as one is why nothing would reach it.
        if (next == null && url != null && url.contains("/Videos/", ignoreCase = true)) note("not read as Emby: ${redact(url)}")
        // Every other stream by its server alone (no path: a list's addresses carry its login) — a film from the server
        // that shows here as another host is being played from a copy or through a proxy, and the server never hears.
        else if (next == null && url != null) runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}:${it.port}" } }.getOrNull()?.let { if (it != lastHost) { lastHost = it; note("stream from $it (not a server item)") } }
        if (next != null) note("stream item=${next.itemId} on ${next.api}")
        val prev = current
        if (prev != null && prev == next) return
        if (prev != null) {
            ticker?.cancel()
            val pos = runCatching { position() }.getOrDefault(lastPositionMs).takeIf { it > 0 } ?: lastPositionMs
            post(prev, "/Sessions/Playing/Stopped", body(prev, pos, paused = false))
        }
        current = next
        lastPositionMs = 0L
        if (next == null) return
        post(next, "/Sessions/Playing", body(next, 0L, paused = false).put("PlayMethod", "DirectPlay").put("CanSeek", true))
        ticker = scope.launch {
            while (isActive) {
                delay(15_000)
                val pos = runCatching { position() }.getOrDefault(lastPositionMs)
                if (pos > 0) lastPositionMs = pos
                val isPlaying = runCatching { playing() }.getOrDefault(true)
                postNow(next, "/Sessions/Playing/Progress", body(next, lastPositionMs, paused = !isPlaying))
            }
        }
    }

    private fun body(ref: Ref, positionMs: Long, paused: Boolean) = JSONObject()
        .put("ItemId", ref.itemId)
        .put("PositionTicks", positionMs * 10_000L)
        .put("IsPaused", paused)

    private fun post(ref: Ref, path: String, body: JSONObject) {
        scope.launch { postNow(ref, path, body) }
    }

    private fun postNow(ref: Ref, path: String, body: JSONObject) {
        runCatching {
            val req = Request.Builder()
                .url("${ref.api}$path")
                .header("X-Emby-Token", ref.token)
                .header("X-Emby-Authorization", "Emby Client=\"OwnTV\", Device=\"OwnTV\", DeviceId=\"owntv-glcn\", Version=\"1.0\", Token=\"${ref.token}\"")
                // glcn: Jellyfin reads the standard header ("MediaBrowser …"); newer versions turn the Emby ones off.
                .header("Authorization", "MediaBrowser Client=\"OwnTV\", Device=\"OwnTV\", DeviceId=\"owntv-glcn\", Version=\"1.0\", Token=\"${ref.token}\"")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "$path → HTTP ${r.code}")
                // Progress every 15 s would fill the diary: only its failures go in.
                if (!path.endsWith("/Progress") || !r.isSuccessful) note("${path.substringBeforeLast("/${ref.itemId}")} item=${ref.itemId} → ${r.code}")
            }
        }.onFailure { Log.w(TAG, "$path failed", it); note("$path item=${ref.itemId} → ${it.javaClass.simpleName}") }
    }

    @Volatile private var lastHost: String? = null

    /** glcn: the last reports sent to the server and what it answered, for the playback report. */
    private val diary = ArrayDeque<String>()

    private fun note(line: String) = synchronized(diary) {
        diary.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line)
        while (diary.size > DIARY_LINES) diary.removeFirst()
    }

    /** glcn: the diary as text (empty when nothing went to a server). */
    fun diaryText(): String = synchronized(diary) { diary.joinToString("\n") }

    /** The address without its key. */
    private fun redact(url: String): String = url.replace(Regex("""(?i)(api_key|ApiKey|X-Emby-Token)=[^&]+"""), "$1=…").take(200)

    private const val DIARY_LINES = 40

    /**
     * glcn: OwnTV counts the item watched (an episode in its end credits, say) — the server is told so outright.
     * Emby only marks an item played from a stopped report past its own share (90 %), which leaving in the credits or
     * going on to the next episode does not reach; a played mark is also what its Trakt plugin passes on.
     */
    fun markPlayed(url: String?) {
        val ref = parse(url) ?: return
        scope.launch {
            val user = tv.own.owntv.core.sync.EmbyBridge.userFor(ref.api, ref.token) ?: sessionUser(ref)
            if (user == null) { Log.w(TAG, "markPlayed: no user for the item"); note("markPlayed item=${ref.itemId} → no user"); return@launch }
            postNow(ref, "/Users/$user/PlayedItems/${ref.itemId}", JSONObject())
        }
    }

    /** The user the token belongs to, from the server's list of sessions (this device's). */
    private fun sessionUser(ref: Ref): String? = runCatching {
        val req = Request.Builder().url("${ref.api}/Sessions?DeviceId=$DEVICE_ID")
            .header("X-Emby-Token", ref.token)
            .header("Authorization", "MediaBrowser Client=\"OwnTV\", Device=\"OwnTV\", DeviceId=\"$DEVICE_ID\", Version=\"1.0\", Token=\"${ref.token}\"")
            .get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            val arr = org.json.JSONArray(r.body?.string().orEmpty())
            (0 until arr.length()).firstNotNullOfOrNull { arr.optJSONObject(it)?.optString("UserId")?.takeIf { u -> u.isNotBlank() } }
        }
    }.getOrNull()

    private const val DEVICE_ID = "owntv-glcn"

    /** One external subtitle file the server holds for an item. */
    data class Subtitle(val url: String, val title: String, val lang: String?)

    /** The item's external subtitles (the `.srt`/`.ass` files beside the video), as attachable URLs. */
    fun subtitles(url: String?): List<Subtitle> {
        val ref = parse(url) ?: return emptyList()
        return runCatching {
            val req = Request.Builder()
                .url("${ref.api}/Items/${ref.itemId}/PlaybackInfo?api_key=${ref.token}")
                .header("X-Emby-Token", ref.token)
                .get().build()
            val json = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching emptyList<Subtitle>()
                JSONObject(r.body?.string().orEmpty())
            }
            val source = json.optJSONArray("MediaSources")?.optJSONObject(0) ?: return@runCatching emptyList<Subtitle>()
            val sourceId = source.optString("Id").ifBlank { ref.itemId }
            val streams = source.optJSONArray("MediaStreams") ?: return@runCatching emptyList<Subtitle>()
            (0 until streams.length()).mapNotNull { i ->
                val s = streams.optJSONObject(i) ?: return@mapNotNull null
                if (s.optString("Type") != "Subtitle" || !s.optBoolean("IsExternal")) return@mapNotNull null
                val index = s.optInt("Index", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val codec = s.optString("Codec").lowercase().ifBlank { "srt" }
                val ext = when (codec) { "subrip" -> "srt"; "ass", "ssa", "vtt", "srt" -> codec; else -> "srt" }
                val lang = s.optString("Language").ifBlank { null }
                val title = s.optString("DisplayTitle").ifBlank { lang ?: "Emby $index" }
                Subtitle(
                    url = "${ref.api}/Videos/${ref.itemId}/$sourceId/Subtitles/$index/Stream.$ext?api_key=${ref.token}",
                    title = "$title (Emby)",
                    lang = lang,
                )
            }
        }.getOrElse {
            Log.w(TAG, "subtitles failed", it)
            emptyList()
        }
    }
}
