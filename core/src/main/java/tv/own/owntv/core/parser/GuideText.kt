package tv.own.owntv.core.parser

/**
 * glcn: the pieces of a guide's text a screen can tint. A title "Show T3 E70 · Episode title" has a season
 * mark and an episode title; a synopsis has lines that open with a label ("· País: España"). Found by
 * pattern, so they are the same pieces whether the guide painted them itself ([KodiMarkup]) or not.
 */
object GuideText {
    const val EPISODE_MARK = " · "

    /** Where a title's pieces lie: the season mark `[seasonFrom, seasonTo)`, the episode title from [episodeFrom]; -1 = none. */
    class Title(val seasonFrom: Int, val seasonTo: Int, val episodeFrom: Int)

    /** "T3", "S02E05", "E70": a season or episode number as guides write it. */
    fun isSeasonWord(word: String): Boolean {
        if (word.length < 2 || word.length > WORD_MAX) return false
        var i = 0
        var groups = 0
        while (i < word.length) {
            if (word[i].uppercaseChar() !in LETTERS) return false
            i++
            val from = i
            while (i < word.length && word[i].isDigit()) i++
            if (i == from) return false
            groups++
        }
        return groups in 1..2
    }

    /** The pieces of [title], or null when it is just a name. */
    fun title(title: String): Title? {
        if (title.length < MIN || !title.any { it.isDigit() } && EPISODE_MARK !in title) return null
        val mark = title.indexOf(EPISODE_MARK)
        val head = if (mark >= 0) mark else title.length
        var seasonFrom = -1
        var seasonTo = -1
        // The first season word after the name; an episode word right behind it belongs to it ("T3 E70").
        var at = title.indexOf(' ')
        while (at in 0 until head) {
            val from = at + 1
            var to = title.indexOf(' ', from).let { if (it < 0 || it > head) head else it }
            if (to > from && isSeasonWord(title.substring(from, to))) {
                val next = title.indexOf(' ', to + 1).let { if (it < 0 || it > head) head else it }
                if (to < head && next > to + 1 && isSeasonWord(title.substring(to + 1, next))) to = next
                seasonFrom = from
                seasonTo = to
                break
            }
            at = title.indexOf(' ', from)
        }
        val episodeFrom = if (mark >= 0) mark + EPISODE_MARK.length else -1
        return if (seasonFrom < 0 && episodeFrom < 0) null else Title(seasonFrom, seasonTo, episodeFrom)
    }

    /**
     * The labels of [text] as `from, to` pairs: a line that opens with a dot and a few capitalised words up to
     * a colon ("· Dirección:"). The synopsis itself opens with the same dot, and is left alone.
     */
    fun labels(text: String): IntArray {
        if (LABEL_DOT !in text) return NONE
        var out = NONE
        var line = 0
        while (line < text.length) {
            val end = text.indexOf('\n', line).let { if (it < 0) text.length else it }
            if (end - line > 3 && text[line] == LABEL_DOT && text[line + 1] == ' ' && text[line + 2].isUpperCase()) {
                val colon = text.indexOf(':', line)
                if (colon in line until minOf(end, line + LABEL_MAX) && text.substring(line, colon).count { it == ' ' } <= LABEL_WORDS) {
                    out += line
                    out += colon + 1
                }
            }
            line = end + 1
        }
        return out
    }

    private const val LETTERS = "TSE"
    private const val WORD_MAX = 8
    private const val MIN = 4
    private const val LABEL_DOT = '·'
    private const val LABEL_MAX = 26
    private const val LABEL_WORDS = 3
    private val NONE = IntArray(0)
}
