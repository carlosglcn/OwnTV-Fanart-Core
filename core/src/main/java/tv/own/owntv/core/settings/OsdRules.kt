package tv.own.owntv.core.settings

/**
 * glcn: how long the player's on-screen controls stay up. [CHOICES] are what Settings cycles through:
 * 0 is the standard timing, a number is seconds, [UNTIL_BACK] keeps them up until Back is pressed.
 */
object OsdRules {
    const val STANDARD_MS = 4_500L
    const val STANDARD_BANNER_MS = 3_000L
    const val UNTIL_BACK = -1
    val CHOICES = listOf(0, 8, 12, 20, UNTIL_BACK)

    /** How long the controls stay up, or null for "until Back". */
    fun controlsMs(setting: Int): Long? = when {
        setting == UNTIL_BACK -> null
        setting <= 0 -> STANDARD_MS
        else -> setting * 1000L
    }

    /** How long the channel banner stays up after a zap: two thirds of the controls' time. */
    fun bannerMs(setting: Int): Long = when {
        setting == UNTIL_BACK -> 20_000L
        setting <= 0 -> STANDARD_BANNER_MS
        else -> setting * 1000L * 2 / 3
    }

    /** The next choice, wrapping round. */
    fun next(setting: Int): Int = CHOICES[(CHOICES.indexOf(setting).coerceAtLeast(0) + 1) % CHOICES.size]
}
