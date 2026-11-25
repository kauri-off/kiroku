package io.github.kauri_off.kiroku.data.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val CLIENT_ID_HEADER = "X-MAL-CLIENT-ID"

private val Request.isRead get() = method == "GET" || method == "HEAD"

internal class UserAgent(
    private val value: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", value)
                .build(),
        )
}

// The client id goes to MAL's API alone and with no token; set per send, so a redirect takes it nowhere else.
internal class ClientId(
    private val api: HttpUrl,
    private val value: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.needsClientId(api)) return chain.proceed(request)
        if (value.isBlank()) throw NoClientId()
        return chain.proceed(request.newBuilder().header(CLIENT_ID_HEADER, value).build())
    }
}

// MAL refuses a request to its API with neither a token nor the client id, for a cause that is the build's: none is sent.
internal class WithoutClientId(
    private val api: HttpUrl,
    private val value: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (value.isBlank() && request.needsClientId(api)) throw NoClientId()
        return chain.proceed(request)
    }
}

// A request to MAL's API that carries no token.
private fun Request.needsClientId(api: HttpUrl): Boolean =
    url.scheme == api.scheme && url.host == api.host && url.port == api.port && header(AUTHORIZATION) == null

// A request that was not sent because the build has no client id; OkHttp tries it no other way.
internal class NoClientId : ProtocolException()

// What the requests of one [MalHttp.call] put on the network, over all its tries.
internal class Sends(
    private val limit: Int,
    val maySendRead: (() -> Boolean)? = null,
) {
    private val reads = ConcurrentHashMap<HttpUrl, AtomicInteger>()

    @Volatile
    var wrote = false

    // No read of these can be sent once more.
    val spent: Boolean get() = reads.values.any { it.get() >= limit }

    fun mayRead(url: HttpUrl): Boolean = reads.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet() <= limit
}

// What one request put on the network, and how its last send ended.
internal class Wire {
    val written = AtomicBoolean()

    @Volatile
    var tried = false

    @Volatile
    var status: Int? = null

    @Volatile
    var failure: IOException? = null
}

// OkHttp was about to send a request that may not be sent again; the request ends as its last send did.
internal class NotSentAgain(
    val status: Int?,
    val failure: IOException?,
) : ProtocolException()

internal class ReadOwnerEnded : ProtocolException()

private fun Request.checkReadOwner() {
    if (isRead && tag(Sends::class.java)?.maySendRead?.invoke() == false) throw ReadOwnerEnded()
}

internal object MarkSends : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        request.checkReadOwner()
        if (!request.isRead) request.tag(Sends::class.java)?.wrote = true
        return chain.proceed(request.newBuilder().tag(Wire::class.java, Wire()).build())
    }
}

// On the network a write goes once and a read of one call as often as the call may ask, whatever OkHttp would resend.
internal object SendLimits : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val wire = request.tag(Wire::class.java) ?: return chain.proceed(request)
        request.checkReadOwner()
        val allowed =
            if (request.isRead) request.tag(Sends::class.java)?.mayRead(request.url) != false else !wire.written.getAndSet(true)
        if (!allowed) throw NotSentAgain(wire.status, wire.failure)
        wire.tried = true
        wire.status = null
        wire.failure = null
        try {
            return chain.proceed(request).also { wire.status = it.code }
        } catch (e: IOException) {
            wire.failure = e
            throw e
        }
    }
}
