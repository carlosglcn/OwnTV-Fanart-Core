package tv.own.owntv.core.netshare

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: a folder shared on the home network (a NAS, a PC) added as a playlist.
 *
 * The source's link is `smb://user:password@host[:port]/share?movies=Films&series=TV%20Shows` — the share, and
 * inside it the folder of films and the folder of series. The sync walks those folders ([SmbLibrary]) and turns
 * the files into the M3U the existing pipeline understands, so they land in Movies and Series, are matched
 * with TMDB like any other title and get their pictures, page and trailer.
 *
 * Each file's address in the library is `smb://host/share/path` — no password in it. When a file is played,
 * [NetShareProxy] serves it to the player over a loopback HTTP address, asking [credentialsFor] for the login.
 */
object NetShare {

    /** A share and how to sign in to it; [movies] / [series] are folders inside the share ("" = none). */
    data class Target(
        val host: String,
        val port: Int,
        val share: String,
        val user: String,
        val password: String,
        val domain: String,
        val movies: String,
        val series: String,
        /** glcn: how the share is reached — [SCHEME] (SMB), [DAV] or [DAVS] (WebDAV over http / https). */
        val scheme: String = SCHEME,
    ) {
        /** `smb://host[:port]/share` — the start of every file address in this share. */
        val root: String get() = scheme + (if (port == defaultPort(scheme)) hostPort(host, DEFAULT_PORT) else hostPort(host, port).let { if (port == DEFAULT_PORT) "$it:$port" else it }) +
            "/" + encodeSegment(share)
    }

    data class Login(val user: String, val password: String, val domain: String)

    fun isShare(url: String): Boolean = url.trim().startsWith(SCHEME, ignoreCase = true)

    /** glcn: the link's protocol when it is a network folder (SMB or WebDAV); null otherwise. */
    fun schemeOf(url: String): String? {
        val u = url.trim()
        return listOf(SCHEME, DAVS, DAV, NFS).firstOrNull { u.startsWith(it, ignoreCase = true) }
    }

    /** glcn: a file the players cannot open themselves (SMB, NFS): it plays through [NetShareProxy]. */
    fun needsProxy(url: String): Boolean {
        val u = url.trim()
        return u.startsWith(SCHEME, ignoreCase = true) || u.startsWith(NFS, ignoreCase = true)
    }

    /** A network folder of any kind (SMB or WebDAV) — what the add-source form edits as one. */
    fun isNetworkFolder(url: String): Boolean = schemeOf(url) != null

    fun defaultPort(scheme: String): Int = when (scheme) { DAV -> 80; DAVS -> 443; NFS -> NFS_PORT; else -> DEFAULT_PORT }

