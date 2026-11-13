package io.github.kauri_off.kiroku.data.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

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

// The client id goes to MAL's API alone, on a request that carries no token; a redirect does not take it elsewhere.
internal class ClientId(
    private val api: HttpUrl,
    private val value: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val toApi = url.scheme == api.scheme && url.host == api.host && url.port == api.port
        if (value.isEmpty() || !toApi || request.header(AUTHORIZATION) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(CLIENT_ID_HEADER, value).build())
    }
}

// A read that got no answer, or a server error for one, is asked again after each of [repeatAfter]; never one that
// timed out or was made offline. A write is marked, so that [WriteOnce] knows it.
internal class SendRules(
    private val repeatAfter: List<Duration>,
    private val online: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isRead) return chain.proceed(request.newBuilder().tag(Sent::class.java, Sent()).build())
        var repeats = 0
        while (true) {
            val wait = repeatAfter.getOrNull(repeats++)
            try {
                val response = chain.proceed(request)
                if (wait == null || response.code !in 500..599) return response
                response.close()
            } catch (e: IOException) {
                if (wait == null || e is InterruptedIOException || chain.call().isCanceled() || !online()) throw e
            }
            try {
                Thread.sleep(wait.inWholeMilliseconds)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }
}

internal class Sent : AtomicBoolean()

// A write goes over the network once, whatever would send it again: a dropped connection, a redirect, a server
// asking for it once more.
internal object WriteOnce : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sent = request.tag(Sent::class.java)
        if (sent != null && !request.isRead && !sent.compareAndSet(false, true)) throw ProtocolException("A write is sent once")
        return chain.proceed(request)
    }
}
