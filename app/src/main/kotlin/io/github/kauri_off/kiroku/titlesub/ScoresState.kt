package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.score.ScoreDistribution

// MAL's score distribution of one title, asked of a service apart from the title's page when this screen is shown.
// Every state has the top bar, so every state shows the session banner while the session is expired (S6).
sealed interface ScoresState {
    // Null until the session is known.
    val session: Session?

    data class Loading(
        override val session: Session?,
    ) : ScoresState

    // A distribution with no votes is a title nobody scored.
    data class Shown(
        val distribution: ScoreDistribution,
        override val session: Session?,
    ) : ScoresState

    // The service has no record of the title.
    data class NotKnown(
        override val session: Session?,
    ) : ScoresState

    data class Failed(
        val error: DataError,
        override val session: Session?,
    ) : ScoresState
}

val ScoresState.sessionExpired: Boolean get() = session is Session.Expired

sealed interface ScoresEvent {
    data object Back : ScoresEvent

    data object Retry : ScoresEvent

    data object SignIn : ScoresEvent
}
