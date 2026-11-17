package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.list.toListPage
import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

// The calculator over the signed-in user's recorded list, against the profile recorded with it; skipped where absent.
class OwnerStatisticsTest {
    @Test
    fun `the calculator over the user's whole recorded list gives the completed count and mean score of their profile`() {
        val pages =
            (1..3).map { page ->
                val name = "own_list_$page"
                val answer = OwnerRecordings.named(name)
                assertTrue("$name is not an answer of 200", answer.status == 200)
                OwnerRecordings.read(name) { MalJson.parseToJsonElement(answer.body).jsonObject.toListPage(Repairs()) }
            }
        assumeTrue(
            "Skipped: the recorded pages hold part of the list only; record it again from the debug menu (section 11.1)",
            pages.last().next == null,
        )
        val profile = OwnerRecordings.named("profile")
        val recorded =
            OwnerRecordings.read("profile") {
                MalJson
                    .parseToJsonElement(profile.body)
                    .jsonObject
                    .getValue("anime_statistics")
                    .jsonObject
            }
        val completed =
            OwnerRecordings.read("profile") {
                recorded
                    .getValue("num_items_completed")
                    .jsonPrimitive.content
                    .toInt()
            }
        val mean = OwnerRecordings.read("profile") { BigDecimal(recorded.getValue("mean_score").jsonPrimitive.content) }

        val statistics = statisticsOf(pages.flatMap { it.entries })

        assertTrue("the completed count differs from the profile's", statistics.statuses.completed == completed)
        // MAL gives a mean of zero where nothing is scored, and the mean to two decimals.
        val calculated = BigDecimal.valueOf(statistics.meanScore ?: 0.0).setScale(2, RoundingMode.HALF_UP)
        assertTrue("the mean score to two decimals differs from the profile's", calculated.compareTo(mean) == 0)
    }
}
