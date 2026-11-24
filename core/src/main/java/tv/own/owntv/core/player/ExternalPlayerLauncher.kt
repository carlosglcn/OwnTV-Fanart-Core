package tv.own.owntv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import tv.own.owntv.core.network.StreamHeaders
import java.io.File
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

// Hands a stream URL (or a downloaded file path) to an external video player (VLC, MX Player, etc.)
// via ACTION_VIEW. When it fires, the in-app player is bypassed entirely (the fullscreen player
// never opens, because OwnTVPlayer is never told to play).
//
// Network URLs (http/https/rtsp/rtmp/udp/mms) are handed over verbatim. Local download paths are
// shared through the app FileProvider with a read permission grant.
//
// A source's User-Agent and any per-channel request headers are passed as intent extras. ACTION_VIEW
// has no standard slot for these, but VLC and MX Player both read the de-facto keys used below, so a
// stream behind a UA check or per-channel auth headers now has a chance of playing instead of a
// guaranteed 403. Players that ignore the extras are unaffected — they see the same intent as before.
//
// A film's resume point goes along as "position" (ms) — MX Player reads it as an int, VLC as a long
// with an int fallback, so the int form serves both.
//
// Limitations: extras are best-effort (an unknown player may still ignore them) and there is no
// prev/next queue. glcn: where the user stopped is read back when the caller asks (onResult) and the
// activity has registered its result hook — VLC and MX Player answer it; other players may not.
class ExternalPlayerLauncher(private val context: Context) {

    // Open url externally. Returns true if an external app was actually launched.
    // [userAgent] is the source's UA; [httpHeaders] is the stored `Key: Value` per-line block from
    // ChannelEntity.httpHeaders (M3U `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP`). Both are optional.
    fun launch(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        userAgent: String? = null,
        httpHeaders: String? = null,
        /** Where to start, in ms — the item's saved resume point; 0 plays from the beginning. */
        startPositionMs: Long = 0L,
        /** glcn: where the person stopped, read back when the player closes (VLC, MX Player and the players that
         *  answer the same way). Needs the activity's result hook ([resultStarter]); without it, launched as before. */
        onResult: ((Outcome) -> Unit)? = null,
    ): Boolean {
        val uri = uriFor(url)
        if (uri == null) {
            toast(localizedContext().getString(tv.own.owntv.core.R.string.player_external_could_not_open))
            return false
        }
        // Try the precise MIME first, then widen. VLC and MX Player advertise `video/*` but NOT every
        // specific type: a live channel ending `.ts` (video/mp2t) or `.m3u8` (application/x-mpegURL)
        // matched no activity at all and reported "no external player installed", even though the same
        // players happily took a movie's video/mp4. Falling back to `video/*`, and finally to the URI
        // with no type at all, gets live streams to the same players without touching the movie path.
        for (mime in mimeCandidates(url)) {
            val intent = Intent(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (mime != null) intent.setDataAndType(uri, mime) else intent.data = uri
            applyHeaders(intent, url, userAgent, httpHeaders)
            // VLC and MX Player show this as the name of what is playing, instead of the URL.
            (if (title != null && subtitle != null) "$title · $subtitle" else title)?.let { intent.putExtra("title", it) }
            if (startPositionMs > 0) intent.putExtra("position", startPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

            if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) continue
            // glcn: started for a result, the player hands back where it stopped (VLC always does then; MX Player
            // wants return_result). The system's "Open with" window forwards the result, so Always still works.
            val starter = resultStarter
            if (onResult != null && starter != null) {
                intent.flags = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                intent.putExtra(EXTRA_RETURN_RESULT, true)
                pending = onResult
                launchedAt = android.os.SystemClock.elapsedRealtime()
                val started = runCatching { starter(intent) }.getOrDefault(false)
                if (started) return true
                pending = null
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // The intent itself, never Intent.createChooser: only the system's own "Open with" window
            // offers Just once / Always, so a user can make one player the default.
            return startActivity(intent)
        }
        toast(localizedContext().getString(tv.own.owntv.core.R.string.player_external_not_found))
        return false
    }

    // Whether any installed app can handle a video URL.
    fun isAvailable(): Boolean {
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType("https://example.com/video.mp4".toUri(), "video/mp4")
        return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
    }

    private fun startActivity(intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }
            .onFailure { toast(localizedContext().getString(tv.own.owntv.core.R.string.player_external_not_found)) }
            .isSuccess

    // Attach the source User-Agent and per-channel headers to the intent, using the keys the common
    // Android players read. Local files need none of this, and an empty header set adds no extras.
    //
    // Keys, all de-facto rather than platform API:
    //  - "headers": String[] of alternating name/value — MX Player, and VLC for Android since 3.x.
    //  - "User-Agent": read by several players (incl. older VLC builds) as a standalone override.
    //  - "android.media.intent.extra.HTTP_HEADERS": the Bundle form some ExoPlayer-based players use.
    // A player that knows none of them ignores the extras entirely, which is the behaviour we had.
    private fun applyHeaders(intent: Intent, url: String, userAgent: String?, httpHeaders: String?) {
        if (url.toUri().scheme?.lowercase() !in NETWORK_SCHEMES) return
        val headers = LinkedHashMap<String, String>(4)
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        // Per-channel headers win over the source UA: a playlist that sets its own User-Agent set it
        // for this specific stream, which is the more specific instruction (same order the in-app
        // engines apply them in).
        headers.putAll(StreamHeaders.decode(httpHeaders))
        if (headers.isEmpty()) return

        intent.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        headers["User-Agent"]?.let { intent.putExtra("User-Agent", it) }
        intent.putExtra(
            "android.media.intent.extra.HTTP_HEADERS",
            Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } },
        )
    }

