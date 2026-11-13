package io.github.kauri_off.kiroku.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

// One line per request sent, made of its method, host, path, the names of its query and how it ended: no header,
// no value of the query and no body, so no token and no client id.
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
            ended(e.javaClass.simpleName)
            throw e
        }
    }
}
