package io.github.kauri_off.kiroku.wiring

import android.content.Context
import coil3.ColorImage
import coil3.Extras
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class LoadedImageSource(
    val url: String,
    val cacheKey: String?,
)

private val imageScope = Extras.Key<String?>(null)
internal val ImageLoader.exportScope: String? get() = defaults.extras[imageScope]

internal fun ImageLoader.Builder.enableEncodedExports(): ImageLoader.Builder = apply { extras[imageScope] = UUID.randomUUID().toString() }

internal fun encodedImageReader(calls: Call.Factory?): Interceptor = Interceptor { readEncoded(it, calls) }

internal class EncodedImageExport(
    val loader: ImageLoader,
    val source: LoadedImageSource,
    private val file: File,
    private val byteLimit: Long,
    private val consume: suspend (File) -> Unit,
) {
    var completed = false
        private set

    suspend fun stage(input: InputStream) {
        file.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = byteLimit
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                if (count < 0) break
                if (count > remaining) throw IOException()
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        if (file.length() == 0L) throw IOException()
    }

    suspend fun finish(request: ImageRequest): SuccessResult {
        coroutineContext.ensureActive()
        consume(file)
        completed = true
        return SuccessResult(ColorImage(0, 1, 1), request)
    }
}

internal val encodedImageExport = Extras.Key<EncodedImageExport?>(null)

internal suspend fun ImageLoader.exportEncodedImage(
    context: Context,
    source: LoadedImageSource,
    stage: File,
    limit: Long,
    consume: suspend (File) -> Unit,
) {
    if (exportScope == null || limit <= 0L) throw IOException()
    val export = EncodedImageExport(this, source, stage, limit, consume)
    val request =
        ImageRequest
            .Builder(context)
            .data(source.url)
            .interceptorCoroutineContext(Dispatchers.IO)
            .apply { extras[encodedImageExport] = export }
            .build()
    val result = execute(request)
    if (result is ErrorResult) throw result.throwable
    if (!export.completed) throw IOException()
}

private suspend fun readEncoded(
    chain: Interceptor.Chain,
    calls: Call.Factory?,
): ImageResult {
    val request = chain.request
    val export = request.extras[encodedImageExport] ?: return chain.proceed()
    val cache = export.loader.diskCache
    val cached = export.source.cacheKey?.let { cache?.openSnapshot(it) }
    if (cached != null) {
        cached.use {
            checkNotNull(cache).fileSystem.read(it.data) { export.stage(inputStream()) }
        }
    } else {
        val requestForSource = Request.Builder().url(export.source.url).build()
        if (!requestForSource.url.isHttps || calls == null) throw IOException()
        calls.newCall(requestForSource).stageEncoded(export)
    }
    return export.finish(request)
}

private suspend fun Call.stageEncoded(export: EncodedImageExport) =
    coroutineScope {
        val cancelCall =
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cancel()
                }
            }
        try {
            execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) throw IOException()
                checkNotNull(response.body).byteStream().use { export.stage(it) }
            }
        } finally {
            cancelCall.cancel()
        }
    }
