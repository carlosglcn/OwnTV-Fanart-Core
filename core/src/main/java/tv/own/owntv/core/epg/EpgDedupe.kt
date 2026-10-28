package tv.own.owntv.core.epg

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Collapses the same programme listed more than once on one channel.
 *
 * Guide rows are keyed by `epgChannelId`, and that key is deliberately looked up across **every**
 * playlist and EPG feed rather than only the channel's own source — one playlist's `xmltv.php`
 * routinely covers another's lineup, and narrowing the query loses rows that are really there. The
 * cost of that breadth is this: when two feeds both carry a channel, every programme comes back
 * twice. The table's unique index is `(sourceId, epgChannelId, startMs)`, so it cannot stop them —
 * the two rows have different `sourceId`s, and usually different start times as well, because two
 * feeds rarely agree to the minute.
 *
 * On screen that looked like a guide drawing a block on top of another block (`22:04–00:16` under
 * `22:10–00:15`, the same film), and a Next/Later list that named the same programme twice in a row.
 *
 * **The test is a shared title and overlapping time, and both halves matter.** Overlapping alone is
 * two feeds disagreeing about a schedule, which is not something this can honestly resolve by
 * throwing half of it away; the same title back to back without overlap is a genuine repeat, which
 * channels really do broadcast. Only both together mean one programme written down twice.
 */
object EpgDedupe {

    /**
     * [rows] for a single channel with repeats removed, in start order.
     *
     * The **longest** of a duplicate set is kept. Where two feeds disagree about the edges, the wider
     * span is the one that covers the whole programme, so a block drawn from it leaves no gap either
     * side; the shorter one would leave the guide with holes it cannot explain.
     */
    fun collapse(rows: List<EpgProgrammeEntity>): List<EpgProgrammeEntity> {
        if (rows.size < 2) return rows
        val sorted = rows.sortedWith(compareBy({ it.startMs }, { it.stopMs }))
        val kept = ArrayList<EpgProgrammeEntity>(sorted.size)
        for (row in sorted) {
            val previous = kept.lastOrNull()
            if (previous != null && isSameProgramme(previous, row)) {
                // Same programme, written twice. Keep whichever spans more of it — glcn: with the other's
                // picture and synopsis when it has none (one feed brings the times, another the fanart).
                kept[kept.lastIndex] = if (row.durationMs > previous.durationMs) row.borrowFrom(previous) else previous.borrowFrom(row)
                continue
            }
            kept += row
        }
        return kept
    }

    /** The same broadcast written down twice: one title, one stretch of time. */
    private fun isSameProgramme(a: EpgProgrammeEntity, b: EpgProgrammeEntity): Boolean =
        a.startMs < b.stopMs && b.startMs < a.stopMs && normalise(a.title) == normalise(b.title)

    /**
     * Titles as two feeds write them: different case, padding, and runs of whitespace where one has
     * wrapped a line. Nothing cleverer — a feed that renames a programme is not the same listing, and
     * guessing that it is would merge two real programmes into one.
     */
    private fun normalise(title: String): String =
        title.trim().lowercase().replace(WHITESPACE, " ")

    private val EpgProgrammeEntity.durationMs: Long get() = (stopMs - startMs).coerceAtLeast(0L)

    /** glcn: this row, with [other]'s picture and synopsis where this one has none. */
    internal fun EpgProgrammeEntity.borrowFrom(other: EpgProgrammeEntity): EpgProgrammeEntity =
        if ((imageUrl != null || other.imageUrl == null) && (description != null || other.description == null)) this
        else copy(imageUrl = imageUrl ?: other.imageUrl, description = description ?: other.description)

    /** glcn: the same broadcast in two feeds — same title, or nearly the same stretch of time. */
    internal fun sameBroadcast(a: EpgProgrammeEntity, b: EpgProgrammeEntity): Boolean {
        if (!(a.startMs < b.stopMs && b.startMs < a.stopMs)) return false
        if (normalise(a.title) == normalise(b.title)) return true
        val overlap = minOf(a.stopMs, b.stopMs) - maxOf(a.startMs, b.startMs)
        return overlap >= 0.8 * a.durationMs && overlap >= 0.8 * b.durationMs
    }

    private val WHITESPACE = Regex("\\s+")
}
