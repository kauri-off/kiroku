package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import kotlinx.coroutines.Dispatchers

// The store of a live source's list: its own database's.
internal fun liveListStore(parts: LiveParts): LocalList = RoomLocalList(parts.database, parts.scope, Dispatchers.IO, parts.unexpected)
