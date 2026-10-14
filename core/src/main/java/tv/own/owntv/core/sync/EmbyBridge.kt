package tv.own.owntv.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * glcn: an Emby (or Jellyfin) server added as an ordinary M3U playlist.
 *
 * The user adds `http://user:password@nas:8096` as an M3U link. Instead of downloading a playlist,
 * the sync signs in to the server, reads the user's films and episodes and turns them into the M3U
 * the existing pipeline already understands — films as `type="movie"`, episodes as
 * `type="series"` named "Show S01E02 Title" — so they land in Movies and Series with their pictures,
 * and resume, favourites and Keep watching work as for any other playlist.
 *
 * Playback is the original file, untouched (`/Videos/{id}/stream?static=true`), which is what the
 * player on a TV box wants on the home network.
 */
internal object EmbyBridge {

    /** Server root (no trailing slash) and the account to sign in with. */
    data class Target(val root: String, val user: String, val password: String)

    /** Signed in: [api] is the root plus the server's API prefix ("/emby" on Emby, none on Jellyfin). */
    data class Session(val api: String, val token: String, val userId: String)

    /** The server refused the user name or password. */
    class AuthFailed : IOException("Emby: usuario o contraseña incorrectos")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private const val AUTH_FIELDS =
        "Client=\"OwnTV\", Device=\"OwnTV\", DeviceId=\"owntv-glcn\", Version=\"1.0\""

    private const val FIELDS = "ProductionYear,ParentIndexNumber,IndexNumber,SeriesName,SeriesId,Container,Genres"

    /**
     * `http(s)://user[:password]@host[:port][/path]` with no file name in the path → a possible server.
     * Anything else (a `.m3u` file, `get.php`, a link without a user) is an ordinary playlist.
     */
    fun parse(url: String): Target? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val info = uri.rawUserInfo?.takeIf { it.isNotEmpty() } ?: return null
        val host = uri.host ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.substringAfterLast('/').contains('.')) return null
        if (!uri.rawQuery.isNullOrEmpty()) return null
        val user = percentDecode(info.substringBefore(':'))
        val password = if (':' in info) percentDecode(info.substringAfter(':')) else ""
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return Target("$scheme://$hostPart$port$path", user, password)
    }

    /** "%40" → "@", leaving "+" as it is (it is a real character in a password). */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { out.write(b); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }

    /**
     * The API prefix this server answers on, or null when it is not Emby/Jellyfin at all (then the
     * link is treated as an ordinary playlist).
     */
    suspend fun detect(t: Target): String? = withContext(Dispatchers.IO) {
        for (prefix in listOf("/emby", "")) {
            val ok = runCatching {
                client.newCall(Request.Builder().url("${t.root}$prefix/System/Info/Public").get().build()).execute().use { r ->
                    r.isSuccessful && JSONObject(r.body?.string().orEmpty()).has("Id")
                }
            }.getOrDefault(false)
            if (ok) return@withContext "${t.root}$prefix"
        }
        null
    }

    /** Signs in; throws [AuthFailed] on a wrong user or password. */
    suspend fun login(t: Target, api: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("Username", t.user).put("Pw", t.password).toString()
            .toRequestBody("application/json".toMediaType())
        // Emby reads X-Emby-Authorization ("Emby …"), Jellyfin reads Authorization ("MediaBrowser …");
        // the same client fields also go in the query string, which older Emby builds read instead.
        val query = "X-Emby-Client=OwnTV&X-Emby-Device-Name=OwnTV&X-Emby-Device-Id=owntv-glcn&X-Emby-Client-Version=1.0"
        val req = Request.Builder().url("$api/Users/AuthenticateByName?$query")
            .header("X-Emby-Authorization", "Emby $AUTH_FIELDS")
            .header("Authorization", "MediaBrowser $AUTH_FIELDS")
            .post(body)
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw AuthFailed()
            if (!r.isSuccessful) throw IOException("Emby: HTTP ${r.code}")
            val j = JSONObject(r.body?.string().orEmpty())
            Session(api, j.getString("AccessToken"), j.getJSONObject("User").getString("Id"))
        }
    }

    private fun items(s: Session, kind: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var start = 0
        while (true) {
            val sort = if (kind == "Movie") "SortName" else "SeriesSortName,ParentIndexNumber,IndexNumber"
            val url = "${s.api}/Users/${s.userId}/Items?Recursive=true&IncludeItemTypes=$kind&Fields=$FIELDS" +
                "&SortBy=$sort&IsMissing=false&StartIndex=$start&Limit=$PAGE"
            val req = Request.Builder().url(url).header("X-Emby-Token", s.token).get().build()
            val page = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Emby: HTTP ${r.code}")
                JSONObject(r.body?.string().orEmpty())
            }
            val arr = page.optJSONArray("Items") ?: break
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            start += arr.length()
            if (arr.length() == 0 || start >= page.optInt("TotalRecordCount", 0)) break
        }
        return out
    }

    private fun attr(v: String) = v.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()
    private fun line(v: String) = v.replace('\n', ' ').replace('\r', ' ').trim()

    /** The whole library as M3U text. */
    suspend fun playlist(s: Session): String = withContext(Dispatchers.IO) {
        fun image(id: String) = "${s.api}/Items/$id/Images/Primary?maxWidth=500&api_key=${s.token}"
        fun stream(it: JSONObject): String {
            val container = it.optString("Container").substringBefore(',').takeIf { c -> c.isNotBlank() }
            return "${s.api}/Videos/${it.getString("Id")}/stream" +
                (container?.let { c -> ".$c" } ?: "") + "?static=true&api_key=${s.token}"
        }
        val sb = StringBuilder("#EXTM3U\n")
        for (m in items(s, "Movie")) {
            val title = m.optString("Name")
            val year = m.optInt("ProductionYear", 0)
            val label = if (year > 0) "$title ($year)" else title
            val group = m.optJSONArray("Genres")?.optString(0)?.takeIf { it.isNotBlank() } ?: MOVIES_GROUP
            sb.append("#EXTINF:-1 type=\"movie\" tvg-logo=\"").append(attr(image(m.getString("Id"))))
                .append("\" group-title=\"").append(attr(group)).append("\",").append(line(label)).append('\n')
                .append(stream(m)).append('\n')
        }
        for (e in items(s, "Episode")) {
            if (!e.has("ParentIndexNumber") || !e.has("IndexNumber")) continue
            val show = e.optString("SeriesName").ifBlank { SERIES_GROUP }
            val label = String.format(
                java.util.Locale.ROOT, "%s S%02dE%02d %s",
                show, e.getInt("ParentIndexNumber"), e.getInt("IndexNumber"), e.optString("Name"),
            ).trim()
            val logo = e.optString("SeriesId").takeIf { it.isNotBlank() }?.let(::image) ?: image(e.getString("Id"))
            sb.append("#EXTINF:-1 type=\"series\" tvg-logo=\"").append(attr(logo))
                .append("\" group-title=\"").append(SERIES_GROUP).append("\",").append(line(label)).append('\n')
                .append(stream(e)).append('\n')
        }
        sb.toString()
    }

    private const val PAGE = 1000
    private const val MOVIES_GROUP = "Películas"
    private const val SERIES_GROUP = "Series"
}
