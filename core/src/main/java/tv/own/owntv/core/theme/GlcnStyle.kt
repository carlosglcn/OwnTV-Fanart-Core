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
) {
    // Lumen: clear glass over a near-black or pearl page, one cool accent.
    LUMEN_NOCHE(false, "#8AB4FF", 0xFF07090B, 0xFF151A21, 0xFFF4F6F7, 0xFFA9B0B6, 0xFFCBD1D6, glass = true, homeBackdrop = 1),
    LUMEN_DIA(true, "#1D5FD6", 0xFFEDF0F3, 0xFFFFFFFF, 0xFF0E1215, 0xFF55606A, 0xFF2A3138, glass = true, homeBackdrop = 1),
    ATARDECER(false, "#FF8A5B", 0xFF0E0B0A, 0xFF1B1512, 0xFFF6EEE8, 0xFFB8A79C, 0xFFD9CBC2),
    ESTUDIO(false, "#8AB4F8", 0xFF1A1C1E, 0xFF25282B, 0xFFE8EAED, 0xFF9AA0A6, 0xFFC9CDD2),
    VITRINA(false, "#FFFFFF", 0xFF000000, 0xFF1A1A1C, 0xFFFFFFFF, 0xFFA3A3A8, 0xFFD6D6DA, glass = true),
    PULSO(false, "#76B900", 0xFF141414, 0xFF1E1E1E, 0xFFF2F2F2, 0xFFA0A0A0, 0xFFD0D0D0),
    NEON(false, "#FF2E9A", 0xFF050507, 0xFF111018, 0xFFF4F4FF, 0xFF9D9DB5, 0xFFCFCFE6),
    NIEVE(true, "#2A7DE1", 0xFFEEF3F9, 0xFFFFFFFF, 0xFF0F1A2A, 0xFF4F5F74, 0xFF2A3A50, glass = true),
    PAPEL(true, "#E0312B", 0xFFF4F1EA, 0xFFFFFFFF, 0xFF141414, 0xFF5E5A54, 0xFF2E2B27),
    ARENA(true, "#C4562E", 0xFFF1E8DB, 0xFFFBF6EF, 0xFF2B211A, 0xFF6E5E50, 0xFF3E332A),
    ;

    companion object {
        fun of(name: String?): GlcnStyle? = entries.firstOrNull { it.name == name }
    }
}
