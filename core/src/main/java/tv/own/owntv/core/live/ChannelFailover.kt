package tv.own.owntv.core.live

import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * glcn: when a channel will not play, the same channel under another entry of its own playlist — in
 * another group, or at another quality — best picture first. "Same" is the guide id when both carry one,
 * else the name once the provider tags and the quality words are taken off ("La 1 HD" = "ES| La 1 FHD").
 */
object ChannelFailover {
    private val noise = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "1080p", "1080i", "720p", "576p", "480p",
        "50fps", "60fps", "raw", "vip", "backup", "bk",
    )

    private fun words(name: String): List<String> =
        ProviderTags.parse(name).name.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in noise }

    /** The name with quality and provider words removed; empty when nothing is left. */
    fun key(name: String): String = words(name).joinToString("")

    /** A word of the name to search for — the longest, at least 3 characters — or null. */
    fun searchToken(name: String): String? = words(name).maxByOrNull { it.length }?.takeIf { it.length >= 3 }

    /** 0 SD · 1 unmarked · 2 HD · 3 FHD / 1080 · 4 4K / UHD. */
    fun quality(name: String): Int {
        val t = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return when {
            "4k" in t || "uhd" in t || "2160p" in t -> 4
            "fhd" in t || "1080p" in t || "1080i" in t -> 3
            "hd" in t || "720p" in t -> 2
            "sd" in t || "576p" in t || "480p" in t -> 0
            else -> 1
        }
    }

    /** [pool] reduced to the same channel as [current], best quality first; [current] itself is left out. */
    fun rank(current: ChannelEntity, pool: List<ChannelEntity>): List<ChannelEntity> {
        val k = key(current.name)
        val epg = current.epgChannelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return pool
            .filter { it.id != current.id }
            .filter { c ->
                (epg != null && c.epgChannelId?.trim()?.lowercase() == epg) ||
                    (k.isNotEmpty() && key(c.name) == k)
            }
            .sortedWith(compareByDescending<ChannelEntity> { quality(it.name) }.thenBy { it.id })
    }
}
