package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.LocalList

// The store of a live source's list. It lives with the process.
@Suppress("UNUSED_PARAMETER")
internal fun liveListStore(parts: LiveParts): LocalList = InMemoryLocalList()
