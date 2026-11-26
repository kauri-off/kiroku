package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.notShown

private val PREFERENCES = notShown("preferences_pb")

internal fun hasPreferencesFile(parts: LiveParts): Boolean = parts.file(PREFERENCES).isFile

// The choices made on a live source, kept in the source's own file.
internal fun livePreferences(parts: LiveParts): StoredPreferences =
    StoredPreferences(parts.file(PREFERENCES), parts.scope, parts.unexpected)
