package tv.own.owntv.player

/**
 * glcn: Dolby Vision profile 7 → profile 8.1 while the film plays — what Kodi does on the SHIELD.
 *
 * A UHD Blu-ray remux carries profile 7: an HDR10 base layer, an enhancement layer (NAL 63) and the Dolby
 * Vision metadata (the RPU, NAL 62) built for both. The SHIELD's Dolby Vision decoder takes profile 8 (base
 * layer + RPU) but not 7, so such a film played in plain HDR10. Rewriting each RPU as profile 8.1 and dropping
 * the enhancement layer gives the decoder a stream it accepts, and the TV shows Dolby Vision.
 *
 * The rewrite is libdovi's "mode 2" (quietvoid/dovi_tool, the library Kodi uses), ported bit for bit:
 * residual off; for a full enhancement layer (FEL) the reshaping curves become the identity, for a minimal one
 * (MEL) they are kept; the display-management matrices become the 8.1 ones; the extension blocks (L1–L11,
 * CM v4.0) are copied untouched; the CRC is recomputed. Anything it does not recognise is left as it was.
 */
internal object DoviP7 {

    /**
     * One HEVC access unit in Annex-B form (as Media3's extractors hand it to the decoder) → the same with each
     * enhancement-layer NAL dropped and each RPU rewritten. Null when there is nothing to change, or the data
     * is not Annex-B.
     */
    fun convertAccessUnit(src: ByteArray, len: Int): ByteArray? {
        val starts = ArrayList<Int>(8) // where each "00 00 01" begins
        var i = 0
        while (i + 2 < len) {
            if (src[i].toInt() == 0 && src[i + 1].toInt() == 0 && src[i + 2].toInt() == 1) {
                starts += i
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty() || starts[0] > 1) return null
        var touched = false
        for (s in starts) {
            val t = nalType(src, s + 3, len)
            if (t == NAL_EL || t == NAL_RPU) { touched = true; break }
        }
        if (!touched) return null
        val out = java.io.ByteArrayOutputStream(len)
        for (k in starts.indices) {
            val begin = starts[k] + 3
            var end = if (k + 1 < starts.size) starts[k + 1] else len
            while (end > begin && src[end - 1].toInt() == 0) end-- // trailing zeros / a 4-byte start code's first byte
            if (end <= begin) continue
            when (nalType(src, begin, len)) {
                NAL_EL -> continue
                NAL_RPU -> {
                    val nal = src.copyOfRange(begin, end)
                    out.write(START_CODE)
                    out.write(convertRpuNal(nal) ?: nal)
                }
                else -> {
                    out.write(START_CODE)
                    out.write(src, begin, end - begin)
                }
            }
        }
        return out.toByteArray()
    }

    private fun nalType(d: ByteArray, at: Int, len: Int): Int = if (at < len) (d[at].toInt() ushr 1) and 0x3F else -1

    /** A whole RPU NAL (its 2-byte header `7C 01`, then the escaped payload) → the profile 8.1 one, or null. */
    fun convertRpuNal(nal: ByteArray): ByteArray? {
        if (nal.size < 3 || nalType(nal, 0, nal.size) != NAL_RPU) return null
        val rbsp = unescape(nal, 2, nal.size)
        val converted = convertRpu(rbsp) ?: return null
        val escaped = escape(converted)
        val out = ByteArray(escaped.size + 2)
        out[0] = nal[0]
        out[1] = nal[1]
        System.arraycopy(escaped, 0, out, 2, escaped.size)
        return out
    }

    /** An unescaped RPU (`0x19 … CRC32 0x80`) of profile 7 → profile 8.1; null when it is not profile 7 or not understood. */
    fun convertRpu(d: ByteArray): ByteArray? = runCatching { convertRpuOrNull(d) }.getOrNull()

    private fun convertRpuOrNull(d: ByteArray): ByteArray? {
        var end = d.size
        var trailing = 0
        while (end > 0 && d[end - 1].toInt() == 0) { end--; trailing++ }
        if (end < 7 || d[0].toInt() != RPU_PREFIX || (d[end - 1].toInt() and 0xFF) != FINAL_BYTE) return null
        val crcStart = end - 5
        if (crc32(d, 1, crcStart) != readU32(d, crcStart)) return null

        val r = BitReader(d, crcStart)
        val w = BitWriter(d.size)
        r.bits(8)
        w.bits(8, RPU_PREFIX.toLong())

        // rpu_data_header
        val rpuType = r.bits(6)
        if (rpuType != 2L) return null
        w.bits(6, rpuType)
        val rpuFormat = r.bits(11)
        w.bits(11, rpuFormat)
        val vdrRpuProfile = r.bits(4)
        w.bits(4, vdrRpuProfile)
        w.bits(4, r.bits(4)) // vdr_rpu_level
        val seqInfo = r.bit()
        w.bit(seqInfo)
        var coefType = 0L
        var denomLen = 0
        var blDepth = 0
        var elDepth = 0
        var vdrDepthMinus8 = -1L
        var reserved3 = 0L
        var elSpatial = false
        var disableResidual = false
        if (seqInfo) {
            w.bit(r.bit()) // chroma_resampling_explicit_filter_flag
            coefType = r.bits(2)
            w.bits(2, coefType)
            var denom = 0L
            if (coefType == 0L) {
                denom = r.ue()
                w.ue(denom)
            }
            w.bits(2, r.bits(2)) // vdr_rpu_normalized_idc
            w.bit(r.bit()) // bl_video_full_range_flag
            if (rpuFormat and 0x700L == 0L) {
                val bl = r.ue()
                w.ue(bl)
                blDepth = (bl + 8).toInt()
                val el = r.ue() // el_bit_depth_minus8 with ext_mapping_idc above bit 8
                w.ue(el)
                elDepth = ((el and 0xFF) + 8).toInt()
                vdrDepthMinus8 = r.ue()
                w.ue(vdrDepthMinus8)
                w.bit(r.bit()) // spatial_resampling_filter_flag
                reserved3 = r.bits(3)
                w.bits(3, reserved3)
                elSpatial = r.bit()
                disableResidual = r.bit()
                // Profile 8.1: no enhancement layer to resample, no residual.
                w.bit(false)
                w.bit(true)
            }
            denomLen = when (coefType) {
                0L -> denom.toInt()
                1L -> 32
                else -> return null
            }
        }
        val profile7 = vdrRpuProfile == 1L && elSpatial && !disableResidual && vdrDepthMinus8 == 4L
        if (!profile7 || blDepth != 10) return null

        val dmPresent = r.bit()
        w.bit(dmPresent)
        val usePrev = r.bit()
        w.bit(usePrev)
        if (usePrev) {
            w.ue(r.ue()) // prev_vdr_rpu_id
        } else {
            // rpu_data_mapping
            w.ue(r.ue()) // vdr_rpu_id
            w.ue(r.ue()) // mapping_color_space
            w.ue(r.ue()) // mapping_chroma_format_idc
            val pivotsFrom = r.pos
            val numPivotsMinus2 = IntArray(COMPONENTS)
            for (cmp in 0 until COMPONENTS) {
                val n = r.ue()
                if (n >= MAX_PIECES) return null
                numPivotsMinus2[cmp] = n.toInt()
                repeat(n.toInt() + 2) { r.bits(blDepth) }
            }
            val pivotsTo = r.pos
            // Residual was on: the NLQ header (method, two pivots) — dropped with the residual.
            val m = r.bits(3)
            if (m != 0L) return null
            r.bits(blDepth)
            r.bits(blDepth)
            r.ue() // num_x_partitions_minus1
            r.ue() // num_y_partitions_minus1
            val curvesFrom = r.pos
            for (cmp in 0 until COMPONENTS) {
                repeat(numPivotsMinus2[cmp] + 1) {
                    when (r.ue()) {
                        0L -> { // polynomial
                            val order = r.ue()
                            if (order > 1) return null
                            if (order == 0L && r.bit()) return null // linear interpolation: unseen in practice
                            repeat(order.toInt() + 2) {
                                if (coefType == 0L) r.se()
                                r.bits(denomLen)
                            }
                        }
                        1L -> { // MMR
                            val order = r.bits(2)
                            if (order > 2) return null
                            if (coefType == 0L) r.se()
                            r.bits(denomLen)
                            repeat(order.toInt() + 1) {
                                repeat(MMR_COEFFS) {
                                    if (coefType == 0L) r.se()
                                    r.bits(denomLen)
                                }
                            }
                        }
                        else -> return null
                    }
                }
            }
            val curvesTo = r.pos
            // NLQ data — only read to tell a full enhancement layer from a minimal one.
            var mel = true
            repeat(COMPONENTS) {
                val offset = r.bits(elDepth)
                val inMaxInt = if (coefType == 0L) r.ue() else 0L
                val inMax = r.bits(denomLen)
                val slopeInt = if (coefType == 0L) r.ue() else 0L
                val slope = r.bits(denomLen)
                val thresholdInt = if (coefType == 0L) r.ue() else 0L
                val threshold = r.bits(denomLen)
                if (offset != 0L || inMaxInt != 1L || inMax != 0L || slopeInt != 0L || slope != 0L ||
                    thresholdInt != 0L || threshold != 0L
                ) mel = false
            }
            if (mel) {
                w.copyBits(d, pivotsFrom, pivotsTo)
            } else {
                for (cmp in 0 until COMPONENTS) {
                    w.ue(0)
                    w.bits(blDepth, 0)
                    w.bits(blDepth, 1023)
                }
            }
            w.ue(0) // num_x_partitions_minus1
            w.ue(0) // num_y_partitions_minus1
            if (mel) {
                w.copyBits(d, curvesFrom, curvesTo)
            } else {
                // FEL: the base layer alone is HDR10, so the curves become the identity (y = x).
                if (coefType != 0L) return null
                repeat(COMPONENTS) {
                    w.ue(0) // mapping_idc: polynomial
                    w.ue(0) // poly_order_minus1
                    w.bit(false) // linear_interp_flag
                    w.se(0)
                    w.bits(denomLen, 0)
                    w.se(1)
                    w.bits(denomLen, 0)
                }
            }
        }

        if (dmPresent) {
            w.ue(r.ue()) // affected_dm_metadata_id
            w.ue(r.ue()) // current_dm_metadata_id
            w.ue(r.ue()) // scene_refresh_flag
            if (reserved3 != 1L) { // not compressed
                repeat(9) { r.bits(16) }
                repeat(3) { r.bits(32) }
                repeat(9) { r.bits(16) }
                for (c in P81_YCC_TO_RGB) w.bits(16, c.toLong() and 0xFFFF)
                for (o in P81_YCC_OFFSETS) w.bits(32, o)
                for (c in P81_RGB_TO_LMS) w.bits(16, c.toLong() and 0xFFFF)
                w.bits(16, r.bits(16)) // signal_eotf
                w.bits(16, r.bits(16)) // signal_eotf_param0
                w.bits(16, r.bits(16)) // signal_eotf_param1
                w.bits(32, r.bits(32)) // signal_eotf_param2
                w.bits(5, r.bits(5)) // signal_bit_depth
                r.bits(2)
                w.bits(2, 0) // signal_color_space
                w.bits(2, r.bits(2)) // signal_chroma_format
                w.bits(2, r.bits(2)) // signal_full_range_flag
                w.bits(12, r.bits(12)) // source_min_pq
                w.bits(12, r.bits(12)) // source_max_pq
                w.bits(10, r.bits(10)) // source_diagonal
            }
            w.ue(r.ue()) // CM v2.9 num_ext_blocks; byte-aligned from here on
        }
        r.align()
        w.align()
        // The extension blocks, CM v4.0 and anything else up to the CRC, unchanged: both sides are byte-aligned.
        w.bytes(d, r.pos / 8, crcStart)
        val body = w.toByteArray()
        val crc = crc32(body, 1, body.size)
        val out = ByteArray(body.size + 5 + trailing)
        System.arraycopy(body, 0, out, 0, body.size)
        out[body.size] = (crc ushr 24).toByte()
        out[body.size + 1] = (crc ushr 16).toByte()
        out[body.size + 2] = (crc ushr 8).toByte()
        out[body.size + 3] = crc.toByte()
        out[body.size + 4] = FINAL_BYTE.toByte()
        return out
    }

    // ---- Bits ----------------------------------------------------------------------------------------------

    private class BitReader(private val d: ByteArray, private val endByte: Int) {
        var pos = 0

        fun bit(): Boolean {
            if (pos >= endByte * 8) throw IndexOutOfBoundsException()
            val b = (d[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b == 1
        }

        fun bits(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or (if (bit()) 1L else 0L) }
            return v
        }

        fun ue(): Long {
            var zeros = 0
            while (!bit()) {
                zeros++
                if (zeros > 40) throw IllegalStateException()
            }
            return (1L shl zeros) - 1 + bits(zeros)
        }

        fun se(): Long {
            val k = ue()
            return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
        }

        fun align() {
            while (pos and 7 != 0) pos++
        }
    }

    private class BitWriter(capacity: Int) {
        private var buf = ByteArray(capacity + 16)
        private var pos = 0

        fun bit(b: Boolean) {
            if ((pos ushr 3) >= buf.size) buf = buf.copyOf(buf.size * 2)
            if (b) buf[pos ushr 3] = (buf[pos ushr 3].toInt() or (1 shl (7 - (pos and 7)))).toByte()
            pos++
        }

        fun bits(n: Int, v: Long) {
            for (i in n - 1 downTo 0) bit((v ushr i) and 1L == 1L)
        }

        fun ue(v: Long) {
            val x = v + 1
            val n = 64 - java.lang.Long.numberOfLeadingZeros(x)
            bits(n - 1, 0)
            bits(n, x)
        }

        fun se(v: Long) = ue(if (v > 0) 2 * v - 1 else -2 * v)

        fun copyBits(src: ByteArray, from: Int, to: Int) {
            for (p in from until to) bit((src[p ushr 3].toInt() ushr (7 - (p and 7))) and 1 == 1)
        }

        fun align() {
            while (pos and 7 != 0) bit(false)
        }

        fun bytes(src: ByteArray, from: Int, to: Int) {
            for (i in from until to) bits(8, (src[i].toLong() and 0xFF))
        }

        fun toByteArray(): ByteArray = buf.copyOf(pos ushr 3)
    }

    // ---- Escaping and CRC ------------------------------------------------------------------------------------

    private fun unescape(d: ByteArray, from: Int, to: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(to - from)
        var zeros = 0
        for (i in from until to) {
            val b = d[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun escape(d: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(d.size + 8)
        var zeros = 0
        for (x in d) {
            val b = x.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) { out.write(3); zeros = 0 }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** CRC-32/MPEG-2 (poly 0x04C11DB7, init all ones, no reflection, no final xor). */
    private fun crc32(d: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((d[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun readU32(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private const val NAL_RPU = 62
    private const val NAL_EL = 63
    private const val RPU_PREFIX = 0x19
    private const val FINAL_BYTE = 0x80
    private const val COMPONENTS = 3
    private const val MAX_PIECES = 8L
    private const val MMR_COEFFS = 7

    // The profile 8.1 display-management matrices (libdovi's set_p81_coeffs).
    private val P81_YCC_TO_RGB = intArrayOf(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0)
    private val P81_YCC_OFFSETS = longArrayOf(16777216L, 134217728L, 134217728L)
    private val P81_RGB_TO_LMS = intArrayOf(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962)
}
