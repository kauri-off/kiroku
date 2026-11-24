package io.github.kauri_off.kiroku.wiring

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.serviceLoaderEnabled
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
    private val context: PlatformContext,
    source: String,
    private val retirementDirectory: () -> File,
    private val calls: Call.Factory,
    private val scope: CoroutineScope,
    private val fileSystem: FileSystem,
    private val isCurrent: suspend (SignInId?) -> Boolean,
) {
    private val root by lazy { File(context.cacheDir, notShown("$source.image-caches")).toOkioPath() }
    private val oldDirectory by lazy { File(context.cacheDir, notShown("$source.images")).toOkioPath() }
    private val owners = mutableMapOf<SignInId?, ImageCache>()
    private val retired = mutableSetOf<SignInId>()
    private val openDisks = mutableSetOf<Path>()
    private var inspected = false

    private val closed by lazy {
        imageLoaderBuilder(context, null, null, requests = Interceptor { throw CancellationException() })
            .memoryCache(null)
            .diskCache(null)
            .build()
    }

    fun loader(owner: SignInId?): ImageLoader =
        synchronized(owners) {
            if (owner in retired) return closed
            val cache = owners.getOrPut(owner) { ImageCache(context, { openDisk(owner) }, calls) { isCurrent(owner) } }
            inspect()
            cache.loader
        }

    fun wasRetired(owner: SignInId?): Boolean = synchronized(owners) { owner in retired }

    fun retire(owner: SignInId?) {
        synchronized(owners) {
            if (owner != null && retired.add(owner)) {
                val cache = owners.remove(owner)
                cache?.closeAdmission()
                scope.launch(Dispatchers.Default) {
                    cache?.drain()
                    withContext(Dispatchers.IO) {
                        tryImageCleanup { fileSystem.deleteRecursively(directory(owner)) }
                    }
                }
            }
            inspect()
        }
    }

    suspend fun recordRetirement(owner: SignInId?) {
        if (owner == null) return
        withContext(Dispatchers.IO) {
            tryImageCleanup {
                val file = retirementFile(owner)
                FileSystem.SYSTEM.createDirectories(checkNotNull(file.parentFile).toOkioPath())
                FileOutputStream(file).use { it.fd.sync() }
            }
        }
    }

    private fun openDisk(owner: SignInId?): DiskCache? =
        synchronized(openDisks) {
            try {
                if (owner != null && wasRecorded(owner)) return null
                val path = directory(owner)
                FileSystem.SYSTEM.createDirectories(path)
                DiskCache
                    .Builder()
                    .directory(path)
                    .build()
                    .also { openDisks.add(path) }
            } catch (e: IOException) {
                logTypes(e)
                null
            } catch (e: SecurityException) {
                logTypes(e)
                null
            }
        }

    private fun retirementFile(owner: SignInId): File = File(retirementDirectory(), owner.diskName())

    private fun wasRecorded(owner: SignInId): Boolean = FileSystem.SYSTEM.metadataOrNull(retirementFile(owner).toOkioPath()) != null

    private fun SignInId.diskName(): String = value.encodeUtf8().sha256().hex()

    private fun directory(owner: SignInId?): Path {
        val name = owner?.diskName() ?: notShown("public")
        return root / name
    }

    private fun inspect() {
        if (inspected) return
        inspected = true
        val requested = owners.keys.toSet()
        val draining = retired.toSet()
        scope.launch(Dispatchers.IO) {
            tryImageCleanup { fileSystem.deleteRecursively(oldDirectory) }
            tryImageCleanup {
                val keep = requested.filter { it == null || !wasRecorded(it) } + draining + null
                val protected = keep.mapTo(mutableSetOf(), ::directory)
                fileSystem.listOrNull(root).orEmpty().filterNot { it in protected }.forEach { path ->
                    synchronized(openDisks) {
                        if (path !in openDisks) tryImageCleanup { fileSystem.deleteRecursively(path) }
                    }
                }
            }
        }
    }
}

private class ImageCache(
    context: PlatformContext,
    openDisk: () -> DiskCache?,
    calls: Call.Factory,
    private val isCurrent: suspend () -> Boolean,
) {
    private val loading = mutableMapOf<Job, MutableList<Call>>()
    private val requestCalls = ThreadLocal<MutableList<Call>>()
    private var retired = false
    private val disk = lazy(openDisk)
    private val built =
        lazy {
            val tracked =
                Call.Factory { request ->
                    val call = calls.newCall(request)
                    val cancel =
                        synchronized(loading) {
                            if (retired) return@synchronized true
                            checkNotNull(requestCalls.get()).add(call)
                            false
                        }
                    if (cancel) call.cancel()
                    call
                }
            imageLoaderBuilder(context, tracked, standInPosters, requests = Interceptor(::load))
                .diskCache { disk.value }
                .build()
        }
    val loader: ImageLoader get() = built.value

    private suspend fun load(chain: Interceptor.Chain): ImageResult =
        coroutineScope {
            if (!isCurrent()) throw CancellationException()
            val job = checkNotNull(coroutineContext[Job])
            val calls = mutableListOf<Call>()
            synchronized(loading) {
                if (retired) throw CancellationException()
                loading[job] = calls
            }
            try {
                withContext(requestCalls.asContextElement(calls)) { chain.proceed() }
            } finally {
                synchronized(loading) { loading.remove(job) }
            }
        }

    fun closeAdmission() {
        synchronized(loading) { retired = true }
    }

    suspend fun drain() {
        val (jobs, calls) = synchronized(loading) { loading.keys.toList() to loading.values.flatten() }
        calls.forEach { it.cancel() }
        jobs.forEach { it.cancel() }
        if (built.isInitialized()) loader.memoryCache?.clear()
        jobs.joinAll()
        if (built.isInitialized()) loader.shutdown()
        if (disk.isInitialized()) withContext(Dispatchers.IO) { disk.value?.shutdown() }
    }
}

internal fun forgetLegacyImages() {
    tryImageCleanup {
        FileSystem.SYSTEM.deleteRecursively(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / notShown("coil3_disk_cache"))
    }
}

private inline fun tryImageCleanup(clean: () -> Unit) {
    try {
        clean()
    } catch (e: IOException) {
        logTypes(e)
    } catch (e: SecurityException) {
        logTypes(e)
    }
}

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