    /** The source link → its share; null when it is not an `smb://` link with a host and a share. */
    fun parse(url: String): Target? {
        val raw = url.trim()
        val scheme = schemeOf(raw) ?: return null
        val rest = raw.substring(scheme.length)
        val query = rest.substringAfter('?', "")
        val beforeQuery = rest.substringBefore('?')
        val slash = beforeQuery.indexOf('/')
        val authority = if (slash >= 0) beforeQuery.substring(0, slash) else beforeQuery
        val path = if (slash >= 0) beforeQuery.substring(slash + 1) else ""
        val at = authority.lastIndexOf('@')
        val info = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val (host, port) = splitHostPort(hostPort, defaultPort(scheme)) ?: return null
        val share = decode(path.substringBefore('/')).takeIf { it.isNotBlank() } ?: return null
        var user = decode(info.substringBefore(':'))
        val password = if (':' in info) decode(info.substringAfter(':')) else ""
        var domain = ""
        // "OFFICE\ana" or "OFFICE;ana": a Windows domain before the user.
        val sep = user.indexOfFirst { it == '\\' || it == ';' }
        if (sep > 0) { domain = user.substring(0, sep); user = user.substring(sep + 1) }
        val params = query.split('&').filter { '=' in it }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }
        return Target(
            host = host, port = port, share = share, user = user, password = password, domain = domain,
            movies = cleanFolder(params[PARAM_MOVIES].orEmpty()), series = cleanFolder(params[PARAM_SERIES].orEmpty()),
            scheme = scheme,
        )
    }

    /** The source link for a share, as the add-source form saves it. */
    fun link(host: String, share: String, user: String, password: String, movies: String, series: String, scheme: String = SCHEME): String {
        val def = defaultPort(scheme)
        val bare = host.trim().replace(Regex("^[a-zA-Z]+://"), "").trim('/')
        val (h, p) = splitHostPort(bare, def) ?: (bare to def)
        val info = if (user.isBlank()) "" else encodeSegment(user.trim()) + (if (password.isNotEmpty()) ":" + encodeSegment(password) else "") + "@"
        val q = listOfNotNull(
            cleanFolder(movies).takeIf { it.isNotEmpty() }?.let { PARAM_MOVIES + "=" + encodeSegment(it) },
            cleanFolder(series).takeIf { it.isNotEmpty() }?.let { PARAM_SERIES + "=" + encodeSegment(it) },
        ).joinToString("&")
        val auth = if (p == def) hostPort(h, DEFAULT_PORT) else hostPort(h, p).let { if (p == DEFAULT_PORT) "$it:$p" else it }
        val shareText = if (scheme == NFS) "/" + share.trim().replace('\\', '/').trim('/') else share.trim().trim('/', '\\')
        return scheme + info + auth + "/" + encodeSegment(shareText) + (if (q.isNotEmpty()) "?$q" else "")
    }

    // ---- Logins for playback -----------------------------------------------------------------------------------

    private val logins = ConcurrentHashMap<String, Login>()

    /** Keeps the login of every share source in [urls] (the app passes all its sources, at start and on change). */
    fun rememberSources(urls: List<String>) {
        for (u in urls) parse(u)?.let { remember(it) }
    }

    fun remember(t: Target) {
        logins[key(t.host, t.port, t.share)] = Login(t.user, t.password, t.domain)
    }

    fun credentialsFor(host: String, port: Int, share: String): Login? = logins[key(host, port, share)]

    private fun key(host: String, port: Int, share: String) = host.lowercase() + ":" + port + "/" + share.lowercase()

    // ---- Addresses --------------------------------------------------------------------------------------------

    /** A file's address: `smb://host[:port]/share/a/b.mkv`, each part encoded. */
    fun fileUrl(t: Target, path: String): String =
        t.root + "/" + path.split('/', '\\').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    /** A file address → host, port, share and the path inside the share (decoded, '/'-separated). */
    data class FileRef(val host: String, val port: Int, val share: String, val path: String, val scheme: String = SCHEME)

    fun fileRef(url: String): FileRef? {
        if (!needsProxy(url)) return null
        val scheme = schemeOf(url) ?: return null
        val rest = url.trim().substring(scheme.length).substringBefore('?')
        val parts = rest.split('/')
        if (parts.size < 3) return null
        val authority = parts[0].substringAfterLast('@')
        val (host, port) = splitHostPort(authority, defaultPort(scheme)) ?: return null
        val share = decode(parts[1])
        val path = parts.drop(2).filter { it.isNotEmpty() }.joinToString("/") { decode(it) }
        if (share.isBlank() || path.isBlank()) return null
        return FileRef(host, port, share, path, scheme)
    }

    fun hostPort(host: String, port: Int): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port == DEFAULT_PORT) h else "$h:$port"
    }

    private fun splitHostPort(s: String, defaultPort: Int = DEFAULT_PORT): Pair<String, Int>? {
        if (s.isBlank()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return s.substring(1, end) to port
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.indexOf(':') == colon) {
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            return s.substring(0, colon) to port
        }
        return s to defaultPort
    }

    private fun cleanFolder(f: String): String = f.replace('\\', '/').trim().trim('/')

    fun encodeSegment(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(s)

    const val SCHEME = "smb://"
    const val DEFAULT_PORT = 445
    /** glcn: WebDAV over http and https (a NAS's WebDAV server; a Synology answers on 5005 / 5006). */
    const val DAV = "webdav://"
    /** glcn: NFS v3 — the share is the export path ("/volume1/video"), kept as one encoded part of the link. */
    const val NFS = "nfs://"
    const val NFS_PORT = 2049
    const val DAVS = "webdavs://"
    private const val PARAM_MOVIES = "movies"
    private const val PARAM_SERIES = "series"
}
