package io.github.kauri_off.kiroku.title

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import coil3.ImageLoader
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.show
import io.github.kauri_off.kiroku.wiring.LoadedImageSource
import io.github.kauri_off.kiroku.wiring.exportEncodedImage
import io.github.kauri_off.kiroku.wiring.exportScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

@Composable
internal fun CoverExportActions(
    title: String,
    loader: ImageLoader,
    snackbars: SnackbarHostState,
    selectedImage: () -> LoadedImageSource?,
) {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val owner = loader.exportScope
    var folderRequest by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }

    fun selectedForExport() = if (owner != null && !busy && folderRequest == null) selectedImage() else null

    fun runExport(
        success: Int?,
        failure: Int,
        export: suspend () -> Unit,
    ) {
        busy = true
        scope.launch {
            val message =
                try {
                    export()
                    success
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IncompleteCoverSave) {
                    R.string.title_image_save_incomplete
                } catch (_: Exception) {
                    failure
                } finally {
                    busy = false
                }
            if (message != null) {
                snackbars.currentSnackbarData?.dismiss()
                snackbars.show(resources.getString(message), action = null)
            }
        }
    }

    fun save(
        source: LoadedImageSource,
        folder: Uri? = null,
    ) {
        runExport(R.string.title_image_saved, R.string.title_image_save_failed) {
            withEncodedCover(context, title, loader, source) { cover ->
                if (folder != null) {
                    saveInFolder(context, cover, folder)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveInPictures(context, cover)
                } else {
                    throw IOException()
                }
            }
        }
    }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val pending = folderRequest
            folderRequest = null
            if (uri != null && pending != null && pending[0] == owner) {
                save(LoadedImageSource(pending[1], pending[2].ifEmpty { null }), uri)
            }
        }
    val enabled = selectedForExport() != null
    IconButton(
        enabled = enabled,
        onClick = {
            selectedForExport()?.let { source ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    save(source)
                } else {
                    folderRequest = arrayListOf(checkNotNull(owner), source.url, source.cacheKey.orEmpty())
                    try {
                        folderPicker.launch(null)
                    } catch (e: RuntimeException) {
                        folderRequest = null
                        runExport(null, R.string.title_image_save_failed) { throw e }
                    }
                }
            }
        },
    ) {
        Icon(painterResource(R.drawable.ic_download), stringResource(R.string.title_image_download))
    }
    IconButton(
        enabled = enabled,
        onClick = {
            selectedForExport()?.let { source ->
                runExport(null, R.string.title_image_share_failed) { shareCoverImage(context, title, loader, source) }
            }
        },
    ) {
        Icon(painterResource(R.drawable.ic_share), stringResource(R.string.title_image_share))
    }
}

internal class CoverShareProvider : FileProvider()

private val shareDirectory = notShown("cover-shares")
private val stageName = notShown("cover-export.tmp")
private const val SHARE_COUNT = 16
private const val EXPORT_BYTES = 64L * 1024 * 1024
private const val SHARE_AGE_MILLIS = 24 * 60 * 60 * 1000L
private val exportLock = Mutex()

private class IncompleteCoverSave(
    cause: Throwable,
) : IOException(cause)

private data class EncodedCover(
    val file: File,
    val name: String,
    val mime: String,
)

private suspend fun withEncodedCover(
    context: Context,
    title: String,
    loader: ImageLoader,
    source: LoadedImageSource,
    share: Boolean = false,
    consume: suspend (EncodedCover) -> Unit,
) = withContext(Dispatchers.IO) {
    exportLock.withLock {
        val limit = if (share) remainingShareBytes(context) else EXPORT_BYTES
        val stage = File(context.cacheDir, stageName)
        try {
            loader.exportEncodedImage(context, source, stage, limit) { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val mime = bounds.outMimeType ?: throw IOException()
                val extension = imageExtension(mime, source.url)
                consume(EncodedCover(file, coverFileName(context, title, extension), mime))
            }
        } finally {
            stage.delete()
        }
    }
}

