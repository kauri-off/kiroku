package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

// On real threads: a row that cannot be mapped fails where it is mapped, and the failure's trace says where that was.
@RunWith(RobolectricTestRunner::class)
class StoreThreadsTest {
    private object StoreThread : CoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = executor.execute { onStoreThread(block) }

        private fun onStoreThread(block: Runnable) = block.run()
    }

    private val database = KirokuDatabase.builder(RuntimeEnvironment.getApplication(), name = null).build()
    private val sharing = CoroutineScope(Dispatchers.Unconfined)
    private val list = RoomLocalList(database, sharing, StoreThread)
    private val titles = TitleStore(database, StoreThread)
    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val entry = checkNotNull(fixtures.entryFor(anime.id))

    @After
    fun close() {
        sharing.cancel()
        database.close()
    }

    private fun Throwable.mappedOnStoreThread() =
        generateSequence(this) { it.cause }.any { cause -> cause.stackTrace.any { it.methodName == "onStoreThread" } }

    private suspend fun failure(call: suspend () -> Any?): Throwable =
        checkNotNull(
            runCatching {
                withTimeout(TIMEOUT) { call() }
            }.exceptionOrNull(),
        )

    private suspend fun stored() {
        list.clear(SignInId("a"))
        list.download(listOf(anime), mapOf(anime.id to StoredEntry.settled(entry.record)), fixtures.now)
        titles.refresh(anime, fixtures.details.getValue(anime.id), fixtures.now)
        assertFalse(IllegalStateException().mappedOnStoreThread())
    }

    private suspend fun corrupt() {
        database.execute("UPDATE list_entry SET shown_status = 'broken', confirmed_status = 'broken'")
        database.execute("UPDATE title SET airing_status = 'broken'")
        database.execute("UPDATE title_details SET source = 'broken'")
    }

    @Test
    fun `the read all collectors share maps its rows on the store's dispatcher`() =
        runBlocking {
            stored()
            val first = CompletableDeferred<Unit>()
            val shared = async { failure { list.observeEntries().onEach { first.complete(Unit) }.collect() } }
            withTimeout(TIMEOUT) { first.await() }

            corrupt()

            assertTrue(shared.await().mappedOnStoreThread())
        }

    @Test
    fun `a collector's own read of the list maps its rows on the store's dispatcher`() =
        runBlocking {
            stored()
            corrupt()
            assertTrue(failure { list.observeEntries().first() }.mappedOnStoreThread())
        }

    @Test
    fun `one entry's rows are mapped on the store's dispatcher`() =
        runBlocking {
            stored()
            corrupt()
            assertTrue(failure { list.observeEntry(anime.id).first() }.mappedOnStoreThread())
        }

    @Test
    fun `a call maps its rows on the store's dispatcher`() =
        runBlocking {
            stored()
            corrupt()
            assertTrue(failure { list.read(anime.id) }.mappedOnStoreThread())
            assertTrue(failure { list.readAll() }.mappedOnStoreThread())
        }

    @Test
    fun `a title's rows are mapped on the store's dispatcher`() =
        runBlocking {
            stored()
            corrupt()
            assertTrue(failure { titles.observeAnime(anime.id).first() }.mappedOnStoreThread())
            assertTrue(failure { titles.observeDetails(anime.id).first() }.mappedOnStoreThread())
        }

    private companion object {
        val TIMEOUT = 20.seconds
    }
}
