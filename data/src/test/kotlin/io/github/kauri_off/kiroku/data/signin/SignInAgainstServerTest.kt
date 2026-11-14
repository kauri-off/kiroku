package io.github.kauri_off.kiroku.data.signin

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.network.CLIENT_ID_HEADER
import io.github.kauri_off.kiroku.data.network.MalBackOff
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.CLIENT_ID
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import io.github.kauri_off.kiroku.data.network.Thing
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.session.AesGcmCipher
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.MalTokenEndpoint
import io.github.kauri_off.kiroku.data.session.TestKeys
import io.github.kauri_off.kiroku.data.session.TokenFormat
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.TokenReply
import io.github.kauri_off.kiroku.data.session.TokenStore
import io.github.kauri_off.kiroku.data.session.authorised
import io.github.kauri_off.kiroku.data.session.refreshGrant
import io.github.kauri_off.kiroku.data.session.resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

// The sign-in, the session and the network layer joined, against servers that answer as MAL's reference prints.
@RunWith(RobolectricTestRunner::class)
class SignInAgainstServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val directory by lazy { folder.newFolder() }
    private val failures = CopyOnWriteArrayList<SignInFailure>()
    private val browser = FakeBrowser()
    private val printed = resource("mal/authorization/token.json")
    private val expired = resource("mal/authorization/expired_access_token.json")
    private val endpoint by lazy { MalTokenEndpoint(network.http) }

    private val session by lazy {
        val file = File(directory, "session")
        val store = TokenStore(file, TokenFormat(AesGcmCipher(TestKeys()), file.name) { network.unexpected += it }, scope + Dispatchers.IO)
        MalSession(store, endpoint, network.clock, scope, { network.unexpected += it })
    }

    private val signIn by lazy {
        malSignIn(File(directory, "session"), REDIRECT, network.http, session, network.clock, scope, { network.unexpected += it }) {
            failures += it
        }
    }

    @After
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    // The fields of a form that is well formed: its content type, and each name once with a value.
    private fun RecordedRequest.form(): Map<String, String> {
        assertEquals("application/x-www-form-urlencoded", headers["Content-Type"])
        val fields =
            body
                ?.utf8()
                .orEmpty()
                .split('&')
                .map { it.split('=') }
        for (field in fields) assertTrue("$field", field.size == 2 && field.all(String::isNotEmpty))
        val names = fields.map { URLDecoder.decode(it[0], "UTF-8") }
        assertEquals(names.distinct(), names)
        return names.zip(fields.map { URLDecoder.decode(it[1], "UTF-8") }).toMap()
    }

    private suspend fun readAsTheUser(): Outcome<Thing> = session.authorised(network.http) { network.things.value.readAs(it) }

    private suspend fun reply(response: MockResponse): String {
        network.signIn.enqueue(response)
        return when (val reply = endpoint.post(refreshGrant("refresh-1"))) {
            is TokenReply.Answer -> "${reply.status} ${reply.body}"
            is TokenReply.NoAnswer -> reply.error.toString()
        }
    }

    @Test
    fun aSignInGoesFromMalsPageToARequestMadeAsTheUser() =
        test {
            network.signIn.enqueue(answer(body = printed))
            network.always(answer())

            signIn.start(browser)
            val address = browser.opened.single()
            assertEquals(network.signIn.url("/v1/oauth2/authorize").toString(), address.substringBefore('?'))
            assertEquals(CLIENT_ID, address.asked("client_id"))
            assertTrue(File(directory, "session.sign-in").length() > 0)
            signIn.redirected(redirectOf(address))
            assertEquals(SignInStep.SignedIn, signIn.ended())
            assertEquals(0, File(directory, "session.sign-in").length())
            assertEquals(setOf("session", "session.sign-in"), directory.list().orEmpty().toSet())

            val token = network.signIn.received()
            assertEquals("POST /v1/oauth2/token", "${token.method} ${token.url.encodedPath}")
            assertNull(token.url.query)
            assertEquals(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to CODE,
                    "code_verifier" to address.asked("code_challenge"),
                    "redirect_uri" to REDIRECT,
                    "client_id" to CLIENT_ID,
                ),
                token.form(),
            )
            assertEquals("Kiroku/1.2.3", token.headers["User-Agent"])
            assertNull(token.headers[CLIENT_ID_HEADER])
            assertNull(token.headers["Authorization"])

            assertEquals(Outcome.Success(Thing(7)), readAsTheUser())
            val read = network.api.received()
            assertEquals("Bearer ACCESS_TOKEN", read.headers["Authorization"])
            assertNull(read.headers[CLIENT_ID_HEADER])

            val secrets = listOf(CODE, address.asked("state"), address.asked("code_challenge"), "ACCESS_TOKEN", "REFRESH_TOKEN", CLIENT_ID)
            assertEquals(2, network.log.size)
            for (line in network.log) assertTrue(line, secrets.none { it in line })
            assertEquals(emptyList<Throwable>(), network.unexpected.toList())
            assertEquals(1, network.signIn.requestCount)
        }

    @Test
    fun aRejectedAccessTokenLeadsToOneRefreshAtTheTokenEndpointAndOneRetryWithTheNewToken() =
        test {
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("old-access", "refresh-1")))
            network.answers { if (it.headers["Authorization"] == "Bearer ACCESS_TOKEN") answer() else answer(401, expired) }
            network.signIn.enqueue(answer(body = printed))

            assertEquals(Outcome.Success(Thing(7)), readAsTheUser())

            assertEquals(listOf("Bearer old-access", "Bearer ACCESS_TOKEN"), List(2) { network.api.received().headers["Authorization"] })
            assertEquals(
                mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-1", "client_id" to CLIENT_ID),
                network.signIn.received().form(),
            )
            assertEquals(1, network.signIn.requestCount)
            assertEquals(2, network.api.requestCount)
        }

    @Test
    fun anyOtherAnswerToARequestMadeAsTheUserIsItsOutcome() =
        test {
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
            network.always(answer(404, "{}"))

            assertEquals(Outcome.Failure(DataError.NotFound), readAsTheUser())

            assertEquals(1, network.api.requestCount)
            assertEquals(0, network.signIn.requestCount)
            assertTrue(session.observeSession().first() is Session.SignedIn)
        }

    @Test
    fun aRefreshThatTheTokenEndpointRejectsEndsTheSession() =
        test {
            assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("old-access", "refresh-1")))
            network.always(answer(401, expired))
            network.signIn.enqueue(answer(400, "no"))

            assertEquals(Outcome.Failure(DataError.SessionExpired), readAsTheUser())

            assertTrue(session.observeSession().first() is Session.Expired)
            assertEquals(1, network.signIn.requestCount)
            assertEquals(Duration.ZERO, network.http.backOff.remaining())
        }

    @Test
    fun theTokenEndpointsAnswerReachesTheSessionWithItsStatus() =
        test {
            assertEquals("200 $printed", reply(answer(body = printed)))
            assertEquals("400 bad", reply(answer(400, "bad")))
            assertEquals("401 $expired", reply(answer(401, expired)))
            assertEquals("404 gone", reply(answer(404, "gone")))
            assertEquals("500 down", reply(answer(500, "down")))
            assertEquals("204 ", reply(MockResponse.Builder().code(204).build()))
            assertEquals(Duration.ZERO, network.http.backOff.remaining())
            assertEquals(DataError.ServerError.toString(), reply(dropped()))
            network.online = false
            assertEquals(DataError.NoConnection.toString(), reply(dropped()))
            assertEquals(8, network.signIn.requestCount)
        }

    @Test
    fun aGrantReachesTheTokenEndpointAsAWellFormedFormWhateverItsValuesHold() =
        test {
            val grant =
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to "a+b/c=d&e f%\u00e9?#",
                    "code_verifier" to "v-._~",
                    "redirect_uri" to REDIRECT,
                )
            network.signIn.enqueue(answer(body = printed))

            endpoint.post(grant)

            val token = network.signIn.received()
            assertEquals("POST /v1/oauth2/token", "${token.method} ${token.url.encodedPath}")
            assertEquals(grant + ("client_id" to CLIENT_ID), token.form())
            assertEquals(1, network.signIn.requestCount)
        }

    @Test
    fun aTokenEndpointThatRefusesStartsTheBackOffLikeAnyRefusal() =
        test {
            assertEquals(DataError.Refused.toString(), reply(answer(403, "no")))
            assertEquals(MalBackOff.First, network.http.backOff.remaining())
            assertEquals("400 bad", reply(answer(400, "bad")))
            assertEquals(MalBackOff.First, network.http.backOff.remaining())
            val wait =
                MockResponse
                    .Builder()
                    .code(429)
                    .setHeader("Retry-After", "3600")
                    .body("slow")
                    .build()
            assertEquals(DataError.Refused.toString(), reply(wait))
            assertEquals(Duration.ofHours(1), network.http.backOff.remaining())
            assertEquals("200 $printed", reply(answer(body = printed)))
            assertEquals(Duration.ZERO, network.http.backOff.remaining())
        }

    @Test
    fun aSignInThatMalRefusesOrRejectsSignsNobodyInAndIsSentOnce() =
        test {
            network.signIn.enqueue(answer(403, "no"))
            signIn.start(browser)
            signIn.redirected(redirectOf(browser.opened.last()))
            assertEquals(SignInStep.Failed(SignInFailure.NoTokens), signIn.ended())
            assertEquals(MalBackOff.First, network.http.backOff.remaining())

            network.signIn.enqueue(answer(401, "no"))
            signIn.start(browser)
            signIn.redirected(redirectOf(browser.opened.last()))
            assertEquals(SignInStep.Failed(SignInFailure.Rejected), signIn.ended())

            network.signIn.enqueue(dropped())
            signIn.start(browser)
            signIn.redirected(redirectOf(browser.opened.last()))
            assertEquals(SignInStep.Failed(SignInFailure.NoTokens), signIn.ended())

            assertEquals(3, network.signIn.requestCount)
            assertEquals(Session.SignedOut, session.observeSession().first())
            assertEquals(listOf(SignInFailure.NoTokens, SignInFailure.Rejected, SignInFailure.NoTokens), failures.toList())
        }
}
