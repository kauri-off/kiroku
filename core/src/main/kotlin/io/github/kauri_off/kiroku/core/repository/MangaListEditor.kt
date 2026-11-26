package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow

enum class MangaListStatus { Reading, Completed, OnHold, Dropped, PlanToRead }

data class MangaListEdit(
    val status: MangaListStatus? = null,
    val score: Int? = null,
    val chaptersRead: Int? = null,
    val volumesRead: Int? = null,
) {
    init {
        require(score == null || score in 0..10)
        require(chaptersRead == null || chaptersRead >= 0)
        require(volumesRead == null || volumesRead >= 0)
    }

    val isEmpty: Boolean get() = status == null && score == null && chaptersRead == null && volumesRead == null

    fun appliedTo(entry: MangaListEntry): MangaListEntry =
        entry.copy(
            status = status?.readingStatus ?: entry.status,
            score = if (score == null) entry.score else score.takeIf { it > 0 },
            explicitlyUnrated = if (score == null) entry.explicitlyUnrated else score == 0,
            chaptersRead = chaptersRead ?: entry.chaptersRead,
            volumesRead = volumesRead ?: entry.volumesRead,
        )

    fun hasEvidenceIn(entry: MangaListEntry): Boolean =
        (score == null || entry.recordedScore != null) &&
            (chaptersRead == null || entry.chaptersRead != null) &&
            (volumesRead == null || entry.volumesRead != null)

    fun isPresentIn(entry: MangaListEntry): Boolean =
        (status == null || entry.status == status.readingStatus) &&
            (score == null || entry.recordedScore == score) &&
            (chaptersRead == null || entry.chaptersRead == chaptersRead) &&
            (volumesRead == null || entry.volumesRead == volumesRead)

    fun remainingAfter(
        baseline: MangaListEntry,
        current: MangaListEntry,
    ): MangaListEdit {
        val sameProgress =
            current.status == baseline.status &&
                (chaptersRead == null || (current.chaptersRead != null && current.chaptersRead == baseline.chaptersRead)) &&
                (volumesRead == null || (current.volumesRead != null && current.volumesRead == baseline.volumesRead))
        return MangaListEdit(
            status = status?.takeIf { sameProgress && it.readingStatus != current.status },
            score =
                score?.takeIf {
                    current.recordedScore != null &&
                        current.recordedScore == baseline.recordedScore &&
                        it != current.recordedScore
                },
            chaptersRead = chaptersRead?.takeIf { sameProgress && it != current.chaptersRead },
            volumesRead = volumesRead?.takeIf { sameProgress && it != current.volumesRead },
        )
    }
}

val MangaListStatus.readingStatus: MangaReadingStatus
    get() =
        MangaReadingStatus(
            when (this) {
                MangaListStatus.Reading -> "reading"
                MangaListStatus.Completed -> "completed"
                MangaListStatus.OnHold -> "on_hold"
                MangaListStatus.Dropped -> "dropped"
                MangaListStatus.PlanToRead -> "plan_to_read"
            },
        )

sealed interface MangaListEditResult {
    data class Confirmed(
        val entry: MangaListEntry,
    ) : MangaListEditResult

    data class Failed(
        val error: DataError,
    ) : MangaListEditResult

    data class Accepted(
        val readBack: Outcome<MangaListEntry>,
    ) : MangaListEditResult

    data class Uncertain(
        val error: DataError,
        val needsRefresh: Boolean,
    ) : MangaListEditResult

    data object RefreshRequired : MangaListEditResult

    data object Discarded : MangaListEditResult
}

interface MangaListEditor : MangaListReader {
    fun observe(owner: SignInId): Flow<Outcome<MangaListSnapshot?>>

    suspend fun edit(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
        retryFrom: MangaListEntry? = null,
    ): MangaListEditResult
}
