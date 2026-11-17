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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomNotificationEngineTest : NotificationEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val folder = TemporaryFolder()

    override fun store(scope: TestScope): NotificationStore {
        val database = databases.open(scope)
        val seeded = scope.launch { database.titles().insertIfAbsent(listOf(NotifyCases.title.toRow(), NotifyCases.other.toRow())) }
        scope.runCurrent()
        check(seeded.isCompleted)
        val file = File(folder.newFolder(), "notify.preferences_pb")
        val preferences = StoredPreferences(file, scope.backgroundScope, { throw it }, scope.dispatcher, PreferencesFileSerializer)
        return RoomNotificationStore(database, preferences, scope.dispatcher) { throw it }
    }
}
