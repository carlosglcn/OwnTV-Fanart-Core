package tv.own.owntv.core.theme

/**
 * glcn: ready-made styles — light or dark, an accent and the page's own tones, chosen together so they
 * always match. Choosing one sets the theme and the accent the user could also set by hand; the page
 * tones are the style's own. [glass] styles turn the translucent surfaces on: they look their best on
 * a stronger device (the app drops the blur by itself where it cannot afford it).
 *
 * Colours are ARGB longs, like [OwnTVPalette] (core carries no compose-ui).
 */
// Top level: an enum's entries cannot read its own companion while they are built.
const val GLCN_MOTION_STILL = 0
const val GLCN_MOTION_DRIFT = 1
const val GLCN_MOTION_ART = 2

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
    val motion: Int = GLCN_MOTION_STILL,
    /**
     * glcn: a second accent — with it the style's accent is a gradient ([accentHex] → this) on buttons and bars, and
     * focus is an outline in that gradient instead of a fill, so the picture under a focused card still shows.
     */
    val accent2Hex: String? = null,
) {
    // Ría: deep navy, a cyan-to-green accent and outlined focus, soft lights drifting behind (night); a pale blue-grey
    // page with navy ink by day — the green only as a fill there, never as text (it does not read on light).
    RIA_NOCHE(false, "#51C6D9", 0xFF061A26, 0xFF0A2A3C, 0xFFDDE8EA, 0xFF9FB8C2, 0xFFC3D6DC,
        gradient = listOf(0xFF0E5A7A, 0xFF35652A, 0xFF00517A, 0xFF061A26), motion = GLCN_MOTION_DRIFT, accent2Hex = "#8DC63F"),
    RIA_DIA(true, "#00517A", 0xFFDDE8EA, 0xFFFFFFFF, 0xFF0B2A3A, 0xFF3F5F6E, 0xFF1E3B4A, accent2Hex = "#3D8A2B"),
    // Brasa: warm graphite, an amber-to-coral accent and outlined focus.
    BRASA(false, "#F5A524", 0xFF121110, 0xFF1B1917, 0xFFF4F1EC, 0xFFB9B2A8, 0xFFD9D3CA, accent2Hex = "#F0603A"),
    // Alba: Brasa's light sister — warm ivory, an orange-to-raspberry accent and the same outlined focus.
    ALBA(true, "#E0521B", 0xFFF6F1EA, 0xFFFFFFFF, 0xFF1E1712, 0xFF6A5D52, 0xFF3A2F27, accent2Hex = "#C2255C"),
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
    // glcn: the light sides of Estudio and Neón, so every theme has both.
    ESTUDIO_DIA(true, "#1A73E8", 0xFFF1F3F4, 0xFFFFFFFF, 0xFF1F1F1F, 0xFF5F6368, 0xFF3C4043),
    NEON_DIA(true, "#D6006F", 0xFFF7F2F8, 0xFFFFFFFF, 0xFF1A1020, 0xFF655A6E, 0xFF3A2F44),

    // glcn: gradient styles — dark glass over a coloured backdrop, one accent drawn from it.
    AURORA(false, "#5EEAD4", 0xFF070B14, 0xFF0E1622, 0xFFF2F7FA, 0xFFA3B1BF, 0xFFCAD5DF, glass = true,
        gradient = listOf(0xFF0F766E, 0xFF6D28D9, 0xFF1D4ED8, 0xFF070B14), motion = GLCN_MOTION_DRIFT),
    CREPUSCULO(false, "#FFB199", 0xFF1A0F24, 0xFF24152E, 0xFFFFF4EF, 0xFFD8B9C4, 0xFFF0D9DF, glass = true,
        gradient = listOf(0xFFFF8A5C, 0xFFD9467A, 0xFF5B2A86, 0xFF22113A)),
    OCEANO(false, "#67E8F9", 0xFF081528, 0xFF0D2036, 0xFFEFFAFC, 0xFF9FC0CC, 0xFFC8DDE4, glass = true,
        gradient = listOf(0xFF0B1D3A, 0xFF0E3A5C, 0xFF0F766E, 0xFF14B8A6)),
    FANART_VIVO(false, "#FBBF24", 0xFF0A0C10, 0xFF141820, 0xFFF4F6F8, 0xFFA8B0BA, 0xFFD0D6DD, glass = true,
        gradient = listOf(0xFF0F766E, 0xFF6D28D9, 0xFF1D4ED8, 0xFF070B14), motion = GLCN_MOTION_ART),
    ;

    /** glcn: a style drawn over a gradient backdrop (the "Gradients" group of the picker). */
    val isGradient: Boolean get() = gradient != null

    companion object {
        const val MOTION_STILL = GLCN_MOTION_STILL
        const val MOTION_DRIFT = GLCN_MOTION_DRIFT
        const val MOTION_ART = GLCN_MOTION_ART
        fun of(name: String?): GlcnStyle? = entries.firstOrNull { it.name == name }
    }
}

/**
 * glcn: a theme — a night style and a day style made to go together. Choosing a theme is the first of the three
 * choices in Appearance; the mode (automatic, dark, light) picks which of its two styles is shown.
 * Styles left out (Atardecer, Pulso, Océano, Fanart vivo) still work for whoever chose them, but are no longer offered.
 */
enum class GlcnTheme(val dark: GlcnStyle, val light: GlcnStyle) {
    RIA(GlcnStyle.RIA_NOCHE, GlcnStyle.RIA_DIA),
    BRASA(GlcnStyle.BRASA, GlcnStyle.ALBA),
    LUMEN(GlcnStyle.LUMEN_NOCHE, GlcnStyle.LUMEN_DIA),
    AURORA(GlcnStyle.AURORA, GlcnStyle.NIEVE),
    CREPUSCULO(GlcnStyle.CREPUSCULO, GlcnStyle.ARENA),
    ESTUDIO(GlcnStyle.ESTUDIO, GlcnStyle.ESTUDIO_DIA),
    NEON(GlcnStyle.NEON, GlcnStyle.NEON_DIA),
    VITRINA(GlcnStyle.VITRINA, GlcnStyle.PAPEL),
    ;

    fun style(light: Boolean): GlcnStyle = if (light) this.light else dark

    companion object {
        fun of(style: GlcnStyle?): GlcnTheme? = style?.let { s -> entries.firstOrNull { it.dark == s || it.light == s } }

        /** Automatic mode: light by day, dark from dusk — by the month, as the evenings draw in and out. */
        fun isDay(nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean {
            val t = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
            val minutes = t.hour * 60 + t.minute
            val (from, to) = when (t.monthValue) {
                11, 12, 1, 2 -> 8 * 60 + 30 to 18 * 60
                3, 10 -> 8 * 60 to 19 * 60 + 30
                else -> 7 * 60 + 30 to 21 * 60 + 30
            }
            return minutes in from until to
        }
    }
}

/** glcn: Appearance › Mode. Not set: the theme's style stays as it was chosen. */
const val GLCN_MODE_AUTO = 0
const val GLCN_MODE_DARK = 1
const val GLCN_MODE_LIGHT = 2

/** glcn: Appearance › Background — plain, the theme's own (its gradient, where it has one), or the picture in focus. */
const val GLCN_BACKDROP_PLAIN = 0
const val GLCN_BACKDROP_THEME = 1
const val GLCN_BACKDROP_FANART = 2
