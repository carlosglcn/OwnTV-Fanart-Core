package tv.own.owntv.player

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * glcn: DVB teletext subtitles (ETSI EN 300 472 / EN 300 706) for live TV on ExoPlayer.
 *
 * Spanish broadcasters (Movistar+, RTVE, Mediaset…) carry their subtitles as teletext page 888, not as
 * DVB bitmaps. Media3 has no teletext support at all — the stream was dropped by the TS extractor — and
 * the bundled libmpv was built without libzvbi. This adds the missing piece in three small parts:
 *
 *  1. [TeletextPayloadReaderFactory] spots a private stream whose PMT entry carries a teletext descriptor
 *     (tag 0x56) listing subtitle pages, and reads it with a [PesReader] + [TeletextReader] — one text
 *     track per subtitle page, its language taken from the descriptor.
 *  2. [TeletextReader] forwards each PES payload untouched as one sample, with the PES time.
 *  3. [TeletextParser] (through [TeletextParserFactory]) decodes the packets of its page into cues while
 *     the extractor transcodes text tracks, so the cues reach the renderer as ordinary Media3 cues and
 *     the app's subtitle style and track menu apply as to any other subtitle.
 *
 * Only what subtitles use is decoded: page header (erase), rows 1–23, the alpha colours and the Latin
 * national subset of the page language. Enhancement packets (X/26 accents, X/28) are ignored.
 */
@OptIn(UnstableApi::class)
object Teletext {
    /** Sample MIME type of a teletext subtitle page before transcoding (the track's `codecs` after it). */
    const val MIME = "application/x-glcn-teletext"

    private const val DESCRIPTOR_TELETEXT = 0x56
    private const val TYPE_SUBTITLE = 0x02
    private const val TYPE_SUBTITLE_HEARING_IMPAIRED = 0x05

    /** A subtitle page from a teletext descriptor: magazine 1–8, page 0x00–0xFF (BCD, e.g. 0x88). */
    data class Page(val language: String?, val magazine: Int, val page: Int, val hearingImpaired: Boolean)

    /** The subtitle pages listed by the teletext descriptor in [descriptorBytes], or empty. */
    fun subtitlePages(descriptorBytes: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var i = 0
        while (i + 2 <= descriptorBytes.size) {
            val tag = descriptorBytes[i].toInt() and 0xFF
            val len = descriptorBytes[i + 1].toInt() and 0xFF
            val end = i + 2 + len
            if (end > descriptorBytes.size) break
            if (tag == DESCRIPTOR_TELETEXT) {
                var j = i + 2
                while (j + 5 <= end) {
                    val lang = String(descriptorBytes, j, 3, Charsets.ISO_8859_1).trim().takeIf { it.isNotEmpty() }
                    val b = descriptorBytes[j + 3].toInt() and 0xFF
                    val type = b ushr 3
                    val mag = (b and 0x07).let { if (it == 0) 8 else it }
                    val page = descriptorBytes[j + 4].toInt() and 0xFF
                    if (type == TYPE_SUBTITLE || type == TYPE_SUBTITLE_HEARING_IMPAIRED) {
                        out += Page(lang, mag, page, type == TYPE_SUBTITLE_HEARING_IMPAIRED)
                    }
                    j += 5
                }
            }
            i = end
        }
        return out
    }

