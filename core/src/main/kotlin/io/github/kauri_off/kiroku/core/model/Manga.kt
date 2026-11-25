package io.github.kauri_off.kiroku.core.model

@JvmInline
value class MangaId(
    val value: Long,
)

data class MangaDetails(
    val id: MangaId,
    val titles: Titles,
    val posterUrl: String?,
    val largePosterUrl: String?,
    val synopsis: String?,
    val format: MangaFormat?,
    val publication: MangaPublication?,
    val volumes: Int?,
    val chapters: Int?,
    val meanScore: Double?,
)

sealed interface MangaFormat {
    enum class Known : MangaFormat { Manga, Novel, LightNovel, OneShot, Doujinshi, Manhwa, Manhua, Oel }

    data class Unrecognized(
        val code: String,
    ) : MangaFormat
}

sealed interface MangaPublication {
    enum class Known : MangaPublication { Finished, Publishing, Upcoming, OnHiatus, Discontinued }

    data class Unrecognized(
        val code: String,
    ) : MangaPublication
}
