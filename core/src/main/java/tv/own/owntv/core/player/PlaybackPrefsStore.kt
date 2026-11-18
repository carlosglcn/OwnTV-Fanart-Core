package tv.own.owntv.core.player

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.PlaybackPrefsDao
import tv.own.owntv.core.settings.SettingsRepository

/** What one item remembers: this profile's zoom and volume, and the stream's audio delay (shared). */
data class ItemPrefs(val zoomMode: String?, val volumeBoost: Int?, val audioDelayMs: Int?)

/**
 * Remembers the zoom/aspect mode, the volume and the A/V-sync offset the user last chose for one
 * specific item, so a film that needs 130% volume, a 4:3 channel the user prefers cropped, or a
 * channel whose audio runs 200 ms early, comes back that way next time.
 *
 * Keyed exactly like the engine pins ([VodEngineStore], [ForceMpvStore]) — [enginePinKey] when the
 * row carries a provider id, the stream URL when it doesn't — which is why a re-sync doesn't lose
 * these: the same item computes the same key again. Scoped to the active profile, so the kids
 * profile's choices never reach the main one.
 *
 * Nothing is stored until the user actually changes something in the player. A missing row, or a
 * null column in an existing one, means "follow the global default in Settings".
 */
class PlaybackPrefsStore(
    private val dao: PlaybackPrefsDao,
    private val settings: SettingsRepository,
    private val quirks: tv.own.owntv.core.database.dao.PlaybackQuirkDao,
) {
    /**
     * The remembered values for [contentKey], or null when this item has none. The audio delay is the
     * stream's, shared by every profile; zoom and volume are this profile's.
     */
    suspend fun prefsFor(contentKey: String): ItemPrefs? = runCatching {
        val own = dao.get(settings.activeProfileId.first(), contentKey)
        val delay = quirks.get(contentKey)?.audioDelayMs
        when {
            own != null -> ItemPrefs(own.zoomMode, own.volumeBoost, delay)
            delay != null -> ItemPrefs(zoomMode = null, volumeBoost = null, audioDelayMs = delay)
            else -> null
        }
    }.getOrNull()

    /** Remember a deliberate zoom choice ([tv.own.owntv.player.ZoomMode] name). */
    suspend fun rememberZoom(contentKey: String, zoomMode: String) {
        runCatching { dao.setZoom(settings.activeProfileId.first(), contentKey, zoomMode) }
    }

    /** Remember a deliberate volume change (percent, 0–150). */
    suspend fun rememberVolume(contentKey: String, volume: Int) {
        runCatching { dao.setVolume(settings.activeProfileId.first(), contentKey, volume) }
    }

    /** Remember a deliberate A/V-sync offset in ms; null forgets it and returns to the global value. */
    suspend fun rememberAudioDelay(contentKey: String, audioDelayMs: Int?) {
        runCatching {
            quirks.setAudioDelay(contentKey, sourceIdOfPinKey(contentKey), mediaTypeOfPinKey(contentKey) ?: "LIVE", audioDelayMs)
        }
    }

    /**
     * The track languages this profile last picked for [trackKey] (a channel, a film, or a whole
     * series), as (audio, subtitle); either may be null. Subtitle may be
     * [SUBTITLES_OFF].
     */
    suspend fun tracksFor(trackKey: String): Pair<String?, String?>? = runCatching {
        dao.get(settings.activeProfileId.first(), trackKey)
            ?.takeIf { it.audioLang != null || it.subtitleLang != null }
            ?.let { it.audioLang to it.subtitleLang }
    }.getOrNull()

    /** Remember a deliberate audio-track pick by its language. */
    suspend fun rememberAudioLang(trackKey: String, lang: String) {
        runCatching { dao.setAudioLang(settings.activeProfileId.first(), trackKey, lang) }
    }

    /** glcn: forget [trackKey]'s audio pick — it named a track this device could not play. */
    suspend fun forgetAudioLang(trackKey: String) {
        runCatching { dao.setAudioLang(settings.activeProfileId.first(), trackKey, null) }
    }

    /** Remember a deliberate subtitle pick by its language, or [SUBTITLES_OFF]. */
    suspend fun rememberSubtitleLang(trackKey: String, lang: String) {
        runCatching { dao.setSubtitleLang(settings.activeProfileId.first(), trackKey, lang) }
    }

    /** Settings escape hatch: forget every item's zoom, on every profile. Volumes are kept. */
    suspend fun clearZoom() {
        runCatching { dao.clearZoom() }
    }

    /** Settings escape hatch: forget every item's volume, on every profile. Zoom modes are kept. */
    suspend fun clearVolume() {
        runCatching { dao.clearVolume() }
    }

    /** How many items currently remember a zoom / a volume — the two Settings rows' chips. */
    fun observeZoomCount(): kotlinx.coroutines.flow.Flow<Int> = dao.observeZoomCount()

    fun observeVolumeCount(): kotlinx.coroutines.flow.Flow<Int> = dao.observeVolumeCount()

    /** Settings escape hatch: forget every item's audio delay (shared by every profile). */
    suspend fun clearAudioDelay() {
        runCatching { quirks.clearAudioDelays() }
    }

    fun observeAudioDelayCount(): kotlinx.coroutines.flow.Flow<Int> = quirks.observeAudioDelayCount()

    companion object {
        /** Stored subtitle choice meaning "the user turned subtitles off here". */
        const val SUBTITLES_OFF = "off"
    }
}