    /** The page a track carries, written into its initialization data by [TeletextReader]. */
    internal fun pageOf(format: Format): Pair<Int, Int>? =
        format.initializationData.firstOrNull()?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and 0xFF) to (it[1].toInt() and 0xFF) }

    // --- Low-level decoding --------------------------------------------------------------------

    private val REVERSE = IntArray(256) { v ->
        var r = 0
        for (b in 0 until 8) if (v and (1 shl b) != 0) r = r or (1 shl (7 - b))
        r
    }

    /** DVB sends each teletext byte bit-reversed; this is the byte in teletext order. */
    internal fun rev(b: Byte): Int = REVERSE[b.toInt() and 0xFF]

    /** Hamming 8/4 data nibble of a byte already in teletext order (D1..D4 at bits 1, 3, 5, 7). */
    internal fun unham(v: Int): Int =
        ((v ushr 1) and 1) or (((v ushr 3) and 1) shl 1) or (((v ushr 5) and 1) shl 2) or (((v ushr 7) and 1) shl 3)

    /** Latin G0 national option positions and their characters, per subset (EN 300 706 table 36). */
    private val NATIONAL_POSITIONS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)
    private val SUBSET_ENGLISH = charArrayOf('£', '$', '@', '←', '½', '→', '↑', '#', '—', '¼', '‖', '¾', '÷')
    private val SUBSET_SPANISH = charArrayOf('ç', '$', '¡', 'á', 'é', 'í', 'ó', 'ú', '¿', 'ü', 'ñ', 'è', 'à')
    private val SUBSET_GERMAN = charArrayOf('#', '$', '§', 'Ä', 'Ö', 'Ü', '^', '_', '°', 'ä', 'ö', 'ü', 'ß')
    private val SUBSET_FRENCH = charArrayOf('é', 'ï', 'à', 'ë', 'ê', 'ù', 'î', '#', 'è', 'â', 'ô', 'û', 'ç')
    private val SUBSET_ITALIAN = charArrayOf('£', '$', 'é', '°', 'ç', '→', '↑', '#', 'ù', 'à', 'ò', 'è', 'ì')
    private val SUBSET_SWEDISH = charArrayOf('#', '¤', 'É', 'Ä', 'Ö', 'Å', 'Ü', '_', 'é', 'ä', 'ö', 'å', 'ü')

    /** The national subset for a page, chosen from the language the PMT gives it. */
    internal fun subsetFor(language: String?): CharArray = when (language?.lowercase()) {
        "spa", "esp", "es", "cat", "ca", "glg", "gl", "eus", "baq", "eu", "por", "pt" -> SUBSET_SPANISH
        "deu", "ger", "de" -> SUBSET_GERMAN
        "fra", "fre", "fr" -> SUBSET_FRENCH
        "ita", "it" -> SUBSET_ITALIAN
        "swe", "sv", "fin", "fi" -> SUBSET_SWEDISH
        else -> SUBSET_ENGLISH
    }

    internal fun charFor(code: Int, subset: CharArray): Char {
        val k = NATIONAL_POSITIONS.indexOf(code)
        return if (k >= 0) subset[k] else if (code == 0x7F) '■' else code.toChar()
    }

    private val COLOURS = intArrayOf(
        Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE,
    )

    /**
     * One row's 40 bytes (teletext order, parity bit still set) to text with colour spans. Spacing
     * attributes show as spaces, as on a teletext screen; the caller trims.
     */
    internal fun rowText(bytes: IntArray, subset: CharArray): CharSequence {
        val sb = SpannableStringBuilder()
        var colour = Color.WHITE
        var spanStart = 0
        fun close() {
            if (colour != Color.WHITE && sb.length > spanStart) {
                sb.setSpan(ForegroundColorSpan(colour), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        for (v in bytes) {
            val c = v and 0x7F
            if (c < 0x20) {
                // Alpha colour codes 0x00–0x07 change the text colour from the next cell on.
                if (c <= 0x07) { close(); colour = COLOURS[c]; spanStart = sb.length + 1 }
                sb.append(' ')
            } else {
                sb.append(charFor(c, subset))
            }
        }
        close()
        return sb
    }
}

/** Reads teletext subtitle streams in place of the stock factory's "unknown stream" for them. */
@OptIn(UnstableApi::class)
class TeletextPayloadReaderFactory(private val delegate: TsPayloadReader.Factory) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        if (streamType == -1 || streamType == 0x06) {
            val pages = Teletext.subtitlePages(esInfo.descriptorBytes)
            if (pages.isNotEmpty()) return PesReader(TeletextReader(pages))
        }
        return delegate.createPayloadReader(streamType, esInfo)
    }
}