private fun remainingShareBytes(context: Context): Long {
    val directory = File(context.cacheDir, shareDirectory)
    directory.mkdirs()
    val files = directory.listFiles() ?: throw IOException()
    val now = System.currentTimeMillis()
    val recent = files.filterNot { now - it.lastModified() >= SHARE_AGE_MILLIS && it.delete() }
    if (recent.size >= SHARE_COUNT) throw IOException()
    return EXPORT_BYTES - recent.sumOf { it.length() }
}

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveInPictures(
    context: Context,
    cover: EncodedCover,
) {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, cover.name)
            put(MediaStore.Images.Media.MIME_TYPE, cover.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + notShown("/Kiroku"))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    coroutineContext.ensureActive()
    val uri = resolver.insert(collection, values) ?: throw IOException()
    writeCover(
        context,
        cover,
        uri,
        rollback = { resolver.delete(uri, null, null) == 1 },
        publish = {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException()
        },
    )
}

private suspend fun saveInFolder(
    context: Context,
    cover: EncodedCover,
    folder: Uri,
) {
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
    coroutineContext.ensureActive()
    val uri = DocumentsContract.createDocument(resolver, parent, cover.mime, cover.name) ?: throw IOException()
    writeCover(context, cover, uri, rollback = { DocumentsContract.deleteDocument(resolver, uri) })
}

private suspend fun writeCover(
    context: Context,
    cover: EncodedCover,
    uri: Uri,
    rollback: () -> Boolean,
    publish: () -> Unit = {},
) {
    try {
        coroutineContext.ensureActive()
        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException()
        stream.use { output -> cover.file.inputStream().use { it.copyWhileActive(output) } }
        coroutineContext.ensureActive()
        publish()
    } catch (failure: Exception) {
        if (!runCatching(rollback).getOrDefault(false)) throw IncompleteCoverSave(failure)
        throw failure
    }
}

private suspend fun InputStream.copyWhileActive(output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val count = read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}

private suspend fun shareCoverImage(
    context: Context,
    title: String,
    loader: ImageLoader,
    source: LoadedImageSource,
) = withEncodedCover(context, title, loader, source, share = true) { cover ->
    val file = File(File(context.cacheDir, shareDirectory), cover.name)
    if (!cover.file.renameTo(file)) throw IOException()
    var handedOff = false
    try {
        val uri = FileProvider.getUriForFile(context, notShown("${context.packageName}.cover-shares"), file)
        withContext(Dispatchers.Main) {
            context.startActivity(shareCoverIntent(uri, cover.mime))
            handedOff = true
        }
    } finally {
        if (!handedOff) file.delete()
    }
}

private fun shareCoverIntent(
    uri: Uri,
    mime: String,
): Intent {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData(null, arrayOf(mime), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(send, null)
}

private fun imageExtension(
    mime: String,
    url: String,
): String {
    val original =
        Uri
            .parse(url)
            .lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
    return when (mime) {
        notShown("image/jpeg") -> if (original == notShown("jpeg")) original else notShown("jpg")
        notShown("image/png") -> notShown("png")
        notShown("image/webp") -> notShown("webp")
        notShown("image/gif") -> notShown("gif")
        notShown("image/avif") -> notShown("avif")
        notShown("image/heif") -> notShown("heif")
        notShown("image/heic") -> notShown("heic")
        notShown("image/bmp") -> notShown("bmp")
        notShown("image/x-icon"), notShown("image/vnd.microsoft.icon") -> notShown("ico")
        else -> throw IOException()
    }
}

private fun coverFileName(
    context: Context,
    title: String,
    extension: String,
): String {
    val name =
        title
            .replace(Regex(notShown("[^\\p{L}\\p{N}]+")), notShown(" "))
            .trim()
            .take(40)
            .trimEnd()
    val prefix = name.ifEmpty { context.getString(R.string.app_name) }
    return notShown("$prefix-${UUID.randomUUID()}.$extension")
}