    // Network scheme: hand the URL over verbatim; otherwise treat as a local file path.
    private fun uriFor(url: String): Uri? {
        val scheme = url.toUri().scheme?.lowercase()
        if (scheme in NETWORK_SCHEMES) return url.toUri()
        // A download or recording saved into a folder the user picked is already a shareable URI —
        // it needs no FileProvider, and wrapping it in one is impossible anyway. It is passed
        // through with the read grant the intent already carries. Whether the other app is allowed
        // to open it is then between it and the provider; before this, a document simply fell
        // through to File(), did not exist, and the external player silently refused to start.
        if (scheme == CONTENT_SCHEME) return url.toUri()
        val file = File(url)
        if (!file.exists()) return null
        val authority = context.packageName + ".fileprovider"
        return runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrNull()
    }

    // MIME types to offer for a URL, most specific first, ending with an untyped attempt. Duplicates
    // are dropped so a plain video/* URL doesn't query the same intent twice.
    private fun mimeCandidates(url: String): List<String?> {
        val path = url.substringBefore('?').substringAfterLast('/', "")
        val ext = path.substringAfterLast('.', "").lowercase()
        val specific = when (ext) {
            "m3u8", "m3u" -> "application/x-mpegURL"
            "ts", "m2t", "mts" -> "video/mp2t"
            "mp4", "m4v", "mov", "3gp" -> "video/mp4"
            else -> "video/*"
        }
        return listOf(specific, "video/*", null).distinct()
    }

    // A blocking DataStore read — only the error toasts need it, never a successful launch.
    private fun localizedContext(): Context =
        AppLocale.wrap(context, LocaleStore.from(context).readBlocking())

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** glcn: what a player said when it closed. [finished] = it played to the end. */
    data class Outcome(val positionMs: Long, val durationMs: Long, val finished: Boolean)

    companion object {
        /** glcn: set by the activity — starts an intent for a result; null while there is no activity. */
        @Volatile var resultStarter: ((Intent) -> Boolean)? = null
        @Volatile private var pending: ((Outcome) -> Unit)? = null
        @Volatile private var launchedAt = 0L

        /**
         * glcn: the player closed. VLC answers extra_position / extra_duration (ms, long); MX Player position /
         * duration (ms, int) and end_by. VLC gives no "ended" flag, and at the end its position can come back as
         * 0: a position of 0 after the time the item lasts counts as played to the end.
         */
        fun deliver(data: Intent?) {
            val callback = pending ?: return
            pending = null
            val extras = data?.extras ?: return
            val position = longOf(extras, EXTRA_VLC_POSITION) ?: longOf(extras, EXTRA_MX_POSITION) ?: -1L
            val duration = longOf(extras, EXTRA_VLC_DURATION) ?: longOf(extras, EXTRA_MX_DURATION) ?: -1L
            val elapsed = android.os.SystemClock.elapsedRealtime() - launchedAt
            val endBy = extras.getString(EXTRA_MX_END_BY)
            val finished = endBy == MX_END_COMPLETION ||
                (duration > 0 && position >= duration - END_SLACK_MS) ||
                (duration > 0 && position <= 0 && elapsed >= duration * 9 / 10)
            if (duration <= 0 && !finished) return
            runCatching { callback(Outcome(position.coerceAtLeast(0L), duration, finished)) }
        }

        private fun longOf(b: Bundle, key: String): Long? {
            if (!b.containsKey(key)) return null
            @Suppress("DEPRECATION")
            return when (val v = b.get(key)) { is Long -> v; is Int -> v.toLong(); else -> null }
        }

        private const val EXTRA_RETURN_RESULT = "return_result"
        private const val EXTRA_VLC_POSITION = "extra_position"
        private const val EXTRA_VLC_DURATION = "extra_duration"
        private const val EXTRA_MX_POSITION = "position"
        private const val EXTRA_MX_DURATION = "duration"
        private const val EXTRA_MX_END_BY = "end_by"
        private const val MX_END_COMPLETION = "playback_completion"
        private const val END_SLACK_MS = 2_000L
        /** URL schemes handed to the external player as-is (everything else is a local file path). */
        val NETWORK_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "udp", "mms")

        /** A file saved into a folder the user picked through the system picker. */
        const val CONTENT_SCHEME = "content"
    }
}
