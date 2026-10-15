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
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
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
        val next = parse(url)
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
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r -> if (!r.isSuccessful) Log.w(TAG, "$path → HTTP ${r.code}") }
        }.onFailure { Log.w(TAG, "$path failed", it) }
    }

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
