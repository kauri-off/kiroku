package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.malSession
import io.github.kauri_off.kiroku.notShown
import java.io.File

private val TOKENS = notShown("tokens")

// The file of a live source's tokens, which the session alone reads and writes.
internal val LiveParts.tokenFile: File get() = file(TOKENS)

// The session of a live source: the one owner of its tokens, which are kept in the source's own file.
internal fun liveSession(parts: LiveParts): MalSession = malSession(parts.tokenFile, parts.http, parts.clock, parts.scope, parts.unexpected)