/** One text track per subtitle page; each PES payload becomes one sample on every track. */
@OptIn(UnstableApi::class)
class TeletextReader(private val pages: List<Teletext.Page>) : ElementaryStreamReader {
    private val outputs = arrayOfNulls<TrackOutput>(pages.size)
    private var writing = false
    private var checkIdentifier = false
    private var sampleTimeUs = C.TIME_UNSET
    private var sampleBytes = 0

    override fun seek() { writing = false; sampleTimeUs = C.TIME_UNSET }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        pages.forEachIndexed { i, p ->
            idGenerator.generateNewId()
            val out = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
            out.format(
                Format.Builder()
                    .setId(idGenerator.formatId)
                    .setSampleMimeType(Teletext.MIME)
                    .setLanguage(p.language)
                    .setRoleFlags(if (p.hearingImpaired) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
                    .setInitializationData(listOf(byteArrayOf(p.magazine.toByte(), p.page.toByte()), (p.language ?: "").toByteArray()))
                    .build(),
            )
            outputs[i] = out
        }
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        writing = pesTimeUs != C.TIME_UNSET
        sampleTimeUs = pesTimeUs
        sampleBytes = 0
        checkIdentifier = true
    }

    override fun consume(data: ParsableByteArray) {
        if (!writing) return
        if (checkIdentifier) {
            if (data.bytesLeft() == 0) return
            // data_identifier 0x10–0x1F: EBU data (teletext). Anything else is not ours.
            val id = data.peekUnsignedByte()
            if (id !in 0x10..0x1F) { writing = false; return }
            checkIdentifier = false
        }
        val pos = data.position
        val n = data.bytesLeft()
        for (o in outputs) { data.position = pos; o?.sampleData(data, n) }
        sampleBytes += n
    }

    override fun packetFinished() {
        if (writing && sampleBytes > 0) {
            for (o in outputs) o?.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytes, 0, null)
        }
        writing = false
    }
}

/**
 * Decodes one subtitle page. A page is "open" from its header packet until another page header
 * of the same magazine; its rows are drawn as they arrive and shown at the end of each PES that
 * changed them, replacing what was on screen (a header with the erase bit and no rows clears it).
 */
@OptIn(UnstableApi::class)
class TeletextParser(private val magazine: Int, private val page: Int, language: String?) : SubtitleParser {
    private val subset = Teletext.subsetFor(language)
    private val rows = arrayOfNulls<CharSequence>(24)
    private var open = false
    private var serial = false
    private var shown = false

    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun reset() { rows.fill(null); open = false; shown = false }

    override fun parse(
        data: ByteArray, offset: Int, length: Int,
        outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>,
    ) {
        var dirty = false
        val end = offset + length
        var i = offset + 1 // skip data_identifier
        while (i + 2 <= end) {
            val unitId = data[i].toInt() and 0xFF
            val unitLen = data[i + 1].toInt() and 0xFF
            val unitStart = i + 2
            i = unitStart + unitLen
            if (i > end) break
            // 0x02 teletext, 0x03 teletext subtitles; 44 bytes: line, framing code, address, 40 data bytes.
            if ((unitId != 0x02 && unitId != 0x03) || unitLen < 44) continue
            val p = unitStart + 2
            val a0 = Teletext.unham(Teletext.rev(data[p]))
            val a1 = Teletext.unham(Teletext.rev(data[p + 1]))
            val mag = (a0 and 0x07).let { if (it == 0) 8 else it }
            val row = (a0 ushr 3) or (a1 shl 1)
            if (row == 0) {
                val units = Teletext.unham(Teletext.rev(data[p + 2]))
                val tens = Teletext.unham(Teletext.rev(data[p + 3]))
                val pg = (tens shl 4) or units
                val c4Erase = (Teletext.unham(Teletext.rev(data[p + 5])) and 0x08) != 0
                serial = (Teletext.unham(Teletext.rev(data[p + 9])) and 0x01) != 0
                if (mag == magazine && pg == page) {
                    open = true
                    if (c4Erase) { rows.fill(null) }
                    dirty = true
                } else if (mag == magazine || serial) {
                    // Another page of this magazine (or any page in serial mode) ends ours. 0xFF is a
                    // time-filling header that closes the page without opening a new one.
                    open = false
                }
            } else if (row in 1..23 && mag == magazine && open) {
                val bytes = IntArray(40) { k -> Teletext.rev(data[p + 2 + k]) }
                rows[row] = Teletext.rowText(bytes, subset)
                dirty = true
            }
        }
        if (!dirty) return
        val cues = buildCues()
        if (cues.isEmpty() && !shown) return
        shown = cues.isNotEmpty()
        output.accept(CuesWithTiming(cues, /* startTimeUs= */ 0L, /* durationUs= */ C.TIME_UNSET))
    }

