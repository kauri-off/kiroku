package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.CLIENT_ID
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.TOKEN
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
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

        val sent = List(5) { network.api.takeRequest() } + List(2) { network.signIn.takeRequest() }
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

        assertEquals(CLIENT_ID, network.api.takeRequest().headers[CLIENT_ID_HEADER])
        val asUser = network.api.takeRequest()
        assertEquals("Bearer $TOKEN", asUser.headers[AUTHORIZATION])
        assertNull(asUser.headers[CLIENT_ID_HEADER])
        assertNull(network.signIn.takeRequest().headers[CLIENT_ID_HEADER])
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
        assertEquals(CLIENT_ID, network.api.takeRequest().headers[CLIENT_ID_HEADER])
        assertNull(network.signIn.takeRequest().headers[CLIENT_ID_HEADER])
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
        val sent = List(2) { network.api.takeRequest() } + network.signIn.takeRequest()
        assertEquals(List(3) { "Kiroku/1.2.3" }, sent.map { it.headers["User-Agent"] })
    }
}
