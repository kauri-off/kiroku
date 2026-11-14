package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import io.github.kauri_off.kiroku.core.model.Titles
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

// A title's summary, as a list row or a search result shows it.
@Entity(tableName = TitleRow.TABLE)
internal data class TitleRow(
    @PrimaryKey val id: Long,
    val romaji: String,
    val english: String?,
    val japanese: String?,
    val synonyms: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "large_poster_url") val largePosterUrl: String?,
    @ColumnInfo(name = "media_type") val mediaType: String?,
    @ColumnInfo(name = "airing_status") val airingStatus: String,
    val episodes: Int?,
    @ColumnInfo(name = "start_date") val startDate: String?,
    @ColumnInfo(name = "broadcast_day") val broadcastDay: Int?,
    @ColumnInfo(name = "broadcast_time") val broadcastTime: String?,
    @ColumnInfo(name = "mean_score") val meanScore: Double?,
    val rank: Int?,
    val genres: String?,
    @ColumnInfo(name = "episode_duration") val episodeDuration: Long?,
) {
    // A stored value is never replaced by a missing one; a picture's two sizes, and a broadcast's day and time, are one value.
    fun mergedWith(newer: TitleRow): TitleRow =
        newer.copy(
            english = newer.english ?: english,
            japanese = newer.japanese ?: japanese,
            posterUrl = newer.posterUrl ?: posterUrl,
            largePosterUrl = if (newer.posterUrl == null) largePosterUrl else newer.largePosterUrl,
            mediaType = newer.mediaType ?: mediaType,
            episodes = newer.episodes ?: episodes,
            startDate = newer.startDate ?: startDate,
            broadcastDay = newer.broadcastDay ?: broadcastDay,
            broadcastTime = if (newer.broadcastDay == null) broadcastTime else newer.broadcastTime,
            meanScore = newer.meanScore ?: meanScore,
            rank = newer.rank ?: rank,
            genres = newer.genres ?: genres,
            episodeDuration = newer.episodeDuration ?: episodeDuration,
        )

    fun toAnime(): Anime =
        Anime(
            id = AnimeId(id),
            titles = Titles(romaji, english, japanese, textsOf(synonyms)),
            posterUrl = posterUrl,
            largePosterUrl = largePosterUrl,
            mediaType = mediaType?.let(mediaTypes::of),
            airingStatus = airingStatuses.of(airingStatus),
            malEpisodes = episodes,
            startDate = startDate?.let(::partialDateOf),
            broadcast = broadcastDay?.let { Broadcast(DayOfWeek.of(it), broadcastTime?.let(LocalTime::parse)) },
            meanScore = meanScore,
            rank = rank,
            genres = genres?.let(::genresOf),
            averageEpisodeDuration = episodeDuration?.let(::durationOf),
        )

    companion object {
        const val TABLE = "title"
    }
}

internal fun Anime.toRow(): TitleRow =
    TitleRow(
        id = id.value,
        romaji = titles.romaji,
        english = titles.english,
        japanese = titles.japanese,
        synonyms = textsToColumn(titles.synonyms),
        posterUrl = posterUrl,
        largePosterUrl = largePosterUrl,
        mediaType = mediaType?.let(mediaTypes::toColumn),
        airingStatus = airingStatuses.toColumn(airingStatus),
        episodes = malEpisodes,
        startDate = startDate?.toColumn(),
        broadcastDay = broadcast?.dayOfWeek?.value,
        broadcastTime = broadcast?.japanTime?.toString(),
        meanScore = meanScore,
        rank = rank,
        genres = genres?.let(::genresToColumn),
        episodeDuration = averageEpisodeDuration?.toColumn(),
    )

internal data class StatisticsColumns(
    val watching: Int,
    val completed: Int,
    @ColumnInfo(name = "on_hold") val onHold: Int,
    val dropped: Int,
    val planned: Int,
    val members: Int,
)

