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

data class MangaListEntry(
    val id: MangaId,
    val titles: Titles,
    val status: MangaReadingStatus,
    val posterUrl: String? = null,
    val chaptersRead: Int? = null,
    val volumesRead: Int? = null,
    val score: Int? = null,
    val updatedAt: Instant? = null,
) {
    init {
        require(id.value > 0)
        require(chaptersRead == null || chaptersRead >= 0)
        require(volumesRead == null || volumesRead >= 0)
        require(score == null || score in 1..10)
    }
}

data class MangaListSnapshot(
    val owner: SignInId,
    val entries: List<MangaListEntry>,
    val syncedAt: Instant?,
)
