package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.BuildConfig

private val Context.providerLessonStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_provider_lessons")

/**
 * What the live engines learned about providers' panels, kept across app starts so the first tune after
 * a restart does not pay for a failed attempt again. One text blob; its contents belong to the engines.
 *
 * Device-local on purpose: not in backups and not in Local sync — a lesson is about how this device's
 * connection meets a panel, and it expires within a week anyway.
 *
 * Written by one core version, read only by the same one: a new core may have changed what a lesson
 * means, so the first start of a new version begins with none.
 */
class ProviderLessonStore(private val context: Context) {

    private val textKey = stringPreferencesKey("lessons")
    private val versionKey = stringPreferencesKey("core_version")

    suspend fun read(): String {
        val prefs = context.providerLessonStore.data.first()
        return textFor(prefs[textKey], prefs[versionKey], BuildConfig.CORE_VERSION)
    }

    suspend fun write(text: String) {
        context.providerLessonStore.edit {
            it[textKey] = text
            it[versionKey] = BuildConfig.CORE_VERSION
        }
    }

    companion object {
        /** The stored [text] when it was written by [currentVersion]; nothing otherwise. */
        internal fun textFor(text: String?, storedVersion: String?, currentVersion: String): String =
            if (storedVersion == currentVersion) text.orEmpty() else ""
    }
}
