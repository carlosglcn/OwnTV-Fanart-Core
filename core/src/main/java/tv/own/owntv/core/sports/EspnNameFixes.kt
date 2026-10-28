package tv.own.owntv.core.sports

/**
 * glcn: clubs ESPN names wrongly — by a nickname or a demonym no guide or fan uses ("Auriense" for Ourense CF).
 * The right name is shown, and it is also what the guide search looks for, so the match's channel is found.
 */
internal object EspnNameFixes {
    private val NAMES = mapOf(
        "Auriense" to "Ourense CF",
    )
    private val ABBREVIATIONS = mapOf(
        "Auriense" to "OUR",
    )

    fun name(espn: String): String = NAMES[espn.trim()] ?: espn

    fun abbreviation(espnName: String, espnAbbreviation: String): String = ABBREVIATIONS[espnName.trim()] ?: espnAbbreviation
}
