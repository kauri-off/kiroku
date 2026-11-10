package io.github.kauri_off.kiroku.core.model

import java.time.LocalDate

sealed interface Session {
    data object SignedOut : Session

    data class SignedIn(
        val userName: String,
    ) : Session

    data object Expired : Session
}

data class UserProfile(
    val name: String,
    val pictureUrl: String?,
    val joined: LocalDate?,
)

data class UserStatistics(
    val statuses: StatusCounts,
    val daysWatched: Double,
    val meanScore: Double?,
    val scores: Map<Int, Int>,
    val genres: List<Counted<Genre>>,
    val mediaTypes: List<Counted<MediaType>>,
    val years: Map<Int, Int>,
)

data class Counted<out T>(
    val value: T,
    val count: Int,
)
