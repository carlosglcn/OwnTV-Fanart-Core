package tv.own.owntv.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * glcn: ExoPlayer's video renderer, with two additions.
 *
 * - Dolby Vision profile 7 (UHD Blu-ray remuxes) on a device whose decoder does not take it — the SHIELD among
 *   them: the track is offered to the decoder as profile 8.1 and every access unit is rewritten on its way in
 *   ([DoviP7]). Kodi does the same; without it such a film plays in HDR10 (or not at all on a box with no Dolby
 *   Vision decoder, as profile 7 has no fallback to plain HEVC in Media3 while 8.1 has).
 * - [onSurfaceOnly]: never start the codec on Media3's placeholder surface (see [OwnTVRenderersFactory]).
 */
@UnstableApi
internal class GlcnVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val onSurfaceOnly: Boolean,
) : MediaCodecVideoRenderer(builder) {

    @Volatile private var doviConvert = false
    private var scratch = ByteArray(0)
    private var failures = 0

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, DoviProfile7.forDecoder(format))

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> = super.getDecoderInfos(mediaCodecSelector, DoviProfile7.forDecoder(format), requiresSecureDecoder)

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val f = formatHolder.format
        doviConvert = f != null && DoviProfile7.shouldConvert(f)
        DolbyVisionNow.set(dolbyVision = f?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION, converted = doviConvert)
        if (doviConvert && f != null) {
            formatHolder.format = DoviProfile7.toProfile8(f)
            failures = 0
            LiveDiagnosticsLog.event("Dolby Vision profile 7 (${f.codecs}) played as profile 8.1 — enhancement layer dropped, RPU rewritten")
        }
        return super.onInputFormatChanged(formatHolder)
    }

    override fun onDisabled() {
        DolbyVisionNow.set(dolbyVision = false, converted = false)
        super.onDisabled()
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        if (doviConvert && !buffer.isEncrypted) buffer.data?.let { rewrite(it) }
        super.onQueueInputBuffer(buffer)
    }

    private fun rewrite(data: java.nio.ByteBuffer) {
        if (data.position() != 0) return
        val n = data.limit()
        if (n <= 0) return
        try {
            if (scratch.size < n) scratch = ByteArray(n + n / 4)
            data.get(scratch, 0, n)
            data.position(0)
            val out = DoviP7.convertAccessUnit(scratch, n) ?: return
            if (out.size > data.capacity()) return
            data.limit(data.capacity())
            data.put(out)
            data.flip()
        } catch (e: Exception) {
            // Never lose the frame over the rewrite: whatever happened, the original bytes go to the decoder.
            data.position(0)
            data.limit(n)
            if (failures++ == 0) android.util.Log.w(TAG, "Dolby Vision rewrite failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // Only a codec still to be started is refused the stand-in: a running one keeps being moved, because
    // releasing it instead costs a black screen until the next keyframe (up to 2 s measured on the TCL).
    override fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo): Boolean =
        if (onSurfaceOnly) codec != null && super.shouldUsePlaceholderSurface(codecInfo) else super.shouldUsePlaceholderSurface(codecInfo)

    // Android 15's surface-less mode is the same start, moved later.
    override fun shouldUseDetachedSurface(codecInfo: MediaCodecInfo): Boolean =
        if (onSurfaceOnly) codec != null && super.shouldUseDetachedSurface(codecInfo) else super.shouldUseDetachedSurface(codecInfo)

    private companion object {
        const val TAG = "GlcnVideoRenderer"
    }
}

/** glcn: which Dolby Vision tracks [GlcnVideoRenderer] rewrites, and how the track is described to the decoder. */
@UnstableApi
internal object DoviProfile7 {

    fun isProfile7(f: Format): Boolean =
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            f.codecs.let { it != null && it.length >= 7 && it.substring(4, 7) == ".07" }

    /** Some decoder on this device takes profile 7 itself (a few TVs): then it keeps the full picture. */
    val deviceTakesProfile7: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { t ->
                    t.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) &&
                        info.getCapabilitiesForType(t).profileLevels.any { it.profile == CodecProfileLevel.DolbyVisionProfileDvheDtb }
                }
            }
        }.getOrDefault(false)
    }

    fun shouldConvert(f: Format): Boolean = isProfile7(f) && !deviceTakesProfile7

    /** "dvhe.07.06" → "dvhe.08.06": same level, profile 8. */
    fun toProfile8(f: Format): Format {
        val c = f.codecs ?: return f
        return f.buildUpon().setCodecs(c.substring(0, 5) + "08" + c.substring(7)).build()
    }

    fun forDecoder(f: Format): Format = if (shouldConvert(f)) toProfile8(f) else f
}

/**
 * glcn: whether ExoPlayer is playing a Dolby Vision track right now, and whether it is a profile 7 rewritten as
 * 8.1 — for the film player's quality badges and its technical panel (mpv never shows Dolby Vision).
 */
object DolbyVisionNow {
    data class State(val dolbyVision: Boolean = false, val converted: Boolean = false)

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(State())
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state

    internal fun set(dolbyVision: Boolean, converted: Boolean) {
        _state.value = State(dolbyVision, converted)
    }
}
