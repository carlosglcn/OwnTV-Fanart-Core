package tv.own.owntv.core.netshare

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

/**
 * glcn: a NAS folder reached over WebDAV (a Synology's WebDAV Server, Nextcloud, a router's share) — handy from
 * outside home, where SMB is not open. Folders are listed with PROPFIND; files play straight from their https
 * address, the login going with each request as a header (`#EXTHTTP` in the playlist), so no proxy is needed.
 */
internal object WebDav {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val PROPFIND_BODY = (
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
        ).toRequestBody("application/xml; charset=utf-8".toMediaType())

    fun lister(t: NetShare.Target): SmbLibrary.Lister {
        val base = (if (t.scheme == NetShare.DAVS) "https://" else "http://") + hostWithPort(t)
        val auth = if (t.user.isBlank()) null else okhttp3.Credentials.basic(t.user, t.password, Charsets.UTF_8)
        fun href(path: String): String =
            "/" + (listOf(t.share) + path.split('/', '\\')).filter { it.isNotEmpty() }.joinToString("/") { NetShare.encodeSegment(it) }

        fun propfind(path: String, depth: Int): okhttp3.Response {
            val url = base + href(path) + "/"
            val req = Request.Builder().url(url).method("PROPFIND", PROPFIND_BODY).header("Depth", depth.toString())
                .apply { auth?.let { header("Authorization", it) } }.build()
            val r = client.newCall(req).execute()
            if (r.code == 401 || r.code == 403) { r.close(); throw SmbAccess.AuthFailed(null) }
            return r
        }

        return object : SmbLibrary.Lister {
            override fun list(path: String): List<SmbAccess.Entry> = propfind(path, 1).use { r ->
                if (r.code != 207) return emptyList()
                val self = href(path).trimEnd('/')
                parse(r.body?.byteStream() ?: return emptyList()).filter { e ->
                    decodePath(e.href).trimEnd('/') != decodePath(self)
                }.mapNotNull { e ->
                    val name = decodePath(e.href.trimEnd('/')).substringAfterLast('/')
                    if (name.isEmpty() || name.startsWith(".")) null else SmbAccess.Entry(name, e.folder, e.size, e.changedMs)
                }
            }

            override fun exists(path: String): Boolean = runCatching { propfind(path, 0).use { it.code == 207 } }
                .getOrElse { if (it is SmbAccess.AuthFailed) throw it else false }

            override fun url(path: String): String = base + href(path)

            override val headerLine: String =
                if (auth == null) "" else "#EXTHTTP:{\"Authorization\":\"" + auth + "\"}\n"
        }
    }

    private fun hostWithPort(t: NetShare.Target): String {
        val h = if (t.host.contains(':') && !t.host.startsWith("[")) "[${t.host}]" else t.host
        return if (t.port == NetShare.defaultPort(t.scheme)) h else "$h:${t.port}"
    }

    private data class DavEntry(val href: String, val folder: Boolean, val size: Long, val changedMs: Long)

    /** A PROPFIND multistatus: each response's href, whether it is a folder, its size and when it changed. */
    private fun parse(input: java.io.InputStream): List<DavEntry> {
        val p = android.util.Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)
        val out = ArrayList<DavEntry>()
        var href: String? = null
        var folder = false
        var size = 0L
        var changed = 0L
        var text = StringBuilder()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = null; folder = false; size = 0L; changed = 0L }
                        "collection" -> folder = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> changed = parseDate(text.toString().trim())
                    "response" -> href?.let { out += DavEntry(it, folder, size, changed) }
                }
            }
            event = p.next()
        }
        return out
    }

    private fun parseDate(s: String): Long = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(s)?.time ?: 0L
    }.getOrDefault(0L)

    /** An href (maybe a full URL) → its path, decoded. */
    private fun decodePath(href: String): String {
        val path = if (href.startsWith("http", ignoreCase = true)) href.substringAfter("://").substringAfter('/', "").let { "/$it" } else href
        return runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(path)
    }
}
