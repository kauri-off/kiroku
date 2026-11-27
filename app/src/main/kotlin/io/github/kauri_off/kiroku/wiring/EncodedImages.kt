package io.github.kauri_off.kiroku.wiring

import android.content.Context
import coil3.ColorImage
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okio.Buffer
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class LoadedImageSource(
    val url: String,
    val cacheKey: String?,
    val fingerprint: String?,
)

private val imageScope = Extras.Key<String?>(null)
internal val ImageLoader.exportScope: String? get() = defaults.extras[imageScope]

internal fun ImageLoader.Builder.enableEncodedExports(): ImageLoader.Builder = apply { extras[imageScope] = UUID.randomUUID().toString() }

internal fun encodedImageReader(calls: Call.Factory?): Interceptor = Interceptor { readEncoded(it, calls) }

internal val captureCoverIdentity = Extras.Key(false)
private val coverIdentity = Extras.Key<CoverIdentity?>(null)
internal const val MAX_ENCODED_IMAGE_BYTES = 64L * 1024 * 1024

internal fun SuccessResult.coverSource(url: String): LoadedImageSource =
    LoadedImageSource(url, diskCacheKey, request.extras[coverIdentity]?.fingerprint)

internal fun captureEncodedIdentity() =
    Interceptor { chain ->
        if (chain.request.extras[captureCoverIdentity] != true) {
            chain.proceed()
        } else {
            val identity = CoverIdentity()
            val request =
                chain.request
                    .newBuilder()
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .apply { extras[coverIdentity] = identity }
                    .decoderFactory { result, _, _ ->
                        Decoder {
                            identity.fingerprint = result.source.fingerprint()
                            null
                        }
                    }.build()
            chain.withRequest(request).proceed()
        }
    }

private class CoverIdentity(
    var fingerprint: String? = null,
)

internal suspend fun ImageRequest.recordSyntheticCover(bytes: () -> ByteArray) {
    val identity = extras[coverIdentity] ?: return
    identity.fingerprint = withContext(Dispatchers.Default) { bytes().toByteString().sha256().hex() }
}

private suspend fun ImageSource.fingerprint(): String? =
    try {
        val path = fileOrNull()
        if (path != null) {
            fileSystem.read(path) { inputStream().fingerprint() }
        } else {
            (source() as? Buffer)?.takeIf { it.size <= MAX_ENCODED_IMAGE_BYTES }?.sha256()?.hex()
        }
    } catch (_: IOException) {
        null
    }

private suspend fun InputStream.fingerprint(): String? {
    val digest = sha256()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        coroutineContext.ensureActive()
        val count = read(buffer)
        if (count < 0) return digest.digest().toByteString().hex()
        total += count
        if (total > MAX_ENCODED_IMAGE_BYTES) return null
        digest.update(buffer, 0, count)
    }
}

private fun sha256() = MessageDigest.getInstance(notShown("SHA-256"))

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
        val digest = sha256()
        file.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = byteLimit
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                if (count < 0) break
                if (count > remaining) throw IOException()
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        if (file.length() == 0L || digest.digest().toByteString().hex() != source.fingerprint) throw IOException()
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
    if (exportScope == null || source.fingerprint == null || limit <= 0L) throw IOException()
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
