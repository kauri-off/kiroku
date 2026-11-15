package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
}
