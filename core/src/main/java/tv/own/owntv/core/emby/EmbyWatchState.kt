package tv.own.owntv.core.emby

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: what an Emby/Jellyfin server says its user has watched — handed from the M3U bridge (which reads it
 * with the library) to the sync wrapper (which writes it into the app's own resume points once the new
 * item ids exist). Keyed by the stream URL without its query, which is the same string in both places.
 */
object EmbyWatchState {

    /** [positionMs] 0 with [played] false = untouched; [durationMs] 0 = the server did not say. */
    data class State(val played: Boolean, val positionMs: Long, val durationMs: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun key(streamUrl: String): String = streamUrl.substringBefore('?')

    /** Reads the item's `UserData` and `RunTimeTicks`; nothing recorded for an untouched item. */
    fun record(streamUrl: String, item: JSONObject) {
        val ud = item.optJSONObject("UserData") ?: return
        val played = ud.optBoolean("Played", false)
        val pos = ud.optLong("PlaybackPositionTicks", 0L) / 10_000L
        if (!played && pos <= 0L) return
        states[key(streamUrl)] = State(played, pos, item.optLong("RunTimeTicks", 0L) / 10_000L)
    }

    /** Takes everything recorded so far; the next sync starts from empty. */
    fun drain(): Map<String, State> {
        val out = HashMap<String, State>(states)
        states.keys.removeAll(out.keys)
        return out
    }
}
