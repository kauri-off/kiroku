package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.RecordedAt
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.json
import io.github.kauri_off.kiroku.data.anilist.AniListServer.Companion.reply
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration as KotlinDuration

// When the client asks AniList: the spacing, its refusals and their block across processes, and the cool-down of failures.
@OptIn(ExperimentalCoroutinesApi::class)
class AniListPaceTest {
    private val network = NetworkFixture()
    private val aniList = AniListServer(network)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(network).around(aniList)

    private val since: Instant = RecordedAt.minus(Duration.ofHours(75))

    private suspend fun TestScope.fetch(vararg ids: Int): Map<AnimeId, Outcome<FetchedAiring?>> =
        aniList.client(this).fetch(ids.map(::titleWithId), since)

    private val spacing: Long = AniListPace.spacingFor(AniListRecordings.limit).inWholeMilliseconds

    private fun gaps(): List<Long> = aniList.requests.map { it.second }.zipWithNext { a, b -> b - a }

    private fun refused(retryAfter: String? = null): MockResponse =
        reply(429, "", headers = retryAfter?.let { mapOf("Retry-After" to it) }.orEmpty())

    // After [hold] since the refusal the client asks again, and not a millisecond sooner.
    private suspend fun TestScope.heldFor(hold: KotlinDuration) {
        val sent = aniList.requests.size
        advanceTimeBy(hold - 1.milliseconds)
        assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))
        assertEquals("$hold", sent, aniList.requests.size)
        advanceTimeBy(1.milliseconds)
        fetch(1)
        assertEquals("$hold", sent + 1, aniList.requests.size)
    }

    @Test
    fun `a request cancelled before its answer still holds the next one back`() =
        runTest {
            val client = aniList.client(this)
            aniList.answer = { MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).build() }
            for (sent in 1..3) {
                val call = launch { client.fetch(listOf(titleWithId(1)), since) }
                val until = System.nanoTime() + 5_000_000_000
                while (aniList.requests.size < sent && System.nanoTime() < until) {
                    advanceUntilIdle()
                    Thread.sleep(5)
                }
                call.cancel()
                call.join()
            }

            assertEquals(3, aniList.requests.size)
            assertTrue(gaps().toString(), gaps().all { it >= spacing })
        }

    @Test
    fun `calls made at once are spaced as calls made one after another`() =
        runTest {
            val client = aniList.client(this)
            aniList.answer = { copiesOf(it) }

            val answers = (1..3).map { id -> async { client.fetch(listOf(titleWithId(id)), since) } }.awaitAll()

            assertTrue(answers.all { it.values.single() is Outcome.Success })
            assertEquals(3, aniList.requests.size)
            assertTrue(gaps().toString(), gaps().all { it >= spacing })
        }

    @Test
    fun `a Retry-After given as a date holds requests back until then`() =
        runTest {
            val at = network.clock.now.plusSeconds(300)
            aniList.answer = { refused(DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC))) }
            fetch(1)

            heldFor(300.seconds)
        }

    @Test
    fun `a Retry-After longer than an hour is honoured in full, up to a day`() =
        runTest {
            aniList.answer = { refused("7200") }
            fetch(1)
            aniList.answer = { refused("${10.days.inWholeSeconds}") }
            heldFor(2.hours)
            heldFor(AniListPace.LongestRetryAfter)
        }

    @Test
    fun `refusals in a row double the wait up to an hour, and an answer starts it again`() =
        runTest {
            aniList.answer = { refused() }
            fetch(1)
            for (minutes in listOf(1, 2, 4, 8, 16, 32, 60, 60)) heldFor(minutes.minutes)

            aniList.answer = { copiesOf(it) }
            heldFor(AniListPace.LongestRefusal)
            aniList.answer = { refused() }
            advanceTimeBy(spacing.milliseconds)
            fetch(1)
            heldFor(AniListPace.FirstRefusal)
        }

    @Test
    fun `a refusal outlives the process, and a new one sends nothing until it ends`() =
        runTest {
            aniList.answer = { refused("600") }
            fetch(1)
            assertTrue(aniList.blockFile.exists())

            aniList.newProcess(this)
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))
            assertEquals(1, aniList.requests.size)

            network.clock.now = network.clock.now.plusSeconds(599)
            aniList.newProcess(this)
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))
            assertEquals(1, aniList.requests.size)

            network.clock.now = network.clock.now.plusSeconds(1)
            aniList.answer = { copiesOf(it) }
            aniList.newProcess(this)
            assertTrue(fetch(1).of(1) is Outcome.Success)
            assertEquals(2, aniList.requests.size)
            assertFalse(aniList.blockFile.exists())
        }

    @Test
    fun `a clock set back between processes cannot make a refusal hold longer than it was set`() =
        runTest {
            aniList.answer = { refused("600") }
            fetch(1)
            network.clock.now = network.clock.now.minus(Duration.ofDays(1))
            aniList.newProcess(this)
            aniList.answer = { copiesOf(it) }
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))

            heldFor(600.seconds)
        }

    @Test
    fun `a clock that stays set back does not start a refusal's hold again for the processes after the first`() =
        runTest {
            aniList.answer = { refused("600") }
            fetch(1)
            network.clock.now = network.clock.now.minus(Duration.ofDays(1))
            aniList.newProcess(this)
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))

            network.clock.now = network.clock.now.plusSeconds(400)
            aniList.newProcess(this)
            aniList.answer = { copiesOf(it) }
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))
            heldFor(200.seconds)
        }

    // A real refusal is not ended early by a clock that was wrong for a while.
    @Test
    fun `a clock set back as a process starts and then put right leaves a refusal's hold as it was set`() =
        runTest {
            aniList.answer = { refused("21600") }
            fetch(1)
            network.clock.now =
                network.clock.now
                    .plusSeconds(60)
                    .minus(Duration.ofDays(1))
            aniList.newProcess(this)
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))

            network.clock.now =
                network.clock.now
                    .plusSeconds(60)
                    .plus(Duration.ofDays(1))
            aniList.newProcess(this)
            aniList.answer = { copiesOf(it) }
            assertEquals(Outcome.Failure(DataError.Refused), fetch(1).of(1))
            heldFor(6.hours - 2.minutes)
        }

    @Test
    fun `a block file that cannot be read holds nothing back and is reported`() =
        runTest {
            aniList.blockFile.writeText("soon")
            aniList.answer = { copiesOf(it) }

            assertTrue(fetch(1).of(1) is Outcome.Success)
            assertEquals(listOf("block"), network.unexpected.map { it.message })
            assertFalse(aniList.blockFile.exists())

            aniList.newProcess(this)
            assertTrue(fetch(2).of(2) is Outcome.Success)
            assertEquals(listOf("block"), network.unexpected.map { it.message })
        }

    @Test
    fun `a 408 is a failure of the server`() =
        runTest {
            aniList.answer = { reply(408, "") }

            assertEquals(Outcome.Failure(DataError.ServerError), fetch(1).of(1))
        }

    @Test
    fun `a title that failed is answered with its failure without a request until its cool-down ends`() =
        runTest {
            aniList.answer = { if (1 in it.ids) reply(500, "") else copiesOf(it) }
            fetch(1)

            advanceTimeBy(FailureCoolDown - 1.milliseconds)
            val answers = fetch(1, 2)
            assertEquals(Outcome.Failure(DataError.ServerError), answers.of(1))
            assertTrue(answers.of(2) is Outcome.Success)
            assertEquals(listOf(listOf(1), listOf(2)), aniList.asked().map { it.ids })

            advanceTimeBy(1.milliseconds)
            fetch(1)
            assertEquals(listOf(1), aniList.asked().last().ids)
        }

    @Test
    fun `a title that failed offline or refused is not cooled down`() =
        runTest {
            network.online = false
            aniList.answer = { dropped() }
            assertEquals(Outcome.Failure(DataError.NoConnection), fetch(1).of(1))

            network.online = true
            aniList.answer = { copiesOf(it) }
            assertTrue(fetch(1).of(1) is Outcome.Success)
            assertEquals(2, aniList.requests.size)
        }

    @Test
    fun `a schedule that never ends fails after forty pages, and is not asked again in its cool-down`() =
        runTest {
            // No recording holds more than a thousand episodes: Conan's first page again, numbered on by 25 each page.
            val first = json("hundreds_of_episodes")
            val nodes = mediaOf(first)[0].jsonObject["airingSchedule"]!!.jsonObject["nodes"]!!.jsonArray
            aniList.answer = { asked ->
                val shifted =
                    nodes.map { node ->
                        val episode = node.jsonObject["episode"]!!.jsonPrimitive.int + 25 * (asked.schedulePage - 1)
                        JsonObject(node.jsonObject + ("episode" to JsonPrimitive(episode)))
                    }
                reply(200, first.edited(MEDIA + listOf("0", "airingSchedule", "nodes"), JsonArray(shifted)).toString())
            }

            assertEquals(Outcome.Failure(DataError.ServerError), fetch(235).of(235))
            assertEquals((1..40).toList(), aniList.asked().map { it.schedulePage })

            assertEquals(Outcome.Failure(DataError.ServerError), fetch(235).of(235))
            assertEquals(40, aniList.requests.size)
        }
}
