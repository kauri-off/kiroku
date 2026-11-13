package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Section 6.4: a write is sent once; a read may be asked again.
class SendRulesTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val things by lazy { network.things.value }

    private fun <T> call(request: suspend Things.() -> T): MalAnswer<T> = runBlocking { network.http.call { things.request() } }

    // The methods of the requests that reached the server, in order.
    private fun sent(): List<String> = List(network.api.requestCount) { network.api.received().method }

    // A connection that has carried a request is one OkHttp by itself would replace and send on again.
    private fun useConnection() {
        network.api.enqueue(answer())
        call { read() }
    }

    @Test
    fun `a write whose connection drops is sent once`() {
        useConnection()
        network.api.enqueue(dropped())
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { write(1) })
        assertEquals(listOf("GET", "PATCH"), sent())
        assertFalse(network.log.last(), "NotSentAgain" in network.log.last())
    }

    @Test
    fun `a removal whose connection drops is sent once`() {
        useConnection()
        network.api.enqueue(dropped())
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { remove() })
        assertEquals(listOf("GET", "DELETE"), sent())
    }

    @Test
    fun `a write the server asks to be sent again is sent once`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .setHeader("Retry-After", "0")
                .build(),
        )
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { write(1) })
        assertEquals(listOf("PATCH"), sent())
        assertTrue(network.log.single(), " 503 " in network.log.single())
    }

    @Test
    fun `a write the server sends elsewhere is sent once`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .code(307)
                .setHeader("Location", "/v2/elsewhere")
                .build(),
        )
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { write(1) })
        assertEquals(listOf("PATCH"), sent())
    }

    @Test
    fun `a write answered with a request timeout is sent once and ends as that answer`() {
        network.online = false
        network.api.enqueue(answer(408))
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { write(1) })
        assertEquals(listOf("PATCH"), sent())
        assertTrue(network.log.single(), " 408 " in network.log.single())
    }

    @Test
    fun `a write the server answers with another address to read is sent once and that address is read`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .code(303)
                .setHeader("Location", "/v2/thing")
                .build(),
        )
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Success(Thing(7)), call { write(1) })
        assertEquals(listOf("PATCH", "GET"), sent())
    }

    @Test
    fun `a write with a failed answer is sent once`() {
        network.api.enqueue(answer(500))
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { write(1) })
        assertEquals(listOf("PATCH"), sent())
    }

    @Test
    fun `a write sent again by its caller is a new request`() {
        network.api.enqueue(answer(401))
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Rejected, call { write(1) })
        assertEquals(MalAnswer.Success(Thing(7)), call { write(1) })
        assertEquals(listOf("PATCH", "PATCH"), sent())
    }

    @Test
    fun `a read whose connection drops is asked again`() {
        useConnection()
        network.api.enqueue(dropped())
        network.api.enqueue(dropped())
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Success(Thing(7)), call { read() })
    }

    @Test
    fun `a read with a server error is asked again twice and no more`() {
        network.api.enqueue(answer(503))
        network.api.enqueue(answer(500))
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Success(Thing(7)), call { read() })
        repeat(4) { network.api.enqueue(answer(503)) }
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { read() })
        assertEquals(6, network.api.requestCount)
    }

    @Test
    fun `a read with any other answer is asked once`() {
        for (code in listOf(400, 401, 403, 404, 429)) {
            network.api.enqueue(answer(code))
            call { read() }
        }
        assertEquals(5, network.api.requestCount)
    }

    @Test
    fun `a read made offline is asked once`() {
        network.online = false
        network.api.enqueue(dropped())
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.NoConnection), call { read() })
        assertEquals(1, network.api.requestCount)
    }

    @Test
    fun `a read that got no answer is asked three times, and once when it timed out`() {
        var asked = 0
        runBlocking {
            network.http.call<Unit> {
                asked++
                throw IOException()
            }
        }
        assertEquals(3, asked)
        asked = 0
        runBlocking {
            network.http.call<Unit> {
                asked++
                throw SocketTimeoutException()
            }
        }
        assertEquals(1, asked)
    }

    @Test
    fun `a read reaches the server three times at most when the server asks for it again each time`() {
        network.always(
            MockResponse
                .Builder()
                .code(503)
                .setHeader("Retry-After", "0")
                .build(),
        )
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { read() })
        assertEquals(3, network.api.requestCount)
        assertEquals(2, network.log.size)
        assertTrue(network.log.toString(), network.log.all { " 503 " in it })
    }

    @Test
    fun `a read reaches the server three times at most when its connection drops each time`() {
        useConnection()
        repeat(4) { network.api.enqueue(dropped()) }
        network.api.enqueue(answer())
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { read() })
        assertEquals(listOf("GET", "GET", "GET", "GET"), sent())
    }

    @Test
    fun `a read answered with a request timeout reaches the server three times at most`() {
        network.always(answer(408))
        assertEquals(MalAnswer.Failed(DataError.ServerError), call { read() })
        assertTrue(network.api.requestCount in 1..3)
    }

    @Test
    fun `a read whose call was cancelled is asked once`() {
        network.repeatReadsAfter = listOf(Duration.ZERO, Duration.ZERO)
        var asked = 0
        val cancelled =
            runBlocking {
                val read =
                    async {
                        network.http.call<Unit> {
                            asked++
                            currentCoroutineContext().cancel()
                            throw IOException()
                        }
                    }
                read.join()
                read.isCancelled
            }
        assertTrue(cancelled)
        assertEquals(1, asked)
    }

    @Test
    fun `a read cancelled while it waits to be asked again is not sent again and leaves no line`() {
        network.repeatReadsAfter = listOf(30.seconds)
        network.always(answer(503))
        runBlocking {
            val read = async(Dispatchers.Default) { network.http.call { things.read() } }
            withTimeout(5.seconds) { while (network.log.isEmpty()) delay(10) }
            delay(100)
            withTimeout(2.seconds) { read.cancelAndJoin() }
            delay(100)
        }
        assertEquals(1, network.api.requestCount)
        assertEquals(1, network.log.size)
    }

    @Test
    fun `reads that wait to be asked again hold no write back`() {
        network.repeatReadsAfter = listOf(30.seconds)
        network.answers { if (it.method == "GET") answer(503) else answer() }
        runBlocking {
            val reads = List(20) { async(Dispatchers.Default) { network.http.call { things.read() } } }
            withTimeout(5.seconds) { while (network.api.requestCount < 20) delay(10) }
            assertEquals(MalAnswer.Success(Thing(7)), withTimeout(2.seconds) { network.http.call { things.write(1) } })
            assertTrue(reads.none { it.isCompleted })
            withTimeout(2.seconds) { reads.forEach { it.cancelAndJoin() } }
        }
        assertEquals(21, network.api.requestCount)
    }

    @Test
    fun `a request cancelled before it was tried leaves no line, and one that could not connect leaves one`() {
        val log = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(MarkSends)
                .addInterceptor(RequestLog(log::add))
                .addNetworkInterceptor(SendLimits)
                .build()
        val request = Request.Builder().url(network.api.url("/")).build()
        val cancelled = client.newCall(request).apply { cancel() }
        assertThrows(IOException::class.java) { cancelled.execute() }
        assertTrue(log.toString(), log.isEmpty())
        network.api.close()
        assertThrows(IOException::class.java) { client.newCall(request).execute() }
        assertEquals(1, log.size)
    }
}
