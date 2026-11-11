package tv.own.owntv.core.trakt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * glcn: Trakt — the watched history and watchlist of a Trakt account, for people whose films and series do not
 * go through an Emby/Jellyfin server with its own Trakt plugin.
 *
 * - **Sign-in on the TV**: the device flow — the app shows a short code, the person types it at trakt.tv/activate
 *   on a phone. The tokens are kept per profile, so each profile can have its own account (or none).
 * - **Watched → Trakt**: a film or episode finished (or marked watched) is added to the account's history, by its
 *   TMDB id — only titles the app has matched to TMDB, never a guess.
 * - **Trakt → app**: the account's watched films and its watchlist, by TMDB id (see TraktSync).
 *
 * The app's Trakt keys come from the build ([configure]); a build without them shows no Trakt row at all.
 */
object Trakt {
    private const val TAG = "Trakt"
    private const val API = "https://api.trakt.tv"
    private const val FILE = "glcn_trakt"
    private val JSON = "application/json".toMediaType()

    @Volatile private var clientId = ""
    @Volatile private var clientSecret = ""
    @Volatile private var appContext: Context? = null

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    /** Bumped on sign-in / sign-out, so the settings row and Home recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun configure(context: Context, id: String, secret: String) {
        appContext = context.applicationContext
        clientId = id.trim()
        clientSecret = secret.trim()
    }

    /** This build carries Trakt keys. */
    val available: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()

    private fun prefs() = appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun connected(profileId: Long): Boolean = prefs()?.getString("$profileId.refresh", null) != null

    fun userName(profileId: Long): String? = prefs()?.getString("$profileId.user", null)

    fun disconnect(profileId: Long) {
        prefs()?.edit()?.apply {
            listOf("access", "refresh", "expires", "user").forEach { remove("$profileId.$it") }
        }?.apply()
        _version.value++
    }

