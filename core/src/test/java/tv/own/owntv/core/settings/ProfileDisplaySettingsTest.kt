package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zoom, text sizes and recent searches belong to each profile; sizes fall back to the shared value, searches do not. */
class ProfileDisplaySettingsTest {

    private val zoom = SettingsRepository.Keys.UI_ZOOM_PCT
    private val active = SettingsRepository.Keys.ACTIVE_PROFILE
    private val sharedSearches = SettingsRepository.Keys.RECENT_SEARCHES

    @Test
    fun `a profile that never chose a size follows the shared one`() {
        val prefs = mutablePreferencesOf(active to 2L, zoom to 120)
        assertEquals(120, profileInt(prefs, zoom))
    }

    @Test
    fun `each profile's own size wins, and switching profile switches it`() {
        val prefs = mutablePreferencesOf(active to 2L, zoom to 120, profileIntKey(zoom, 2) to 90, profileIntKey(zoom, 3) to 140)
        assertEquals(90, profileInt(prefs, zoom))
        prefs[active] = 3L
        assertEquals(140, profileInt(prefs, zoom))
    }

    @Test
    fun `a size is written to the active profile, or to the shared key before any profile exists`() {
        assertEquals("ui_zoom_percent_4", profileIntWriteKey(mutablePreferencesOf(active to 4L), zoom).name)
        assertEquals("ui_zoom_percent", profileIntWriteKey(mutablePreferencesOf(), zoom).name)
        assertEquals("ui_zoom_percent", profileIntWriteKey(mutablePreferencesOf(active to -1L), zoom).name)
    }

    @Test
    fun `recent searches are each profile's own, with no fallback to the shared list`() {
        val prefs = mutablePreferencesOf(active to 2L, sharedSearches to "news\nfilm", profileRecentSearchesKey(3) to "kids")
        assertNull(prefs[recentSearchesKey(prefs)])
        prefs[active] = 3L
        assertEquals(listOf("kids"), parseRecentSearches(prefs[recentSearchesKey(prefs)]))
        assertEquals("recent_searches", recentSearchesKey(mutablePreferencesOf()).name)
    }

    @Test
    fun `the backup names the block on export and restore, and profile deletion forgets it`() {
        val backup = File("src/main/java/tv/own/owntv/core/backup/BackupManager.kt").readText()
        assertTrue(backup.contains("put(\"profileDisplay\", filterByProfile(settings.exportProfileDisplay(), pidKeys))"))
        assertTrue(backup.contains("root.optJSONObject(\"profileDisplay\")?.let { settings.importProfileDisplay(remapProfileKeys(it, profileIdMap), pids) }"))
        val profiles = File("src/main/java/tv/own/owntv/core/profile/ProfileManager.kt").readText()
        assertTrue(profiles.contains("settings.forgetProfileDisplay(profile.id)"))
    }
}
