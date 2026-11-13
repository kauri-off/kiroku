package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// The client is built on its first use and never on the main thread, which here is the test's.
class FirstUseTest {
    @get:Rule
    internal val network = NetworkFixture()

    @Test
    fun `a request made from the main thread builds the client off it`() {
        network.api.enqueue(answer())
        val things by network.http.api(Things::class)
        assertEquals(MalAnswer.Success(Thing(7)), runBlocking { network.http.call { things.read() } })
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `the client and a service that are not used are not built, so the main thread can name them`() {
        assertFalse(network.http.api(Things::class).isInitialized())
        assertFalse(network.http.signIn(Things::class).isInitialized())
        assertNotNull(network.http.callFactory)
    }

    @Test
    fun `a use on the main thread fails`() {
        val request = Request.Builder().url(network.api.url("/")).build()
        assertThrows(IllegalStateException::class.java) { network.http.callFactory.newCall(request) }
        assertThrows(IllegalStateException::class.java) { network.http.api(Things::class).value }
    }
}
