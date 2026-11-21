package tv.own.owntv.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFolderMatchTest {

    private val matrix = SubtitleFolderMatch.Target("The Matrix", 1999, null, null)
    private val episode = SubtitleFolderMatch.Target("Breaking Bad", 2008, 1, 3)

    @Test
    fun aFilmMatchesExactlyWhenOnlyReleaseDetailFollowsItsTitle() {
        assertTrue(SubtitleFolderMatch.isExact(matrix, "The.Matrix.1999.1080p.BluRay.x264.srt"))
        assertTrue(SubtitleFolderMatch.isExact(matrix, "the matrix (1999).en.srt"))
        assertTrue(SubtitleFolderMatch.isExact(matrix, "The Matrix.srt"))
        assertFalse(SubtitleFolderMatch.isExact(matrix, "The.Matrix.Reloaded.2003.srt"))
        assertFalse(SubtitleFolderMatch.isExact(matrix, "The.Matrix.2021.srt"))
        assertFalse(SubtitleFolderMatch.isExact(SubtitleFolderMatch.Target("Up", 2009, null, null), "Up.In.The.Air.2009.srt"))
    }

    @Test
    fun anEpisodeMatchesOnlyItsOwnCode() {
        assertTrue(SubtitleFolderMatch.isExact(episode, "Breaking.Bad.S01E03.720p.srt"))
        assertTrue(SubtitleFolderMatch.isExact(episode, "breaking bad 1x03.srt"))
        assertFalse(SubtitleFolderMatch.isExact(episode, "Breaking.Bad.S01E04.srt"))
        assertFalse(SubtitleFolderMatch.isExact(episode, "Breaking.Bad.srt"))
    }

    @Test
    fun namesAreComparedInEveryAlphabetsLowerCase() {
        val target = SubtitleFolderMatch.Target("Ирония судьбы", 1975, null, null)
        assertTrue(SubtitleFolderMatch.isExact(target, "ИРОНИЯ.СУДЬБЫ.1975.srt"))
        assertEquals(listOf("ИРОНИЯ.СУДЬБЫ.srt"), SubtitleFolderMatch.filter(listOf("ИРОНИЯ.СУДЬБЫ.srt", "Other.srt"), "ирония") { it })
    }

    @Test
    fun theBestMatchIsListedFirst() {
        val files = listOf("Zoo.srt", "Breaking.Bad.S01E04.srt", "Breaking.Bad.S01E03.srt", "Better.Call.Saul.S01E03.srt")
        assertEquals("Breaking.Bad.S01E03.srt", SubtitleFolderMatch.rank(episode, files) { it }.first())
        assertEquals("Zoo.srt", SubtitleFolderMatch.rank(episode, files) { it }.last())
    }

    @Test
    fun aBlankSearchKeepsEveryFileAndWordsMayComeInAnyOrder() {
        val files = listOf("The.Matrix.1999.srt", "Matrix.Reloaded.srt")
        assertEquals(files, SubtitleFolderMatch.filter(files, "  ") { it })
        assertEquals(listOf("Matrix.Reloaded.srt"), SubtitleFolderMatch.filter(files, "reloaded MATRIX") { it })
    }
}
