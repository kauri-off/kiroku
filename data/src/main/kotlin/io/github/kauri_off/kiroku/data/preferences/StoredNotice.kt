package io.github.kauri_off.kiroku.data.preferences

import androidx.datastore.core.FileStorage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Whether a notice was shown, in a Preferences file of its own; one that cannot be read was not shown, and is reported.
class StoredNotice(
    file: File,
    scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store =
        PreferenceDataStoreFactory.create(
            storage = FileStorage(PreferencesFileSerializer) { file },
            corruptionHandler =
                ReplaceFileCorruptionHandler { e ->
                    unexpected(PreferencesFailure(e))
                    emptyPreferences()
                },
            scope = CoroutineScope(scope.coroutineContext + io),
        )

    suspend fun shown(): Boolean =
        withContext(io) {
            try {
                store.data.first().asMap()[SHOWN] == true
            } catch (e: IOException) {
                unexpected(PreferencesFailure(e))
                false
            }
        }

    suspend fun markShown() {
        withContext(io) {
            try {
                store.edit { it[SHOWN] = true }
            } catch (e: IOException) {
                unexpected(PreferencesFailure(e))
            }
        }
    }

    private companion object {
        val SHOWN = booleanPreferencesKey("shown")
    }
}
