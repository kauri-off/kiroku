package io.github.kauri_off.kiroku.data.preferences

import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

class StoredPreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "account.preferences_pb")
    private val reports = CopyOnWriteArrayList<Throwable>()
    private val threads = CopyOnWriteArraySet<Thread>()
    private val defaults = ListStatus.entries.associateWith(TabSort::defaultFor)

    // The file's own format, noting the threads the file is read and written on.
    private val format =
        object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun readFrom(input: InputStream): Preferences {
                threads += Thread.currentThread()
                return PreferencesFileSerializer.readFrom(input)
            }

            override suspend fun writeTo(
                t: Preferences,
                output: OutputStream,
            ) {
                threads += Thread.currentThread()
                PreferencesFileSerializer.writeTo(t, output)
            }
        }

    // One process over the file: its store ends with it, as when the process ends.
    private fun <T> process(block: suspend (StoredPreferences) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                block(StoredPreferences(file, scope, reports::add, Dispatchers.IO, format))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    // What another version of the app wrote into the file.
    private fun written(vararg values: Preferences.Pair<*>) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            PreferenceDataStoreFactory.create(scope = scope) { file }.edit { it.putAll(*values) }
            scope.coroutineContext.job.cancelAndJoin()
        }

    private fun sort(status: String) = stringPreferencesKey("list_sort_$status")

    private fun direction(status: String) = stringPreferencesKey("list_direction_$status")

    @Test
    fun `with nothing chosen every tab has its default, and a new process over the same file reads the chosen sorts`() {
        process { assertEquals(defaults, it.observeListSorts().first()) }
        process {
            it.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending))
            it.setListSort(ListStatus.Watching, TabSort(ListSort.Title, SortDirection.Descending))
            it.setListSort(ListStatus.Planned, TabSort(ListSort.StartDate))
        }
        val chosen =
            defaults +
                mapOf(
                    ListStatus.Completed to TabSort(ListSort.Score, SortDirection.Ascending),
                    ListStatus.Watching to TabSort(ListSort.Title, SortDirection.Descending),
                    ListStatus.Planned to TabSort(ListSort.StartDate, SortDirection.Ascending),
                )
        process { assertEquals(chosen, it.observeListSorts().first()) }
        process { it.setListSort(ListStatus.Watching, TabSort(ListSort.UpNext)) }
        process { assertEquals(chosen + (ListStatus.Watching to TabSort(ListSort.UpNext)), it.observeListSorts().first()) }
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `a choice that cannot be read is its tab's default, reported once, and the other tabs keep theirs`() {
        val unreadable =
            listOf(
                arrayOf(sort("watching") to "next_airing"),
                arrayOf(sort("watching") to "title", direction("watching") to "sideways"),
                arrayOf(sort("watching") to "title"),
                arrayOf(sort("watching") to "up_next", direction("watching") to "ascending"),
                arrayOf(direction("watching") to "ascending"),
                arrayOf(intPreferencesKey("list_sort_watching") to 2),
                arrayOf(sort("plan_to_watch") to "up_next"),
            )
        for (values in unreadable) {
            file.delete()
            reports.clear()
            written(sort("dropped") to "score", direction("dropped") to "ascending", *values)
            val read =
                process {
                    val first = it.observeListSorts().first()
                    it.setListSort(ListStatus.OnHold, TabSort(ListSort.Title))
                    assertEquals(first + (ListStatus.OnHold to TabSort(ListSort.Title)), it.observeListSorts().first())
                    first
                }
            val case = values.joinToString()
            assertEquals(case, defaults + (ListStatus.Dropped to TabSort(ListSort.Score, SortDirection.Ascending)), read)
            assertEquals(case, 1, reports.size)
            assertTrue(case, reports.single() is PreferencesFailure)
        }
    }

    @Test
    fun `a damaged file is every tab's default, is reported, and the next choice is kept`() {
        file.writeText("not a preferences file")
        process { assertEquals(defaults, it.observeListSorts().first()) }
        assertEquals(1, reports.size)
        assertTrue(reports.single() is PreferencesFailure)
        process { it.setListSort(ListStatus.Dropped, TabSort(ListSort.Title)) }
        process { assertEquals(defaults + (ListStatus.Dropped to TabSort(ListSort.Title)), it.observeListSorts().first()) }
        assertEquals(1, reports.size)
    }

    @Test
    fun `the file is read and written off the thread that asks`() {
        val asking = Thread.currentThread()
        process { it.observeListSorts().first() }
        process {
            it.setListSort(ListStatus.Completed, TabSort(ListSort.Score))
            it.observeListSorts().first()
        }
        assertTrue(threads.isNotEmpty())
        assertFalse(threads.toString(), asking in threads)
    }
}
