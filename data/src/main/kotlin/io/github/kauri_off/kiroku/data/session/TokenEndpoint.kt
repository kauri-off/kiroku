package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// MAL's token endpoint: a grant's form goes out, with the client's own credentials added by the sender.
internal fun interface TokenEndpoint {
    suspend fun post(grant: Map<String, String>): TokenReply
}

internal sealed interface TokenReply {
    // What MAL answered, unread.
    class Answer(
        val status: Int,
        val body: String,
    ) : TokenReply

    data class NoAnswer(
        val error: DataError,
    ) : TokenReply
}

internal fun refreshGrant(refreshToken: String): Map<String, String> =
    mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
    )

// Not a data class: its text must never hold a token.
internal class TokenGrant(
    val access: String,
    val refresh: String,
)

internal sealed interface TokenResult {
    class Granted(
        val grant: TokenGrant,
    ) : TokenResult

    // MAL will never accept this grant.
    data object Rejected : TokenResult

    // Nothing is known about the grant: it may be tried again.
    data class Failed(
        val error: DataError,
    ) : TokenResult
}

// S4: an answer grants tokens only when it succeeded and carries both.
internal fun TokenReply.read(): TokenResult =
    when (this) {
        is TokenReply.NoAnswer -> TokenResult.Failed(error)
        is TokenReply.Answer ->
            when {
                status in 200..299 -> grantIn(body)?.let(TokenResult::Granted) ?: TokenResult.Failed(DataError.ServerError)
                rejectsGrant(status) -> TokenResult.Rejected
                else -> TokenResult.Failed(DataError.ServerError)
            }
    }

// OAuth's statuses for a grant or a client that is not valid; MAL's own answers are not recorded yet.
internal fun rejectsGrant(status: Int): Boolean = status == 400 || status == 401

@Serializable
private class TokenAnswer(
    @SerialName("access_token") val access: String? = null,
    @SerialName("refresh_token") val refresh: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

private fun grantIn(body: String): TokenGrant? {
    val answer =
        try {
            json.decodeFromString<TokenAnswer>(body)
        } catch (_: SerializationException) {
            return null
        }
    val access = answer.access?.takeIf { it.isNotBlank() } ?: return null
    val refresh = answer.refresh?.takeIf { it.isNotBlank() } ?: return null
    return TokenGrant(access, refresh)
}
