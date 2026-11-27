package io.github.kauri_off.kiroku.title

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import coil3.Image
import coil3.toBitmap
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.shell.show
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

@Composable
internal fun CoverExportActions(
    title: String,
    snackbars: SnackbarHostState,
    selectedImage: () -> Image?,
) {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var choosingFolder by remember { mutableStateOf<Image?>(null) }

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
        image: Image,
        folder: Uri? = null,
    ) {
        runExport(R.string.title_image_saved, R.string.title_image_save_failed) {
            saveCoverImage(context, title, image, folder)
        }
    }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val image = choosingFolder
            choosingFolder = null
            if (uri != null && image != null) save(image, uri)
        }
    val enabled = !busy && choosingFolder == null && selectedImage() != null
    IconButton(
        enabled = enabled,
        onClick = {
            if (!busy && choosingFolder == null) {
                selectedImage()?.let { image ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        save(image)
                    } else {
                        choosingFolder = image
                        try {
                            folderPicker.launch(null)
                        } catch (e: RuntimeException) {
                            choosingFolder = null
                            runExport(null, R.string.title_image_save_failed) { throw e }
                        }
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
            if (!busy && choosingFolder == null) {
                selectedImage()?.let { image ->
                    runExport(null, R.string.title_image_share_failed) { shareCoverImage(context, title, image) }
                }
            }
        },
    ) {
        Icon(painterResource(R.drawable.ic_share), stringResource(R.string.title_image_share))
    }
}

internal class CoverShareProvider : FileProvider()

private val pngMimeType = notShown("image/png")
private val shareDirectory = notShown("cover-shares")
private const val SHARE_COUNT = 16
private const val SHARE_BYTES = 64L * 1024 * 1024
private const val SHARE_AGE_MILLIS = 24 * 60 * 60 * 1000L
private val shareLock = Mutex()

private suspend fun saveCoverImage(
    context: Context,
    title: String,
    image: Image,
    folder: Uri?,
) = withContext(Dispatchers.IO) {
    val name = coverFileName(context, title)
    when {
        folder != null -> saveInFolder(context, image, name, folder)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> saveInPictures(context, image, name)
        else -> throw IOException()
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveInPictures(
    context: Context,
    image: Image,
    name: String,
) {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, pngMimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + notShown("/Kiroku"))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: throw IOException()
    writeCover(
        context,
        image,
        uri,
        rollback = { resolver.delete(uri, null, null) },
        publish = {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException()
        },
    )
}

private suspend fun saveInFolder(
    context: Context,
    image: Image,
    name: String,
    folder: Uri,
) {
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
    val uri = DocumentsContract.createDocument(resolver, parent, pngMimeType, name) ?: throw IOException()
    writeCover(context, image, uri, rollback = { DocumentsContract.deleteDocument(resolver, uri) })
}

private suspend fun writeCover(
    context: Context,
    image: Image,
    uri: Uri,
    rollback: () -> Unit,
    publish: () -> Unit = {},
) {
    var finished = false
    try {
        coroutineContext.ensureActive()
        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException()
        stream.use { image.writePng(it) }
        coroutineContext.ensureActive()
        publish()
        finished = true
    } finally {
        if (!finished) runCatching(rollback)
    }
}

private suspend fun shareCoverImage(
    context: Context,
    title: String,
    image: Image,
) = withContext(Dispatchers.IO) {
    shareLock.withLock {
        val directory = File(context.cacheDir, shareDirectory)
        directory.mkdirs()
        val files = directory.listFiles() ?: throw IOException()
        val now = System.currentTimeMillis()
        val recent = files.filterNot { now - it.lastModified() > SHARE_AGE_MILLIS && it.delete() }
        val file = File(directory, coverFileName(context, title))
        var handedOff = false
        try {
            file.outputStream().use { image.writePng(it) }
            val length = file.length()
            if (length > SHARE_BYTES) throw IOException()
            var size = length + recent.sumOf { it.length() }
            var count = 1 + recent.size
            for (expired in recent.sortedBy { it.lastModified() }) {
                if (size <= SHARE_BYTES && count <= SHARE_COUNT) break
                val bytes = expired.length()
                if (!expired.delete()) continue
                size -= bytes
                count--
            }
            if (size > SHARE_BYTES || count > SHARE_COUNT) throw IOException()
            val uri = FileProvider.getUriForFile(context, notShown("${context.packageName}.cover-shares"), file)
            withContext(Dispatchers.Main) {
                context.startActivity(shareCoverIntent(uri))
                handedOff = true
            }
        } finally {
            if (!handedOff) file.delete()
        }
    }
}

private fun shareCoverIntent(uri: Uri): Intent {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = pngMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData(null, arrayOf(pngMimeType), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(send, null)
}

private fun coverFileName(
    context: Context,
    title: String,
): String {
    val name =
        title
            .replace(Regex(notShown("[^\\p{L}\\p{N}]+")), notShown(" "))
            .trim()
            .take(40)
            .trimEnd()
    val prefix = name.ifEmpty { context.getString(R.string.app_name) }
    return notShown("$prefix-${UUID.randomUUID()}.png")
}

private fun Image.writePng(stream: OutputStream) {
    if (!toBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException()
}
