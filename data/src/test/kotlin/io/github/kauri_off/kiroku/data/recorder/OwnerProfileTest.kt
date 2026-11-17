package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.profile.toProfile
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The signed-in user's profile as MAL sent it; skipped where it is absent.
class OwnerProfileTest {
    @Test
    fun `profile - the user's statistics hold a count of titles per status and the totals, each a number`() {
        val answer = OwnerRecordings.named("profile")
        assertTrue("profile is not an answer of 200", answer.status == 200)
        val statistics =
            OwnerRecordings.read("profile") {
                MalJson
                    .parseToJsonElement(answer.body)
                    .jsonObject
                    .getValue("anime_statistics")
                    .jsonObject
            }
        val numbers =
            listOf(
                "num_items_watching",
                "num_items_completed",
                "num_items_on_hold",
                "num_items_dropped",
                "num_items_plan_to_watch",
                "num_items",
                "num_days_watched",
                "num_episodes",
                "num_times_rewatched",
                "mean_score",
            )
        for (key in numbers) {
            val value = statistics[key]
            assertTrue("profile: anime_statistics.$key", value is JsonPrimitive && !value.isString)
        }
    }

    // A failure names the fields read in part, never a value.
    @Test
    fun `profile - the app's mapper reads it whole, the statistics as MAL states them`() {
        val answer = OwnerRecordings.named("profile")
        val repairs = Repairs()
        val profile = OwnerRecordings.read("profile") { MalJson.parseToJsonElement(answer.body).jsonObject.toProfile(repairs) }
        assertEquals("profile: fields read in part", emptySet<String>(), repairs.fields)
        assertTrue("profile: joined_at", profile.joined != null)
        val stated = profile.stated
        assertTrue("profile: anime_statistics", stated != null)
        val figures =
            with(stated!!) {
                mapOf(
                    "num_items_watching" to watching,
                    "num_items_completed" to completed,
                    "num_items_on_hold" to onHold,
                    "num_items_dropped" to dropped,
                    "num_items_plan_to_watch" to planned,
                    "num_items" to entries,
                    "num_episodes" to episodes,
                    "num_times_rewatched" to rewatched,
                    "num_days_watched" to daysWatched,
                )
            }
        assertEquals("profile: figures not read", emptySet<String>(), figures.filterValues { it == null }.keys)
    }
}