// What only a title's own page shows, and when MAL was last asked for it.
@Entity(
    tableName = "title_details",
    foreignKeys = [
        ForeignKey(TitleRow::class, parentColumns = ["id"], childColumns = ["title_id"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class TitleDetailsRow(
    @PrimaryKey @ColumnInfo(name = "title_id") val titleId: Long,
    val synopsis: String?,
    @ColumnInfo(name = "end_date") val endDate: String?,
    @ColumnInfo(name = "season_year") val seasonYear: Int?,
    val season: String?,
    val source: String?,
    val studios: String,
    val rating: String?,
    @Embedded(prefix = "statistics_") val statistics: StatisticsColumns?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
) {
    // The same rule as a summary's; the season's year and name are one value.
    fun mergedWith(newer: TitleDetailsRow): TitleDetailsRow =
        newer.copy(
            synopsis = newer.synopsis ?: synopsis,
            endDate = newer.endDate ?: endDate,
            seasonYear = newer.seasonYear ?: seasonYear,
            season = if (newer.seasonYear == null) season else newer.season,
            source = newer.source ?: source,
            rating = newer.rating ?: rating,
            statistics = newer.statistics ?: statistics,
        )
}

internal fun AnimeDetails.toRow(fetchedAt: Instant): TitleDetailsRow =
    TitleDetailsRow(
        titleId = id.value,
        synopsis = synopsis,
        endDate = endDate?.toColumn(),
        seasonYear = season?.year,
        season = season?.season?.let(seasons::toColumn),
        source = source?.let(sources::toColumn),
        studios = textsToColumn(studios),
        rating = rating?.let(ageRatings::toColumn),
        statistics =
            statistics?.let {
                val counts = it.statuses
                StatisticsColumns(counts.watching, counts.completed, counts.onHold, counts.dropped, counts.planned, it.members)
            },
        fetchedAt = fetchedAt.toColumn(),
    )

// One related title of a title, in the place MAL gave it. A title that is named here cannot be deleted by itself.
@Entity(
    tableName = "title_relation",
    primaryKeys = ["title_id", "position"],
    foreignKeys = [
        ForeignKey(TitleRow::class, parentColumns = ["id"], childColumns = ["title_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TitleRow::class, parentColumns = ["id"], childColumns = ["related_id"]),
    ],
    indices = [Index("related_id")],
)
internal data class TitleRelationRow(
    @ColumnInfo(name = "title_id") val titleId: Long,
    val position: Int,
    @ColumnInfo(name = "related_id") val relatedId: Long,
    val relation: String,
)

// One title recommended from a title, in the place MAL gave it; it cannot be deleted by itself either.
@Entity(
    tableName = "title_recommendation",
    primaryKeys = ["title_id", "position"],
    foreignKeys = [
        ForeignKey(TitleRow::class, parentColumns = ["id"], childColumns = ["title_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TitleRow::class, parentColumns = ["id"], childColumns = ["recommended_id"]),
    ],
    indices = [Index("recommended_id")],
)
internal data class TitleRecommendationRow(
    @ColumnInfo(name = "title_id") val titleId: Long,
    val position: Int,
    @ColumnInfo(name = "recommended_id") val recommendedId: Long,
)

internal data class RelatedTitle(
    @Embedded val link: TitleRelationRow,
    @Relation(parentColumn = "related_id", entityColumn = "id") val title: TitleRow,
)

internal data class RecommendedTitle(
    @Embedded val link: TitleRecommendationRow,
    @Relation(parentColumn = "recommended_id", entityColumn = "id") val title: TitleRow,
)

// A title's details with the summaries of the titles they name, read as one.
internal data class DetailsWithTitles(
    @Embedded val details: TitleDetailsRow,
    @Relation(entity = TitleRelationRow::class, parentColumn = "title_id", entityColumn = "title_id")
    val related: List<RelatedTitle>,
    @Relation(entity = TitleRecommendationRow::class, parentColumn = "title_id", entityColumn = "title_id")
    val recommendations: List<RecommendedTitle>,
) {
    // A named title whose row cannot be read is left out, and [unexpected] hears of it.
    fun toDetails(unexpected: (Throwable) -> Unit): AnimeDetails =
        AnimeDetails(
            id = AnimeId(details.titleId),
            synopsis = details.synopsis,
            endDate = details.endDate?.let(::partialDateOf),
            season = details.seasonYear?.let { year -> details.season?.let { AnimeSeason(year, seasons.of(it)) } },
            source = details.source?.let(sources::of),
            studios = textsOf(details.studios),
            rating = details.rating?.let(ageRatings::of),
            statistics =
                details.statistics?.let {
                    TitleStatistics(StatusCounts(it.watching, it.completed, it.onHold, it.dropped, it.planned), it.members)
                },
            related =
                related.sortedBy { it.link.position }.mapNotNull {
                    readable(unexpected) { RelatedAnime(it.title.toAnime(), relationTypes.of(it.link.relation)) }
                },
            recommendations = recommendations.sortedBy { it.link.position }.mapNotNull { readable(unexpected) { it.title.toAnime() } },
        )
}
