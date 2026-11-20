package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.list.toListPage
import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

// The calculator over the signed-in user's recorded list, against the profile recorded with it; skipped where absent.
class OwnerStatisticsTest {
    private val pages: List<JsonObject> by lazy {
        val pages =
            (1..3).map { page ->
                val name = "own_list_$page"
                val answer = OwnerRecordings.named(name)
                assertTrue("$name is not an answer of 200", answer.status == 200)
                OwnerRecordings.read(name) { MalJson.parseToJsonElement(answer.body).jsonObject }
            }
        assumeTrue(
            "Skipped: the recorded pages hold part of the list only; record it again from the debug menu (section 11.1)",
            OwnerRecordings.read("own_list_3") { pages.last().toListPage(Repairs()).next == null },
        )
        pages
    }

    private val statistics: UserStatistics by lazy {
        val entries = pages.flatMap { page -> OwnerRecordings.read("own_list") { page.toListPage(Repairs()).entries } }
        assertTrue("the recorded list is empty", entries.isNotEmpty())
        statisticsOf(entries)
    }

    private fun stated(key: String): BigDecimal {
        val profile = OwnerRecordings.named("profile")
        return OwnerRecordings.read("profile") {
            val figures =
                MalJson
                    .parseToJsonElement(profile.body)
                    .jsonObject
                    .getValue("anime_statistics")
                    .jsonObject
            BigDecimal(figures.getValue(key).jsonPrimitive.content)
        }
    }

    @Test
    fun `the calculator over the user's whole recorded list gives the completed count and mean score of their profile`() {
        val completed = stated("num_items_completed").toInt()
        val mean = stated("mean_score")

        assertTrue("the completed count differs from the profile's", statistics.statuses.completed == completed)
        // MAL gives a mean of zero where nothing is scored, and the mean to two decimals.
        val calculated = BigDecimal.valueOf(statistics.meanScore ?: 0.0).setScale(2, RoundingMode.HALF_UP)
        assertTrue("the mean score to two decimals differs from the profile's", calculated.compareTo(mean) == 0)
    }

    // MAL counts each rewatch in both, so the pages must carry the rewatch counts.
    @Test
    fun `the calculator over the user's whole recorded list gives the days and episodes watched of their profile`() {
        val pages = pages
        val carried =
            OwnerRecordings.read("own_list") {
                pages.all { page ->
                    page.getValue("data").jsonArray.all { "num_times_rewatched" in it.jsonObject.getValue("list_status").jsonObject }
                }
            }
        assumeTrue("Skipped: the recorded pages hold no rewatch counts; record them again from the debug menu (section 11.1)", carried)
        val episodes = stated("num_episodes").toInt()
        val watched = stated("num_days_watched").toDouble()
        // MAL defines this one as the sum of its four status days, each given to two decimals.
        val summed = stated("num_days").toDouble()

        assertTrue("the episodes watched differ from the profile's", statistics.episodesWatched == episodes)
        val days = statistics.daysWatched
        assertTrue("the days watched to two decimals differ from the profile's", abs(days - watched) < 0.01 || abs(days - summed) <= 0.025)
    }
}
