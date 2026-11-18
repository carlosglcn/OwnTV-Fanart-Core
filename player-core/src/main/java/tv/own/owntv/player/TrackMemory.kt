package tv.own.owntv.player

import tv.own.owntv.core.player.PlaybackPrefsStore

/**
 * Remembered audio / subtitle choice for one item, turned into what an engine
 * should do once it knows the stream's tracks.
 *
 * A remembered choice is stored as a *language*, not a track id — ids change between streams of the
 * same channel, and between episodes of the same series, while "the German track" does not. When the
 * stream has no track in that language nothing changes, so the global preferred language still applies.
 */
internal object TrackMemory {

    /** The audio track to switch to, or null when none matches or a matching one is already playing. */
    fun audioToSelect(tracks: List<TrackOption>, lang: String?): TrackOption? {
        // glcn: like Kodi, the best sound in the wanted language — a film's TrueHD/Atmos over the AC3
        // copy beside it. Without a remembered language, the language of the track the file chose.
        val want = lang?.takeIf { it.isNotBlank() } ?: tracks.firstOrNull { it.selected }?.lang
        val candidates = if (want.isNullOrBlank()) {
            if (tracks.any { it.lang != null }) return null else tracks
        } else tracks.filter { subtitleLanguageMatches(want, it.lang) }
        val best = candidates.filterNot(::isCommentary).maxWithOrNull(compareBy<TrackOption> { audioRank(it.codec) }.thenBy { -it.typeIndex })
            ?: return null
        val current = tracks.firstOrNull { it.selected }
        if (current != null && current.mpvId == best.mpvId) return null
        if (current != null && candidates.any { it.mpvId == current.mpvId } && audioRank(current.codec) >= audioRank(best.codec)) return null
        return best
    }

    private fun isCommentary(t: TrackOption): Boolean =
        t.label.contains("comment", ignoreCase = true) || t.label.contains("comentario", ignoreCase = true)

    /** How good an audio codec sounds, roughly as Kodi ranks its default stream. */
    fun audioRank(codec: String?): Int {
        val c = codec?.lowercase()?.filter { it.isLetterOrDigit() } ?: return 0
        return when {
            c.contains("truehd") || c.contains("mlp") -> 7
            c.contains("dtshd") || c.contains("dtsma") || c.contains("dtsx") -> 6
            c.contains("eac3") || c.contains("ec3") || c.contains("joc") -> 5
            c.contains("flac") || c.startsWith("pcm") -> 5
            c.contains("dts") -> 4
            c.contains("ac3") -> 3
            c.contains("opus") || c.contains("aac") -> 2
            c.contains("mp3") || c.contains("mp2") || c.contains("mpeg") -> 1
            else -> 0
        }
    }

    sealed interface SubtitleAction {
        data object None : SubtitleAction
        data object Off : SubtitleAction
        data class Select(val track: TrackOption) : SubtitleAction
    }

    /**
     * What to do with subtitles. [allowImage] is false where a bitmap subtitle would cost an engine
     * handoff (mpv VOD) or cannot be drawn at all (mpv live) — a remembered choice never triggers that.
     */
    fun subtitleAction(tracks: List<TrackOption>, choice: String?, allowImage: Boolean): SubtitleAction {
        if (choice.isNullOrBlank()) return SubtitleAction.None
        if (choice == PlaybackPrefsStore.SUBTITLES_OFF) {
            return if (tracks.any { it.selected }) SubtitleAction.Off else SubtitleAction.None
        }
        if (tracks.any { it.selected && subtitleLanguageMatches(choice, it.lang) }) return SubtitleAction.None
        val track = tracks.firstOrNull { subtitleLanguageMatches(choice, it.lang) && (allowImage || !it.image) }
        return track?.let { SubtitleAction.Select(it) } ?: SubtitleAction.None
    }
}

/**
 * One load's remembered track languages, the same on both engines. Each half is applied once, when both
 * the answer from the database and that kind's track list are in — whichever arrives last does it. A pick
 * by the user closes that half too, so a database answer arriving after it cannot undo it.
 */
internal class TrackRecall(val key: String) {
    private var loaded = false
    private var audio: String? = null
    private var sub: String? = null
    var audioDone = false
    private var subDone = false

    /** The database answered (or the "original language" stood in for it). */
    fun load(audio: String?, sub: String?) {
        this.audio = audio
        this.sub = sub
        loaded = true
    }

    /** Apply whichever half is ready. [allowImage]: see [TrackMemory.subtitleAction]. */
    fun apply(
        audioTracks: List<TrackOption>,
        subTracks: List<TrackOption>,
        allowImage: Boolean,
        selectAudio: (Int) -> Unit,
        subtitlesOff: () -> Unit,
        selectSubtitle: (Int) -> Unit,
    ) {
        if (!loaded) return
        if (!audioDone && audioTracks.isNotEmpty()) {
            audioDone = true
            TrackMemory.audioToSelect(audioTracks, audio)?.let { selectAudio(it.mpvId) }
        }
        if (!subDone && subTracks.isNotEmpty()) {
            subDone = true
            when (val action = TrackMemory.subtitleAction(subTracks, sub, allowImage)) {
                TrackMemory.SubtitleAction.None -> Unit
                TrackMemory.SubtitleAction.Off -> subtitlesOff()
                is TrackMemory.SubtitleAction.Select -> selectSubtitle(action.track.mpvId)
            }
        }
    }

    /**
     * The user picked a track: that half is closed. Returns the language to store, or null when the track
     * has none. A picked audio language is also what an engine switch later in this load re-applies.
     */
    fun picked(isAudio: Boolean, lang: String?): String? {
        if (isAudio) audioDone = true else subDone = true
        if (lang.isNullOrBlank()) return null
        if (isAudio) audio = lang
        return lang
    }
}
