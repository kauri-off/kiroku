package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.user.RemoteMangaLists
import io.github.kauri_off.kiroku.data.network.Device
import kotlinx.coroutines.delay

class SandboxPublicManga(
    private val mal: SandboxMal,
    private val device: Device,
) : RemoteMangaLists {
    override suspend fun read(
        name: String,
        part: suspend (List<MangaListEntry>) -> Unit,
    ): Outcome<Unit> {
        delay(mal.delayMillis)
        if (offline()) return Outcome.Failure(DataError.NoConnection)
        if (sameUser(name, SandboxUsers.PUBLIC)) return Outcome.Failure(DataError.PrivateList)
        if (!sameUser(name, SandboxUsers.PRIVATE)) return Outcome.Failure(DataError.UnknownUser)
        for ((index, page) in pages.withIndex()) {
            if (index > 0) delay(mal.delayMillis)
            if (offline()) return Outcome.Failure(DataError.NoConnection)
            part(page)
        }
        return Outcome.Success(Unit)
    }

    override fun sameUser(
        a: String,
        b: String,
    ) = a.equals(b, ignoreCase = true)

    private fun offline() = mal.offline || !device.isOnline()

    internal companion object {
        val pages =
            listOf(
                listOf(
                    MangaListEntry(MangaId(101), Titles("The Library Below"), MangaReadingStatus("reading"), chaptersRead = 6),
                    MangaListEntry(MangaId(102), Titles("The Glass Orchard"), MangaReadingStatus("completed"), score = 8),
                ),
                listOf(
                    MangaListEntry(MangaId(103), Titles("An Atlas of Quiet Places"), MangaReadingStatus("reading"), chaptersRead = 2),
                    MangaListEntry(MangaId(104), Titles("Winter Tides"), MangaReadingStatus("on_hold"), chaptersRead = 9),
                ),
            )
    }
}
