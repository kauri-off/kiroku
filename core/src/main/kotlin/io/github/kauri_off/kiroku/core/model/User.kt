package io.github.kauri_off.kiroku.core.model

import java.time.LocalDate

sealed interface Session {
    data object SignedOut : Session

    // One sign-in: a refresh of its tokens keeps it, signing in again makes a new one.
    data class SignedIn(
        val signIn: SignInId,
    ) : Session

    // The sign-in whose tokens MAL no longer takes; its list stays its own.
    data class Expired(
        val signIn: SignInId,
    ) : Session
}

@JvmInline
value class SignInId(
    val value: String,
)

data class UserProfile(
    val name: String,
    val pictureUrl: String?,
    val joined: LocalDate?,
    val stated: StatedFigures? = null,
)

// What the profile's source states of the user's list, as it states it; the app's own statistics are the calculator's.
data class StatedFigures(
    val watching: Int,
    val completed: Int,
    val onHold: Int,
    val dropped: Int,
    val planned: Int,
    val entries: Int,
    val episodes: Int,
    val rewatched: Int,
    val daysWatched: Double,
    // Zero when the source has no score to average.
    val meanScore: Double,
)

// Made only by the statistics calculator, and by the fixtures as what a test expects of it.
@ConsistentCopyVisibility
data class UserStatistics internal constructor(
    val statuses: StatusCounts,
    val daysWatched: Double,
    val meanScore: Double?,
    val scores: Map<Int, Int>,
    val genres: List<Counted<Genre>>,
    val mediaTypes: List<Counted<MediaType>>,
    val years: Map<Int, Int>,
) {
    init {
        require(daysWatched >= 0.0)
        require(meanScore == null || meanScore in 1.0..10.0)
        require(scores.keys.all { it in 1..10 })
    }
}

@ConsistentCopyVisibility
data class Counted<out T> internal constructor(
    val value: T,
    val count: Int,
)
