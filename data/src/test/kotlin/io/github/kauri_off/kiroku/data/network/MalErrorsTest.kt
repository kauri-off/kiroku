package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

// Section 2.3's rows for MAL, and that a request ends in an answer whatever happens to it.
class MalErrorsTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val things by lazy { network.things.value }

    private fun read(anotherUsersList: Boolean = false): MalAnswer<Thing> =
        runBlocking {
            network.http.call(anotherUsersList) { things.read() }
        }

    private fun readAnswered(
        code: Int,
        anotherUsersList: Boolean = false,
    ): MalAnswer<Thing> {
        network.always(answer(code))
        return read(anotherUsersList)
    }

    private fun failed(error: DataError) = MalAnswer.Failed(error)

    @Test
    fun `an answer is its body`() {
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Success(Thing(7)), read())
        assertEquals("/v2/thing", network.api.takeRequest().target)
    }

    @Test
    fun `a request that gets no answer on a device that is offline is no connection`() {
        network.online = false
        network.api.enqueue(dropped())
        assertEquals(failed(DataError.NoConnection), read())
    }

    @Test
    fun `a request that gets no answer on a device that is online is a server error`() {
        repeat(3) { network.api.enqueue(dropped()) }
        assertEquals(failed(DataError.ServerError), read())
    }

    @Test
    fun `a host that cannot be reached is no connection or a server error by the device's state`() {
        network.api.close()
        assertEquals(failed(DataError.ServerError), read())
        network.online = false
        assertEquals(failed(DataError.NoConnection), read())
    }

    @Test
    fun `a server's failure is a server error`() {
        for (code in listOf(500, 502, 503, 504, 408)) assertEquals("$code", failed(DataError.ServerError), readAnswered(code))
        assertEquals(Duration.ZERO, network.http.backOff.remaining())
    }

    @Test
    fun `a refusal of another user's list is a private list and nothing backs off`() {
        assertEquals(failed(DataError.PrivateList), readAnswered(403, anotherUsersList = true))
        assertEquals(Duration.ZERO, network.http.backOff.remaining())
    }

    @Test
    fun `a refusal anywhere else is a refusal and background work backs off`() {
        assertEquals(failed(DataError.Refused), readAnswered(403))
        assertEquals(MalBackOff.First, network.http.backOff.remaining())
    }

    @Test
    fun `too many requests is a refusal also for another user's list, for as long as the answer names`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .code(429)
                .setHeader("Retry-After", "3600")
                .build(),
        )
        assertEquals(failed(DataError.Refused), read(anotherUsersList = true))
        assertEquals(Duration.ofHours(1), network.http.backOff.remaining())
    }

    @Test
    fun `an answer after a refusal lets background work call again`() {
        readAnswered(403)
        assertEquals(MalAnswer.Success(Thing(7)), readAnswered(200))
        assertEquals(Duration.ZERO, network.http.backOff.remaining())
    }

    @Test
    fun `a refused write is a refusal`() {
        network.api.enqueue(answer(403))
        assertEquals(failed(DataError.Refused), runBlocking { network.http.call { things.write(1) } })
    }

    @Test
    fun `rejected credentials are their own answer, and an invalid request where only the client id was sent`() {
        assertEquals(MalAnswer.Rejected, readAnswered(401))
        assertEquals(Outcome.Failure(DataError.InvalidRequest), MalAnswer.Rejected.toOutcome())
        assertEquals(Duration.ZERO, network.http.backOff.remaining())
    }

    @Test
    fun `what is not there is not found, and an unknown user for another user's list`() {
        assertEquals(failed(DataError.NotFound), readAnswered(404))
        assertEquals(failed(DataError.UnknownUser), readAnswered(404, anotherUsersList = true))
    }

    @Test
    fun `a request MAL cannot take is an invalid request`() {
        for (code in listOf(400, 405, 409, 422)) assertEquals("$code", failed(DataError.InvalidRequest), readAnswered(code))
    }

    @Test
    fun `an answer that is neither a success nor a failure is a server error`() {
        network.always(MockResponse.Builder().code(302).build())
        assertEquals(failed(DataError.ServerError), read())
    }

    @Test
    fun `an answer that cannot be read is a server error, reported without its text`() {
        val bodies = listOf("""{"value":"${NetworkFixture.TOKEN}"}""", """{"other":"${NetworkFixture.TOKEN}"}""", NetworkFixture.TOKEN, "")
        for (body in bodies) {
            network.api.enqueue(answer(body = body))
            assertEquals(body, failed(DataError.ServerError), read())
        }
        assertEquals(bodies.size, network.unexpected.size)
        for (reported in network.unexpected) {
            assertTrue(reported is UnusableAnswer && reported.cause == null && reported.stackTrace.isNotEmpty())
            assertFalse(reported.toString(), NetworkFixture.TOKEN in reported.stackTraceToString())
        }
    }

    @Test
    fun `an answer cut off in its body is a server error`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .body("""{"value":""" + " ".repeat(100_000) + "7}")
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )
        assertEquals(failed(DataError.ServerError), read())
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `an exception of the code that reads the answer is a server error, reported without its message`() {
        network.api.enqueue(answer())
        val answer =
            runBlocking {
                network.http.call {
                    check(things.read().value == 0) { NetworkFixture.TOKEN }
                }
            }
        assertEquals(failed(DataError.ServerError), answer)
        assertEquals("java.lang.IllegalStateException", network.unexpected.single().message)
    }

    @Test
    fun `a cancelled request is cancelled, not an answer`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .body(NetworkFixture.THING)
                .headersDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        assertNull(runBlocking { withTimeoutOrNull(200) { network.http.call { things.read() } } })
        assertTrue(network.unexpected.isEmpty())
    }
}
