package io.github.kauri_off.kiroku.data.session

import kotlinx.serialization.Serializable

// Not a data class: its text must never hold a token.
@Serializable
internal class Tokens(
    val signIn: String,
    val access: String,
    val refresh: String,
    val obtainedAt: Long,
    // The seconds MAL gave the access token, when it said so. Nothing acts on it: a token is refreshed when MAL rejects it.
    val lifetime: Long? = null,
)

internal sealed interface Stored {
    data object SignedOut : Stored

    data class Guest(
        val generation: String,
    ) : Stored

    class SignedIn(
        val tokens: Tokens,
    ) : Stored

    // The sign-in whose tokens MAL rejected.
    class Expired(
        val signIn: String,
    ) : Stored
}
