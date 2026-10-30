package tv.own.owntv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Dolby Vision profile 7 → 8.1 rewrite gives libdovi's bytes exactly (FEL and MEL). */
class DoviP7Test {

    private fun res(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dovi/$name")).use { it.readBytes() }

    private fun rpuNal(file: String): ByteArray {
        val d = res(file)
        return byteArrayOf(0x7C, 0x01) + d.copyOfRange(4, d.size)
    }

    @Test
    fun `full enhancement layer becomes 8_1 with identity curves`() {
        assertArrayEquals(res("fel_orig_to_81.bin"), DoviP7.convertRpuNal(rpuNal("fel_orig.bin")))
    }

    @Test
    fun `minimal enhancement layer becomes 8_1 keeping its curves`() {
        assertArrayEquals(res("mel_orig_to_81.bin"), DoviP7.convertRpuNal(rpuNal("mel_orig.bin")))
    }

    @Test
    fun `an access unit loses its enhancement layer and gets the new RPU`() {
        val slice = byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x55, 0x66)
        val el = byteArrayOf(0, 0, 0, 1, 0x7E, 0x01, 0x11, 0x22)
        val au = slice + el + byteArrayOf(0, 0, 1) + rpuNal("fel_orig.bin")
        val expected = slice + byteArrayOf(0, 0, 0, 1) + res("fel_orig_to_81.bin")
        assertArrayEquals(expected, DoviP7.convertAccessUnit(au, au.size))
    }

    @Test
    fun `a profile 8 RPU and a plain access unit are left alone`() {
        assertNull(DoviP7.convertRpuNal(res("fel_orig_to_81.bin")))
        val plain = byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x55, 0x66)
        assertNull(DoviP7.convertAccessUnit(plain, plain.size))
    }
}
