package tv.own.owntv.core.player

/**
 * glcn: which engine a film or episode should start on, decided before it opens — the way Kodi has one player
 * that suits each file, built from the two this app has.
 *
 * - ExoPlayer for 4K, HDR and Dolby Vision: the device's own video path, Dolby Vision (profile 7 rewritten as
 *   8.1, see the player's GlcnVideoRenderer) and the files mpv's direct path freezes on (odd 4K sizes).
 * - ExoPlayer for Dolby Atmos / TrueHD / DTS-HD when the receiver takes them undecoded ([Pick.EXO_FOR_SOUND],
 *   checked by the player): ExoPlayer bitstreams them reliably, mpv's passthrough is experimental.
 * - mpv for old or unusual containers and codecs (AVI, WMV, ISO, VOB, RMVB, Xvid/DivX, VC-1…), which ExoPlayer
 *   reads poorly or not at all.
 * - Anything else: no opinion, the Movies & Series player setting decides.
 *
 * Only a hint: the player applies it only under an "…first" setting (the other engine stays the automatic
 * fallback), never over an "…only" setting or a per-item choice made with the engine button.
 */
object EngineHint {

    enum class Pick {
        /** ExoPlayer: 4K, HDR, Dolby Vision. */
        EXO,
        /** ExoPlayer, but only if the sound can go to the receiver undecoded (the player knows). */
        EXO_FOR_SOUND,
        /** mpv: a container or codec ExoPlayer reads poorly. */
        MPV,
    }

    /** The engine this file suits, or null for no opinion. */
    fun of(
        name: String?,
        url: String?,
        qualityRank: Int = 0,
        capabilities: String? = null,
        containerExt: String? = null,
    ): Pick? {
        val ext = (containerExt?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() } ?: extensionOf(url)).lowercase()
        if (ext in MPV_CONTAINERS) return Pick.MPV
        val text = name.orEmpty()
        if (MPV_CODECS.containsMatchIn(text)) return Pick.MPV
        val caps = capabilities.orEmpty().lowercase()
        if (EXO_CAPABILITIES.any { it in caps }) return Pick.EXO
        if (qualityRank >= RANK_4K) return Pick.EXO
        if (EXO_MARKS.containsMatchIn(text)) return Pick.EXO
        if ("atmos" in caps || SOUND_MARKS.containsMatchIn(text)) return Pick.EXO_FOR_SOUND
        return null
    }

    private fun extensionOf(url: String?): String {
        val path = url.orEmpty().substringBefore('?').substringBefore('#').substringAfterLast('/')
        return if ('.' in path) path.substringAfterLast('.') else ""
    }

    private const val RANK_4K = 4
    private val MPV_CONTAINERS = setOf("avi", "wmv", "asf", "iso", "img", "vob", "rm", "rmvb", "divx", "xvid", "ogm", "3gp", "m2v", "mpg", "mpeg", "dat")
    private val MPV_CODECS = Regex("""(?i)\b(?:xvid|divx|vc-?1|wmv\d?|rmvb|mpeg-?2)\b""")
    private val EXO_CAPABILITIES = listOf("dolby vision", "hdr")
    private val SOUND_MARKS = Regex("""(?i)\b(?:atmos|truehd|dts-?hd(?:[ .-]?ma)?|dts[ :-]?x)\b""")
    private val EXO_MARKS = Regex("""(?i)\b(?:4k|uhd|2160p|dovi|dv|hdr10\+?|hdr)\b""")
}
