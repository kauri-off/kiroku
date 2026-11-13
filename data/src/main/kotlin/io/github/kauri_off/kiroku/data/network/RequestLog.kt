package io.github.kauri_off.kiroku.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

// One line per request tried: method, host, path, the names of its query and how it ended; no header, value or body.
internal class RequestLog(
    private val log: (String) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val query =
            url.queryParameterNames
                .takeIf { it.isNotEmpty() }
                ?.joinToString("&", prefix = "?")
                .orEmpty()
        val line = "${request.method} ${url.host}${url.encodedPath}$query"
        val start = System.nanoTime()

        fun ended(how: String) = log("$line $how ${(System.nanoTime() - start) / 1_000_000} ms")
        try {
            return chain.proceed(request).also { ended(it.code.toString()) }
        } catch (e: IOException) {
            val neverTried = chain.call().isCanceled() && request.tag(Wire::class.java)?.tried != true
            if (!neverTried) ended(how(e))
            throw e
        }
    }

    private fun how(e: IOException): String =
        if (e is NotSentAgain) e.status?.toString() ?: (e.failure ?: e).javaClass.simpleName else e.javaClass.simpleName
}
