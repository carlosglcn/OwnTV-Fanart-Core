package tv.own.owntv.core.parser

/**
 * glcn: guides written for Kodi carry its text markup — `Mañaneros 360 [COLOR tomato]T3 E70[/COLOR]`, a
 * synopsis whose `[COLOR teal]· País:[/COLOR]` labels are tinted. Here the markup comes off, so a title is a
 * title again for everything that reads it (artwork, search, repeats), and what the guide painted is noted in
 * [Seen]: the screens then tint the same pieces by pattern ([GuideText]) with the guide's own colours.
 *
 * A coloured title is put in the shape the plain guides use, "Show T3 E70 · Episode title".
 */
object KodiMarkup {

    /** The colours one guide used, as ARGB; 0 where it used none. */
    class Seen {
        var season = 0
        var episode = 0
        var label = 0
        val any: Boolean get() = season != 0 || episode != 0 || label != 0
    }

    private class Piece(val text: String, val argb: Int)

    fun has(text: String): Boolean = text.indexOf(OPEN) >= 0 && text.indexOf(COLOR, ignoreCase = true) >= 0

    /** [raw] as "Show T3 E70 · Episode title", its colours noted in [seen]. */
    fun title(raw: String, seen: Seen?): String {
        if (!has(raw)) return raw
        val show = StringBuilder()
        val token = StringBuilder()
        val episode = StringBuilder()
        for (p in pieces(raw)) {
            val t = p.text.replace('\n', ' ').trim()
            if (t.isEmpty()) continue
            when {
                p.argb == 0 -> show.append(' ').append(t)
                GuideText.isSeasonWord(t.substringBefore(' ')) -> {
                    token.append(' ').append(t)
                    if (seen != null && seen.season == 0) seen.season = p.argb
                }
                else -> {
                    episode.append(' ').append(t)
                    if (seen != null && seen.episode == 0) seen.episode = p.argb
                }
            }
        }
        if (show.isBlank()) return plain(raw)
        val out = StringBuilder(show.trim())
        if (token.isNotBlank()) out.append(' ').append(token.trim())
        if (episode.isNotBlank()) out.append(GuideText.EPISODE_MARK).append(episode.trim())
        return out.toString()
    }

    /** [raw] without its markup; the first colour it used noted as the labels' in [seen]. */
    fun text(raw: String?, seen: Seen?): String? {
        if (raw == null || !has(raw)) return raw
        val out = StringBuilder(raw.length)
        for (p in pieces(raw)) {
            out.append(p.text)
            if (seen != null && seen.label == 0 && p.argb != 0) seen.label = p.argb
        }
        return out.toString()
    }

    fun plain(raw: String): String = buildString { for (p in pieces(raw)) append(p.text) }.trim()

