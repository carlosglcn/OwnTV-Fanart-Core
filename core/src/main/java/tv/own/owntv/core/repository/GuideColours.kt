package tv.own.owntv.core.repository

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.parser.KodiMarkup

/**
 * glcn: the colours each guide painted its own text with (a Kodi-style guide: season marks, episode titles,
 * the labels of a synopsis), by guide source. Learnt while the guide is read — its markup is not kept — and
 * remembered, since an unchanged guide is not read again. A guide that paints nothing has no entry, and the
 * screens tint the same pieces with the theme's colours instead.
 */
object GuideColours {
    /** ARGB each; 0 = the guide did not paint that piece. */
    class Palette(val season: Int, val episode: Int, val label: Int)

    private const val PREFS = "glcn_guide_colours"
    private const val SEP = ','

    private fun prefs() = runCatching {
        org.koin.core.context.GlobalContext.get().get<android.content.Context>()
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    private val palettes = ConcurrentHashMap<Long, Palette>()
    @Volatile private var loaded = false

    private val _version = MutableStateFlow(0)
    /** Moves when a guide's colours change, so a screen already drawn repaints. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val all = prefs()?.all ?: return
            for ((key, value) in all) {
                val id = key.toLongOrNull() ?: continue
                val parts = (value as? String)?.split(SEP)?.mapNotNull { it.toIntOrNull() } ?: continue
                if (parts.size == 3) palettes[id] = Palette(parts[0], parts[1], parts[2])
            }
            loaded = true
        }
    }

    fun of(sourceId: Long): Palette? {
        load()
        return palettes[sourceId]
    }

    /** A guide was read through: [seen] is what it painted (nothing, for an ordinary guide). */
    fun learnt(sourceId: Long, seen: KodiMarkup.Seen) {
        load()
        val before = palettes[sourceId]
        if (!seen.any) {
            if (before == null) return
            palettes.remove(sourceId)
            prefs()?.edit()?.remove(sourceId.toString())?.apply()
        } else {
            if (before != null && before.season == seen.season && before.episode == seen.episode && before.label == seen.label) return
            palettes[sourceId] = Palette(seen.season, seen.episode, seen.label)
            prefs()?.edit()?.putString(sourceId.toString(), listOf(seen.season, seen.episode, seen.label).joinToString(SEP.toString()))?.apply()
        }
        _version.value = _version.value + 1
    }
}
