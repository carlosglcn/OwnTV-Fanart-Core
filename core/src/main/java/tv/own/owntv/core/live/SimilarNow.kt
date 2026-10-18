package tv.own.owntv.core.live

import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import java.text.Normalizer

/**
 * glcn: "You may like" — programmes on the guide soon (on air now or starting within the next hours) that
 * share the genre of the one in focus, best match first. Judged from the guide's categories; a programme
 * with none falls back to nothing, so the row simply does not show.
 */
class SimilarNow(
    private val channelDao: ChannelDao,
    private val guide: GuideReader,
) {

    data class Entry(val channel: ChannelEntity, val programme: EpgProgrammeEntity)

    suspend fun find(
        title: String?,
        categories: String?,
        year: Int?,
        focusedChannelId: Long?,
        focusedChannelName: String? = null,
        sourceIds: List<Long>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        now: Long = System.currentTimeMillis(),
        limit: Int = 8,
    ): List<Entry> {
        if (title.isNullOrBlank() || sourceIds.isEmpty()) return emptyList()
        val wanted = genres(categories)
        val focusFamily = focusedChannelName?.let { family(it) }
        if (wanted.isEmpty()) return emptyList()
        val channels = channelDao.allForSources(sourceIds, MAX_CHANNELS)
            .filter { CustomizeKeys.channel(it) !in cust.hiddenItems }
            .filter { it.id != focusedChannelId }
            // The same channel in another quality (La 2 HD / La 2 SD) is not a suggestion.
            .filter { focusFamily == null || family(it.name) != focusFamily }
            .map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
        if (channels.isEmpty()) return emptyList()
        val byId = channels.associateBy { it.id }
        val slice = guide.slice(channels, cust, globalShiftMinutes, now, now + AHEAD_MS)
        val ownTitle = fold(title)
        val scored = ArrayList<Pair<Int, Entry>>()
        for ((id, rows) in slice) {
            val ch = byId[id] ?: continue
            for (p in rows) {
                if (p.stopMs <= now || p.startMs > now + AHEAD_MS) continue
                if (fold(p.title) == ownTitle) continue
                val shared = genres(p.categories).count { it in wanted }
                if (shared == 0) continue
                var score = shared * 10
                if (QUALITY.containsMatchIn(ch.name)) score += 1
                if (!p.imageUrl.isNullOrBlank()) score += 4
                if (p.startMs <= now) score += 2
                if (year != null && p.year == year) score += 1
                scored += score to Entry(ch, p)
            }
        }
        return scored
            .sortedWith(compareByDescending<Pair<Int, Entry>> { it.first }.thenBy { it.second.programme.startMs })
            .map { it.second }
            .distinctBy { fold(it.programme.title) }
            .take(limit)
    }

    companion object {
        private const val MAX_CHANNELS = 5_000
        private val QUALITY = Regex("(?i)\\b(uhd|fhd|hd|4k)\\b")
        private val QUALITY_WORDS = Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p?|720p?|50fps|60fps)\\b")

        /** A channel's name without its provider tag or quality, so La 2 HD and La 2 SD are one family. */
        fun family(name: String): String =
            QUALITY_WORDS.replace(fold(ProviderTags.parse(name).name), "").replace(Regex("[^a-z0-9]+"), "")
        private const val AHEAD_MS = 4 * 3_600_000L

        /** Genres too broad to say anything ("Series", "Movie"…): they never count as a match on their own. */
        private val GENERIC = setOf(
            "serie", "series", "pelicula", "peliculas", "movie", "movies", "film", "show", "programa",
            "tv", "television", "general", "otros", "varios",
        )

        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

        /** The folded genres of a joined categories string, without the generic ones. */
        fun genres(categories: String?): Set<String> {
            if (categories.isNullOrBlank()) return emptySet()
            return categories.split('\u001F', ',', ';', '/', '|', '·')
                .map(::fold)
                .filter { it.length > 2 && it !in GENERIC }
                .toSet()
        }
    }
}
