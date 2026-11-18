package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.CLIENT_ID
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.TOKEN
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import io.github.kauri_off.kiroku.data.session.MalTokenEndpoint
import io.github.kauri_off.kiroku.data.session.TokenReply
import io.github.kauri_off.kiroku.data.session.refreshGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration

// Section 6.4: every request names Kiroku; the token and the client id reach MAL and no log.
class CredentialsTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val things by lazy { network.things.value }
    private val signInThings by lazy { network.signInThings.value }

    private fun <T> call(request: suspend () -> T): MalAnswer<T> = runBlocking { network.http.call(request = request) }

    @Test
    fun `the log of requests that carry the token and the client id holds neither`() {
        val leaking = """{"value":7,"token":"$TOKEN","id":"$CLIENT_ID"}"""
        network.api.enqueue(answer(body = leaking))
        network.api.enqueue(answer(body = leaking))
        network.api.enqueue(answer(403, body = leaking))
        network.api.enqueue(answer(body = "$TOKEN $CLIENT_ID"))
        network.api.enqueue(dropped())
        network.signIn.enqueue(answer(body = leaking))
        network.signIn.enqueue(answer(400, body = leaking))

        call { things.read() }
        call { things.readAs(bearer(TOKEN)) }
        call { things.exchange(bearer(TOKEN), CLIENT_ID, TOKEN, CLIENT_ID, TOKEN) }
        call { things.readAs(bearer(TOKEN)) }
        call { things.exchange(bearer(TOKEN), CLIENT_ID, TOKEN, CLIENT_ID, TOKEN) }
        call { signInThings.exchange(bearer(TOKEN), CLIENT_ID, TOKEN, CLIENT_ID, TOKEN) }
        call { signInThings.exchange(bearer(TOKEN), CLIENT_ID, TOKEN, CLIENT_ID, TOKEN) }

        val sent = List(5) { network.api.received() } + List(2) { network.signIn.received() }
        assertEquals(CLIENT_ID, sent[0].headers[CLIENT_ID_HEADER])
        assertEquals("Bearer $TOKEN", sent[1].headers[AUTHORIZATION])
        val exchanges = sent.filter { it.method == "POST" }
        assertEquals(4, exchanges.size)
        for (exchange in exchanges) {
            val carried = exchange.target + exchange.body!!.utf8() + exchange.headers[AUTHORIZATION]
            assertTrue(TOKEN in exchange.target && TOKEN in exchange.body!!.utf8() && CLIENT_ID in carried)
        }

        val written = network.log + network.unexpected.map { it.stackTraceToString() }
        assertEquals(7, network.log.size)
        assertEquals(1, network.unexpected.size)
        assertEquals(
            "POST ${network.api.hostName}/v2/exchange?id&token 403",
            network.log[2].substringBeforeLast(' ').substringBeforeLast(' '),
        )
        for (line in written) {
            assertFalse(line, TOKEN in line)
            assertFalse(line, CLIENT_ID in line)
        }
    }

    @Test
    fun `the client id goes to the API on a request without a token, and nowhere else`() {
        network.api.enqueue(answer())
        network.api.enqueue(answer())
        network.signIn.enqueue(answer())
        call { things.read() }
        call { things.readAs(bearer(TOKEN)) }
        call { signInThings.read() }

        assertEquals(CLIENT_ID, network.api.received().headers[CLIENT_ID_HEADER])
        val asUser = network.api.received()
        assertEquals("Bearer $TOKEN", asUser.headers[AUTHORIZATION])
        assertNull(asUser.headers[CLIENT_ID_HEADER])
        assertNull(network.signIn.received().headers[CLIENT_ID_HEADER])
    }

    @Test
    fun `the client id does not follow a redirect to another host`() {
        network.api.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Location", network.signIn.url("/elsewhere"))
                .build(),
        )
        network.signIn.enqueue(answer())
        assertEquals(MalAnswer.Success(Thing(7)), call { things.read() })
        assertEquals(CLIENT_ID, network.api.received().headers[CLIENT_ID_HEADER])
        assertNull(network.signIn.received().headers[CLIENT_ID_HEADER])
    }

    @Test
    fun `a build without a client id sends MAL's API no request without a token, and is not held back for it`() {
        network.clientId = " "
        network.api.enqueue(answer())
        network.signIn.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Location", network.api.url("/v2/thing"))
                .build(),
        )

        assertEquals(MalAnswer.Failed(DataError.NoClientId), call { things.read() })
        assertEquals(MalAnswer.Failed(DataError.NoClientId), call { signInThings.read() })
        val image = Request.Builder().url(network.api.url("/v2/picture.jpg")).build()
        val imageFailure =
            runBlocking {
                withContext(Dispatchers.IO) {
                    runCatching {
                        network.http.callFactory
                            .newCall(image)
                            .execute()
                    }
                }
            }
        assertEquals(MalAnswer.Success(Thing(7)), call { things.readAs(bearer(TOKEN)) })

        val asUser = network.api.received()
        assertEquals("Bearer $TOKEN", asUser.headers[AUTHORIZATION])
        assertNull(asUser.headers[CLIENT_ID_HEADER])
        assertEquals(1, network.api.requestCount)
        assertEquals(1, network.signIn.requestCount)
        assertTrue(imageFailure.exceptionOrNull() is NoClientId)
        assertEquals(Duration.ZERO, runBlocking { network.http.refusedFor() })
        assertTrue(network.log.first(), network.log.first().contains("/v2/thing NoClientId"))
        assertEquals(emptyList<Throwable>(), network.unexpected.toList())
    }

    @Test
    fun `a build without a client id answers before it opens a connection`() {
        network.clientId = ""
        network.api.close()
        network.online = false

        assertEquals(MalAnswer.Failed(DataError.NoClientId), call { things.read() })
    }

    @Test
    fun `a build without a client id asks for no token, so MAL cannot end the session for it`() {
        network.clientId = ""
        network.signIn.enqueue(answer(401, body = """{"error":"invalid_client"}"""))

        val reply = runBlocking { MalTokenEndpoint(network.http).post(refreshGrant(TOKEN)) }

        assertEquals(TokenReply.NoAnswer(DataError.NoClientId), reply)
        assertEquals(0, network.signIn.requestCount)
    }

    @Test
    fun `every request names Kiroku and its version`() {
        network.api.enqueue(answer())
        network.api.enqueue(answer())
        network.signIn.enqueue(answer())
        call { things.read() }
        call { things.write(1) }
        runBlocking {
            withContext(Dispatchers.IO) {
                network.http.callFactory
                    .newCall(Request.Builder().url(network.signIn.url("/picture.jpg")).build())
                    .execute()
                    .close()
            }
        }
        val sent = List(2) { network.api.received() } + network.signIn.received()
        assertEquals(List(3) { "Kiroku/1.2.3" }, sent.map { it.headers["User-Agent"] })
    }
}
