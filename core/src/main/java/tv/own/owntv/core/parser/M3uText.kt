package tv.own.owntv.core.parser

/**
 * glcn: cleaning for a playlist's `tvg-id`, the key the guide is matched on.
 *
 * Some playlist generators (Enigma2 bridges among them) write ids HTML-escaped — `M&#43; ESTRENOS HD`
 * for `M+ ESTRENOS HD` — and some send UTF-8 that was decoded as Windows-1252 and encoded again —
 * `M+ CLÃSICOS HD` for `M+ CLÁSICOS HD`. Either way the id never equals the guide's, and the channel
 * shows no guide. Only the id is repaired: the display name keys favourites and history, so it is
 * left exactly as the playlist writes it.
 */
internal object M3uText {

    fun repairId(raw: String): String = repairMojibake(decodeEntities(raw)).trim()

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);")

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePointString)
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePointString)
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                e == "nbsp" -> " "
                else -> null
            } ?: m.value
        }
    }

    private fun codePointString(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    /**
     * Re-reads text that is UTF-8 shown as Windows-1252/Latin-1. Applied only when it has the tell-tale
     * lead characters (Ã, Â) and only kept when the bytes really are valid UTF-8, so text that is
     * already correct is never changed.
     */
    fun repairMojibake(s: String): String {
        if (s.none { it == '\u00C3' || it == '\u00C2' }) return s
        val bytes = ByteArray(s.length)
        for ((i, c) in s.withIndex()) {
            val b = CP1252_REVERSE[c] ?: if (c.code <= 0xFF) c.code else return s
            bytes[i] = b.toByte()
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrDefault(s)
    }

    /** The Windows-1252 characters in 0x80–0x9F, back to their byte. */
    private val CP1252_REVERSE: Map<Char, Int> = mapOf(
        '\u20AC' to 0x80, '\u201A' to 0x82, '\u0192' to 0x83, '\u201E' to 0x84, '\u2026' to 0x85,
        '\u2020' to 0x86, '\u2021' to 0x87, '\u02C6' to 0x88, '\u2030' to 0x89, '\u0160' to 0x8A,
        '\u2039' to 0x8B, '\u0152' to 0x8C, '\u017D' to 0x8E, '\u2018' to 0x91, '\u2019' to 0x92,
        '\u201C' to 0x93, '\u201D' to 0x94, '\u2022' to 0x95, '\u2013' to 0x96, '\u2014' to 0x97,
        '\u02DC' to 0x98, '\u2122' to 0x99, '\u0161' to 0x9A, '\u203A' to 0x9B, '\u0153' to 0x9C,
        '\u017E' to 0x9E, '\u0178' to 0x9F,
    )
}