    /** The rows with text, as one cue near the bottom (or the top, when the page uses the upper half). */
    private fun buildCues(): List<Cue> {
        val lines = ArrayList<Pair<Int, CharSequence>>()
        for (r in 1..23) {
            val t = rows[r] ?: continue
            val trimmed = trim(t)
            if (trimmed.isNotEmpty()) lines += r to trimmed
        }
        if (lines.isEmpty()) return emptyList()
        val text = SpannableStringBuilder()
        lines.forEachIndexed { k, (_, t) -> if (k > 0) text.append('\n'); text.append(t) }
        val top = lines.last().first < 12
        return listOf(
            Cue.Builder()
                .setText(text)
                .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setLine(if (top) 0.1f else 0.9f, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(if (top) Cue.ANCHOR_TYPE_START else Cue.ANCHOR_TYPE_END)
                .build(),
        )
    }

    private fun trim(t: CharSequence): CharSequence {
        var s = 0
        var e = t.length
        while (s < e && t[s].isWhitespace()) s++
        while (e > s && t[e - 1].isWhitespace()) e--
        return t.subSequence(s, e)
    }
}

/** Adds [TeletextParser] to whatever subtitle parser factory the media source would use. */
@OptIn(UnstableApi::class)
class TeletextParserFactory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == Teletext.MIME || delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (format.sampleMimeType == Teletext.MIME) Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE else delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        if (format.sampleMimeType != Teletext.MIME) return delegate.create(format)
        val (mag, page) = Teletext.pageOf(format) ?: (8 to 0x88)
        return TeletextParser(mag, page, format.language)
    }
}

/**
 * The live TS extractors with teletext subtitles: [base] for everything, its TsExtractor swapped for
 * one that reads teletext ([TeletextPayloadReaderFactory]) and parses it ([TeletextParserFactory]).
 * [tsPayloadReaderFactory] builds the same stock payload reader factory [base] was configured with.
 */
@OptIn(UnstableApi::class)
class TeletextExtractorsFactory(
    private val base: androidx.media3.extractor.DefaultExtractorsFactory,
    private val tsPayloadReaderFactory: () -> TsPayloadReader.Factory,
) : ExtractorsFactory {
    private var parserFactory: SubtitleParser.Factory =
        TeletextParserFactory(androidx.media3.extractor.text.DefaultSubtitleParserFactory())
    private var transcoding = true

    init { base.setSubtitleParserFactory(parserFactory) }

    @Deprecated("Media3 legacy subtitle path")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        transcoding = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        base.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        parserFactory = subtitleParserFactory as? TeletextParserFactory ?: TeletextParserFactory(subtitleParserFactory)
        base.setSubtitleParserFactory(parserFactory)
        return this
    }

    override fun createExtractors(): Array<Extractor> = swap(base.createExtractors())

    override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(base.createExtractors(uri, responseHeaders))

    private fun swap(extractors: Array<Extractor>): Array<Extractor> {
        // Without transcoding the cues would need a legacy decoder that does not exist — keep the stock one.
        if (!transcoding) return extractors
        return Array(extractors.size) { i ->
            if (extractors[i] is TsExtractor) {
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT, 0, parserFactory, TimestampAdjuster(0),
                    TeletextPayloadReaderFactory(tsPayloadReaderFactory()), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
                )
            } else extractors[i]
        }
    }
}
