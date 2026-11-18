package tv.own.owntv.player

/**
 * The panel-wide lessons [LiveStreamQuirks] keeps across app starts, and their stored form.
 *
 * Only lessons about a whole panel are kept. A lesson about one stream, the pre-roll lesson and the
 * catch-up decoder lesson stay with the run that learned them: those can come from one bad moment of
 * one feed, and carrying them on would cost later starts something that works.
 *
 * Each lesson lasts [EXPIRY_MS] from when it was learned, so a provider that fixes its panel is back to
 * the normal path within a week at worst — at once where a forget path exists.
 */
object ProviderLessons {

    enum class Kind { HLS_REDIRECT, DASH_REDIRECT, REFUSES_SEGMENTS, SINGLE_SESSION, BLOCKS_DEFAULT_UA, UNTRUSTED_CERTIFICATE }

    data class Lesson(val kind: Kind, val host: String)

    const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000

    /** One lesson a line: kind, host, and when it was learned (wall clock, ms), tab-separated. */
    fun encode(learnedAt: Map<Lesson, Long>): String =
        learnedAt.entries.joinToString("\n") { (lesson, at) -> "${lesson.kind.name}\t${lesson.host}\t$at" }

    /**
     * The lessons in [text] still younger than [EXPIRY_MS] at [nowMs]. A line this build cannot read — a
     * kind it does not know, a damaged line — is skipped, never fatal.
     */
    fun decode(text: String, nowMs: Long): Map<Lesson, Long> = buildMap {
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[1].isEmpty()) return@forEach
            val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return@forEach
            val at = parts[2].toLongOrNull() ?: return@forEach
            if (nowMs - at >= EXPIRY_MS) return@forEach
            put(Lesson(kind, parts[1]), at)
        }
    }
}
