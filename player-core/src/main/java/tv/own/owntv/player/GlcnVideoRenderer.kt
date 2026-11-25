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
    /** Dolby Vision turned off by the user: the RPU and the enhancement layer are dropped, the HEVC base layer plays (HDR10). */
    @Volatile private var doviStrip = false
    private var scratch = ByteArray(0)
    private var failures = 0
    private var reportedLayer: DolbyVisionNow.Layer? = null
    /** glcn: the current track's profile ("8.1"…), kept for every update of [DolbyVisionNow]. */
    private var dvProfile: String? = null
    private var dvTrack: Format? = null

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, DoviProfile7.forDecoder(format))

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> = super.getDecoderInfos(mediaCodecSelector, DoviProfile7.forDecoder(format), requiresSecureDecoder)

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val f = formatHolder.format
        doviStrip = f != null && DoviProfile7.shouldStrip(f)
        doviConvert = !doviStrip && f != null && DoviProfile7.shouldConvert(f)
        DoviP7.lastLayer = null
        reportedLayer = null
        dvTrack = f?.takeIf { it.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION }
        dvProfile = dvTrack?.let { DoviProfile7.profileName(it) }
        DolbyVisionNow.set(
            dolbyVision = f?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !doviStrip,
            converted = doviConvert,
            turnedOff = doviStrip,
            profile = dvProfile,
        )
        if (doviStrip && f != null) {
            formatHolder.format = DoviProfile7.toBaseLayer(f)
            failures = 0
            LiveDiagnosticsLog.event("Dolby Vision (${f.codecs}) turned off in Settings: played as plain HEVC (base layer)")
        } else if (doviConvert && f != null) {
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
        if ((doviConvert || doviStrip) && !buffer.isEncrypted) buffer.data?.let { rewrite(it) }
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
            val out = DoviP7.convertAccessUnit(scratch, n, dropRpu = doviStrip)
            if (doviConvert) reportLayer()
            if (out == null) return
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

    // glcn: the decoder chosen says whether Dolby Vision is really decoded — on a box without a Dolby Vision decoder
    // Media3 plays profile 8 from its HEVC base layer, and the panel said "Dolby Vision" all the same.
    override fun onCodecInitialized(name: String, configuration: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration, initializedTimestampMs: Long, initializationDurationMs: Long) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        val track = dvTrack ?: return
        if (doviStrip) return
        val decoderMime = codecInfo?.codecMimeType ?: return
        if (decoderMime == MimeTypes.VIDEO_DOLBY_VISION) return
        val base = DoviProfile7.baseName(track)
        DolbyVisionNow.set(dolbyVision = false, converted = false, profile = dvProfile, base = base)
        LiveDiagnosticsLog.event("Dolby Vision ${dvProfile ?: track.codecs} without a Dolby Vision decoder ($name): played as $base from the base layer")
    }

    /** Once the first RPU has been read: FEL or MEL, for the player's technical panel and the report. */
    private fun reportLayer() {
        val layer = DoviP7.lastLayer ?: return
        if (layer == reportedLayer) return
        reportedLayer = layer
        DolbyVisionNow.set(dolbyVision = true, converted = true, layer = layer, profile = dvProfile)
        LiveDiagnosticsLog.event("Dolby Vision profile 7 enhancement layer: ${layer.name}")
    }

    // glcn: Amlogic's OMX decoders (KM9PRO and other S905 boxes) kept from one channel to the next show three frames
    // and then freeze, with the sound going on ("frames stuck at 3"); a decoder made new for the channel plays.
    // A fresh one costs about 0.1 s per zap there, nothing elsewhere.
    override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format, isFormatSupported: Boolean): DecoderReuseEvaluation =
        if (codecInfo.name.startsWith(AMLOGIC_OMX)) {
            DecoderReuseEvaluation(codecInfo.name, oldFormat, newFormat, DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND)
        } else super.canReuseCodec(codecInfo, oldFormat, newFormat, isFormatSupported)

    // Only a codec still to be started is refused the stand-in: a running one keeps being moved, because
    // releasing it instead costs a black screen until the next keyframe (up to 2 s measured on the TCL).
    override fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo): Boolean =
        if (onSurfaceOnly) codec != null && super.shouldUsePlaceholderSurface(codecInfo) else super.shouldUsePlaceholderSurface(codecInfo)

    // Android 15's surface-less mode is the same start, moved later.
    override fun shouldUseDetachedSurface(codecInfo: MediaCodecInfo): Boolean =
        if (onSurfaceOnly) codec != null && super.shouldUseDetachedSurface(codecInfo) else super.shouldUseDetachedSurface(codecInfo)

    private companion object {
        const val TAG = "GlcnVideoRenderer"
        const val AMLOGIC_OMX = "OMX.amlogic."
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

    /** "Dolby Vision" off in Settings (for a receiver or TV without it); set from [PlaybackStartup]. */
    @Volatile var dolbyVisionOff = false

    /**
     * A Dolby Vision track whose base layer is plain HEVC on its own: profile 7 (HDR10) and profile 8 (HDR10,
     * SDR or HLG). Profile 5 is not: its picture only looks right through the Dolby Vision decoder.
     */
    fun hasHevcBaseLayer(f: Format): Boolean {
        if (f.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return false
        val c = f.codecs ?: return false
        if (c.length < 7 || !(c.startsWith("dvhe") || c.startsWith("dvh1"))) return false
        val profile = c.substring(5, 7)
        return profile == "07" || profile == "08"
    }

    fun shouldStrip(f: Format): Boolean = dolbyVisionOff && hasHevcBaseLayer(f)

    /**
     * glcn: the profile as people name it: "5", "7", and profile 8 with its base layer's kind — "8.1" over HDR10,
     * "8.4" over HLG, "8.2" over SDR (as the stream's colour info says; plain "8" when it says nothing).
     */
    fun profileName(f: Format): String? {
        val c = f.codecs ?: return null
        if (c.length < 7) return null
        val p = c.substring(5, 7).toIntOrNull() ?: return null
        if (p != 8) return p.toString()
        return when (f.colorInfo?.colorTransfer) {
            androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "8.1"
            androidx.media3.common.C.COLOR_TRANSFER_HLG -> "8.4"
            androidx.media3.common.C.COLOR_TRANSFER_SDR -> "8.2"
            else -> "8"
        }
    }

    /** glcn: what a Dolby Vision track's base layer is on its own: "HLG", "SDR" or (most often) "HDR10". */
    fun baseName(f: Format): String = when (f.colorInfo?.colorTransfer) {
        androidx.media3.common.C.COLOR_TRANSFER_HLG -> BASE_HLG
        androidx.media3.common.C.COLOR_TRANSFER_SDR -> BASE_SDR
        else -> BASE_HDR10
    }

    private const val BASE_HDR10 = "HDR10"
    private const val BASE_HLG = "HLG"
    private const val BASE_SDR = "SDR"

    /** The same track described as plain HEVC: any HEVC decoder takes it, and the TV gets the base layer's HDR10. */
    fun toBaseLayer(f: Format): Format =
        f.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()

    /** "dvhe.07.06" → "dvhe.08.06": same level, profile 8. */
    fun toProfile8(f: Format): Format {
        val c = f.codecs ?: return f
        return f.buildUpon().setCodecs(c.substring(0, 5) + "08" + c.substring(7)).build()
    }

    fun forDecoder(f: Format): Format = when {
        shouldStrip(f) -> toBaseLayer(f)
        shouldConvert(f) -> toProfile8(f)
        else -> f
    }
}

/**
 * glcn: whether ExoPlayer is playing a Dolby Vision track right now, and whether it is a profile 7 rewritten as
 * 8.1 — for the film player's quality badges and its technical panel (mpv never shows Dolby Vision).
 */
object DolbyVisionNow {
    /** What a profile 7 film's enhancement layer carries: real picture data (FEL) or next to nothing (MEL). */
    enum class Layer { FEL, MEL }

    /**
     * [layer]: for a converted profile 7, whether its enhancement layer was full (FEL — some picture detail is
     * lost, as in Kodi) or minimal (MEL — nothing lost); null until the first RPU is read.
     * [turnedOff]: a Dolby Vision film played as HDR10 because the user turned Dolby Vision off.
     */
    data class State(
        val dolbyVision: Boolean = false,
        val converted: Boolean = false,
        val layer: DolbyVisionNow.Layer? = null,
        val turnedOff: Boolean = false,
        /** glcn: the track's Dolby Vision profile as people name it ("5", "7", "8.1", "8.4"); null when not known. */
        val profile: String? = null,
        /**
         * glcn: a Dolby Vision track this device has no Dolby Vision decoder for, played from its plain HEVC base
         * layer — the TV gets [base] ("HDR10", "HLG", "SDR"), not Dolby Vision.
         */
        val base: String? = null,
    ) {
        val fel: Boolean get() = layer == DolbyVisionNow.Layer.FEL
        val mel: Boolean get() = layer == DolbyVisionNow.Layer.MEL
    }

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(State())
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state

    internal fun set(
        dolbyVision: Boolean, converted: Boolean, layer: DolbyVisionNow.Layer? = null, turnedOff: Boolean = false,
        profile: String? = null, base: String? = null,
    ) {
        _state.value = State(dolbyVision, converted, layer, turnedOff, profile, base)
    }
}
