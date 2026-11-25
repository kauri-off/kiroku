package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.CLIENT_ID_HEADER
import io.github.kauri_off.kiroku.data.network.MalAnswer
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.nextOffset
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.granted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Dispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MalSuggestionsTest {
    @get:Rule
    internal val network = NetworkFixture()

    @get:Rule
    val folder = TemporaryFolder()

    private val sessions by lazy { SessionFixture(folder.root) }
    private val session by lazy { sessions.open() }
    private val search by lazy { MalSearch(network.http, network.clock) { session } }

    @After
    fun close() = runBlocking { sessions.close() }

    @Test
    fun `suggestions never send a request without the sign-in that owns the list`() =
        runBlocking {
            assertEquals(Outcome.Failure(DataError.SessionExpired), search.openBrowse(BrowseList.Suggestions).load(0, false))
            assertEquals(Outcome.Failure(DataError.ListEnded), search.openSuggestions(SignInId("retired")).load(0, false))
            val owner = signIn()
            val suggestions = search.openSuggestions(owner)
            assertEquals(Outcome.Failure(DataError.InvalidRequest), suggestions.load(-1, false))
            signIn()
            assertEquals(Outcome.Failure(DataError.ListEnded), suggestions.load(10, false))
            assertEquals(0, network.api.requestCount)
        }

    @Test
    fun `suggestions use bearer only and retry a rejected token at the same offset`() =
        runBlocking {
            network.repeatReadsAfter = emptyList()
            network.clientId = ""
            network.api.enqueue(answer(401, body = ""))
            network.api.enqueue(answer(503, body = ""))
            sessions.answer = { granted("renewed-access", "renewed-refresh") }
            val owner = signIn()
            assertEquals(Outcome.Failure(DataError.ServerError), search.openSuggestions(owner).load(20, includeAdult = true))
            assertRequest(20, "access")
            assertRequest(20, "renewed-access")
            assertEquals(1, sessions.grants.size)
            assertEquals(Session.SignedIn(owner), session.currentSession())
        }

    @Test
    fun `a queued suggestions GET is rejected before sending after sign-in replacement`() =
        runBlocking {
            val dispatcher = Dispatcher().apply { maxRequests = 1 }
            network.calls = dispatcher
            network.repeatReadsAfter = emptyList()
            val release = CountDownLatch(1)
            network.answers {
                check(release.await(5, TimeUnit.SECONDS))
                answer(503, body = "")
            }
            val owner = signIn()
            val occupying = async(Dispatchers.Default) { network.http.call { network.things.value.read() } }
            val queued =
                try {
                    assertEquals(
                        "/v2/thing",
                        network.api
                            .received()
                            .url.encodedPath,
                    )
                    val pending = async(Dispatchers.Default) { search.openSuggestions(owner).load(0, false) }
                    withTimeout(5.seconds) {
                        while (dispatcher.queuedCallsCount() == 0) delay(1.milliseconds)
                    }
                    signIn("replacement")
                    pending
                } finally {
                    release.countDown()
                }
            assertEquals(MalAnswer.Failed(DataError.ServerError), occupying.await())
            assertEquals(Outcome.Failure(DataError.ListEnded), queued.await())
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `a retryable response from a retired sign-in cannot send another suggestions GET`() =
        runBlocking {
            network.repeatReadsAfter = listOf(10.milliseconds)
            val release = CountDownLatch(1)
            network.answers {
                check(release.await(5, TimeUnit.SECONDS))
                answer(503, body = "")
            }
            val owner = signIn()
            val pending = async(Dispatchers.Default) { search.openSuggestions(owner).load(17, false) }
            try {
                assertRequest(17, "access")
                signIn("replacement")
            } finally {
                release.countDown()
            }
            assertEquals(Outcome.Failure(DataError.ListEnded), pending.await())
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `recorded suggestions preserve page order and offsets under the adult filter`() =
        runBlocking {
            val first = OwnerRecordings.named("suggestions_first")
            val second = OwnerRecordings.named("suggestions_next")
            val suggestions = search.openSuggestions(signIn())
            for (includeAdult in listOf(false, true)) {
                var offset = 0
                for ((name, recorded) in listOf("suggestions_first" to first, "suggestions_next" to second)) {
                    network.api.enqueue(answer(recorded.status, recorded.body))
                    val result = suggestions.load(offset, includeAdult)
                    assertRequest(offset, "access")
                    OwnerRecordings.read(name) {
                        check(result is Outcome.Success)
                        val json = MalJson.parseToJsonElement(recorded.body).jsonObject
                        val source = json.getValue("data").jsonArray.map { it.jsonObject.getValue("node").jsonObject }
                        val expected = source.filter { includeAdult || it["nsfw"] == JsonPrimitive("white") }
                        check(
                            result.value.items.map {
                                it.item.id.value
                                    .toString()
                            } == expected.map { it.getValue("id").toString() },
                        )
                        check(result.value.items.all { it.position == null })
                        check(result.value.nextOffset == nextOffset(json["paging"]))
                    }
                    if (name == "suggestions_first") offset = checkNotNull((result as Outcome.Success).value.nextOffset)
                }
            }
        }

    private suspend fun signIn(access: String = "access"): SignInId {
        assertTrue(session.signIn(TokenGrant(access, "refresh")) is Outcome.Success)
        return (session.currentSession() as Session.SignedIn).signIn
    }

    private fun assertRequest(
        offset: Int,
        access: String,
    ) {
        val request = network.api.received()
        assertEquals("GET", request.method)
        assertEquals("/v2/anime/suggestions", request.url.encodedPath)
        assertEquals(setOf("fields", "offset", "limit"), request.url.queryParameterNames)
        assertEquals(MalFields.SUGGESTIONS, request.url.queryParameter("fields"))
        assertEquals(offset.toString(), request.url.queryParameter("offset"))
        assertEquals("10", request.url.queryParameter("limit"))
        assertEquals("Bearer $access", request.headers[AUTHORIZATION])
        assertNull(request.headers[CLIENT_ID_HEADER])
    }
}
