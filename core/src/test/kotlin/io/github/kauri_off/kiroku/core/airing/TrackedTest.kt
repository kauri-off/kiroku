package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class TrackedTest {
    private val statuses: List<EntryStatus> = ListStatus.entries + Fixtures.unknownListStatus
    private val airing = AiringStatus.known + AiringStatus.Unrecognized("on_hiatus")

    @Test
    fun `the tracked titles are the unfinished ones in Watching, Planned or On hold, an unknown airing status among them`() {
        val tracked =
            statuses.flatMap { status ->
                airing
                    .filter { ListEntry(FixtureAnime.exactToWatch.copy(airingStatus = it), values(status), Fixtures.reference.now).tracked }
                    .map { status to it }
            }
        val unfinished = airing - AiringStatus.Finished
        assertEquals(
            listOf(ListStatus.Watching, ListStatus.Planned, ListStatus.OnHold).flatMap { s ->
                unfinished.map { s to it }
            },
            tracked,
        )
    }

    @Test
    fun `a finale is kept for N2's three days after the longest delay`() = assertEquals(75.hours, finaleKeptFor)

    private fun values(status: EntryStatus) = ListValues(status, 0, null, null, null)
}
