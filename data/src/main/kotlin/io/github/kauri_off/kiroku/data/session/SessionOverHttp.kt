package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.data.network.MalAnswer
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.bearer
import kotlinx.coroutines.CoroutineScope
import okhttp3.ResponseBody
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
    request: suspend (authorization: String) -> T,
): Outcome<T> =
    authorised { accessToken ->
        when (val answer = http.call { request(bearer(accessToken)) }) {
            is MalAnswer.Success -> Attempt.Answered(Outcome.Success(answer.value))
            is MalAnswer.Failed -> Attempt.Answered(Outcome.Failure(answer.error))
            MalAnswer.Rejected -> Attempt.Rejected
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

    // An answer that is not a success leaves as the exception Retrofit throws, so a refusal starts the back-off.
    override suspend fun post(grant: Map<String, String>): TokenReply {
        var answered: TokenReply.Answer? = null
        val answer =
            http.call {
                val response = api.token(grant + ("client_id" to http.clientId))
                val reply = TokenReply.Answer(response.code(), (response.body() ?: response.errorBody())?.string().orEmpty())
                if (!response.isSuccessful) {
                    answered = reply
                    throw HttpException(response)
                }
                reply
            }
        return when (answer) {
            is MalAnswer.Success -> answer.value
            MalAnswer.Rejected -> answered ?: TokenReply.Answer(UNAUTHORIZED, "")
            is MalAnswer.Failed -> answered?.takeUnless { answer.error == DataError.Refused } ?: TokenReply.NoAnswer(answer.error)
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
