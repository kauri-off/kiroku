package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.signin.malSignIn
import io.github.kauri_off.kiroku.signin.signInFailed

// The sign-in of a live source: its waiting sign-in is kept beside the source's tokens, which it hands to [session].
internal fun liveSignIn(
    parts: LiveParts,
    session: MalSession,
): SignIn =
    malSignIn(parts.tokenFile, BuildConfig.OAUTH_REDIRECT, parts.http, session, parts.clock, parts.scope, parts.unexpected, signInFailed)
