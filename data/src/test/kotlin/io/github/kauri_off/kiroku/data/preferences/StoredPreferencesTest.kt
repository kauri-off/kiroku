package io.github.kauri_off.kiroku.data.preferences

import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
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

    // While set, the disk fails a read or a write of the file.
    @Volatile private var readsFail = false

    @Volatile private var writesFail = false

    // The file's own format, noting the threads the file is read and written on.
    private var beforeWrite: suspend () -> Unit = {}

    private val format =
        object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun readFrom(input: InputStream): Preferences {
                threads += Thread.currentThread()
                if (readsFail) throw IOException("read")
                return PreferencesFileSerializer.readFrom(input)
            }

            override suspend fun writeTo(
                t: Preferences,
                output: OutputStream,
            ) {
                threads += Thread.currentThread()
                beforeWrite()
                if (writesFail) throw IOException("write")
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

    // What the file holds, read as any other version of the app reads it.
    private fun stored(): Preferences =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            PreferenceDataStoreFactory
                .create(scope = scope) { file }
                .data
                .first()
                .also { scope.coroutineContext.job.cancelAndJoin() }
        }

    @Test
    fun `title language and the search filter persist, retain notification choices and stay after sign out`() {
        val notificationSettings = NotificationSettings(on = false, alsoPlanned = true, delay = NotificationDelay.Hour)
        process {
            assertEquals(TitleLanguage.Romaji, it.observeTitleLanguage().first())
            assertEquals(false, it.observeAdultInSearch().first())
            it.writeNotificationSettings(notificationSettings)
            assertEquals(Outcome.Success(Unit), it.setTitleLanguage(TitleLanguage.English))
            assertEquals(Outcome.Success(Unit), it.setAdultInSearch(true))
            it.forgetUsersChoices()
        }
        process {
            assertEquals(TitleLanguage.English, it.observeTitleLanguage().first())
            assertEquals(true, it.observeAdultInSearch().first())
            assertEquals(notificationSettings, it.observeNotificationSettings().first())
            writesFail = true
            assertEquals(Outcome.Failure(DataError.StorageError), it.setTitleLanguage(TitleLanguage.Japanese))
            assertEquals(Outcome.Failure(DataError.StorageError), it.setAdultInSearch(false))
            assertEquals(TitleLanguage.English, it.observeTitleLanguage().first())
            assertEquals(true, it.observeAdultInSearch().first())
            writesFail = false
            assertEquals(Outcome.Success(Unit), it.setTitleLanguage(TitleLanguage.Japanese))
            assertEquals(Outcome.Success(Unit), it.setAdultInSearch(false))
        }
        process {
            assertEquals(TitleLanguage.Japanese, it.observeTitleLanguage().first())
            assertEquals(false, it.observeAdultInSearch().first())
            assertEquals(notificationSettings, it.observeNotificationSettings().first())
        }
    }

    @Test
    fun `unreadable settings fall back individually and are reported once`() {
        written(stringPreferencesKey("title_language") to "unknown", stringPreferencesKey("adult_in_search") to "true")
        process {
            repeat(2) { _ ->
                assertEquals(TitleLanguage.Romaji, it.observeTitleLanguage().first())
                assertEquals(false, it.observeAdultInSearch().first())
            }
        }
        assertEquals(2, reports.size)
    }

    @Test
    fun `the same settings observers recover after a read error and a successful write`() {
        written(stringPreferencesKey("title_language") to "japanese", booleanPreferencesKey("adult_in_search") to true)
        readsFail = true
        process { settings ->
            coroutineScope {
                val changes = Channel<Pair<TitleLanguage, Boolean>>(Channel.UNLIMITED)
                val observed =
                    launch {
                        combine(settings.observeTitleLanguage(), settings.observeAdultInSearch(), ::Pair).collect { changes.send(it) }
                    }
                try {
                    withTimeout(5_000) {
                        assertEquals(TitleLanguage.Romaji to false, changes.receive())
                        readsFail = false
                        assertEquals(Outcome.Success(Unit), settings.setTitleLanguage(TitleLanguage.English))
                        while (changes.receive() != (TitleLanguage.English to true)) Unit
                    }
                } finally {
                    observed.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `two outstanding toggle operations advance the choice twice`() {
        process { settings ->
            coroutineScope {
                val first = async { settings.changeAdultInSearch { !it } }
                val second = async { settings.changeAdultInSearch { !it } }
                assertEquals(Outcome.Success(Unit), first.await())
                assertEquals(Outcome.Success(Unit), second.await())
                val current = settings.observeAdultSearchChoice().first()
                assertFalse(current.enabled)
                assertEquals(2L, current.revision)
            }
        }
        process { assertEquals(2L, it.observeAdultSearchChoice().first().revision) }
    }

    private val opened = stringPreferencesKey("opened_profiles")

    @Test
    fun `Manga sorts survive reopening the file and stay separate by status media and sign-in`() {
        val owner = SignInId("first")
        val replacement = SignInId("first_other")
        val planned = MangaReadingStatus("plan_to_read")
        val unfamiliar = MangaReadingStatus("__")
        val mangaScore = TabSort(ListSort.Score, SortDirection.Ascending)
        val mangaTitle = TabSort(ListSort.Title, SortDirection.Descending)
        val anime = TabSort(ListSort.StartDate, SortDirection.Descending)
        process {
            it.setListSort(ListStatus.Planned, anime)
            it.setMangaListSort(owner, planned, mangaScore)
            it.setMangaListSort(owner, unfamiliar, mangaTitle)
            it.setMangaListSort(replacement, planned, mangaTitle)
        }
        process {
            assertEquals(anime, it.observeListSorts().first()[ListStatus.Planned])
            assertEquals(mapOf(planned to mangaScore, unfamiliar to mangaTitle), it.mangaSorts(owner))
            assertEquals(mapOf(planned to mangaTitle), it.mangaSorts(replacement))
            it.forgetUsersChoices(keepMangaFor = replacement)
            assertTrue(it.mangaSorts(owner).isEmpty())
            assertEquals(mapOf(planned to mangaTitle), it.mangaSorts(replacement))
            it.forgetUsersChoices()
            assertTrue(it.mangaSorts(replacement).isEmpty())
        }
    }

    @Test
    fun `a recreated task waits for the pending Manga sort instead of loading the old committed choice`() =
        process { preferences ->
            val owner = SignInId("reader")
            val reading = MangaReadingStatus("reading")
            val pending = TabSort(ListSort.Score)
            val latest = TabSort(ListSort.Score, SortDirection.Ascending)
            preferences.setMangaListSort(owner, reading, TabSort(ListSort.Title))
            val writing = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            beforeWrite = {
                writing.complete(Unit)
                finish.await()
            }
            coroutineScope {
                val write = async { preferences.setMangaListSort(owner, reading, pending) }
                writing.await()
                val direction = async(start = CoroutineStart.UNDISPATCHED) { preferences.setMangaListSort(owner, reading, latest) }
                val recreated = async(start = CoroutineStart.UNDISPATCHED) { preferences.mangaSorts(owner) }
                try {
                    assertFalse(recreated.isCompleted)
                } finally {
                    finish.complete(Unit)
                }
                write.await()
                direction.await()
                assertEquals(mapOf(reading to latest), recreated.await())
            }
        }

    @Test
    fun `startup reconciles retired Manga sorts after a failed deletion and preserves the replacement`() {
        val retired = SignInId("retired")
        val replacement = SignInId("replacement")
        val reading = MangaReadingStatus("reading")
        val sort = TabSort(ListSort.Score, SortDirection.Ascending)
        process {
            it.setMangaListSort(retired, reading, TabSort(ListSort.Title))
            it.setMangaListSort(replacement, reading, sort)
            writesFail = true
            assertEquals(Outcome.Failure(DataError.StorageError), it.reconcileMangaListSorts(replacement))
            assertEquals(mapOf(reading to TabSort(ListSort.Title)), it.mangaSorts(retired))
        }
        writesFail = false
        process {
            assertEquals(Outcome.Success(Unit), it.reconcileMangaListSorts(replacement))
            assertTrue(it.mangaSorts(retired).isEmpty())
            assertEquals(mapOf(reading to sort), it.mangaSorts(replacement))
        }
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
    fun `a write the disk fails is reported, the choices before it hold, and the next write is kept`() {
        val score = TabSort(ListSort.Score, SortDirection.Ascending)
        process {
            it.setListSort(ListStatus.Completed, score)
            writesFail = true
            it.setListSort(ListStatus.Completed, TabSort(ListSort.Title))
            assertEquals(1, reports.size)
            assertTrue(reports.single() is PreferencesFailure)
            assertEquals(score, it.observeListSorts().first().getValue(ListStatus.Completed))
            writesFail = false
            it.setListSort(ListStatus.Planned, TabSort(ListSort.Title))
        }
        process {
            val sorts = it.observeListSorts().first()
            assertEquals(score, sorts.getValue(ListStatus.Completed))
            assertEquals(TabSort(ListSort.Title), sorts.getValue(ListStatus.Planned))
        }
        assertEquals(1, reports.size)
    }

    @Test
    fun `forgetting the user's choices takes the sorts and the profiles opened and leaves the rest, and a disk that fails it throws`() {
        val score = TabSort(ListSort.Score, SortDirection.Ascending)
        val other = stringPreferencesKey("another_choice")
        written(other to "kept", sort("dropped") to "score", direction("dropped") to "ascending")
        process {
            it.setListSort(ListStatus.Completed, score)
            it.updateOpenedProfiles { listOf("sample_viewer") }
            writesFail = true
            assertTrue(runCatching { it.forgetUsersChoices() }.exceptionOrNull() is PreferencesFailure)
            writesFail = false
            assertEquals(score, it.observeListSorts().first().getValue(ListStatus.Completed))
            it.forgetUsersChoices()
            assertEquals(defaults, it.observeListSorts().first())
            assertEquals(emptyList<String>(), it.observeOpenedProfiles().first())
        }
        process {
            assertEquals(defaults, it.observeListSorts().first())
            assertEquals(emptyList<String>(), it.observeOpenedProfiles().first())
        }
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(other, "kept")), stored().asMap())
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `the profiles opened are kept in their order for a new process, each change made on the names kept`() {
        process {
            assertEquals(emptyList<String>(), it.observeOpenedProfiles().first())
            it.updateOpenedProfiles { names -> listOf("b_two") + names }
            it.updateOpenedProfiles { names -> listOf("a-one") + names }
        }
        process {
            assertEquals(listOf("a-one", "b_two"), it.observeOpenedProfiles().first())
            it.updateOpenedProfiles { emptyList() }
        }
        process { assertEquals(emptyList<String>(), it.observeOpenedProfiles().first()) }
        assertEquals(emptyMap<Preferences.Key<*>, Any>(), stored().asMap())
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `profiles opened that the app would not open are none, are reported once, and the next one opened is kept`() {
        written(opened to "sample_viewer\nnot a name")
        process {
            assertEquals(emptyList<String>(), it.observeOpenedProfiles().first())
            assertEquals(emptyList<String>(), it.observeOpenedProfiles().first())
            it.updateOpenedProfiles { names -> listOf("sample_viewer") + names }
        }
        process { assertEquals(listOf("sample_viewer"), it.observeOpenedProfiles().first()) }
        assertEquals(1, reports.size)
        assertTrue(reports.single() is PreferencesFailure)
    }

    @Test
    fun `a file the disk cannot read gives every tab its default and is reported`() {
        process { it.setListSort(ListStatus.Completed, TabSort(ListSort.Score)) }
        readsFail = true
        process { assertEquals(defaults, it.observeListSorts().first()) }
        assertEquals(1, reports.size)
        assertTrue(reports.single() is PreferencesFailure)
        readsFail = false
        process { assertEquals(TabSort(ListSort.Score), it.observeListSorts().first().getValue(ListStatus.Completed)) }
    }

    // The codes are the file's format: a file written with them is read as these sorts, and a choice is written so.
    @Test
    fun `every status, sort and direction is stored under its own code`() {
        val raw =
            mapOf(
                "watching" to ("up_next" to null),
                "plan_to_watch" to ("title" to "descending"),
                "completed" to ("last_updated" to "ascending"),
                "on_hold" to ("score" to "descending"),
                "dropped" to ("start_date" to "ascending"),
            )
        val sorts =
            mapOf(
                ListStatus.Watching to TabSort(ListSort.UpNext),
                ListStatus.Planned to TabSort(ListSort.Title, SortDirection.Descending),
                ListStatus.Completed to TabSort(ListSort.LastUpdated, SortDirection.Ascending),
                ListStatus.OnHold to TabSort(ListSort.Score, SortDirection.Descending),
                ListStatus.Dropped to TabSort(ListSort.StartDate, SortDirection.Ascending),
            )
        written(
            *raw
                .flatMap { (status, codes) ->
                    listOfNotNull(sort(status) to codes.first, codes.second?.let { direction(status) to it })
                }.toTypedArray(),
        )
        process { assertEquals(sorts, it.observeListSorts().first()) }

        file.delete()
        process { store -> sorts.forEach { (status, sort) -> store.setListSort(status, sort) } }
        val stored = stored().asMap().mapKeys { it.key.name }
        val expected =
            raw
                .flatMap { (status, codes) ->
                    listOfNotNull("list_sort_$status" to codes.first, codes.second?.let { "list_direction_$status" to it })
                }.toMap()
        assertEquals(expected, stored)
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `notification settings written are read by a new process over the same file, under their codes, and stay when the sorts go`() {
        process { assertEquals(NotificationSettings(), it.observeNotificationSettings().first()) }
        val codes =
            mapOf(
                NotificationDelay.None to "none",
                NotificationDelay.HalfHour to "half_hour",
                NotificationDelay.Hour to "hour",
                NotificationDelay.ThreeHours to "three_hours",
            )
        for ((delay, code) in codes) {
            val chosen = NotificationSettings(on = delay.ordinal % 2 == 0, alsoPlanned = delay.ordinal % 2 == 1, delay = delay)
            process { it.writeNotificationSettings(chosen) }
            process { assertEquals(chosen, it.observeNotificationSettings().first()) }
            val stored = stored().asMap().mapKeys { it.key.name }
            assertEquals(mapOf("notify_on" to chosen.on, "notify_also_planned" to chosen.alsoPlanned, "notify_delay" to code), stored)
        }
        val chosen = NotificationSettings(on = false, alsoPlanned = true, delay = NotificationDelay.Hour)
        process {
            it.writeNotificationSettings(chosen)
            it.setListSort(ListStatus.Completed, TabSort(ListSort.Score))
            it.forgetUsersChoices()
        }
        process { assertEquals(chosen, it.observeNotificationSettings().first()) }
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `a notification setting that cannot be read counts as never made, is reported once, and the others keep theirs`() {
        val on = booleanPreferencesKey("notify_on")
        val planned = booleanPreferencesKey("notify_also_planned")
        val delay = stringPreferencesKey("notify_delay")
        val made = NotificationSettings(on = false, alsoPlanned = true, delay = NotificationDelay.ThreeHours)
        val unreadable =
            listOf(
                arrayOf(stringPreferencesKey("notify_on") to "false", planned to true, delay to "three_hours") to made.copy(on = true),
                arrayOf(on to false, intPreferencesKey("notify_also_planned") to 1, delay to "three_hours") to
                    made.copy(alsoPlanned = false),
                arrayOf(on to false, planned to true, delay to "two_hours") to made.copy(delay = NotificationDelay.None),
                arrayOf(on to false, planned to true, booleanPreferencesKey("notify_delay") to true) to
                    made.copy(delay = NotificationDelay.None),
            )
        for ((values, expected) in unreadable) {
            file.delete()
            reports.clear()
            written(*values)
            val read =
                process {
                    val first = it.observeNotificationSettings().first()
                    it.setListSort(ListStatus.OnHold, TabSort(ListSort.Title))
                    assertEquals(first, it.observeNotificationSettings().first())
                    first
                }
            val case = values.joinToString()
            assertEquals(case, expected, read)
            assertEquals(case, 1, reports.size)
            assertTrue(case, reports.single() is PreferencesFailure)
        }
    }

    @Test
    fun `a notification setting the disk cannot write throws and leaves the one before, and an unreadable file gives defaults`() {
        val chosen = NotificationSettings(on = false)
        process {
            it.writeNotificationSettings(chosen)
            writesFail = true
            val failed = runCatching { it.writeNotificationSettings(NotificationSettings(delay = NotificationDelay.Hour)) }
            assertTrue(failed.exceptionOrNull() is PreferencesFailure)
            writesFail = false
            assertEquals(chosen, it.observeNotificationSettings().first())
        }
        assertEquals(emptyList<Throwable>(), reports)
        readsFail = true
        process { assertEquals(NotificationSettings(), it.observeNotificationSettings().first()) }
        assertEquals(1, reports.size)
        readsFail = false
        process { assertEquals(chosen, it.observeNotificationSettings().first()) }
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
