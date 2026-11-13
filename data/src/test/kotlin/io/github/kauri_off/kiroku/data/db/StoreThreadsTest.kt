package io.github.kauri_off.kiroku.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// On real threads, over a database file. A row that cannot be mapped fails where it is mapped, and the failure's
// trace says where that was; a read of the list can be held back once its rows are read.
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

    // A read of the list that waits, with its rows read, until it is released.
    private class Hold {
        val reached = CountDownLatch(1)
        val released = CountDownLatch(1)
    }

    // What the coming reads of the list do, in their order: wait at a hold, or pass at null.
    private val holds = ConcurrentLinkedQueue<Optional<Hold>>()
    private val reads = AtomicInteger()

    private val driver =
        object : SQLiteDriver {
            override val hasConnectionPool: Boolean get() = true

            override fun open(fileName: String): SQLiteConnection {
                val connection = AndroidSQLiteDriver().open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        if (!sql.endsWith("ORDER BY e.title_id")) return statement
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean = statement.step().also { more -> if (!more) read() }
                        }
                    }
                }
            }
        }

    private val context = RuntimeEnvironment.getApplication()
    private val database = KirokuDatabase.builder(context, NAME).setDriver(driver).build()
    private val sharing = CoroutineScope(Dispatchers.Unconfined)
    private val list = RoomLocalList(database, sharing, StoreThread)
    private val titles = TitleStore(database, StoreThread)
    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val entry = checkNotNull(fixtures.entryFor(anime.id))

    @After
    fun close() {
        holds.clear()
        sharing.cancel()
        database.close()
        context.deleteDatabase(NAME)
    }

    private fun read() {
        reads.incrementAndGet()
        val hold = holds.poll()?.orElse(null) ?: return
        hold.reached.countDown()
        check(hold.released.await(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS))
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(TIMEOUT) { while (!condition()) delay(POLL) }

    private fun watched(count: Int) = StoredEntry.settled(entry.record.copy(values = entry.values.copy(watchedEpisodes = count)))

    private fun CoroutineScope.collector(seen: MutableList<Int>) =
        launch(Dispatchers.Default) { list.observeEntries().collect { seen += it.single().values.watchedEpisodes } }

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

    @Test
    fun `a collector that joins beside others gets no list older than the one stored when it joined`() =
        runBlocking {
            stored()
            val beside = CopyOnWriteArrayList<Int>()
            val standing = collector(beside)
            until { beside.isNotEmpty() && reads.get() >= 2 }
            val shared = Hold()
            val next = Hold()

            holds += Optional.of(shared)
            list.write(mapOf(anime.id to watched(20)))
            until { shared.reached.count == 0L }
            list.write(mapOf(anime.id to watched(21)))
            holds += listOf(Optional.empty(), Optional.of(next))
            val seen = CopyOnWriteArrayList<Int>()
            val joined = collector(seen)
            delay(SETTLE)
            shared.released.countDown()
            until { next.reached.count == 0L }
            delay(SETTLE)

            assertEquals(listOf(21), seen.toList())
            next.released.countDown()
            until { beside.last() == 21 }
            assertEquals(listOf(12, 20, 21), beside.toList())
            joined.cancel()
            standing.cancel()
        }

    private companion object {
        const val NAME = "threads.db"
        val TIMEOUT = 20.seconds
        val SETTLE = 200.milliseconds
        val POLL = 5.milliseconds
    }
}
