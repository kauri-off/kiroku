package io.github.kauri_off.kiroku.core.model

import java.time.Instant

@JvmInline
value class MangaReadingStatus(
    val code: String,
) {
    init {
        require(code.isNotBlank())
    }
}

enum class MangaListNumber { Score, ChaptersRead, VolumesRead }

data class MangaListEntry(
    val id: MangaId,
    val titles: Titles,
    val status: MangaReadingStatus,
    val posterUrl: String? = null,
    val chaptersRead: Int? = null,
    val volumesRead: Int? = null,
    val score: Int? = null,
    val updatedAt: Instant? = null,
    val explicitlyUnrated: Boolean = false,
    val unverifiedNumbers: Set<MangaListNumber> = emptySet(),
) {
    init {
        require(id.value > 0)
        require(chaptersRead == null || chaptersRead >= 0)
        require(volumesRead == null || volumesRead >= 0)
        require(score == null || score in 1..10)
        require(!explicitlyUnrated || score == null)
    }

    val recordedScore: Int? get() = (score ?: 0.takeIf { explicitlyUnrated }).takeUnless { MangaListNumber.Score in unverifiedNumbers }
    val recordedChaptersRead: Int? get() = chaptersRead.takeUnless { MangaListNumber.ChaptersRead in unverifiedNumbers }
    val recordedVolumesRead: Int? get() = volumesRead.takeUnless { MangaListNumber.VolumesRead in unverifiedNumbers }
}

data class MangaListSnapshot(
    val owner: SignInId,
    val entries: List<MangaListEntry>,
    val syncedAt: Instant?,
)
