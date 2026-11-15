package io.github.kauri_off.kiroku.wiring

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import okhttp3.Call

// Fetched through the source's HTTP client or not at all; decoded at the size drawn, cached in memory and on disk.
internal fun imageLoader(
    context: PlatformContext,
    calls: Call.Factory?,
    standIn: Interceptor?,
): ImageLoader =
    ImageLoader
        .Builder(context)
        .serviceLoaderEnabled(false)
        .components {
            standIn?.let { add(it) }
            calls?.let { add(OkHttpNetworkFetcherFactory(callFactory = { it })) }
        }.build()
