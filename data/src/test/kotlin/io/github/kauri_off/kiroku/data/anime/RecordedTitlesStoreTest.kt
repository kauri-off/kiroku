package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.data.db.TestDatabases
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.db.dispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

// What the mappers make of MAL's answers comes back from the store as it went in.
@RunWith(RobolectricTestRunner::class)
class RecordedTitlesStoreTest {
    @get:Rule
    val databases = TestDatabases()

    private val at = Instant.parse("2026-11-14T10:00:00Z")

    private fun TestScope.store(): Pair<TitleStore, List<Throwable>> {
        val unexpected = mutableListOf<Throwable>()
        return TitleStore(databases.open(this), dispatcher) { unexpected += it } to unexpected
    }

    @Test
    fun `every recorded title page comes back from the store as it was mapped`() =
        runTest {
            for (name in titlePages) {
                val (store, unexpected) = store()
                val (anime, details) = recordedTitle(name)
                store.refresh(anime, details, at)
                assertEquals(name, anime, store.observeAnime(anime.id).first())
                assertEquals(name, details, store.observeDetails(anime.id).first()?.details)
                assertTrue(unexpected.isEmpty())
            }
        }

    @Test
    fun `every recorded title comes back from the store as it was mapped, an unknown code as the same code`() =
        runTest {
            val (first, second) = recordedNodes.filter { (_, node) -> "media_type" in node }.map { it.second }.distinctBy { it["id"] }
            val unknown =
                listOf(
                    first.edited(listOf("media_type"), JsonPrimitive("web_film")),
                    second.edited(listOf("status"), JsonPrimitive("on_hiatus")),
                )
            val titles = (unknown + recordedNodes.map { it.second }).map { it.toNode().toAnime(Repairs()) }.distinctBy { it.id }
            assertTrue(titles.any { it.mediaType == MediaType.Unrecognized("web_film") })
            assertTrue(titles.any { it.airingStatus == AiringStatus.Unrecognized("on_hiatus") })
            val (store) = store()
            store.addUnknown(titles)
            for (anime in titles) assertEquals(anime, store.observeAnime(anime.id).first())
        }

    @Test
    fun `a code the app knows is not stored as an unknown one, so nothing reads back as another value`() =
        runTest {
            val (anime, details) = recordedTitle("title_airing")
            val (store) = store()
            val refused = runCatching { store.refresh(anime.copy(mediaType = MediaType.Unrecognized("movie")), details, at) }
            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            assertNull(store.observeAnime(anime.id).first())
        }
}
