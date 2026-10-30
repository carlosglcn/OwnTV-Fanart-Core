package tv.own.owntv.core.netshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * glcn: a share's films and series as M3U text — what [tv.own.owntv.core.sync.M3uSyncer] reads, the same way it
 * reads an Emby server ([tv.own.owntv.core.sync.EmbyBridge]). Films are `type="movie"` named "Title (Year)";
 * episodes are `type="series"` named "Show S01E02 Title". Titles come from file and folder names; the pictures,
 * synopsis and trailer come later, from TMDB, as for any playlist.
 *
 * Films: every video under the films folder. A folder named like a film ("Avatar (2009)") gives the name; any
 * other folder ("Animation", "4K") becomes the film's category. Series: each folder in the series folder is a
 * show; its videos are its episodes ("S01E02", "1x02", "Cap.102", or "02" inside "Season 1").
 */
object SmbLibrary {

    class FolderMissing(val folder: String) : java.io.IOException("SMB folder not found: $folder")

    /** What a test found: the share opened, and how many films and episodes its folders hold (-1 = not asked). */
    data class Count(val films: Int, val episodes: Int)

    /** What the walk needs from a share, whatever speaks to it (SMB, WebDAV). */
    internal interface Lister {
        fun list(path: String): List<SmbAccess.Entry>
        fun exists(path: String): Boolean
        fun url(path: String): String
        /** An `#EXTHTTP` line (with its newline) the player needs for each file — a WebDAV login — or "". */
        val headerLine: String get() = ""
    }

    suspend fun playlist(t: NetShare.Target): String = withContext(Dispatchers.IO) {
        if (t.scheme == NetShare.DAV || t.scheme == NetShare.DAVS) return@withContext build(WebDav.lister(t), t)
        NetShare.remember(t)
        if (t.scheme == NetShare.NFS) {
            val nfs = try {
                NfsAccess.mount(t.host, t.share, t.user)
            } catch (e: Exception) {
                NfsAccess.forget(t.host, t.share, t.user)
                throw java.io.IOException("NFS mount failed: ${e.javaClass.simpleName}: ${e.message}", e)
            }
            return@withContext build(object : Lister {
                override fun list(path: String) = NfsAccess.list(nfs, path)
                override fun exists(path: String) = NfsAccess.folderExists(nfs, path)
                override fun url(path: String) = NetShare.fileUrl(t, path)
            }, t)
        }
        val login = NetShare.Login(t.user, t.password, t.domain)
        SmbAccess.withShare(t.host, t.port, t.share, login) { share ->
            build(object : Lister {
                override fun list(path: String) = SmbAccess.list(share, path)
                override fun exists(path: String) = SmbAccess.folderExists(share, path)
                override fun url(path: String) = NetShare.fileUrl(t, path)
            }, t)
        }
    }

    private fun build(lister: Lister, t: NetShare.Target): String {
        val sb = StringBuilder("#EXTM3U\n")
        if (t.movies.isNotEmpty()) {
            if (!lister.exists(t.movies)) throw FolderMissing(t.movies)
            films(lister, t, sb)
        }
        if (t.series.isNotEmpty()) {
            if (!lister.exists(t.series)) throw FolderMissing(t.series)
            series(lister, t, sb)
        }
        return sb.toString()
    }

    /** Opens the share and counts what the folders hold, for the form's Test button. */
    suspend fun test(t: NetShare.Target): Count {
        val text = playlist(t)
        var films = 0
        var episodes = 0
        for (line in text.lineSequence()) {
            if (!line.startsWith("#EXTINF")) continue
            if (line.contains(TYPE_MOVIE)) films++ else if (line.contains(TYPE_SERIES)) episodes++
        }
        return Count(if (t.movies.isEmpty()) -1 else films, if (t.series.isEmpty()) -1 else episodes)
    }

