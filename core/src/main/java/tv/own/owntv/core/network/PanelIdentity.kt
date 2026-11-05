package tv.own.owntv.core.network

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Which User-Agent to present to a panel the user set none for, and the one that worked last time.
 *
 * Panels filter on identity in two opposite ways: some blocklist player names, some only admit the apps
 * they know. So one fallback is not enough. [next] walks a short, finite list and a panel keeps the first
 * identity that actually produced a picture. That lesson is saved across app starts (keyed by `host:port`),
 * because re-discovering it costs several seconds of black screen on the first channel of every session.
 *
 * Only a proven success is remembered: a refusal alone can be one dead channel, not the panel's rule. A
 * saved identity that is refused later is dropped with [forget], so a panel changing its rules is back to
 * the normal order on the next tune.
 *
 * A User-Agent the user configured never comes through here: that setting is a decision, not a guess.
 */
object PanelIdentity {

    private const val PREFS = "panel_identity"

    private val ladder = listOf(
        HttpClient.DEFAULT_USER_AGENT,
        HttpClient.FALLBACK_USER_AGENT,
        HttpClient.COMPATIBLE_USER_AGENT,
    )

    private val learned = ConcurrentHashMap<String, String>()

    @Volatile private var prefs: SharedPreferences? = null

    /** Load the saved lessons. Without this they simply last for the process, as in tests. */
    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.all.forEach { (host, ua) -> if (ua is String && ua in ladder) learned[host] = ua }
        prefs = p
    }

    /** The identity a tune of [url] opens with. */
    fun startFor(url: String): String = learned[hostKey(url)] ?: HttpClient.DEFAULT_USER_AGENT

    /** The identity to try after [current] was refused; null when the list is spent. */
    fun next(current: String): String? {
        val at = ladder.indexOf(current)
        return if (at < 0) null else ladder.getOrNull(at + 1)
    }

    /** [userAgent] produced a picture from [url]'s panel. */
    fun remember(url: String, userAgent: String) {
        if (userAgent !in ladder) return
        if (userAgent == HttpClient.DEFAULT_USER_AGENT) return forget(url)
        val host = hostKey(url)
        if (learned.put(host, userAgent) != userAgent) prefs?.edit()?.putString(host, userAgent)?.apply()
    }

    /** Every identity was refused, so whatever was saved for this panel no longer holds. */
    fun forget(url: String) {
        val host = hostKey(url)
        if (learned.remove(host) != null) prefs?.edit()?.remove(host)?.apply()
    }

    private fun hostKey(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return authority.ifBlank { url }.lowercase()
    }

    internal fun clearForTest() {
        learned.clear()
    }
}
