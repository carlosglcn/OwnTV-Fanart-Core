package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.Keys

/** The player's remote-button options: unset is today's behaviour, and every choice survives a write. */
class PlayerRemoteOptionsPrefsTest {

    @Test
    fun `nothing stored reads as the player's own behaviour`() {
        val options = readPlayerRemoteOptions(emptyPreferences())
        assertEquals(PlayerRemoteOptions(), options)
        assertEquals(PlayerHoldOkAction.CHANNEL_LIST, options.holdOk)
        assertEquals(PlayerShortOkAction.SHOW_CONTROLS, options.shortOk)
        assertEquals(false, options.swapChannelKeys)
        assertEquals(true, options.numberKeysTune)
        assertEquals(PlayerDpadUpDownAction.ZAP, options.dpadUpDown)
    }

    @Test
    fun `every field round-trips`() {
        val chosen = PlayerRemoteOptions(
            holdOk = PlayerHoldOkAction.PROGRAMME_INFO,
            shortOk = PlayerShortOkAction.GUIDE,
            swapChannelKeys = true,
            numberKeysTune = false,
            dpadUpDown = PlayerDpadUpDownAction.CONTROLS_AND_HISTORY,
        )
        val prefs = mutablePreferencesOf()
        writePlayerRemoteOptions(prefs, chosen)
        assertEquals(chosen, readPlayerRemoteOptions(prefs))
    }

    @Test
    fun `a name this build does not know falls back to the default`() {
        val prefs = mutablePreferencesOf(Keys.PLAYER_HOLD_OK to "TELETEXT", Keys.PLAYER_SHORT_OK to "MENU", Keys.PLAYER_DPAD_UP_DOWN to "VOLUME")
        val options = readPlayerRemoteOptions(prefs)
        assertEquals(PlayerHoldOkAction.CHANNEL_LIST, options.holdOk)
        assertEquals(PlayerShortOkAction.SHOW_CONTROLS, options.shortOk)
        assertEquals(PlayerDpadUpDownAction.ZAP, options.dpadUpDown)
    }

    @Test
    fun `a restored choice must be one this build has`() {
        assertNull(SettingsImportRules.string("player_hold_ok", "TELETEXT"))
        assertEquals("GUIDE", SettingsImportRules.string("player_hold_ok", "GUIDE"))
        assertNull(SettingsImportRules.string("player_short_ok", "MENU"))
        assertEquals("CHANNEL_LIST", SettingsImportRules.string("player_short_ok", "CHANNEL_LIST"))
        assertNull(SettingsImportRules.string("player_dpad_up_down", "VOLUME"))
        assertEquals("DISABLED", SettingsImportRules.string("player_dpad_up_down", "DISABLED"))
    }
}
