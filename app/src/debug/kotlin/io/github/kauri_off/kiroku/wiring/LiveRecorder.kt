package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.data.recorder.RecorderFolders
import io.github.kauri_off.kiroku.data.recorder.SignedInRecorder
import io.github.kauri_off.kiroku.data.recorder.signedInRecorder
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

private val JOURNAL = notShown("recording")
private val RECORDED = notShown("recorded")
private val RUN = notShown("recorded-run")
private val PRIVATE_READS = notShown("private-reads")
private val PRIVATE_MANGA_MUTATIONS = notShown("private-manga-mutations")

internal fun mangaCaptureRoot(context: Context): File =
    File(context.noBackupFilesDir, notShown("${LiveSource.Account.name}.$PRIVATE_MANGA_MUTATIONS"))

internal fun retireMangaCapturesForOtherSource(
    context: Context,
    scope: CoroutineScope,
) {
    scope.launch(Dispatchers.IO) {
        val root = mangaCaptureRoot(context)
        var reported = false
        while (!eraseMangaCaptures(root)) {
            if (!reported) {
                logTypes(IOException())
                reported = true
            }
            delay(1.seconds)
        }
    }
}

private fun eraseMangaCaptures(root: File): Boolean =
    try {
        root.deleteRecursively()
    } catch (_: SecurityException) {
        false
    }

internal fun liveRecorder(
    parts: LiveParts,
    session: Lazy<MalSession>,
): SignedInRecorder =
    signedInRecorder(
        session = session,
        http = parts.http,
        journal = { parts.file(JOURNAL) },
        folders = {
            val shared = parts.context.getExternalFilesDir(null) ?: parts.context.filesDir
            val name = parts.source.name
            RecorderFolders(File(shared, notShown("$name.$RECORDED")), File(shared, notShown("$name.$RUN")))
        },
        privateReads = { parts.file(PRIVATE_READS) },
        scope = parts.scope,
        privateMutations = if (parts.source === LiveSource.Account) ({ mangaCaptureRoot(parts.context) }) else null,
    )

internal fun liveMangaObserver(graph: LiveGraph) = graph.recorder.manga

internal fun forgetMangaCaptures(graph: LiveGraph) {
    graph.recorder.manga?.forget()
}
