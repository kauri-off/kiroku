package io.github.kauri_off.kiroku.signin

import io.github.kauri_off.kiroku.core.repository.NoSignIn
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.wiring.AppGraph

// A graph over a MAL account has that account's sign-in.
interface SignInOwner {
    val signIn: SignIn
}

private val none = NoSignIn(signInFailed)

// The graph's sign-in; a graph without one starts none and fails every redirect.
val AppGraph.signIn: SignIn get() = (this as? SignInOwner)?.signIn ?: none
