package io.github.kauri_off.kiroku.statistics

import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.text.genreNames

// The statistics pages' states over the fixture set, every number the calculator's, for screenshots and tests.
object StatisticsFixtureStates {
    val set = Fixtures.reference

    val otherName: String get() = set.otherUserName

    val own: UserStatistics by lazy { statisticsOf(set.entries) }

    // Another user's list of several thousand titles.
    val large: UserStatistics by lazy { statisticsOf(set.otherUserList) }

    val nothing: UserStatistics by lazy { statisticsOf(emptyList()) }

    // The demo list with no title scored.
    val nothingScored: UserStatistics by lazy {
        statisticsOf(set.entries.map { it.copy(values = it.values.copy(score = null)) })
    }

    // The demo list as if the source named no genre, format or start date of any title.
    val nothingKnown: UserStatistics by lazy {
        statisticsOf(set.entries.map { it.copy(anime = it.anime.copy(mediaType = null, genres = null, startDate = null)) })
    }

    // One title with one genre, one score, one format and one start year.
    val single: UserStatistics by lazy {
        val entry = set.entries.first { it.values.score != null && it.anime.mediaType != null && it.anime.startDate != null }
        statisticsOf(
            listOf(
                entry.copy(
                    anime =
                        entry.anime.copy(
                            posterUrl = null,
                            genres =
                                entry.anime.genres
                                    .orEmpty()
                                    .take(1),
                        ),
                ),
            ),
        )
    }

    // Every genre, theme and demographic the app names, each counted a different number of times: the longest list of
    // genres, with the longest names.
    val everyGenre: UserStatistics by lazy {
        val genres = genreNames.keys.map { Genre(it, it.toString()) }
        val base = set.entries.first()
        statisticsOf(
            genres.indices.map { index ->
                base.copy(anime = base.anime.copy(posterUrl = null, genres = genres.drop(index)))
            },
        )
    }

    fun shown(
        page: StatisticsPage,
        statistics: UserStatistics = own,
        name: String? = null,
    ) = StatisticsState(page, name, StatisticsContent.Shown(statistics))

    fun of(
        page: StatisticsPage,
        content: StatisticsContent,
        name: String? = null,
    ) = StatisticsState(page, name, content)

    fun reading(
        page: StatisticsPage,
        loaded: Int,
        total: Int?,
    ) = StatisticsState(page, otherName, StatisticsContent.Reading(UserListProgress(loaded, total)))
}
