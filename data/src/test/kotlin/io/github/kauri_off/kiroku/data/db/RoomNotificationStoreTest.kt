package io.github.kauri_off.kiroku.data.db

import androidx.datastore.preferences.core.PreferencesFileSerializer
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.notify.Announced
import io.github.kauri_off.kiroku.core.notify.Followed
import io.github.kauri_off.kiroku.core.notify.NotificationRecord
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.notify.StoredNotifications
import io.github.kauri_off.kiroku.core.notify.TitleRecord
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RoomNotificationStoreTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val folder = TemporaryFolder()

    private val fixtures = Fixtures.reference
    private val anime = FixtureAnime.exactToWatch
    private val other = FixtureAnime.exactCaughtUp
    private val owner = SignInId("owner")
    private val unexpected = mutableListOf<Throwable>()

    private val record =
        NotificationRecord(
            since = fixtures.now,
            titles =
                mapOf(
                    anime.id to TitleRecord(Followed(ListStatus.Watching, fixtures.now), Announced(3, fixtures.now), standing = true),
                    other.id to TitleRecord(followed = null, Announced(5, fixtures.now.plusSeconds(5))),
                ),
        )

    private fun sent(of: Int) =
        SentNotification(
            anime,
            of,
            fixtures.now,
            fixtures.now,
            fixtures.now.plusSeconds(of.toLong()),
            Recheck.Answered,
            shown = of % 2 == 0,
        )

    private inner class Device(
        scope: TestScope,
    ) {
        val database = databases.open(scope)
        val list = RoomLocalList(database, scope.backgroundScope, scope.dispatcher) { throw it }
        val titles = TitleStore(database, scope.dispatcher) { throw it }
        val airing = RoomAiringStore(database, scope.dispatcher) { throw it }
        val profiles = RoomProfileStore(database, scope.dispatcher) { throw it }
        private val preferences =
            StoredPreferences(
                File(folder.root, "notify.preferences_pb"),
                scope.backgroundScope,
                { throw it },
                scope.dispatcher,
                PreferencesFileSerializer,
            )
        val store = RoomNotificationStore(database, preferences, scope.dispatcher) { unexpected += it }

        suspend fun signedIn() {
            list.clear(owner)
            list.download(listOf(anime, other), mapOf(anime.id to StoredEntry.settled(entry(anime)), other.id to null), fixtures.now)
            store.clear(owner)
            store.write(record, listOf(sent(1), sent(2)), fixtures.now)
        }
    }

    private fun entry(anime: Anime) = checkNotNull(fixtures.entryFor(anime.id)).record

    // N10: the list, the airing data, a title's page and the profile write their own tables, and clearing the list leaves the record to the engine.
    @Test
    fun `no download, refresh, list write or clearing of the list touches the record or what was posted`() =
        runTest {
            val device = Device(this)
            device.signedIn()
            val before = device.store.read()
            assertEquals(StoredNotifications(owner, record), before)
            databases.statements.clear()

            device.list.download(listOf(other), mapOf(other.id to StoredEntry.settled(entry(other))), fixtures.now.plusSeconds(60))
            device.list.write(
                mapOf(
                    anime.id to StoredEntry.settled(entry(anime).copy(values = entry(anime).values.copy(watchedEpisodes = 1))),
                ),
            )
            device.list.restore(mapOf(anime.id to StoredEntry.settled(entry(anime))))
            device.list.add(other, StoredEntry.settled(entry(other)))
            device.titles.refresh(anime, fixtures.details.getValue(anime.id), fixtures.now)
            device.airing.write(mapOf(anime.id to StoredAiring(fixtures.schedules.getValue(anime.id), fixtures.now)))
            device.airing.clear()
            device.profiles.replace(StoredProfile(owner, UserProfile("someone", null, null), fixtures.now)) { true }
            device.list.clear(SignInId("another"))
            device.list.clear(null)

            val touching = databases.statements.filter { "notification" in it }
            assertEquals(emptyList<String>(), touching)
            assertEquals(before, device.store.read())
            assertEquals(1L, device.database.rows(NotificationStateRow.TABLE))
            assertEquals(2L, device.database.rows(SentRow.TABLE))
            // An entry whose title row the list's clearing took is not given, until the engine clears the store.
            assertEquals(emptyList<SentNotification>(), device.store.observeSent().first())
            device.store.clear(null)
            assertEquals(0L, device.database.rows(SentRow.TABLE) + device.database.rows(NotificationTitleRow.TABLE))
            assertEquals(StoredNotifications(null, NotificationRecord()), device.store.read())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `a write that fails stores nothing of it, and the record before is whole`() =
        runTest {
            val device = Device(this)
            device.signedIn()
            val before = device.store.read()
            val sentBefore = device.store.observeSent().first()
            for (table in listOf(NotificationStateRow.TABLE, NotificationTitleRow.TABLE, SentRow.TABLE)) {
                device.database.breakWrites(table)
                val failed =
                    runCatching {
                        device.store.write(
                            NotificationRecord(since = fixtures.now),
                            listOf(sent(3)),
                            fixtures.now.plusSeconds(2),
                        )
                    }
                assertTrue(table, failed.isFailure)
                assertTrue(table, runCatching { device.store.clear(SignInId("another")) }.isFailure)
                device.database.repair(table)
                assertEquals(table, before, device.store.read())
                assertEquals(table, sentBefore, device.store.observeSent().first())
            }
        }

    @Test
    fun `a row that cannot be read is left out and reported, and the others are read`() =
        runTest {
            val device = Device(this)
            device.signedIn()
            device.database.execute("UPDATE notification_title SET followed_since = NULL WHERE title_id = ${anime.id.value}")
            device.database.execute("UPDATE notification_sent SET recheck = 'asked twice' WHERE episode = 1")

            assertEquals(StoredNotifications(owner, record.copy(titles = record.titles - anime.id)), device.store.read())
            assertEquals(listOf(sent(2)), device.store.observeSent().first())
            assertEquals(2, unexpected.size)
            assertTrue(unexpected.all { it is UnreadableRow })
        }
}
