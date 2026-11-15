package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.notShown

private val PREFERENCES = notShown("preferences_pb")

// The choices made on a live source, kept in the source's own file.
internal fun livePreferences(parts: LiveParts): PreferencesRepository =
    StoredPreferences(parts.file(PREFERENCES), parts.scope, parts.unexpected)
