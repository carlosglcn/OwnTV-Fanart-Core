package tv.own.owntv.core.theme

/**
 * glcn: ready-made styles — light or dark, an accent and the page's own tones, chosen together so they
 * always match. Choosing one sets the theme and the accent the user could also set by hand; the page
 * tones are the style's own. [glass] styles turn the translucent surfaces on: they look their best on
 * a stronger device (the app drops the blur by itself where it cannot afford it).
 *
 * Colours are ARGB longs, like [OwnTVPalette] (core carries no compose-ui).
 */
enum class GlcnStyle(
    val light: Boolean,
    val accentHex: String,
    val page: Long,
    val glassTint: Long,
    val text: Long,
    val muted: Long,
    val body: Long,
    val glass: Boolean = false,
    /** Home's background this style asks for (0 classic, 1 cinema, 2 panel); null leaves the user's own choice. */
    val homeBackdrop: Int? = null,
    /** glcn: gradient styles — the colours of the page's backdrop (ARGB), drawn behind everything. */
    val gradient: List<Long>? = null,
    /** glcn: how the gradient lives — [MOTION_STILL], [MOTION_DRIFT] (soft lights drifting slowly) or [MOTION_ART]
     *  (taken from the focused picture, over the drifting lights where there is none). */
    val motion: Int = MOTION_STILL,
) {
    // Lumen: clear glass over a near-black or pearl page, one turquoise accent (no other style uses it).
    LUMEN_NOCHE(false, "#4FD1C5", 0xFF06090A, 0xFF111C1C, 0xFFF2F7F6, 0xFFA5B2B0, 0xFFC9D3D1, glass = true),
    LUMEN_DIA(true, "#0E8F86", 0xFFF3F5F2, 0xFFFFFFFF, 0xFF0D1413, 0xFF566663, 0xFF2A3836, glass = true),
    ATARDECER(false, "#FF8A5B", 0xFF0E0B0A, 0xFF1B1512, 0xFFF6EEE8, 0xFFB8A79C, 0xFFD9CBC2),
    ESTUDIO(false, "#8AB4F8", 0xFF1A1C1E, 0xFF25282B, 0xFFE8EAED, 0xFF9AA0A6, 0xFFC9CDD2),
    VITRINA(false, "#FFFFFF", 0xFF000000, 0xFF1A1A1C, 0xFFFFFFFF, 0xFFA3A3A8, 0xFFD6D6DA, glass = true),
    PULSO(false, "#76B900", 0xFF141414, 0xFF1E1E1E, 0xFFF2F2F2, 0xFFA0A0A0, 0xFFD0D0D0),
    NEON(false, "#FF2E9A", 0xFF050507, 0xFF111018, 0xFFF4F4FF, 0xFF9D9DB5, 0xFFCFCFE6),
    NIEVE(true, "#2A7DE1", 0xFFEEF3F9, 0xFFFFFFFF, 0xFF0F1A2A, 0xFF4F5F74, 0xFF2A3A50, glass = true),
    PAPEL(true, "#E0312B", 0xFFF4F1EA, 0xFFFFFFFF, 0xFF141414, 0xFF5E5A54, 0xFF2E2B27),
    ARENA(true, "#C4562E", 0xFFF1E8DB, 0xFFFBF6EF, 0xFF2B211A, 0xFF6E5E50, 0xFF3E332A),

    // glcn: gradient styles — dark glass over a coloured backdrop, one accent drawn from it.
    AURORA(false, "#5EEAD4", 0xFF070B14, 0xFF0E1622, 0xFFF2F7FA, 0xFFA3B1BF, 0xFFCAD5DF, glass = true,
        gradient = listOf(0xFF0F766E, 0xFF6D28D9, 0xFF1D4ED8, 0xFF070B14), motion = MOTION_DRIFT),
    CREPUSCULO(false, "#FFB199", 0xFF1A0F24, 0xFF24152E, 0xFFFFF4EF, 0xFFD8B9C4, 0xFFF0D9DF, glass = true,
        gradient = listOf(0xFFFF8A5C, 0xFFD9467A, 0xFF5B2A86, 0xFF22113A)),
    OCEANO(false, "#67E8F9", 0xFF081528, 0xFF0D2036, 0xFFEFFAFC, 0xFF9FC0CC, 0xFFC8DDE4, glass = true,
        gradient = listOf(0xFF0B1D3A, 0xFF0E3A5C, 0xFF0F766E, 0xFF14B8A6)),
    FANART_VIVO(false, "#FBBF24", 0xFF0A0C10, 0xFF141820, 0xFFF4F6F8, 0xFFA8B0BA, 0xFFD0D6DD, glass = true,
        gradient = listOf(0xFF0F766E, 0xFF6D28D9, 0xFF1D4ED8, 0xFF070B14), motion = MOTION_ART),
    ;

    /** glcn: a style drawn over a gradient backdrop (the "Gradients" group of the picker). */
    val isGradient: Boolean get() = gradient != null

    companion object {
        const val MOTION_STILL = 0
        const val MOTION_DRIFT = 1
        const val MOTION_ART = 2
        fun of(name: String?): GlcnStyle? = entries.firstOrNull { it.name == name }
    }
}
