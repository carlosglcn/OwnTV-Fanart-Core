package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The remembered-language recall both engines share: each half once, and a user's pick wins. */
class TrackRecallTest {

    private fun t(id: Int, lang: String?, selected: Boolean = false) =
        TrackOption(label = "", mpvId = id, selected = selected, lang = lang)

    private val picks = mutableListOf<String>()

    private fun TrackRecall.applyTo(audio: List<TrackOption>, subs: List<TrackOption>) = apply(
        audioTracks = audio,
        subTracks = subs,
        allowImage = true,
        selectAudio = { picks += "audio:$it" },
        subtitlesOff = { picks += "subs-off" },
        selectSubtitle = { picks += "sub:$it" },
    )

    @Test fun `nothing is applied before the database answers`() {
        val r = TrackRecall("k")
        r.applyTo(listOf(t(1, "en", true), t(2, "de")), listOf(t(3, "de")))
        assertEquals(emptyList<String>(), picks)
    }

    @Test fun `each half is applied once, whichever arrives last`() {
        val r = TrackRecall("k")
        r.load(audio = "de", sub = "de")
        r.applyTo(listOf(t(1, "en", true), t(2, "de")), emptyList())
        assertEquals(listOf("audio:2"), picks)
        r.applyTo(listOf(t(1, "en", true), t(2, "de")), listOf(t(3, "de")))
        assertEquals(listOf("audio:2", "sub:3"), picks)
        r.applyTo(listOf(t(1, "en", true), t(2, "de")), listOf(t(3, "de")))
        assertEquals("applied once", listOf("audio:2", "sub:3"), picks)
    }

    @Test fun `a pick by the user closes that half before the database answers`() {
        val r = TrackRecall("k")
        assertEquals("fr", r.picked(isAudio = true, "fr"))
        r.load(audio = "de", sub = null)
        r.applyTo(listOf(t(1, "fr", true), t(2, "de")), emptyList())
        assertEquals(emptyList<String>(), picks)
    }

    @Test fun `a picked audio language is what an engine switch re-applies`() {
        val r = TrackRecall("k")
        r.load(audio = "de", sub = null)
        r.picked(isAudio = true, "fr")
        r.audioDone = false // an engine switch
        r.applyTo(listOf(t(1, "en", true), t(2, "fr")), emptyList())
        assertEquals(listOf("audio:2"), picks)
    }

    @Test fun `a track with no language is not stored`() {
        assertNull(TrackRecall("k").picked(isAudio = false, " "))
    }
}
