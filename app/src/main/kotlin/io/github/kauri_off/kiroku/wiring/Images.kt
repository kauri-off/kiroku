package io.github.kauri_off.kiroku.wiring

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.serviceLoaderEnabled
import io.github.kauri_off.kiroku.notShown
import okhttp3.Call

// Fetched through the source's HTTP client or not at all; decoded at the size drawn, cached in memory and on disk.
internal fun imageLoader(
    context: PlatformContext,
    calls: Call.Factory?,
    standIn: Interceptor?,
    schemes: Set<String> = Https,
): ImageLoader =
    ImageLoader
        .Builder(context)
        .serviceLoaderEnabled(false)
        .components {
            add(OnlySchemes(schemes))
            standIn?.let { add(it) }
            calls?.let { add(OkHttpNetworkFetcherFactory(callFactory = { it })) }
        }.build()

internal val Https = setOf(notShown("https"))

// An address of another scheme, or anything that is not an address, fails before the caches and Coil's local fetchers.
private class OnlySchemes(
    private val schemes: Set<String>,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val address = (request.data as? String) ?: (request.data as? Uri)?.toString()
        val scheme = address?.substringBefore(':', missingDelimiterValue = "")?.lowercase()
        if (scheme != null && scheme in schemes) return chain.proceed()
        return ErrorResult(request.error(), request, IllegalArgumentException(notShown("Not an allowed image address")))
    }
}
