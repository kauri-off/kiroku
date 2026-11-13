package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

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
    fun `a read that timed out is asked once`() {
        val asked = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(SendRules(listOf(1.milliseconds)) { true })
                .addInterceptor {
                    asked.incrementAndGet()
                    throw SocketTimeoutException()
                }.build()
        assertThrows(SocketTimeoutException::class.java) {
            client.newCall(Request.Builder().url(network.api.url("/")).build()).execute()
        }
        assertEquals(1, asked.get())
    }
}
