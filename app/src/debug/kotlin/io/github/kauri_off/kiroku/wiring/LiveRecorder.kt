package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.data.recorder.RecorderFolders
import io.github.kauri_off.kiroku.data.recorder.SignedInRecorder
import io.github.kauri_off.kiroku.data.recorder.signedInRecorder
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.notShown
import java.io.File

private val JOURNAL = notShown("recording")
private val RECORDED = notShown("recorded")
private val RUN = notShown("recorded-run")
private val PRIVATE_READS = notShown("private-reads")

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
        privateMutations = if (parts.source === LiveSource.Account) ({ parts.file(notShown("private-manga-mutations")) }) else null,
    )

internal fun liveMangaObserver(graph: LiveGraph) = graph.recorder.manga

internal fun forgetMangaCaptures(graph: LiveGraph) {
    graph.recorder.manga?.forget()
}
