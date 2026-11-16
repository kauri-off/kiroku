package io.github.kauri_off.kiroku.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// On real threads, over a database file: a write can be held inside its transaction while other calls run.
@RunWith(RobolectricTestRunner::class)
class AiringStoreThreadsTest {
    private object StoreThread : CoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = executor.execute { onStoreThread(block) }

        private fun onStoreThread(block: Runnable) = block.run()
    }

    // The next insert into [table] waits, inside its transaction, until it is released.
    private class Hold(
        val table: String,
    ) {
        val reached = CountDownLatch(1)
        val released = CountDownLatch(1)

        fun park() {
            reached.countDown()
            check(released.await(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS))
        }
    }

    private val hold = AtomicReference<Hold?>()

    private val driver =
        object : SQLiteDriver {
            override val hasConnectionPool: Boolean get() = AndroidSQLiteDriver().hasConnectionPool

            override fun open(fileName: String): SQLiteConnection {
                val connection = AndroidSQLiteDriver().open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean {
                                hold.get()?.takeIf { sql.startsWith("INSERT") && "INTO `${it.table}` " in sql }?.let {
                                    hold.set(null)
                                    it.park()
                                }
                                return statement.step()
                            }
                        }
                    }
                }
            }
        }

    private val context = RuntimeEnvironment.getApplication()
    private val database = KirokuDatabase.builder(context, NAME).setDriver(driver).build()
    private val sharing = CoroutineScope(Dispatchers.Unconfined)
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val airing = RoomAiringStore(database, StoreThread) { unexpected += it }
    private val list = RoomLocalList(database, sharing, Dispatchers.IO) { throw it }
    private val titles = TitleStore(database, Dispatchers.IO) { throw it }
    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val entry = checkNotNull(fixtures.entryFor(anime.id))
    private val before = mapOf(anime.id to StoredAiring(fixtures.schedules.getValue(anime.id), fixtures.now))
    private val after =
        mapOf(
            anime.id to
                StoredAiring(
                    AiringSchedule(
                        fixtures.schedules
                            .getValue(anime.id)
                            .episodes
                            .drop(1),
                        24,
                    ),
                    fixtures.now.plusSeconds(60),
                ),
            AnimeId(7) to StoredAiring(null, fixtures.now.plusSeconds(60)),
        )

    @After
    fun close() {
        hold.set(null)
        sharing.cancel()
        database.close()
        context.deleteDatabase(NAME)
    }

    private fun watched(count: Int) = StoredEntry.settled(entry.record.copy(values = entry.values.copy(watchedEpisodes = count)))

    private suspend fun stored() {
        list.clear(SignInId("a"))
        list.download(listOf(anime), mapOf(anime.id to StoredEntry.settled(entry.record)), fixtures.now)
        airing.write(before)
    }

    private fun Throwable.mappedOnStoreThread() =
        generateSequence(this) { it.cause }.any { cause -> cause.stackTrace.any { it.methodName == "onStoreThread" } }

    @Test
    fun `a read while a write is inside its transaction gives the store as it was, and then as written`() =
        runBlocking {
            stored()
            val held = Hold(AiringEpisodeRow.TABLE).also { hold.set(it) }

            val writing = async(Dispatchers.IO) { airing.write(after) }
            withTimeout(TIMEOUT) { while (held.reached.count > 0) delay(POLL) }
            val during = withTimeout(TIMEOUT) { airing.read() }
            held.released.countDown()
            withTimeout(TIMEOUT) { writing.await() }

            assertEquals(before, during)
            assertEquals(after, airing.read())
            assertEquals(after, airing.observe().first())
        }

    @Test
    fun `a list write and a title's refresh made during an airing write lose nothing, and neither does the airing write`() =
        runBlocking {
            stored()
            val held = Hold(AiringEpisodeRow.TABLE).also { hold.set(it) }

            val writing = async(Dispatchers.IO) { airing.write(after) }
            withTimeout(TIMEOUT) { while (held.reached.count > 0) delay(POLL) }
            val listWrite = async(Dispatchers.IO) { list.write(mapOf(anime.id to watched(20))) }
            val refresh = async(Dispatchers.IO) { titles.refresh(anime, fixtures.details.getValue(anime.id), fixtures.now) }
            delay(SETTLE)
            assertFalse(listWrite.isCompleted || refresh.isCompleted)
            held.released.countDown()
            withTimeout(TIMEOUT) { listOf(writing, listWrite, refresh).forEach { it.await() } }

            assertEquals(after, airing.read())
            assertEquals(
                20,
                list
                    .observeEntries()
                    .first()
                    .single()
                    .values.watchedEpisodes,
            )
            assertEquals(fixtures.details.getValue(anime.id), titles.observeDetails(anime.id).first()?.details)
        }

    @Test
    fun `an airing write made during a list download loses nothing, and neither does the download`() =
        runBlocking {
            stored()
            val held = Hold(ListEntryRow.TABLE).also { hold.set(it) }
            val added = FixtureAnime.exactCaughtUp
            val addedEntry = checkNotNull(fixtures.entryFor(added.id))

            val download =
                async(Dispatchers.IO) {
                    list.download(listOf(added), mapOf(added.id to StoredEntry.settled(addedEntry.record)), fixtures.now)
                }
            withTimeout(TIMEOUT) { while (held.reached.count > 0) delay(POLL) }
            val writing = async(Dispatchers.IO) { airing.write(after) }
            delay(SETTLE)
            assertFalse(writing.isCompleted)
            held.released.countDown()
            withTimeout(TIMEOUT) { listOf(download, writing).forEach { it.await() } }

            assertEquals(after, airing.read())
            assertEquals(setOf(anime.id, added.id), list.readAll().keys)
        }

    @Test
    fun `the store maps its rows on its dispatcher`() =
        runBlocking {
            stored()
            database.execute("UPDATE airing_episode SET number = 0 WHERE number = 1")

            assertEquals(emptyMap<AnimeId, StoredAiring>(), airing.read())
            assertEquals(emptyMap<AnimeId, StoredAiring>(), withTimeout(TIMEOUT) { airing.observe().first() })
            assertEquals(2, unexpected.size)
            assertTrue(unexpected.all { it.mappedOnStoreThread() })
        }

    private companion object {
        const val NAME = "airing-threads.db"
        val TIMEOUT = 20.seconds
        val SETTLE = 200.milliseconds
        val POLL = 5.milliseconds
    }
}
