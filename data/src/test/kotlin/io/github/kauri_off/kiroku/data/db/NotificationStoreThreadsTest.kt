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
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.Announced
import io.github.kauri_off.kiroku.core.notify.Followed
import io.github.kauri_off.kiroku.core.notify.NotificationRecord
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.notify.StoredNotifications
import io.github.kauri_off.kiroku.core.notify.TitleRecord
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// On real threads, over a database file: a list download, an airing refresh and a record write made together.
@RunWith(RobolectricTestRunner::class)
class NotificationStoreThreadsTest {
    @get:Rule
    val folder = TemporaryFolder()

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
    private val sharing = CoroutineScope(Dispatchers.IO)
    private val list = RoomLocalList(database, sharing, Dispatchers.IO) { throw it }
    private val airing = RoomAiringStore(database, Dispatchers.IO) { throw it }
    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val added = FixtureAnime.exactCaughtUp
    private val owner = SignInId("owner")
    private val schedule = mapOf(anime.id to StoredAiring(AiringSchedule(fixtures.schedules.getValue(anime.id).episodes, 24), fixtures.now))
    private val record =
        NotificationRecord(
            since = fixtures.now,
            titles =
                mapOf(
                    anime.id to TitleRecord(Followed(ListStatus.Watching, fixtures.now), Announced(2, fixtures.now), standing = true),
                ),
        )
    private val sent = SentNotification(anime, 2, fixtures.now, fixtures.now, fixtures.now, Recheck.Answered)

    private val store by lazy {
        val preferences = StoredPreferences(File(folder.root, "notify.preferences_pb"), sharing) { throw it }
        RoomNotificationStore(database, preferences, Dispatchers.IO) { throw it }
    }

    @After
    fun close() {
        hold.set(null)
        sharing.cancel()
        database.close()
        context.deleteDatabase(NAME)
    }

    private fun entry(of: Anime) = StoredEntry.settled(checkNotNull(fixtures.entryFor(of.id)).record)

    private suspend fun stored() {
        list.clear(owner)
        list.download(listOf(anime), mapOf(anime.id to entry(anime)), fixtures.now)
        store.clear(owner)
    }

    // The write into [held] waits inside its transaction while the other two are started; then all three are kept.
    private fun together(held: String) =
        runBlocking {
            stored()
            val hold = Hold(held).also { hold.set(it) }
            val writes =
                listOf(
                    ListEntryRow.TABLE to suspend { list.download(listOf(added), mapOf(added.id to entry(added)), fixtures.now) },
                    AiringRow.TABLE to suspend { airing.write(schedule) },
                    NotificationTitleRow.TABLE to suspend { store.write(record, listOf(sent), fixtures.now) },
                )
            val first = async(Dispatchers.IO) { writes.single { it.first == held }.second() }
            withTimeout(TIMEOUT) { while (hold.reached.count > 0) delay(POLL) }
            val others = writes.filter { it.first != held }.map { async(Dispatchers.IO) { it.second() } }
            delay(SETTLE)
            assertFalse(held, others.any { it.isCompleted })
            hold.released.countDown()
            withTimeout(TIMEOUT) { (others + first).forEach { it.await() } }

            assertEquals(held, setOf(anime.id, added.id), list.readAll().keys)
            assertEquals(held, schedule, airing.read())
            assertEquals(held, StoredNotifications(owner, record), store.read())
            assertEquals(held, listOf(sent), store.observeSent().first())
        }

    @Test
    fun `an airing refresh and a record write made during a list download lose nothing, and neither does the download`() =
        together(ListEntryRow.TABLE)

    @Test
    fun `a list download and an airing refresh made during a record write lose nothing, and neither does the record write`() =
        together(NotificationTitleRow.TABLE)

    @Test
    fun `a list download and a record write made during an airing refresh lose nothing, and neither does the refresh`() =
        together(AiringRow.TABLE)

    private companion object {
        const val NAME = "notification-threads.db"
        val TIMEOUT = 20.seconds
        val SETTLE = 200.milliseconds
        val POLL = 5.milliseconds
    }
}
