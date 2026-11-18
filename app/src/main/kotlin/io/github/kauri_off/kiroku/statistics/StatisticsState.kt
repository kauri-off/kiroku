package io.github.kauri_off.kiroku.statistics

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.user.UserListProgress

// The pages the Profile's statistics rows open.
enum class StatisticsPage { Scores, Genres, FormatsAndYears }

data class StatisticsState(
    val page: StatisticsPage,
    // Whose statistics: another user's name, or null for the signed-in user.
    val name: String?,
    val content: StatisticsContent,
    val sessionExpired: Boolean = false,
) {
    init {
        require(content !is StatisticsContent.SignedOut || name == null)
        require(content !is StatisticsContent.Reading || name != null)
    }
}

sealed interface StatisticsContent {
    data object Loading : StatisticsContent

    data object SignedOut : StatisticsContent

    // Another user's list, not whole yet, so no statistics are shown over it.
    data class Reading(
        val progress: UserListProgress,
    ) : StatisticsContent

    data class Failed(
        val error: DataError,
    ) : StatisticsContent

    data class Shown(
        val statistics: UserStatistics,
    ) : StatisticsContent
}

sealed interface StatisticsEvent {
    data object Back : StatisticsEvent

    data object Retry : StatisticsEvent

    data object SignIn : StatisticsEvent
}
