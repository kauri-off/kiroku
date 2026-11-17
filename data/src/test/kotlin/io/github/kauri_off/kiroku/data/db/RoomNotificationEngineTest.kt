package io.github.kauri_off.kiroku.data.db

import androidx.datastore.preferences.core.PreferencesFileSerializer
import io.github.kauri_off.kiroku.core.notify.NotificationEngineContract
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.notify.NotifyCases
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomNotificationEngineTest : NotificationEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val folder = TemporaryFolder()

    private var database: KirokuDatabase? = null

    // What the store reported; any report fails the test but after a damage.
    private val reported = CopyOnWriteArrayList<Throwable>()

    @Volatile
    private var damaged = false

    override suspend fun damageRecord(): Boolean {
        damaged = true
        checkNotNull(database).execute("UPDATE notification_state SET since = NULL")
        return true
    }

    @After
    fun reports() = assertEquals(if (damaged) 1 else 0, reported.count { it is UnreadableRow })

    override fun store(scope: TestScope): NotificationStore {
        val database = databases.open(scope).also { database = it }
        val seeded = scope.launch { database.titles().insertIfAbsent(listOf(NotifyCases.title.toRow(), NotifyCases.other.toRow())) }
        scope.runCurrent()
        check(seeded.isCompleted)
        val file = File(folder.newFolder(), "notify.preferences_pb")
        val preferences = StoredPreferences(file, scope.backgroundScope, { throw it }, scope.dispatcher, PreferencesFileSerializer)
        return RoomNotificationStore(database, preferences, scope.dispatcher) {
            reported += it
            if (!damaged) throw it
        }
    }
}