    /** [raw] cut where its colour changes. Tags Kodi would not know stay as written ("[REC]"). */
    private fun pieces(raw: String): List<Piece> {
        val out = ArrayList<Piece>(4)
        val run = StringBuilder()
        val colours = ArrayList<Int>(2)
        fun flush() {
            if (run.isNotEmpty()) { out.add(Piece(run.toString(), colours.lastOrNull() ?: 0)); run.setLength(0) }
        }
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val close = if (c == OPEN) raw.indexOf(CLOSE, i + 1) else -1
            if (close < 0 || close - i > TAG_MAX) { run.append(c); i++; continue }
            val tag = raw.substring(i + 1, close).trim()
            val name = tag.uppercase()
            when {
                name.startsWith(COLOR) && name.length > COLOR.length && name[COLOR.length] == ' ' -> {
                    flush(); colours.add(argb(tag.substring(COLOR.length + 1)))
                }
                name == END + COLOR -> { flush(); if (colours.isNotEmpty()) colours.removeAt(colours.size - 1) }
                name == BREAK -> run.append('\n')
                name.removePrefix(END) in STYLES -> Unit
                else -> { run.append(c); i++; continue }
            }
            i = close + 1
        }
        flush()
        return out
    }

    /** A Kodi colour — a name from its list, or AARRGGBB / RRGGBB in hex — as ARGB; 0 when unknown. */
    fun argb(colour: String): Int {
        val key = colour.trim().lowercase()
        NAMES[key]?.let { return it }
        val hex = key.removePrefix(HASH)
        if (hex.length != RGB && hex.length != ARGB) return 0
        val value = hex.toLongOrNull(RADIX) ?: return 0
        return (if (hex.length == RGB) value or OPAQUE else value).toInt()
    }

    private const val OPEN = '['
    private const val CLOSE = ']'
    private const val TAG_MAX = 24
    private const val COLOR = "COLOR"
    private const val END = "/"
    private const val BREAK = "CR"
    private const val HASH = "#"
    private const val RGB = 6
    private const val ARGB = 8
    private const val RADIX = 16
    private const val OPAQUE = 0xFF000000L
    private val STYLES = setOf("B", "I", "LIGHT", "UPPERCASE", "LOWERCASE", "CAPITALIZE")

    /** The names guides use, from Kodi's colour list (the CSS ones). */
    private val NAMES: Map<String, Int> = HashMap<String, Int>().apply {
        val list = "white=ffffff black=000000 red=ff0000 green=008000 blue=0000ff yellow=ffff00 orange=ffa500 " +
            "gold=ffd700 goldenrod=daa520 darkgoldenrod=b8860b tomato=ff6347 teal=008080 lime=00ff00 limegreen=32cd32 " +
            "magenta=ff00ff fuchsia=ff00ff slateblue=6a5acd cadetblue=5f9ea0 cyan=00ffff aqua=00ffff silver=c0c0c0 " +
            "gray=808080 grey=808080 lightgray=d3d3d3 lightgrey=d3d3d3 darkgray=a9a9a9 darkgrey=a9a9a9 dimgray=696969 " +
            "maroon=800000 purple=800080 navy=000080 olive=808000 pink=ffc0cb hotpink=ff69b4 deeppink=ff1493 " +
            "crimson=dc143c firebrick=b22222 darkred=8b0000 orangered=ff4500 darkorange=ff8c00 coral=ff7f50 " +
            "salmon=fa8072 lightsalmon=ffa07a khaki=f0e68c violet=ee82ee orchid=da70d6 plum=dda0dd indigo=4b0082 " +
            "blueviolet=8a2be2 darkviolet=9400d3 mediumpurple=9370db royalblue=4169e1 dodgerblue=1e90ff " +
            "deepskyblue=00bfff skyblue=87ceeb lightskyblue=87cefa lightblue=add8e6 steelblue=4682b4 " +
            "cornflowerblue=6495ed mediumblue=0000cd darkblue=00008b midnightblue=191970 turquoise=40e0d0 " +
            "mediumturquoise=48d1cc darkturquoise=00ced1 aquamarine=7fffd4 darkcyan=008b8b seagreen=2e8b57 " +
            "mediumseagreen=3cb371 springgreen=00ff7f lightgreen=90ee90 palegreen=98fb98 forestgreen=228b22 " +
            "darkgreen=006400 yellowgreen=9acd32 greenyellow=adff2f chartreuse=7fff00 lawngreen=7cfc00 " +
            "olivedrab=6b8e23 beige=f5f5dc wheat=f5deb3 tan=d2b48c chocolate=d2691e sienna=a0522d brown=a52a2a " +
            "peru=cd853f sandybrown=f4a460 lightyellow=ffffe0 ivory=fffff0 lavender=e6e6fa slategray=708090 " +
            "slategrey=708090 lightslategray=778899 darkslategray=2f4f4f darkslateblue=483d8b mediumslateblue=7b68ee"
        for (pair in list.split(' ')) {
            val at = pair.indexOf('=')
            put(pair.substring(0, at), (pair.substring(at + 1).toLong(RADIX) or OPAQUE).toInt())
        }
    }
}
