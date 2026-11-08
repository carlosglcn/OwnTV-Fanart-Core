package tv.own.owntv.core.metadata

/**
 * A title's YouTube trailer keys, best first, as stored in the single `trailerKey` column: joined by
 * commas (a YouTube key never contains one). Older rows hold one key, which reads as a list of one, so
 * no migration is needed. Several are kept because an owner can block a video from embedded players.
 */
object TrailerKeys {
    /** At most this many: enough backups, without a long row of retries before the "open in YouTube" offer. */
    const val MAX = 4

    /**
     * glcn: written after a key whose trailer is not in the viewer's language, so the full-screen player
     * turns on subtitles in the viewer's language for it. A YouTube key never contains a "~".
     */
    private const val SUBTITLED = "~cc"

    fun join(keys: List<String>): String? =
        keys.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.removeSuffix(SUBTITLED) }.take(MAX).takeIf { it.isNotEmpty() }?.joinToString(",")

    /** The plain keys, best first — marks taken off. */
    fun split(stored: String?): List<String> = raw(stored).map { it.removeSuffix(SUBTITLED) }

    /** glcn: [key] marked to be shown with subtitles in the viewer's language. */
    fun subtitled(key: String): String = key.removeSuffix(SUBTITLED) + SUBTITLED

    /** glcn: whether the trailer [key] of [stored] is in another language than the viewer's. */
    fun wantsSubtitles(stored: String?, key: String): Boolean = raw(stored).any { it == key + SUBTITLED }

    private fun raw(stored: String?): List<String> =
        stored?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