    private fun films(lister: Lister, t: NetShare.Target, sb: StringBuilder) {
        // (path, the category it is under, the folder it sits in)
        fun walk(path: String, depth: Int, group: String?, folder: String?) {
            if (depth > MAX_DEPTH) return
            val entries = runCatching { lister.list(path) }.getOrDefault(emptyList())
            for (e in entries.sortedBy { it.name.lowercase() }) {
                val child = "$path/${e.name}"
                if (e.folder) {
                    if (MediaFileNames.isSkippedFolder(e.name)) continue
                    val filmFolder = MediaFileNames.isFilmFolder(e.name)
                    walk(child, depth + 1, if (filmFolder) group else (group ?: e.name), if (filmFolder) e.name else null)
                    continue
                }
                if (!MediaFileNames.isVideo(e.name) || MediaFileNames.isExtra(e.name)) continue
                if (e.size in 1 until MIN_FILM_BYTES) continue
                val film = MediaFileNames.film(e.name, folder)
                if (film.title.isBlank()) continue
                val label = if (film.year != null) "${film.title} (${film.year})" else film.title
                sb.append("#EXTINF:-1 ").append(TYPE_MOVIE)
                    .quality(e.name)
                    .extra("glcn-year", film.year?.toString())
                    .extra("glcn-added", e.changedMs.takeIf { it > 0 }?.toString())
                    .append(" group-title=\"").append(attr(group ?: MOVIES_GROUP)).append("\",").append(line(label)).append('\n')
                    .append(lister.headerLine).append(lister.url(child)).append('\n')
            }
        }
        walk(t.movies, 0, null, null)
    }

    private fun series(lister: Lister, t: NetShare.Target, sb: StringBuilder) {
        fun emit(show: String, ep: MediaFileNames.Episode, path: String, changedMs: Long) {
            val label = String.format(java.util.Locale.ROOT, "%s S%02dE%02d %s", show, ep.season, ep.episode, ep.title.orEmpty()).trim()
            sb.append("#EXTINF:-1 ").append(TYPE_SERIES)
                .quality(path.substringAfterLast('/'))
                .extra("glcn-added", changedMs.takeIf { it > 0 }?.toString())
                .append(" group-title=\"").append(SERIES_GROUP).append("\",").append(line(label)).append('\n')
                .append(lister.headerLine).append(lister.url(path)).append('\n')
        }
        /** A show's folder: every video inside it, at any depth, with the season its folder names. */
        fun walkShow(show: String, path: String, depth: Int, folderSeason: Int?) {
            if (depth > MAX_DEPTH) return
            val entries = runCatching { lister.list(path) }.getOrDefault(emptyList())
            for (e in entries.sortedBy { it.name.lowercase() }) {
                val child = "$path/${e.name}"
                if (e.folder) {
                    if (MediaFileNames.isSkippedFolder(e.name)) continue
                    walkShow(show, child, depth + 1, MediaFileNames.seasonOfFolder(e.name) ?: folderSeason)
                    continue
                }
                if (!MediaFileNames.isVideo(e.name) || MediaFileNames.isExtra(e.name)) continue
                val ep = MediaFileNames.episode(e.name, folderSeason) ?: continue
                emit(show, ep, child, e.changedMs)
            }
        }
        val top = runCatching { lister.list(t.series) }.getOrDefault(emptyList())
        for (e in top.sortedBy { it.name.lowercase() }) {
            val child = "${t.series}/${e.name}"
            if (e.folder) {
                if (MediaFileNames.isSkippedFolder(e.name)) continue
                val show = MediaFileNames.show(e.name).takeIf { it.isNotBlank() } ?: continue
                walkShow(show, child, 1, MediaFileNames.seasonOfFolder(e.name))
            } else if (MediaFileNames.isVideo(e.name) && !MediaFileNames.isExtra(e.name)) {
                // Loose in the series folder: the show comes from the name itself ("Show.S01E02.mkv").
                val ep = MediaFileNames.episode(e.name, null) ?: continue
                val show = ep.show ?: continue
                emit(show, ep, child, e.changedMs)
            }
        }
    }

    /** The quality the file's own name advertises (4K, HDR, Atmos…) — the clean title shown loses it. */
    private fun StringBuilder.quality(fileName: String): StringBuilder {
        val q = tv.own.owntv.core.catalog.ProviderCatalogMetadataParser.parse(fileName.substringBeforeLast('.'), null)
        if (q.qualityRank > 0) extra("glcn-rank", q.qualityRank.toString())
        return extra("glcn-quality", q.capabilities)
    }

    private fun StringBuilder.extra(key: String, value: String?): StringBuilder {
        if (!value.isNullOrBlank()) append(' ').append(key).append("=\"").append(attr(value)).append('"')
        return this
    }

    private fun attr(v: String) = v.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()
    private fun line(v: String) = v.replace('\n', ' ').replace('\r', ' ').replace(',', ' ').trim()

    private const val TYPE_MOVIE = "type=\"movie\""
    private const val TYPE_SERIES = "type=\"series\""
    private const val MAX_DEPTH = 6
    /** Smaller than this is a clip, not a film (when the share says the size). */
    private const val MIN_FILM_BYTES = 40L * 1024 * 1024
    private const val MOVIES_GROUP = "Películas"
    private const val SERIES_GROUP = "Series"
}
