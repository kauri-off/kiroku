package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Inexact
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.RecordedAt
import io.github.kauri_off.kiroku.data.anime.recordedTitle
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.recorder.AniListRequests
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

// Each recorded pair of AniList's answer and MAL's page, through the client and M3-02's calculator at the recording.
class AniListCasesTest {
    private val network = NetworkFixture()
    private val aniList = AniListServer(network)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(network).around(aniList)

    private class Case(
        val aniList: String,
        val mal: String,
        val count: AiredCount,
        val inexact: Inexact?,
    )

    private val cases =
        listOf(
            Case("airing_weekly", "airing_weekly", AiredCount.Exact(7), null),
            Case("releasing_without_times", "airing_releasing_without_times", AiredCount.Unknown, Inexact.NoSchedule),
            Case("on_a_break", "airing_on_a_break", AiredCount.Exact(1180), null),
            Case("hundreds_of_episodes", "airing_hundreds_of_episodes", AiredCount.Exact(1220), null),
            Case("not_yet_aired_with_time", "airing_not_yet_aired_with_time", AiredCount.Exact(0), null),
            Case("not_yet_aired_without_time", "airing_not_yet_aired_without_time", AiredCount.Estimated(0), Inexact.NoSchedule),
            Case("finished_last_month", "airing_finished_last_month", AiredCount.Exact(10), null),
            Case("totals_differ_by_one", "airing_totals_differ_by_one", AiredCount.Estimated(0), Inexact.TotalsDisagree),
            Case("totals_differ_by_more", "airing_totals_differ_by_more", AiredCount.Exact(219), null),
            Case("unknown_alone", "airing_unknown_to_anilist", AiredCount.Unknown, Inexact.NoSchedule),
        )

    @Test
    fun `each recorded case gives its count and its reason`() =
        runTest {
            val client = aniList.client(this)
            for (case in cases) {
                val anime = recordedTitle(case.mal).anime
                val fetched = client.fetch(listOf(anime), RecordedAt).getValue(anime.id) as Outcome.Success
                val airing = airingAt(anime, fetched.value?.schedule, RecordedAt)

                assertEquals(case.aniList, case.count, airing.aired)
                assertEquals(case.aniList, case.inexact, airing.inexact)
            }
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `every case with MAL's page is read here`() {
        val names = cases.map { it.aniList }.toSet()

        assertEquals(AniListRequests.filter { it.withMalPages && it.ids.size == 1 && !it.recheck }.map { it.name }.toSet(), names)
    }
}
