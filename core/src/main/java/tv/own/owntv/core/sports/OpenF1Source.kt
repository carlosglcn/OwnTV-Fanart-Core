package tv.own.owntv.core.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchEvent
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * glcn: what happened in a Formula 1 session that is over — pit stops and the race director's notes (red
 * flag, safety car, penalties, the chequered flag) — from OpenF1's free history. The live feed is a paid
 * service, so nothing here is asked while a session runs, and it is asked once per session (kept an hour).
 * Every read is defensive: anything unexpected gives an empty list, never an error.
 */
class OpenF1Source(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val kept = HashMap<String, Pair<Long, List<MatchEvent>>>()

    suspend fun timeline(match: Match): List<MatchEvent> {
        val now = System.currentTimeMillis()
        kept[match.id]?.takeIf { now - it.first < KEEP_MS }?.let { return it.second }
        val events = runCatching { read(match) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
        // An empty answer is kept for less: the history can take a few minutes to appear after the flag.
        if (events.isNotEmpty()) kept[match.id] = now to events
        return events
    }

    private suspend fun read(match: Match): List<MatchEvent> {
        val session = sessionAt(match.startMs) ?: return emptyList()
        val key = session.opt("session_key")?.toString() ?: return emptyList()
        val began = instant(session.optString("date_start")) ?: match.startMs
        val (control, pits, drivers) = coroutineScope {
            val c = async { rows("race_control", key) }
            val p = async { rows("pit", key) }
            val d = async { rows("drivers", key) }
            Triple(c.await(), p.await(), d.await())
        }
        val names = HashMap<Int, String>()
        for (d in drivers) {
            val n = d.optInt("driver_number", -1)
            val acronym = d.optString("name_acronym").trim()
            if (n >= 0 && acronym.isNotEmpty()) names[n] = acronym
        }
        fun minute(at: Long) = (((at - began) / 60_000L).coerceAtLeast(0L)).toString() + "'"
        val out = ArrayList<Pair<Long, MatchEvent>>()
        for (r in control) {
            val at = instant(r.optString("date")) ?: continue
            val message = r.optString("message").trim().ifEmpty { continue }
            if (!worthKeeping(r.optString("category"), r.optString("flag"), message)) continue
            out += at to MatchEvent(MatchEvent.Kind.RACE_CONTROL, minute(at), null, emptyList(), message.lowercase().replaceFirstChar { it.titlecase() })
        }
        for (r in pits) {
            val at = instant(r.optString("date")) ?: continue
            val who = names[r.optInt("driver_number", -1)] ?: continue
            val secs = (if (r.has("stop_duration") && !r.isNull("stop_duration")) r.optDouble("stop_duration") else r.optDouble("pit_duration"))
                .takeIf { it.isFinite() && it > 0 }
            val lap = r.optInt("lap_number", 0).takeIf { it > 0 }
            val text = listOfNotNull(lap?.let { "L$it" }, secs?.let { String.format(java.util.Locale.ROOT, "%.1f s", it) }).joinToString("  ·  ").ifEmpty { null }
            out += at to MatchEvent(MatchEvent.Kind.PIT_STOP, minute(at), null, listOf(who), text)
        }
        return out.sortedBy { it.first }.map { it.second }.take(MAX_EVENTS)
    }

    /** The messages worth a line: the safety car, red and chequered flags, penalties and retirements. */
    private fun worthKeeping(category: String, flag: String, message: String): Boolean {
        val m = message.uppercase()
        return category.equals("SafetyCar", true) ||
            flag.equals("RED", true) || flag.equals("CHEQUERED", true) ||
            "PENALTY" in m || "RETIRED" in m || ("STOPPED" in m && "TRACK" in m)
    }

    private suspend fun sessionAt(startMs: Long): JSONObject? {
        val from = Instant.ofEpochMilli(startMs - WINDOW_MS).toString()
        val to = Instant.ofEpochMilli(startMs + WINDOW_MS).toString()
        val url = "$BASE/sessions".toHttpUrl().newBuilder().addQueryParameter("date_start>", from) // the name carries the operator: date_start>=….addQueryParameter("date_start<", to).build().toString()
        return array(get(url)).minByOrNull { r -> kotlin.math.abs((instant(r.optString("date_start")) ?: Long.MAX_VALUE / 2) - startMs) }
    }

    private suspend fun rows(path: String, sessionKey: String): List<JSONObject> =
        runCatching { array(get("$BASE/$path?session_key=$sessionKey")) }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    private fun array(body: String): List<JSONObject> {
        val a = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun instant(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw SportsHttpException(r.code)
            r.body.string()
        }
    }

    private companion object {
        const val BASE = "https://api.openf1.org/v1"
        const val TIMEOUT_SECONDS = 12L
        const val KEEP_MS = 60 * 60_000L
        const val WINDOW_MS = 90 * 60_000L
        const val MAX_EVENTS = 80
    }
}
