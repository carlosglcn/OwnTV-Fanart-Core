package tv.own.owntv.core.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * glcn: what the Playlists screen knows about each playlist beyond its row in the database — kept on this device.
 *
 * - **Off**: a playlist switched off is kept (its content, favourites, history) but neither shown nor refreshed,
 *   until it is switched back on ([activeProfileSources] and auto-refresh leave it out).
 * - **Last failure**: the raw message of the last sync that failed for good, and when; cleared by a sync that
 *   works. The screen tells it in plain words ([tv.own.owntv.core.util.classifySyncFailure]).
 * - **Expiry**: the subscription's end (ms), as last read from the provider, for the warning before it ends.
 * - **Mark**: the user's own colour (a palette index) and tag text for the playlist's mark, if any.
 *
 * [version] moves on every change, so screens can re-read.
 */
object SourceGlcnPrefs {
    private const val PREFS = "glcn_sources"

    private fun prefs() = runCatching {
        org.koin.core.context.GlobalContext.get().get<android.content.Context>()
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    private val _disabled = MutableStateFlow(readDisabled())
    /** The playlists switched off (ids). */
    val disabled: StateFlow<Set<Long>> = _disabled.asStateFlow()

    private fun readDisabled(): Set<Long> =
        prefs()?.getString(KEY_DISABLED, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    fun isDisabled(id: Long) = id in _disabled.value

    fun setDisabled(id: Long, off: Boolean) {
        val next = if (off) _disabled.value + id else _disabled.value - id
        prefs()?.edit()?.putString(KEY_DISABLED, next.joinToString(","))?.apply()
        _disabled.value = next
        bump()
    }

    // ---- Last failure

    fun failed(id: Long, message: String?) {
        prefs()?.edit()?.putString("$id$ERR", message.orEmpty())?.putLong("$id$ERR_AT", System.currentTimeMillis())?.apply()
        bump()
    }

    fun succeeded(id: Long) {
        val p = prefs() ?: return
        if (!p.contains("$id$ERR")) return
        p.edit().remove("$id$ERR").remove("$id$ERR_AT").apply()
        bump()
    }

    /** The last failure's raw message and time, or null when the last sync worked. */
    fun lastFailure(id: Long): Pair<String, Long>? = prefs()?.let { p ->
        if (!p.contains("$id$ERR")) null else p.getString("$id$ERR", "").orEmpty() to p.getLong("$id$ERR_AT", 0L)
    }

    // ---- Expiry

    fun setExpiry(id: Long, ms: Long?) {
        val e = prefs()?.edit() ?: return
        if (ms == null) e.remove("$id$EXP") else e.putLong("$id$EXP", ms)
        e.apply()
    }

    fun expiry(id: Long): Long? = prefs()?.let { p -> if (p.contains("$id$EXP")) p.getLong("$id$EXP", 0L) else null }

    /** True once per playlist and end date: the warning before it ends is shown only the first time. */
    fun takeExpiryWarning(id: Long, ms: Long): Boolean {
        val p = prefs() ?: return false
        if (p.getLong("$id$WARNED", -1L) == ms) return false
        p.edit().putLong("$id$WARNED", ms).apply()
        return true
    }

    // ---- Mark

    fun markColour(id: Long): Int? = prefs()?.let { p -> if (p.contains("$id$COLOUR")) p.getInt("$id$COLOUR", 0) else null }
    fun markText(id: Long): String? = prefs()?.getString("$id$TEXT", null)?.takeIf { it.isNotBlank() }

    fun setMark(id: Long, colour: Int?, text: String?) {
        val e = prefs()?.edit() ?: return
        if (colour == null) e.remove("$id$COLOUR") else e.putInt("$id$COLOUR", colour)
        if (text.isNullOrBlank()) e.remove("$id$TEXT") else e.putString("$id$TEXT", text.trim())
        e.apply()
        bump()
    }

    private const val KEY_DISABLED = "disabled"
    private const val ERR = ".err"
    private const val ERR_AT = ".errAt"
    private const val EXP = ".exp"
    private const val WARNED = ".warned"
    private const val COLOUR = ".colour"
    private const val TEXT = ".text"
}
