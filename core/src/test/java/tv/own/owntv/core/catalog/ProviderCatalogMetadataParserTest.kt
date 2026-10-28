package tv.own.owntv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the parser reads out of a provider's title. The parser runs each pattern only when the title
 * holds the text that pattern needs, so these cover each pattern's own spellings: a gate that is too
 * narrow shows up here as a tag that is no longer found.
 */
class ProviderCatalogMetadataParserTest {

    private fun parse(title: String) = ProviderCatalogMetadataParser.parse(title, null)

    @Test
    fun `language prefix, HDR10+ and Atmos are all found`() {
        val p = parse("EN| Show HDR10+ ATMOS 7.1")
        assertEquals("Show", p.canonicalTitle)
        assertEquals("EN", p.language)
        assertEquals("HDR10+ • Dolby Atmos", p.capabilities)
    }

    @Test
    fun `bracketed language, lower-case resolution and Dolby Vision`() {
        val p = parse("[FR] Film 2160p DV")
        assertEquals("FR", p.language)
        assertEquals(4, p.qualityRank)
        assertEquals("Dolby Vision", p.capabilities)
        assertEquals("Film", p.canonicalTitle)
    }

    @Test
    fun `every audio spelling counts, underscore and space included`() {
        assertEquals("5.1 Audio", parse("Film DDP 5_1").capabilities)
        assertEquals("2.0 Audio", parse("Film 2 0").capabilities)
        assertEquals("7.1 Audio", parse("Film 7.1").capabilities)
    }

    @Test
    fun `trailing language and the year`() {
        assertEquals("EN", parse("Show EN").language)
        val p = parse("The Movie (2020)")
        assertEquals("The Movie", p.canonicalTitle)
        assertEquals(2020, p.parsedYear)
    }

    @Test
    fun `quality ranks`() {
        assertEquals(5, parse("Movie 8K").qualityRank)
        assertEquals(3, parse("Movie FHD").qualityRank)
        assertEquals(2, parse("Movie hd").qualityRank)
        assertEquals(1, parse("Movie SD").qualityRank)
    }

    @Test
    fun `a title without any tag is left as it is`() {
        val p = parse("Nothing Here")
        assertEquals("Nothing Here", p.canonicalTitle)
        assertEquals(null, p.language)
        assertEquals(0, p.qualityRank)
        assertEquals(null, p.capabilities)
    }

    @Test
    fun `badges read resolution, remux, hdr and audio`() {
        assertEquals(listOf("4K", "REMUX"), ProviderCatalogMetadataParser.badges("Normal [4K R]"))
        assertEquals(listOf("REMUX", "DV"), ProviderCatalogMetadataParser.badges("La asistenta [DV R]"))
        assertEquals(listOf("4K", "HDR10+", "ATMOS"), ProviderCatalogMetadataParser.badges("Dune 4K HDR10+ Atmos"))
        assertEquals(listOf("1080p", "REMUX"), ProviderCatalogMetadataParser.badges("Alien 1080p REMUX"))
        assertEquals(emptyList<String>(), ProviderCatalogMetadataParser.badges("Rocky (R)"))
    }

    @Test
    fun `a poster shows the best picture mark and remux only`() {
        assertEquals(listOf("DV", "REMUX"), ProviderCatalogMetadataParser.posterBadges("Film [4K DV R] Atmos"))
        assertEquals(listOf("4K"), ProviderCatalogMetadataParser.posterBadges("Film 4K 5.1"))
        assertEquals(emptyList<String>(), ProviderCatalogMetadataParser.posterBadges("Film 1080p"))
    }
}
