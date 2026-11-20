package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.AiringEngine
import io.github.kauri_off.kiroku.core.airing.InMemoryAiringStore
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.idsOf
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.json
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.pagesOf
import io.github.kauri_off.kiroku.data.anilist.AniListServer.Companion.reply
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Instant

// The airing engine over the AniList client and M3-01's recordings: what a refresh costs, a failure, a refusal, a page behind a refresh.
@OptIn(ExperimentalCoroutinesApi::class)
class AniListEngineTest {
    private val network = NetworkFixture()
    private val aniList = AniListServer(network)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(network).around(aniList)

    // A list whose titles are all Watching, as the engine reads it.
    private class Watching(
        titles: List<Anime>,
    ) : ListSource {
        private val entries =
            MutableStateFlow(titles.map { ListEntry(it, ListValues(ListStatus.Watching, 0, null, null, null), Instant.EPOCH) })

        override fun observeEntries(): Flow<List<ListEntry>> = entries

        override fun observeEntry(id: AnimeId): Flow<ListEntry?> = entries.map { all -> all.firstOrNull { it.anime.id == id } }

        override fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> =
            entries.map { all -> all.filter { it.anime.id in ids }.associateBy { it.anime.id } }

        override fun observeSyncedAt(): Flow<Instant?> = flowOf(null)

        override suspend fun sync(): Outcome<Unit> = Outcome.Success(Unit)
    }

    private val store = InMemoryAiringStore()

    private fun TestScope.engine(ids: List<Int>): AiringEngine =
        AiringEngine(aniList.client(this), store, Watching(ids.map(::titleWithId)), network.clock, backgroundScope) { throw it }

    // How many episodes M3-01 recorded for [id] over the pages of [case].
    private fun recordedEpisodes(
        case: String,
        id: Int,
    ): Int =
        pagesOf(case)
            .flatMap { file -> mediaOf(json(file)).filter { it.jsonObject["idMal"]!!.jsonPrimitive.int == id }.take(1) }
            .sumOf {
                it.jsonObject["airingSchedule"]!!
                    .jsonObject["nodes"]!!
                    .jsonArray.size
            }

    private fun id(value: Int) = AnimeId(value.toLong())

    @Test
    fun `a refresh asks for the tracked titles in the requests the client's count gives, and stores each as AniList knows it`() =
        runTest {
            val ids = idsOf("unknown_among_others")
            assertEquals(Outcome.Success(Unit), engine(ids).refresh())

            // Two entries on one page of titles and no schedule beyond its first page: one request.
            assertEquals(listOf(Asked(ids, 1, 1)), aniList.asked())
            val stored = store.read()
            assertEquals(ids.map(::id).toSet(), stored.keys)
            assertNull(stored.getValue(id(9874)).schedule)
            assertEquals(emptyList<Any>(), stored.getValue(id(62734)).schedule!!.episodes)
            assertEquals(
                recordedEpisodes("airing_weekly", 61987),
                stored
                    .getValue(id(61987))
                    .schedule!!
                    .episodes.size,
            )
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `after a failure a pull on the list or a page asks AniList at once, and a refresh nobody asked for waits`() =
        runTest {
            val ids = listOf(1, 2)
            val engine = engine(ids)
            aniList.answer = { reply(500, "") }
            assertEquals(Outcome.Failure(DataError.ServerError), engine.refreshStale())
            aniList.answer = { copiesOf(it) }

            assertEquals(Outcome.Failure(DataError.ServerError), engine.refreshStale())
            assertEquals(Outcome.Failure(DataError.ServerError), engine.refresh(ids.map(::id).toSet()))
            assertEquals(1, aniList.requests.size)
            assertEquals(emptyMap<AnimeId, Any>(), store.read())

            assertEquals(Outcome.Success(Unit), engine.refresh(titleWithId(1)))
            assertEquals(Outcome.Success(Unit), engine.refresh())
            assertEquals(listOf(listOf(1), ids), aniList.asked().drop(1).map { it.ids })
            assertEquals(ids.map(::id).toSet(), store.read().keys)
        }

    @Test
    fun `a refusal leaves what is stored as it was, and a pull on the list or a page sends nothing while it holds`() =
        runTest {
            val ids = idsOf("airing_weekly")
            val engine = engine(ids)
            assertEquals(Outcome.Success(Unit), engine.refresh())
            val before = store.read()

            aniList.answer = { reply(429, "", headers = mapOf("Retry-After" to "60")) }
            assertEquals(Outcome.Failure(DataError.Refused), engine.refresh())
            assertEquals(Outcome.Failure(DataError.Refused), engine.refresh())
            assertEquals(Outcome.Failure(DataError.Refused), engine.refresh(titleWithId(ids.first())))

            assertEquals(2, aniList.requests.size)
            assertEquals(before, store.read())
        }

    @Test
    fun `a page asked for behind a refresh of a long schedule is answered once the refresh is, each title whole`() =
        runTest {
            val engine = engine(idsOf("hundreds_of_episodes"))
            val refresh = async { engine.refresh() }
            runCurrent()
            val page = async { engine.refresh(titleWithId(21)) }

            assertEquals(Outcome.Success(Unit), refresh.await())
            assertEquals(Outcome.Success(Unit), page.await())

            // 156 episodes held: a page of titles and six more pages of episodes; then the page's three.
            assertEquals(pagesOf("hundreds_of_episodes").size + pagesOf("on_a_break").size, aniList.requests.size)
            assertEquals(7, pagesOf("hundreds_of_episodes").size)
            assertTrue(aniList.asked().take(7).all { it.ids == listOf(235) })
            assertTrue(aniList.asked().drop(7).all { it.ids == listOf(21) })
            assertEquals(
                recordedEpisodes("hundreds_of_episodes", 235),
                store
                    .read()
                    .getValue(id(235))
                    .schedule!!
                    .episodes.size,
            )
            assertEquals(setOf(id(235)), store.read().keys)
            assertEquals(
                recordedEpisodes("on_a_break", 21),
                engine
                    .observePageSchedule(id(21))
                    .first()!!
                    .episodes.size,
            )
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }
}
