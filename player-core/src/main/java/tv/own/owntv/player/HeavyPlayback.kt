package tv.own.owntv.player

/**
 * glcn: a 4K, HDR or Dolby Vision film or episode is starting. Such a file asks for far more memory in its first
 * seconds than any other, so the app is told first and lets go of what it keeps only for comfort (the trailer
 * player, most pictures held in memory) before the decoder and the film buffer take theirs.
 */
object HeavyPlayback {
    /** Set by the app; called on the thread that starts playback. */
    @Volatile var onStart: (() -> Unit)? = null

    /**
     * The film now starting is 4K / HDR / Dolby Vision. ExoPlayer is then built without libass: its overlay drew
     * styled subtitles over a 4K picture and the app's memory ran out within seconds (native crash on the SHIELD,
     * even paused). Such a file shows its ASS/SSA lines as plain subtitles instead.
     */
    @Volatile var active: Boolean = false
        internal set

    internal fun starting() {
        active = true
        runCatching { onStart?.invoke() }
    }

    internal fun ordinary() {
        active = false
    }
}
