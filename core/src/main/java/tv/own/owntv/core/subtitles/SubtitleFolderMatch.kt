package tv.own.owntv.core.subtitles

import tv.own.owntv.core.database.entity.nameKeyOf

/**
 * How well a subtitle file's name fits the film or episode playing — the order "Find in folder" lists a
 * folder in, and the rule "Load matching subtitle automatically" attaches by.
 *
 * Names are compared as words in their Unicode lowercase form, split at anything that is not a letter or
 * a digit, so "The.Matrix.1999.en.srt", "the matrix (1999).srt" and "Матрица_1999.srt" all read as words.
 * A file **matches exactly** when its name starts with the title's words and what follows is release
 * detail, not more title: for a film, nothing, a year, a resolution or a known release or language tag
 * (and a year, where both have one, must agree); for an episode, its own season and episode code. "Up" is
 * therefore not an exact match for "Up.In.The.Air.2009.srt", and S01E03 never takes S01E04's file.
 */
object SubtitleFolderMatch {

    /** What is playing: [title] already cleaned of provider tags; [season]/[episode] only for an episode. */
    data class Target(val title: String, val year: Int?, val season: Int?, val episode: Int?) {
        val isEpisode: Boolean get() = season != null && episode != null
    }

    /** [files] best first: by [score], then by name. */
    fun <T> rank(target: Target, files: List<T>, nameOf: (T) -> String): List<T> {
        val titleWords = words(target.title)
        return files
            .map { it to score(target, titleWords, nameOf(it)) }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { nameKeyOf(nameOf(it.first)) })
            .map { it.first }
    }

    /** [files] whose name holds every word of [query], in any letter case; all of them for a blank query. */
    fun <T> filter(files: List<T>, query: String, nameOf: (T) -> String): List<T> {
        val needles = words(query)
        if (needles.isEmpty()) return files
        return files.filter { file -> nameKeyOf(nameOf(file)).let { name -> needles.all { it in name } } }
    }

    fun isExact(target: Target, fileName: String): Boolean {
        val titleWords = words(target.title)
        val fileWords = words(baseName(fileName))
        if (titleWords.isEmpty() || fileWords.size < titleWords.size || fileWords.subList(0, titleWords.size) != titleWords) return false
        val code = episodeCode(fileName)
        if (target.isEpisode) return code == target.season to target.episode
        if (code != null || !yearAgrees(target.year, fileName)) return false
        val next = fileWords.getOrNull(titleWords.size) ?: return true
        return YEAR_WORD.matches(next) || RESOLUTION_WORD.matches(next) || next in RELEASE_WORDS
    }

    internal fun score(target: Target, titleWords: List<String>, fileName: String): Int {
        if (titleWords.isEmpty()) return 0
        val fileWords = words(baseName(fileName))
        val fileSet = fileWords.toHashSet()
        var score = 60 * titleWords.count { it in fileSet } / titleWords.size
        if (fileWords.size >= titleWords.size && fileWords.subList(0, titleWords.size) == titleWords) score += 20
        val fileYear = yearOf(fileName)
        if (target.year != null && fileYear != null) score += if (fileYear == target.year) 10 else -30
        val code = episodeCode(fileName)
        score += when {
            target.isEpisode && code == target.season to target.episode -> 40
            target.isEpisode && code != null -> -60
            target.isEpisode -> -10
            code != null -> -20
            else -> 0
        }
        return score
    }

    internal fun words(text: String): List<String> =
        nameKeyOf(text).split(NON_WORD).filter { it.isNotEmpty() }

    private fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    private fun yearOf(fileName: String): Int? = YEAR.findAll(fileName).lastOrNull()?.value?.toIntOrNull()

    private fun yearAgrees(year: Int?, fileName: String): Boolean {
        val fileYear = yearOf(fileName)
        return year == null || fileYear == null || fileYear == year
    }

    /** Season and episode from "S01E03", "s1.e3" or "1x03"; null when the name carries none. */
    private fun episodeCode(fileName: String): Pair<Int, Int>? {
        val name = nameKeyOf(fileName)
        EPISODE_SE.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        EPISODE_X.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val YEAR = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val YEAR_WORD = Regex("(?:19|20)\\d{2}")
    private val RESOLUTION_WORD = Regex("\\d{3,4}[pi]")
    private val EPISODE_SE = Regex("(?<![\\p{L}\\p{N}])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?!\\d)")
    private val EPISODE_X = Regex("(?<![\\p{L}\\p{N}])(\\d{1,2})x(\\d{2,3})(?!\\d)")

    /** Words that follow a film's title in a release name without being part of it. */
    private val RELEASE_WORDS = setOf(
        "bluray", "brrip", "bdrip", "webrip", "web", "webdl", "hdtv", "hdrip", "dvdrip", "remux", "x264", "x265",
        "h264", "h265", "hevc", "extended", "unrated", "remastered", "proper", "repack", "imax", "uhd", "hdr",
        "sub", "subs", "forced", "sdh",
        "en", "eng", "english", "de", "ger", "deu", "german", "fr", "fre", "fra", "french", "es", "spa", "spanish",
        "it", "ita", "italian", "nl", "dut", "nld", "dutch", "pt", "por", "ru", "rus", "pl", "pol", "tr", "tur",
        "ar", "ara", "sv", "swe", "da", "dan", "no", "nor", "fi", "fin", "el", "gre", "ell", "he", "heb", "hu",
        "hun", "cs", "cze", "ces", "ro", "rum", "ron", "uk", "ukr", "ko", "kor", "ja", "jpn", "zh", "chi", "zho",
    )
}
