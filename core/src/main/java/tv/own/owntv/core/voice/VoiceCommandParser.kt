package tv.own.owntv.core.voice

import java.text.Normalizer

/** What was understood from a spoken phrase. */
enum class VoiceKind { ANY, CHANNEL, MOVIE, SERIES }

/** [query] is what to look for; [kind] what the person said it was (ANY when they did not); [onTv] they asked for the television. */
data class VoiceCommand(val query: String, val kind: VoiceKind, val onTv: Boolean)

/**
 * glcn: the words of a spoken request, turned into a title and a place — no server, no settings.
 * "Pon Neox en la tele" → (Neox, any kind, on the television); "quiero ver la película Gladiator" →
 * (Gladiator, movie); "busca la serie The Last of Us" → (The Last of Us, series). Spanish and English.
 */
object VoiceCommandParser {
    private val FILLER = "(?:(?:por favor|oye|ok|vale)\\s+)*"
    private val VERBS = "(?:pon(?:me|er|gas)?|quiero ver|quiero que pongas|ver|ve|"
    private val VERBS_B = "reproduce(?:me)?|busca(?:me)?|"
    private val VERBS_EN = "abre|enciende|sintoniza|play|watch|put on|show me|open|find)"
    private val LEAD = Regex("^" + FILLER + "(?:" + VERBS + VERBS_B + VERBS_EN + "(?:\\s+|$))+")
    private val TV_WORD = "(?:tele(?:vision|visión)?|tv|televisor|television)"
    private val TV_TAIL = Regex("\\s+(?:en|on)\\s+(?:la\\s+|el\\s+|the\\s+|mi\\s+)?" + TV_WORD + "\\s*$")
    private val KIND_ART = "^(?:(?:el|la|los|las|un|una|the)\\s+)?"
    private val KIND_NAMES = "(canal|cadena|channel|peli|pelicula|película|film|movie|serie|show|"
    private val KIND_NAMES2 = "episodio|capitulo|capítulo)\\s+(?:de\\s+)?"
    private val KIND_WORD = Regex(KIND_ART + KIND_NAMES + KIND_NAMES2)

    fun parse(spoken: String): VoiceCommand? {
        var s = spoken.trim().trimEnd('.', '!', '?', ',')
        if (s.isBlank()) return null
        val lower = s.lowercase()
        // Work on the lower-case copy for matching but keep the person's own capitals in the result.
        var start = 0
        var end = s.length
        LEAD.find(lower)?.let { start = it.range.last + 1 }
        var onTv = false
        TV_TAIL.find(lower.substring(0, end))?.let { onTv = true; end = it.range.first }
        if (end <= start) return null
        s = s.substring(start, end).trim()
        var kind = VoiceKind.ANY
        KIND_WORD.find(s.lowercase())?.let { m ->
            kind = when (m.groupValues[1]) {
                "canal", "cadena", "channel" -> VoiceKind.CHANNEL
                "peli", "pelicula", "película", "film", "movie" -> VoiceKind.MOVIE
                else -> VoiceKind.SERIES
            }
            s = s.substring(m.range.last + 1).trim()
        }
        // "la 1", "la sexta", "el hormiguero": the article is part of a channel's or show's name — kept,
        // unless a kind word came first ("el canal la 1" keeps its own "la").
        if (s.isBlank()) return null
        return VoiceCommand(s, kind, onTv)
    }

    /** Lower-case, accent-free text for comparing a spoken title with a library's. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()
}
