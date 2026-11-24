package io.github.kauri_off.kiroku.wiring

import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.serviceLoaderEnabled
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

internal fun imageLoaderBuilder(
    context: PlatformContext,
    calls: Call.Factory?,
    standIn: Interceptor?,
    schemes: Set<String> = Https,
    requests: Interceptor? = null,
): ImageLoader.Builder =
    ImageLoader
        .Builder(context)
        .serviceLoaderEnabled(false)
        .components {
            requests?.let { add(it) }
            add(OnlySchemes(schemes))
            standIn?.let { add(it) }
            calls?.let { add(OkHttpNetworkFetcherFactory(callFactory = { it })) }
        }

internal class LiveImages(
    context: PlatformContext,
    source: String,
    calls: Call.Factory,
) {
    private val requestsLock = Any()
    private var activeRequests: MutableSet<Job>? = mutableSetOf()

    // Coil resolves size before the interceptor runs.
    private val sizing = IdentityHashMap<ImageRequest, MutableSet<Job>?>()
    private val forgetting = Mutex()
    private val directory by lazy { File(context.cacheDir, notShown("$source.images")) }
    private val disk by lazy {
        directory.mkdirs()
        DiskCache.Builder().directory(directory.toOkioPath()).build()
    }

    private val loader =
        lazy {
            imageLoaderBuilder(context, calls, standInPosters, requests = Interceptor(::load))
                .diskCache { disk }
                .eventListenerFactory { RequestStart(synchronized(requestsLock) { activeRequests }) }
                .build()
        }

    fun loader(): ImageLoader = loader.value

    private suspend fun load(chain: Interceptor.Chain): ImageResult =
        coroutineScope {
            val job = checkNotNull(coroutineContext[Job])
            val started =
                synchronized(requestsLock) {
                    val session = sizing.remove(chain.request)
                    if (session == null || session !== activeRequests) throw CancellationException()
                    session.also { it.add(job) }
                }
            try {
                chain.proceed()
            } finally {
                synchronized(requestsLock) { started.remove(job) }
            }
        }

    suspend fun forget(signOut: suspend () -> Unit) {
        forgetting.withLock {
            withContext(NonCancellable) {
                val active =
                    synchronized(requestsLock) {
                        checkNotNull(activeRequests).toList().also { activeRequests = null }
                    }
                try {
                    active.forEach { it.cancel() }
                    active.joinAll()
                    if (loader.isInitialized()) loader.value.memoryCache?.clear()
                    withContext(Dispatchers.IO) {
                        forgetDisk {
                            if (directory.exists()) disk.clear()
                        }
                        forgetDisk {
                            FileSystem.SYSTEM.deleteRecursively(LegacyImageCache)
                        }
                    }
                    signOut()
                } finally {
                    synchronized(requestsLock) { activeRequests = mutableSetOf() }
                }
            }
        }
    }

    private inline fun forgetDisk(clear: () -> Unit) {
        try {
            clear()
        } catch (e: IOException) {
            logTypes(e)
        }
    }

    private inner class RequestStart(
        private val session: MutableSet<Job>?,
    ) : EventListener() {
        override fun onStart(request: ImageRequest) {
            synchronized(requestsLock) { sizing[request] = session }
        }

        override fun onCancel(request: ImageRequest) {
            synchronized(requestsLock) { sizing.remove(request) }
        }

        override fun onError(
            request: ImageRequest,
            result: ErrorResult,
        ) {
            synchronized(requestsLock) { sizing.remove(request) }
        }
    }
}

private val LegacyImageCache = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / notShown("coil3_disk_cache")

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
