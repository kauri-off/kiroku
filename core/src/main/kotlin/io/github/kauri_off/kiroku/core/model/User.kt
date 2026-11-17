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

// Whose list the session's is: nobody's signed out, its sign-in's also once expired (S6).
val Session.owner: SignInId?
    get() =
        when (this) {
            Session.SignedOut -> null
            is Session.SignedIn -> signIn
            is Session.Expired -> signIn
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
    val watching: Int?,
    val completed: Int?,
    val onHold: Int?,
    val dropped: Int?,
    val planned: Int?,
    val entries: Int?,
    val episodes: Int?,
    val rewatched: Int?,
    val daysWatched: Double?,
    // Null also when the source has nothing scored to average.
    val meanScore: Double?,
)

// Made only by the statistics calculator, and by the fixtures as what a test expects of it.
@ConsistentCopyVisibility
data class UserStatistics internal constructor(
    // Every title on the list, of a status the app does not know too.
    val titleCount: Int,
    val statuses: StatusCounts,
    val daysWatched: Double,
    val meanScore: Double?,
    val scores: Map<Int, Int>,
    val genres: List<Counted<Genre>>,
    val mediaTypes: List<Counted<MediaType>>,
    val years: Map<Int, Int>,
) {
    init {
        require(titleCount >= statuses.total)
        require(daysWatched >= 0.0)
        require(meanScore == null || meanScore in 1.0..10.0)
        require(scores.keys.all { it in 1..10 })
        require(scored <= titleCount)
    }

    // The titles that have the user's score, and those that do not.
    val scored: Int get() = scores.values.sum()

    val unscored: Int get() = titleCount - scored

    // The start years by decade, as the decade's first year, newest first.
    val decades: List<Counted<Int>>
        get() =
            years.entries
                .groupBy({ it.key.floorDiv(DECADE) * DECADE }, { it.value })
                .map { (decade, counts) -> Counted(decade, counts.sum()) }
                .sortedByDescending { it.value }

    private companion object {
        const val DECADE = 10
    }
}

@ConsistentCopyVisibility
data class Counted<out T> internal constructor(
    val value: T,
    val count: Int,
)
