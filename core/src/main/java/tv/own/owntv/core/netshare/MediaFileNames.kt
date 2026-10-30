package tv.own.owntv.core.netshare

/**
 * glcn: what a video file's name (and its folder's) says — the title and year of a film, the show, season and
 * episode of an episode. Release names carry a lot besides the title ("Dune.Part.Two.2024.2160p.UHD.BluRay.x265-GRP"):
 * everything from the first technical word on is dropped, so TMDB is asked for "Dune Part Two" (2024).
 */
object MediaFileNames {

    data class Film(val title: String, val year: Int?)

    data class Episode(val show: String?, val season: Int, val episode: Int, val title: String?)

    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "mpg", "mpeg", "webm", "flv", "iso", "vob", "ogm", "divx", "3gp",
    )

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /** Samples, trailers and extras that sit next to a film: never listed as titles. */
    fun isExtra(name: String): Boolean {
        val n = name.substringBeforeLast('.').lowercase()
        return n == "sample" || n.endsWith("-sample") || n.endsWith(".sample") || n.startsWith("sample-") ||
            EXTRA_SUFFIXES.any { n.endsWith(it) }
    }

    /** Folders that hold no titles: extras, and the NAS's own (Synology's thumbnails, the recycle bin). */
    fun isSkippedFolder(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith(".") || n.startsWith("@") || n.startsWith("#") || n == "\$recycle.bin" ||
            n in SKIPPED_FOLDERS
    }

    /** "Season 2", "Temporada 02", "S2", "Staffel 3" → 2 / 2 / 2 / 3; null for any other folder. */
    fun seasonOfFolder(name: String): Int? {
        SEASON_FOLDER.matchEntire(name.trim())?.let { return it.groupValues[1].toIntOrNull() }
        if (name.trim().lowercase() in SPECIALS) return 0
        return null
    }

    /** A film from its file name, or from its folder's when the file's says too little ("movie.mkv", "CD1"). */
    fun film(fileName: String, folderName: String?): Film {
        val fromFile = parseFilm(fileName.substringBeforeLast('.'))
        val fromFolder = folderName?.let { parseFilm(it) }
        // A folder named like a film ("Avatar (2009)") is the better name: it is what the person typed.
        if (fromFolder != null && fromFolder.year != null && fromFolder.title.isNotBlank()) {
            if (fromFile.year == null || fromFile.year == fromFolder.year) return fromFolder
        }
        if (fromFile.title.length < 2 || fromFile.title.lowercase() in GENERIC_NAMES || fromFile.title.all { it.isDigit() || it == ' ' }) {
            if (fromFolder != null && fromFolder.title.isNotBlank()) return fromFolder
        }
        return fromFile
    }

    /** True when a folder's own name reads as one film ("Avatar (2009)"), not a group ("Animación"). */
    fun isFilmFolder(name: String): Boolean = parseFilm(name).year != null

    fun parseFilm(raw: String): Film {
        var s = spaced(raw)
        s = BRACKETS.replace(s, " ")
        val yearMatch = YEAR.findAll(s).lastOrNull { it.range.first > 0 }
        var year: Int? = null
        if (yearMatch != null) {
            year = yearMatch.groupValues[1].toInt()
            s = s.substring(0, yearMatch.range.first)
        }
        s = cutAtJunk(s)
        return Film(tidy(s), year)
    }

    /** An episode from its file name; [season] from its folder when the name has only the episode. */
    fun episode(fileName: String, folderSeason: Int?): Episode? {
        val base = spaced(fileName.substringBeforeLast('.'))
        SXXEYY.find(base)?.let { m ->
            return Episode(showBefore(base, m.range.first), m.groupValues[1].toInt(), m.groupValues[2].toInt(), titleAfter(base, m.range.last + 1))
        }
        NXNN.find(base)?.let { m ->
            return Episode(showBefore(base, m.range.first), m.groupValues[1].toInt(), m.groupValues[2].toInt(), titleAfter(base, m.range.last + 1))
        }
        // "Cap.102" (the Spanish way): season 1, episode 02.
        CAP.find(base)?.let { m ->
            val digits = m.groupValues[1]
            val season = if (digits.length >= 3) digits.dropLast(2).toInt() else (folderSeason ?: 1)
            val ep = if (digits.length >= 3) digits.takeLast(2).toInt() else digits.toInt()
            return Episode(showBefore(base, m.range.first), season, ep, titleAfter(base, m.range.last + 1))
        }
        EPISODE_WORD.find(base)?.let { m ->
            return Episode(showBefore(base, m.range.first), folderSeason ?: 1, m.groupValues[1].toInt(), titleAfter(base, m.range.last + 1))
        }
        // "03 - Title" inside a season folder.
        if (folderSeason != null) LEADING_NUMBER.find(base)?.let { m ->
            return Episode(null, folderSeason, m.groupValues[1].toInt(), titleAfter(base, m.range.last + 1))
        }
        return null
    }

    /** A show's folder name, tidied; a year in it stays, as "Show (2019)", to tell remakes apart. */
    fun show(folderName: String): String {
        val film = parseFilm(folderName)
        return if (film.year != null) "${film.title} (${film.year})" else film.title
    }

    private fun showBefore(base: String, end: Int): String? =
        tidy(cutAtJunk(BRACKETS.replace(base.substring(0, end), " "))).takeIf { it.length >= 2 }

    private fun titleAfter(base: String, start: Int): String? {
        if (start >= base.length) return null
        return tidy(cutAtJunk(BRACKETS.replace(base.substring(start), " "))).takeIf { it.length >= 2 }
    }

    /** Dots and underscores between words → spaces (only when the name has no spaces of its own). */
    private fun spaced(raw: String): String {
        val s = raw.trim()
        return if (' ' !in s && (s.count { it == '.' } >= 2 || '_' in s)) s.replace('.', ' ').replace('_', ' ') else s.replace('_', ' ')
    }

    private fun cutAtJunk(s: String): String {
        val m = JUNK.find(s) ?: return s
        return if (m.range.first == 0) s else s.substring(0, m.range.first)
    }

    private fun tidy(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().trim('-', '–', '.', ',', ' ', '(', '[', '_').trim()

    private val YEAR = Regex("""[\s(.\[]((?:19|20)\d{2})(?=[\s).\]]|$)""")
    private val BRACKETS = Regex("""\[[^\]]*]|\{[^}]*}""")
    private val SXXEYY = Regex("""(?i)\bS(\d{1,2})\s*[._-]?\s*E(\d{1,3})(?:\s*-?\s*E\d{1,3})?\b""")
    private val NXNN = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val CAP = Regex("""(?i)\bcap(?:itulo|ítulo)?\.?\s*(\d{1,4})\b""")
    private val EPISODE_WORD = Regex("""(?i)\b(?:ep|episode|episodio|episodi|folge)\.?\s*(\d{1,3})\b""")
    private val LEADING_NUMBER = Regex("""^\s*(\d{1,3})(?=\s|[-.]|$)""")
    private val SEASON_FOLDER = Regex("""(?i)(?:season|temporada|temp|staffel|saison|stagione|serie|s)\s*[._-]?\s*(\d{1,2})""")
    private val JUNK = Regex(
        """(?i)(?:^|[\s(\[-])(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr10\+?|hdr|dovi|dolby\s?vision|bluray|blu-ray|bdrip|brrip|bdremux|""" +
            """remux|web-?dl|webrip|hdtv|dvdrip|dvd|hdrip|x264|x265|h\s?264|h\s?265|hevc|avc|xvid|divx|10bit|8bit|dts(?:-hd)?|truehd|atmos|ac3|eac3|""" +
            """ddp?5\.?1|aac|flac|5\.1|7\.1|proper|repack|extended|unrated|remastered|imax|multi|dual|castellano|spanish|español|latino|vose|""" +
            """subs?|subtitulado|screener|hdcam)(?=$|[\s)\].-])""",
    )
    private val EXTRA_SUFFIXES = listOf("-trailer", ".trailer", " trailer", "-featurette", "-behindthescenes", "-deleted", "-interview", "-scene", "-short", "-extra")
    private val SKIPPED_FOLDERS = setOf(
        "extras", "extra", "featurettes", "trailers", "trailer", "behind the scenes", "deleted scenes", "interviews", "scenes", "shorts",
        "samples", "sample", "subs", "subtitles", "subtitulos", "subtítulos", "lost+found", "@eadir", "#recycle", "#snapshot",
    )
    private val SPECIALS = setOf("specials", "especiales", "extras de temporada")
    private val GENERIC_NAMES = setOf("movie", "film", "pelicula", "película", "video", "cd1", "cd2", "part1", "part 1", "disc1", "main")
}