    // ---- Device sign-in ----------------------------------------------------------------------------------------

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresInS: Int, val intervalS: Int)

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data object Done : Poll
        data object Expired : Poll
        data object Denied : Poll
        data class Failed(val message: String) : Poll
    }

    suspend fun startDevice(): DeviceCode? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("client_id", clientId).toString()
            http.newCall(Request.Builder().url("$API/oauth/device/code").post(body.toRequestBody(JSON)).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val j = JSONObject(r.body.string())
                DeviceCode(
                    j.getString("device_code"), j.getString("user_code"), j.optString("verification_url", "https://trakt.tv/activate"),
                    j.optInt("expires_in", 600), j.optInt("interval", 5),
                )
            }
        }.onFailure { Log.w(TAG, "device code failed", it) }.getOrNull()
    }

    suspend fun pollDevice(profileId: Long, code: DeviceCode): Poll = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("code", code.deviceCode).put("client_id", clientId).put("client_secret", clientSecret).toString()
            http.newCall(Request.Builder().url("$API/oauth/device/token").post(body.toRequestBody(JSON)).build()).execute().use { r ->
                when (r.code) {
                    200 -> {
                        storeTokens(profileId, JSONObject(r.body.string()))
                        fetchUserName(profileId)
                        _version.value++
                        Poll.Done
                    }
                    400 -> Poll.Pending
                    429 -> Poll.SlowDown
                    404, 409, 410 -> Poll.Expired
                    418 -> Poll.Denied
                    else -> Poll.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { Poll.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun storeTokens(profileId: Long, j: JSONObject) {
        val expires = System.currentTimeMillis() + j.optLong("expires_in", 0L) * 1000L
        prefs()?.edit()
            ?.putString("$profileId.access", j.getString("access_token"))
            ?.putString("$profileId.refresh", j.getString("refresh_token"))
            ?.putLong("$profileId.expires", expires)
            ?.apply()
    }

    private fun fetchUserName(profileId: Long) {
        val j = get(profileId, "/users/settings") as? JSONObject ?: return
        val name = j.optJSONObject("user")?.optString("username").orEmpty()
        if (name.isNotEmpty()) prefs()?.edit()?.putString("$profileId.user", name)?.apply()
    }

    /** A valid access token, refreshed when it is near its end; null when not signed in or the refresh failed. */
    private fun token(profileId: Long): String? {
        val p = prefs() ?: return null
        val access = p.getString("$profileId.access", null) ?: return null
        val expires = p.getLong("$profileId.expires", 0L)
        if (System.currentTimeMillis() < expires - REFRESH_MARGIN_MS) return access
        val refresh = p.getString("$profileId.refresh", null) ?: return null
        return runCatching {
            val body = JSONObject()
                .put("refresh_token", refresh).put("client_id", clientId).put("client_secret", clientSecret)
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token").toString()
            http.newCall(Request.Builder().url("$API/oauth/token").post(body.toRequestBody(JSON)).build()).execute().use { r ->
                if (r.code == 401 || r.code == 400) { disconnect(profileId); return@use null }
                if (!r.isSuccessful) return@use null
                val j = JSONObject(r.body.string())
                storeTokens(profileId, j)
                j.getString("access_token")
            }
        }.getOrNull()
    }

    private fun request(profileId: Long, path: String): Request.Builder? {
        val t = token(profileId) ?: return null
        return Request.Builder().url(API + path)
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .header("Authorization", "Bearer $t")
    }

    private fun get(profileId: Long, path: String): Any? = runCatching {
        val rq = request(profileId, path)?.get()?.build() ?: return null
        http.newCall(rq).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "GET $path → ${r.code}"); return@use null }
            val text = r.body.string().trim()
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }.onFailure { Log.w(TAG, "GET $path failed", it) }.getOrNull()

    private fun post(profileId: Long, path: String, body: JSONObject): Boolean = runCatching {
        val rq = request(profileId, path)?.post(body.toString().toRequestBody(JSON))?.build() ?: return false
        http.newCall(rq).execute().use { r ->
            if (!r.isSuccessful) Log.w(TAG, "POST $path → ${r.code}")
            r.isSuccessful
        }
    }.onFailure { Log.w(TAG, "POST $path failed", it) }.getOrDefault(false)

    // ---- Watched → Trakt ---------------------------------------------------------------------------------------

    suspend fun addMovieToHistory(profileId: Long, tmdbId: Int): Boolean = withContext(Dispatchers.IO) {
        if (!connected(profileId)) return@withContext false
        val movie = JSONObject().put("ids", JSONObject().put("tmdb", tmdbId))
        post(profileId, "/sync/history", JSONObject().put("movies", JSONArray().put(movie)))
    }

    suspend fun addEpisodeToHistory(profileId: Long, showTmdbId: Int, season: Int, episode: Int): Boolean = withContext(Dispatchers.IO) {
        if (!connected(profileId)) return@withContext false
        val show = JSONObject()
            .put("ids", JSONObject().put("tmdb", showTmdbId))
            .put("seasons", JSONArray().put(JSONObject().put("number", season).put("episodes", JSONArray().put(JSONObject().put("number", episode)))))
        post(profileId, "/sync/history", JSONObject().put("shows", JSONArray().put(show)))
    }

    // ---- Trakt → app -------------------------------------------------------------------------------------------

    /** The TMDB ids of every film the account has watched; null when it could not be read. */
    suspend fun watchedMovieTmdbIds(profileId: Long): Set<Int>? = withContext(Dispatchers.IO) {
        val a = get(profileId, "/sync/watched/movies") as? JSONArray ?: return@withContext null
        buildSet { for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb", 0)?.takeIf { it > 0 }?.let(::add) }
    }

    /** The TMDB ids of the account's watchlist films, newest first; null when it could not be read. */
    suspend fun watchlistMovieTmdbIds(profileId: Long): List<Int>? = withContext(Dispatchers.IO) {
        val a = get(profileId, "/sync/watchlist/movies") as? JSONArray ?: return@withContext null
        buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
                if (id > 0) add(o.optString("listed_at") to id)
            }
        }.sortedByDescending { it.first }.map { it.second }
    }

    private const val REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
}
