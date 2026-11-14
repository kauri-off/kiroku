package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

// How a value is written in a column. Every form is part of the schema: changing one is a migration.

// A stored row that cannot be read, without its message, which could quote the list: the failure's type and stack.
internal class UnreadableRow(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}

// What [read] makes of a row, or nothing for a row that cannot be read, which [unexpected] hears of.
internal inline fun <T : Any> readable(
    unexpected: (Throwable) -> Unit,
    read: () -> T,
): T? =
    try {
        read()
    } catch (e: Exception) {
        unexpected(UnreadableRow(e))
        null
    }

private const val NANOS_PER_SECOND = 1_000_000_000L

// An instant is its nanoseconds since the epoch, so it comes back exactly and sorts in a query.
internal fun Instant.toColumn(): Long = Math.addExact(Math.multiplyExact(epochSecond, NANOS_PER_SECOND), nano.toLong())

internal fun instantOf(column: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(column, NANOS_PER_SECOND), Math.floorMod(column, NANOS_PER_SECOND))

internal fun Duration.toColumn(): Long = toNanos()

internal fun durationOf(column: Long): Duration = Duration.ofNanos(column)

// A date keeps its precision: "2024", "2024-05" or "2024-05-03".
internal fun PartialDate.toColumn(): String =
    when (this) {
        is PartialDate.Day -> date.toString()
        is PartialDate.Month -> yearMonth.toString()
        is PartialDate.OnlyYear -> value.toString()
    }

internal fun partialDateOf(column: String): PartialDate =
    when (column.drop(1).count { it == '-' }) {
        0 -> PartialDate.OnlyYear(Year.parse(column))
        1 -> PartialDate.Month(YearMonth.parse(column))
        else -> PartialDate.Day(LocalDate.parse(column))
    }

// The codes of one type's values, MAL's own. One the app does not recognise is stored as MAL gave it, so a version that knows it reads it.
internal class Codes<T : Any>(
    known: List<T>,
    private val unrecognized: ((String) -> T)? = null,
    private val code: (T) -> String,
) {
    private val values = known.associateBy(code)

    init {
        check(values.size == known.size)
    }

    // An unrecognised value under a known code is refused: it would read back as another value.
    fun toColumn(value: T): String = code(value).also { require((values[it] ?: value) == value) }

    fun of(column: String): T = values[column] ?: unrecognized?.invoke(column) ?: error("No value has the stored code")
}

// Codes no recording holds: g, pg, r+, winter, dropped, spin_off, parent_story, full_story, alternative_setting, every source but four.
internal val listStatuses =
    Codes(ListStatus.entries) {
        when (it) {
            ListStatus.Watching -> "watching"
            ListStatus.Planned -> "plan_to_watch"
            ListStatus.Completed -> "completed"
            ListStatus.OnHold -> "on_hold"
            ListStatus.Dropped -> "dropped"
        }
    }

internal val airingStatuses =
    Codes(AiringStatus.entries) {
        when (it) {
            AiringStatus.NotYetAired -> "not_yet_aired"
            AiringStatus.Airing -> "currently_airing"
            AiringStatus.Finished -> "finished_airing"
        }
    }

internal val seasons =
    Codes(Season.entries) {
        when (it) {
            Season.Winter -> "winter"
            Season.Spring -> "spring"
            Season.Summer -> "summer"
            Season.Fall -> "fall"
        }
    }

internal val mediaTypes =
    Codes(
        listOf(
            MediaType.Tv,
            MediaType.TvSpecial,
            MediaType.Movie,
            MediaType.Ova,
            MediaType.Ona,
            MediaType.Special,
            MediaType.Music,
            MediaType.Commercial,
            MediaType.PromotionalVideo,
        ),
        MediaType::Unrecognized,
    ) {
        when (it) {
            MediaType.Tv -> "tv"
            MediaType.TvSpecial -> "tv_special"
            MediaType.Movie -> "movie"
            MediaType.Ova -> "ova"
            MediaType.Ona -> "ona"
            MediaType.Special -> "special"
            MediaType.Music -> "music"
            MediaType.Commercial -> "cm"
            MediaType.PromotionalVideo -> "pv"
            is MediaType.Unrecognized -> it.malCode
        }
    }

internal val sources =
    Codes(
        listOf(
            Source.Original,
            Source.Manga,
            Source.FourKomaManga,
            Source.WebManga,
            Source.DigitalManga,
            Source.Novel,
            Source.LightNovel,
            Source.WebNovel,
            Source.VisualNovel,
            Source.Game,
            Source.CardGame,
            Source.Book,
            Source.PictureBook,
            Source.Radio,
            Source.Music,
            Source.MixedMedia,
            Source.Other,
        ),
        Source::Unrecognized,
    ) {
        when (it) {
            Source.Original -> "original"
            Source.Manga -> "manga"
            Source.FourKomaManga -> "4_koma_manga"
            Source.WebManga -> "web_manga"
            Source.DigitalManga -> "digital_manga"
            Source.Novel -> "novel"
            Source.LightNovel -> "light_novel"
            Source.WebNovel -> "web_novel"
            Source.VisualNovel -> "visual_novel"
            Source.Game -> "game"
            Source.CardGame -> "card_game"
            Source.Book -> "book"
            Source.PictureBook -> "picture_book"
            Source.Radio -> "radio"
            Source.Music -> "music"
            Source.MixedMedia -> "mixed_media"
            Source.Other -> "other"
            is Source.Unrecognized -> it.malCode
        }
    }

internal val ageRatings =
    Codes(
        listOf(
            AgeRating.AllAges,
            AgeRating.Children,
            AgeRating.Teens13,
            AgeRating.Violence17,
            AgeRating.MildNudity,
            AgeRating.Hentai,
        ),
        AgeRating::Unrecognized,
    ) {
        when (it) {
            AgeRating.AllAges -> "g"
            AgeRating.Children -> "pg"
            AgeRating.Teens13 -> "pg_13"
            AgeRating.Violence17 -> "r"
            AgeRating.MildNudity -> "r+"
            AgeRating.Hentai -> "rx"
            is AgeRating.Unrecognized -> it.malCode
        }
    }

internal val relationTypes =
    Codes(RelationType.known, RelationType::Unrecognized) {
        when (it) {
            RelationType.Prequel -> "prequel"
            RelationType.Sequel -> "sequel"
            RelationType.ParentStory -> "parent_story"
            RelationType.FullStory -> "full_story"
            RelationType.SideStory -> "side_story"
            RelationType.SpinOff -> "spin_off"
            RelationType.AlternativeSetting -> "alternative_setting"
            RelationType.AlternativeVersion -> "alternative_version"
            RelationType.Summary -> "summary"
            RelationType.Other -> "other"
            RelationType.Character -> "character"
            is RelationType.Unrecognized -> it.malCode
        }
    }

@Serializable
private class StoredGenre(
    val id: Int,
    val name: String,
)

private val texts = ListSerializer(String.serializer())
private val storedGenres = ListSerializer(StoredGenre.serializer())

// A list in one column is a JSON array.
internal fun textsToColumn(values: List<String>): String = Json.encodeToString(texts, values)

internal fun textsOf(column: String): List<String> = Json.decodeFromString(texts, column)

internal fun genresToColumn(values: List<Genre>): String = Json.encodeToString(storedGenres, values.map { StoredGenre(it.id, it.malName) })

internal fun genresOf(column: String): List<Genre> = Json.decodeFromString(storedGenres, column).map { Genre(it.id, it.name) }
