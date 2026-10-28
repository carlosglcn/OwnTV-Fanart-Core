package tv.own.owntv.core.sports

/**
 * glcn: clubs ESPN names wrongly — by a nickname or a demonym no guide or fan uses. The right name is shown, and
 * it is also what the guide search looks for, so the match's channel is found. (Auriense FC is a club of its own —
 * ESPN had it right.)
 */
internal object EspnNameFixes {
    private val NAMES = emptyMap<String, String>()
    private val ABBREVIATIONS = emptyMap<String, String>()

    fun name(espn: String): String = NAMES[espn.trim()] ?: espn

    fun abbreviation(espnName: String, espnAbbreviation: String): String = ABBREVIATIONS[espnName.trim()] ?: espnAbbreviation
}
