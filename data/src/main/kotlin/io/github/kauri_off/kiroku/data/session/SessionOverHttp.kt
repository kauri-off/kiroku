package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.network.MalAnswer
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.ReadAdmission
import io.github.kauri_off.kiroku.data.network.bearer
import kotlinx.coroutines.CoroutineScope
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.io.File
import java.time.Clock

// The session over [file], which no other object may read or write, with MAL's token endpoint reached through [http].
fun malSession(
    file: File,
    http: MalHttp,
    clock: Clock,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
): MalSession = malSession(file, MalTokenEndpoint(http), clock, scope, unexpected)

// A request made as the signed-in user: it carries the token the session hands it, and a 401 goes back to the session.
internal suspend fun <T> MalSession.authorised(
    http: MalHttp,
    expectedOwner: SignInId? = null,
    firstRead: (() -> ReadAdmission)? = null,
    guardWrite: Boolean = false,
    request: suspend (authorization: String) -> T,
): Outcome<T> {
    var admission = firstRead
    return authorised(expectedOwner) { accessToken ->
        val maySend = expectedOwner?.let { owner -> { isSignedInAs(owner) } }
        val maySendWrite = if (guardWrite) maySend else null
        val answer =
            http.call(
                maySendRead = maySend,
                firstRead = admission,
                maySendWrite = maySendWrite,
            ) { request(bearer(accessToken)) }
        when (answer) {
            is MalAnswer.Success -> Attempt.Answered(Outcome.Success(answer.value))
            is MalAnswer.Failed -> Attempt.Answered(Outcome.Failure(answer.error))
            MalAnswer.Rejected -> {
                admission = null
                Attempt.Rejected
            }
        }
    }
}

private interface TokenApi {
    @FormUrlEncoded
    @POST("token")
    suspend fun token(
        @FieldMap form: Map<String, String>,
    ): Response<ResponseBody>
}

// MAL's token endpoint, on the sign-in host; the client has no secret, so its id is all it adds to a grant.
internal class MalTokenEndpoint(
    private val http: MalHttp,
) : TokenEndpoint {
    private val api by http.signIn(TokenApi::class)

    // An answer that is not a success leaves as the exception Retrofit throws, with its body, so a refusal starts the back-off.
    override suspend fun post(grant: Map<String, String>): TokenReply {
        // MAL would reject a grant without the client id and so end the session, for a cause that is the build's.
        if (http.clientId.isBlank()) return TokenReply.NoAnswer(DataError.NoClientId)
        var answered: TokenReply.Answer? = null
        val answer =
            http.call {
                val response = api.token(grant + ("client_id" to http.clientId))
                val reply = TokenReply.Answer(response.code(), (response.body() ?: response.errorBody())?.string().orEmpty())
                if (!response.isSuccessful) {
                    answered = reply
                    val body = reply.body.toResponseBody(response.errorBody()?.contentType())
                    throw HttpException(Response.error<ResponseBody>(body, response.raw()))
                }
                reply
            }
        return when (answer) {
            is MalAnswer.Success -> answer.value
            MalAnswer.Rejected -> answered ?: TokenReply.Answer(UNAUTHORIZED, "")
            is MalAnswer.Failed -> answered?.takeUnless { answer.error == DataError.Refused } ?: TokenReply.NoAnswer(answer.error)
        }
    }

    override suspend fun refusing(): Boolean = !http.refusedFor().isZero

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
