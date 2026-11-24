package io.github.kauri_off.kiroku.data.profile

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.StatedFigures
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.given
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.authorised
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// What MAL gives of the signed-in user beside the list, asked as that user through the session (S5).
class MalProfiles(
    private val http: MalHttp,
    private val session: MalSession,
) {
    private val api by http.api(ProfileApi::class)

    // An answer read with values left out is reported; one without the user's name cannot be used.
    internal suspend fun own(expectedOwner: SignInId? = null): Outcome<UserProfile> {
        val answer =
            session.authorised(http, expectedOwner) { authorization ->
                val repairs = Repairs()
                api.own(MalFields.PROFILE, authorization).toProfile(repairs) to repairs
            }
        if (answer !is Outcome.Success) return answer as Outcome.Failure
        val (profile, repairs) = answer.value
        if (repairs.fields.isNotEmpty()) http.unexpected(RepairedAnswer(repairs.fields))
        return Outcome.Success(profile)
    }
}

private interface ProfileApi {
    @GET("users/@me")
    suspend fun own(
        @Query("fields") fields: String,
        @Header(AUTHORIZATION) authorization: String,
    ): JsonObject
}

// MAL's own empty values are absent; an unreadable value is left out, and noted in [repairs].
internal fun JsonObject.toProfile(repairs: Repairs): UserProfile =
    UserProfile(
        name = requireNotNull(text("name")),
        pictureUrl = repairs.read("picture", get("picture").given(), text("picture")),
        joined = repairs.read("joined_at", get("joined_at").given(), text("joined_at")?.let(::dayOf)),
        stated = get("anime_statistics")?.let { figuresOf(it, repairs) },
    )

private fun JsonObject.text(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return value.content.takeIf { value.isString && it.isNotBlank() }
}

// The day in the offset MAL gives.
private fun dayOf(text: String): LocalDate? =
    try {
        OffsetDateTime.parse(text).toLocalDate()
    } catch (_: DateTimeParseException) {
        null
    }

// Each figure MAL states, an unreadable one left out and noted; none when it states none.
private fun figuresOf(
    element: JsonElement,
    repairs: Repairs,
): StatedFigures? {
    if (!element.given()) return null
    val statistics = element as? JsonObject
    if (statistics == null) {
        repairs.note("anime_statistics")
        return null
    }
    val within = repairs.within("anime_statistics")

    fun <T : Any> figure(
        key: String,
        read: (JsonPrimitive) -> T?,
    ): T? {
        val given = statistics[key]
        return within.read(key, given.given(), (given as? JsonPrimitive)?.let(read))
    }

    fun count(key: String): Int? = figure(key) { it.intOrNull?.takeIf { count -> count >= 0 } }

    // MAL states a mean of 0 for a list with nothing scored.
    val mean = figure("mean_score") { it.doubleOrNull?.takeIf { mean -> mean in 0.0..10.0 } }?.takeIf { it > 0.0 }
    val figures =
        StatedFigures(
            watching = count("num_items_watching"),
            completed = count("num_items_completed"),
            onHold = count("num_items_on_hold"),
            dropped = count("num_items_dropped"),
            planned = count("num_items_plan_to_watch"),
            entries = count("num_items"),
            episodes = count("num_episodes"),
            rewatched = count("num_times_rewatched"),
            daysWatched = figure("num_days_watched") { it.doubleOrNull?.takeIf { days -> days >= 0.0 } },
            meanScore = mean,
        )
    return figures.takeIf { it != None }
}

// No figure stated.
private val None = StatedFigures(null, null, null, null, null, null, null, null, null, null)
