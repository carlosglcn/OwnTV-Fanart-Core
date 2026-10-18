package tv.own.owntv.core.reminder

import java.text.Normalizer

/**
 * glcn: what the viewer "usually watches". Arithmetic only — no clock, no database — so each rule is
 * unit-testable (shaped after [ReminderSchedule]).
 *
 * A **habit** is a programme title the profile has watched on the same channel on at least
 * [MIN_AIRINGS] different days within the last [WINDOW_MS] (four weeks). Only the programme's title is
 * compared, after [titleKey] strips episode numbers and the like, so "Telediario 2" and "Telediario 2
 * (T3 E5)" are the same habit.
 */
object HabitRules {

    const val WINDOW_MS = 28L * 24 * 3600 * 1000
    const val MIN_AIRINGS = 3

    /** A channel counts as watched for a programme only after it stayed on this long (zapping does not). */
    const val DWELL_MS = 4L * 60 * 1000

    /** The log is capped, oldest dropped first. */
    const val MAX_ENTRIES = 600

    /** How far ahead the next showings of a habit are turned into reminders. */
    const val LOOKAHEAD_MS = 36L * 3600 * 1000

    data class Viewing(
        val profileId: Long,
        val channelId: Long,
        val channelName: String,
        val epgChannelId: String,
        val title: String,
        val key: String,
        val startMs: Long,
        val atMs: Long,
    )

    data class Habit(
        val profileId: Long,
        val channelId: Long,
        val channelName: String,
        val epgChannelId: String,
        val title: String,
        val key: String,
        val count: Int,
    )

    private val accents = Regex("\\p{Mn}+")
    private val brackets = Regex("[(\\[].*?[)\\]]")
    private val season = Regex("\\b(t|temp|temporada|s)\\s*\\.?\\s*\\d+\\b")
    private val episode = Regex("\\b(e|ep|cap|capitulo|episodio)\\s*\\.?\\s*\\d+\\b")
    private val sxe = Regex("\\b\\d+\\s*x\\s*\\d+\\b")
    private val junk = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")

    /** The programme title reduced to what identifies the show, or "" when nothing useful is left. */
    fun titleKey(title: String): String {
        var s = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).replace(accents, "")
        s = s.replace(brackets, " ").replace(sxe, " ").replace(season, " ").replace(episode, " ")
        s = s.replace(junk, " ").replace(spaces, " ").trim()
        return if (s.length >= 3) s else ""
    }

    /** The guide stores channel ids trimmed and lower-cased. */
    fun epgKey(epgChannelId: String): String = epgChannelId.trim().lowercase()

    fun habits(viewings: List<Viewing>, profileId: Long, now: Long): List<Habit> =
        viewings.asSequence()
            .filter { it.profileId == profileId && it.key.isNotEmpty() && now - it.atMs <= WINDOW_MS }
            .groupBy { it.channelId to it.key }
            .mapNotNull { (_, list) ->
                val airings = list.map { it.startMs }.distinct().size
                if (airings < MIN_AIRINGS) return@mapNotNull null
                val last = list.maxBy { it.atMs }
                Habit(profileId, last.channelId, last.channelName, last.epgChannelId, last.title, last.key, airings)
            }
            .sortedByDescending { it.count }
            .toList()

    /** Whether [title] on [channelId] is one of the profile's habits. */
    fun isHabit(habits: List<Habit>, channelId: Long, title: String): Boolean {
        val key = titleKey(title)
        return key.isNotEmpty() && habits.any { it.channelId == channelId && it.key == key }
    }

    /** Drops what fell out of the window and keeps the newest [MAX_ENTRIES]. */
    fun prune(viewings: List<Viewing>, now: Long): List<Viewing> =
        viewings.filter { now - it.atMs <= WINDOW_MS }.sortedBy { it.atMs }.takeLast(MAX_ENTRIES)

    // One line per viewing, tab separated; free text has its separators flattened.
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(viewings: List<Viewing>): String = viewings.joinToString("\n") {
        listOf(it.profileId, it.channelId, clean(it.channelName), clean(it.epgChannelId), clean(it.title), it.key, it.startMs, it.atMs)
            .joinToString("\t")
    }

    fun decode(text: String?): List<Viewing> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 8) return@mapNotNull null
        runCatching {
            Viewing(f[0].toLong(), f[1].toLong(), f[2], f[3], f[4], f[5], f[6].toLong(), f[7].toLong())
        }.getOrNull()
    }.toList()

    /** The "channel:start" markers of showings already turned into a reminder, so a dismissed one never returns. */
    fun doneKey(channelId: Long, startMs: Long) = "$channelId:$startMs"
}
