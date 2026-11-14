package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.malSession
import io.github.kauri_off.kiroku.notShown

private val TOKENS = notShown("tokens")

// The session of a live source: the one owner of its tokens, which are kept in the source's own file.
internal fun liveSession(parts: LiveParts): MalSession =
    malSession(parts.file(TOKENS), parts.http, parts.clock, parts.scope, parts.unexpected)
