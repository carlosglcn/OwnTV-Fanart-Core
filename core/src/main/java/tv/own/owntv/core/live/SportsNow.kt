package tv.own.owntv.core.live

import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import java.text.Normalizer

/**
 * glcn: "Sports now" — every channel of the given playlists whose programme on air right now is sport,
 * judged from the guide's categories first and its title second ("Fútbol", "Deportes", "LaLiga",
 * "Champions", "NBA", "Fórmula 1"…). Live events ("DIRECTO …") come first, then by start time.
 */
class SportsNow(
    private val channelDao: ChannelDao,
    private val guide: GuideReader,
) {

    data class Entry(val channel: ChannelEntity, val programme: EpgProgrammeEntity)

    suspend fun find(
        sourceIds: List<Long>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        now: Long = System.currentTimeMillis(),
    ): List<Entry> {
        if (sourceIds.isEmpty()) return emptyList()
        val channels = channelDao.allForSources(sourceIds, MAX_CHANNELS)
            .filter { CustomizeKeys.channel(it) !in cust.hiddenItems }
            .map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
        if (channels.isEmpty()) return emptyList()
        val byId = channels.associateBy { it.id }
        val slice = guide.slice(channels, cust, globalShiftMinutes, now, now + 1)
        return slice.mapNotNull { (id, rows) ->
            val ch = byId[id] ?: return@mapNotNull null
            val p = rows.firstOrNull { now in it.startMs until it.stopMs } ?: return@mapNotNull null
            if (isSport(p)) Entry(ch, p) else null
        }
            // One row per programme: the same match on an HD and an SD copy shows once.
            .distinctBy { it.programme.title.lowercase() + "|" + it.programme.startMs + "|" + it.channel.name.lowercase().replace(" hd", "") }
            .sortedWith(compareByDescending<Entry> { isLive(it.programme) }.thenBy { it.programme.startMs })
    }

    companion object {
        private const val MAX_CHANNELS = 5_000

        private val SPORT_WORDS = listOf(
            "deport", "futbol", "baloncesto", "basket", "tenis", "golf", "motor", "motociclismo",
            "automovilismo", "formula 1", "f1", "motogp", "ciclismo", "rugby", "balonmano", "boxeo",
            "atletismo", "natacion", "voleibol", "padel", "laliga", "liga de campeones", "champions",
            "europa league", "conference league", "copa del rey", "nba", "euroliga", "euroleague", "acb",
            "nfl", "ufc", "sport", "wwe", "hipica", "esqui", "dardos", "snooker",
        )
        private val WORD_RE = Regex("\\b(" + SPORT_WORDS.joinToString("|") { Regex.escape(it) } + ")")

        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        fun isSport(p: EpgProgrammeEntity): Boolean {
            val cats = p.categories?.let(::fold).orEmpty()
            if (cats.isNotEmpty() && WORD_RE.containsMatchIn(cats)) return true
            return WORD_RE.containsMatchIn(fold(p.title))
        }

        fun isLive(p: EpgProgrammeEntity): Boolean {
            val t = fold(p.title)
            return t.startsWith("directo") || t.startsWith("(en directo)") || t.contains(" en directo") || t.startsWith("live")
        }
    }
}
