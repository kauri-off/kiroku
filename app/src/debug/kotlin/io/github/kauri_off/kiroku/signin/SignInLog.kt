package io.github.kauri_off.kiroku.signin

import android.util.Log
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.wiring.LOG_TAG

// Debug and preview builds log why a sign-in failed; a reason names no credential.
internal val signInFailed: ((SignInFailure) -> Unit)? = { Log.i(LOG_TAG, "Sign-in failed: $it") }
