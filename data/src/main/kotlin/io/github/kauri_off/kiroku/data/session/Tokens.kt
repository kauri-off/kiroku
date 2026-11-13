package io.github.kauri_off.kiroku.data.session

import kotlinx.serialization.Serializable

// Not a data class: its text must never hold a token.
@Serializable
internal class Tokens(
    val signIn: String,
    val access: String,
    val refresh: String,
    val obtainedAt: Long,
)

internal sealed interface Stored {
    data object SignedOut : Stored

    class SignedIn(
        val tokens: Tokens,
    ) : Stored

    // The sign-in whose tokens MAL rejected.
    class Expired(
        val signIn: String,
    ) : Stored
}
