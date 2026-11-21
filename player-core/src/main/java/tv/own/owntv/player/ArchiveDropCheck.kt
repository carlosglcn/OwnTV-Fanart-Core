package tv.own.owntv.player

/**
 * Is a catch-up programme losing so many frames that its decode path is too slow for this device?
 *
 * Measured over a window of [OwnTVPlayer.ARCHIVE_DROP_WINDOW_MS] of steady playback: more than a tenth of
 * the frames the stream sends dropped (by the output or the decoder). A healthy stream drops a handful
 * at most; the report that caused this lost about five frames in every twenty-five on the copy path.
 */
internal object ArchiveDropCheck {
    /** What to do about a programme that drops too many frames, by the decode path it is on. */
    enum class Action { NONE, DIRECT, EXOPLAYER }

    fun tooMany(dropped: Long, fps: Double, windowMs: Long): Boolean {
        val rate = if (fps.isFinite() && fps in 10.0..120.0) fps else DEFAULT_FPS
        val sent = rate * windowMs / 1000.0
        return dropped >= MIN_DROPS && dropped > sent * MAX_DROP_SHARE
    }

    /**
     * The copy path (frames drawn through the GPU) goes to the direct hardware path, once. Software goes
     * to ExoPlayer, which decodes in hardware. The direct path has nowhere better to go.
     */
    fun action(hwdecCurrent: String?, triedDirect: Boolean): Action = when (hwdecCurrent) {
        "mediacodec-copy" -> if (triedDirect) Action.NONE else Action.DIRECT
        "no", "" -> Action.EXOPLAYER
        else -> Action.NONE
    }

    private const val DEFAULT_FPS = 25.0
    private const val MAX_DROP_SHARE = 0.10
    private const val MIN_DROPS = 10L
}
