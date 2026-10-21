package tv.own.owntv.core.metadata

import android.content.Context
import tv.own.owntv.core.settings.SettingsRepository

/**
 * glcn: the platform badge of a title (Netflix, HBO…), fetched once per title and remembered on the
 * device. A small side cache, not a table, so the database schema stays as it is.
 */
class PlatformBadgeRepository(
    context: Context,
    private val provider: MetadataProvider,
    private val settings: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences("owntv_platform_badges", Context.MODE_PRIVATE)
    private val memory = HashMap<String, PlatformBadge?>()

    /** What is already known on the device, without any network: [Known.Unknown] when nothing is stored yet. */
    fun peek(tmdbId: Int, isTv: Boolean): Known {
        if (tmdbId <= 0) return Known.Unknown
        val key = (if (isTv) "tv3_" else "mv3_") + tmdbId
        synchronized(memory) { if (memory.containsKey(key)) return Known.Value(memory[key]) }
        val raw = prefs.getString(key, null) ?: return Known.Unknown
        val parts = raw.split('|')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return Known.Unknown
        val hasBadge = parts.getOrNull(1) == "1"
        if (System.currentTimeMillis() - at >= (if (hasBadge) FOUND_TTL_MS else MISSING_TTL_MS)) return Known.Unknown
        val badge = if (hasBadge) PlatformBadge(
            name = parts.getOrNull(3).orEmpty(),
            logoPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
            original = parts.getOrNull(2) == "1",
            square = parts.getOrNull(5) == "1",
        ) else null
        synchronized(memory) { memory[key] = badge }
        return Known.Value(badge)
    }

    sealed interface Known {
        data object Unknown : Known
        data class Value(val badge: PlatformBadge?) : Known
    }

    suspend fun get(tmdbId: Int, isTv: Boolean): PlatformBadge? {
        if (tmdbId <= 0 || !settings.metadataConfig().enabled) return null
        val key = (if (isTv) "tv3_" else "mv3_") + tmdbId
        val now = System.currentTimeMillis()
        (peek(tmdbId, isTv) as? Known.Value)?.let { return it.badge }
        // A transport failure is not remembered: the next open asks again.
        val badge = runCatching { provider.platform(tmdbId, isTv) }.getOrNull()
        if (badge != null) {
            prefs.edit().putString(key, "$now|1|${if (badge.original) 1 else 0}|${badge.name.replace('|', ' ')}|${badge.logoPath.orEmpty()}|${if (badge.square) 1 else 0}").apply()
        }
        // A null is kept for this session only: it may be "no platform" or a failed request.
        synchronized(memory) { memory[key] = badge }
        return badge
    }

    private companion object {
        const val FOUND_TTL_MS = 30L * 24 * 3600 * 1000
        const val MISSING_TTL_MS = 7L * 24 * 3600 * 1000
    }
}
