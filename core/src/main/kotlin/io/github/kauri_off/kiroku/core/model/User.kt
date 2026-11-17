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
