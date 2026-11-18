package io.github.kauri_off.kiroku.data.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.StatedFigures
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.sandbox.SandboxFixture
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

// The profile's client and mapper over the sandbox's answer, which is held to the owner's recording, with one value changed.
class MalProfilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    @get:Rule
    internal val sandbox = SandboxFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }

    @After
    fun close() = runBlocking { sessions.close() }

    private val given: JsonObject by lazy {
        val (status, body) = sandbox.ask("GET", "users/@me?fields=${MalFields.PROFILE}")
        check(status == 200)
        MalJson.parseToJsonElement(body).jsonObject
    }

    private fun own(answer: JsonObject): Outcome<*> {
        network.always(answer(body = answer.toString()))
        val session = sessions.open()
        runBlocking { session.signIn(TokenGrant(ACCESS, "refresh")) }
        return runBlocking { MalProfiles(network.http, session).own() }
    }

    private fun mapped(answer: JsonObject): Pair<UserProfile, Set<String>> {
        val repairs = Repairs()
        return answer.toProfile(repairs) to repairs.fields
    }

    @Test
    fun `the profile is asked as the signed-in user, with the profile's fields, and read whole`() {
        val outcome = own(given)

        val request = network.api.received()
        assertEquals("/v2/users/@me", request.url.encodedPath)
        assertEquals(MalFields.PROFILE, request.url.queryParameter("fields"))
        assertEquals("Bearer $ACCESS", request.headers[AUTHORIZATION])
        val profile = (outcome as Outcome.Success).value as UserProfile
        assertNotNull(profile.joined)
        assertNotNull(profile.stated)
        assertEquals(emptyList<Throwable>(), network.unexpected)
    }

    @Test
    fun `a value that cannot be read is left out, and the answer is reported by its fields alone`() {
        val odd =
            given
                .edited(
                    listOf("joined_at"),
                    JsonPrimitive("yesterday"),
                ).edited(listOf("anime_statistics", "num_items"), JsonPrimitive("x"))

        val profile = (own(odd) as Outcome.Success).value as UserProfile

        assertNull(profile.joined)
        assertEquals(mapped(given).first.stated?.copy(entries = null), profile.stated)
        assertEquals(given.getValue("name").let { (it as JsonPrimitive).content }, profile.name)
        val reported = network.unexpected.single()
        assertTrue(reported is RepairedAnswer)
        assertEquals(setOf("joined_at", "anime_statistics.num_items"), (reported as RepairedAnswer).fields)
    }

    @Test
    fun `an answer without the user's name cannot be used`() {
        assertEquals(Outcome.Failure(DataError.ServerError), own(given.edited(listOf("name"))))
        assertTrue(network.unexpected.single() is UnusableAnswer)
    }

    @Test
    fun `MAL's empty values are absent and not reported, and a value of another kind is`() {
        val picture = "https://example.invalid/picture.jpg"
        assertEquals(
            picture to emptySet<String>(),
            mapped(JsonObject(given + ("picture" to JsonPrimitive(picture)))).let {
                it.first.pictureUrl to
                    it.second
            },
        )
        assertEquals(emptySet<String>(), mapped(JsonObject(given + ("picture" to JsonPrimitive("")))).second)
        assertEquals(emptySet<String>(), mapped(given.edited(listOf("anime_statistics"))).second)
        assertEquals(setOf("picture"), mapped(JsonObject(given + ("picture" to JsonPrimitive(3)))).second)
        val odd =
            mapped(
                given
                    .edited(listOf("anime_statistics", "num_items"), JsonPrimitive(-1))
                    .edited(listOf("anime_statistics", "num_days_watched"), JsonPrimitive("many")),
            )
        assertEquals(setOf("anime_statistics.num_items", "anime_statistics.num_days_watched"), odd.second)
        assertEquals(whole.copy(entries = null, daysWatched = null), odd.first.stated)
        assertEquals(setOf("anime_statistics"), mapped(given.edited(listOf("anime_statistics"), JsonPrimitive(7))).second)
    }

    private val whole: StatedFigures get() = checkNotNull(mapped(given).first.stated)

    private fun withMean(mean: JsonPrimitive) = mapped(given.edited(listOf("anime_statistics", "mean_score"), mean))

    @Test
    fun `a mean of 0 is nothing scored and not reported, and a mean above 10 is reported`() {
        assertEquals(whole.copy(meanScore = 7.5) to emptySet<String>(), withMean(JsonPrimitive(7.5)).let { it.first.stated to it.second })
        assertEquals(whole.copy(meanScore = null) to emptySet<String>(), withMean(JsonPrimitive(0)).let { it.first.stated to it.second })
        assertEquals(
            whole.copy(meanScore = null) to setOf("anime_statistics.mean_score"),
            withMean(JsonPrimitive(10.01)).let { it.first.stated to it.second },
        )
    }

    @Test
    fun `a null figure is left out alone, and a fraction where MAL gives a count is left out alone and reported`() {
        val episodes = { value: JsonElement -> mapped(given.edited(listOf("anime_statistics", "num_episodes"), value)) }
        assertEquals(whole.copy(episodes = null) to emptySet<String>(), episodes(JsonNull).let { it.first.stated to it.second })
        assertEquals(
            whole.copy(episodes = null) to setOf("anime_statistics.num_episodes"),
            episodes(JsonPrimitive(12.5)).let { it.first.stated to it.second },
        )
    }

    @Test
    fun `the join date is the day in the offset MAL gives, not in UTC`() {
        val late = mapped(given.edited(listOf("joined_at"), JsonPrimitive("2020-03-04T23:30:00-05:00")))
        assertEquals(LocalDate.of(2020, 3, 4) to emptySet<String>(), late.first.joined to late.second)
    }

    @Test
    fun `a field the mapper does not know never fails it and is not reported`() {
        val unknown = JsonObject(mapOf("kind" to JsonArray(listOf(JsonNull)), "more" to JsonPrimitive("x")))
        val statistics = JsonObject(given.getValue("anime_statistics").jsonObject + ("num_days_unknown" to unknown))
        val answer = JsonObject(given + ("location" to unknown) + ("anime_statistics" to statistics))
        assertEquals(mapped(given), mapped(answer))
    }

    private companion object {
        const val ACCESS = "access-token"
    }
}
