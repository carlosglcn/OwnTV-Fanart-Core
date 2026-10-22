package tv.own.owntv.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    @Test fun channelOnTv() = assertEquals(VoiceCommand("Neox", VoiceKind.ANY, true), VoiceCommandParser.parse("Pon Neox en la tele"))
    @Test fun movie() = assertEquals(VoiceCommand("Gladiator", VoiceKind.MOVIE, false), VoiceCommandParser.parse("quiero ver la película Gladiator"))
    @Test fun series() = assertEquals(VoiceCommand("The Last of Us", VoiceKind.SERIES, false), VoiceCommandParser.parse("busca la serie The Last of Us"))
    @Test fun keepsChannelArticle() = assertEquals(VoiceCommand("La 1", VoiceKind.ANY, true), VoiceCommandParser.parse("ponme La 1 en la tv"))
    @Test fun channelWord() = assertEquals(VoiceCommand("La Sexta", VoiceKind.CHANNEL, false), VoiceCommandParser.parse("pon el canal La Sexta"))
    @Test fun plain() = assertEquals(VoiceCommand("Antena 3", VoiceKind.ANY, false), VoiceCommandParser.parse("Antena 3"))
    @Test fun empty() = assertNull(VoiceCommandParser.parse("pon"))
}
