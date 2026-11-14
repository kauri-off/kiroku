package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The session over HTTP, against a server that answers as MAL's authorization reference prints.
@RunWith(RobolectricTestRunner::class)
class SessionAgainstServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fixture by lazy { SessionFixture(folder.newFolder()) }
    private val server = MockWebServer()
    private val client = OkHttpClient()

    private val arrived = CountDownLatch(10)
    private val rejected = CountDownLatch(10)
    private val bearers = CopyOnWriteArrayList<String>()
    private val tokenRequests = CopyOnWriteArrayList<String>()

    private val mal =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/v1/oauth2/token" -> {
                        tokenRequests += "${request.method} ${request.body?.utf8()}"
                        assertTrue(rejected.await(10, TimeUnit.SECONDS))
                        MockResponse(200, headersOf("Content-Type", "application/json"), resource("mal/authorization/token.json"))
                    }
                    else -> {
                        val bearer = request.headers["Authorization"].orEmpty()
                        bearers += bearer
                        if (bearer == "Bearer ACCESS_TOKEN") {
                            MockResponse(200, headersOf(), "")
                        } else {
                            arrived.countDown()
                            assertTrue(arrived.await(10, TimeUnit.SECONDS))
                            rejected.countDown()
                            MockResponse(
                                401,
                                headersOf(
                                    "WWW-Authenticate",
                                    "Bearer error=\"invalid_token\",error_description=\"The access token expired\"",
                                ),
                                resource("mal/authorization/expired_access_token.json"),
                            )
                        }
                    }
                }
        }

    @After
    fun close() {
        runBlocking { fixture.close() }
        server.close()
    }

    private suspend fun postGrant(grant: Map<String, String>): TokenReply =
        withContext(Dispatchers.IO) {
            val form =
                FormBody
                    .Builder()
                    .add("client_id", "client")
                    .apply { grant.forEach { (name, value) -> add(name, value) } }
                    .build()
            try {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(server.url("/v1/oauth2/token"))
                            .post(form)
                            .build(),
                    ).execute()
                    .use {
                        TokenReply.Answer(it.code, it.body.string())
                    }
            } catch (_: IOException) {
                TokenReply.NoAnswer(DataError.NoConnection)
            }
        }

    private val request =
        AuthorisedRequest { token ->
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(server.url("/authorised"))
                        .header("Authorization", "Bearer $token")
                        .build()
                client.newCall(request).execute().use {
                    when {
                        it.code == 401 -> Attempt.Rejected
                        it.isSuccessful -> Attempt.Answered(Outcome.Success(Unit))
                        else -> Attempt.Answered(Outcome.Failure(DataError.ServerError))
                    }
                }
            }
        }

    @Test
    fun tenRequestsWithAnExpiredAccessTokenCauseOneRefreshAndOneRetryEach() =
        runBlocking {
            withTimeout(30_000) {
                server.dispatcher = mal
                server.start()
                fixture.answer = ::postGrant
                val session = fixture.open()
                session.signIn(TokenGrant("expired-access", "refresh-1"))
                val signIn = session.observeSession().first()

                val outcomes = (1..10).map { async { session.authorised(request) } }.awaitAll()

                assertEquals(List(10) { Outcome.Success(Unit) }, outcomes)
                assertEquals(listOf("POST client_id=client&grant_type=refresh_token&refresh_token=refresh-1"), tokenRequests.toList())
                assertEquals(List(10) { "Bearer ACCESS_TOKEN" } + List(10) { "Bearer expired-access" }, bearers.sorted())
                assertEquals(signIn, session.observeSession().first())
                assertTrue(signIn is Session.SignedIn)
                fixture.close()
                assertTrue(
                    fixture
                        .store()
                        .read()
                        .text()
                        .contains(" ACCESS_TOKEN REFRESH_TOKEN "),
                )
            }
        }
}
