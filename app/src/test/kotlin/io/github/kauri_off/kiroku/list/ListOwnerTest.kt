package io.github.kauri_off.kiroku.list

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoServer
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

// The List while the list's owner changes under it, and while a download the engine owes waits for the connection.
@OptIn(ExperimentalCoroutinesApi::class)
class ListOwnerTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val set = Fixtures.reference
    private val a = Session.SignedIn(SignInId("a"))
    private val b = Session.SignedIn(SignInId("b"))

    // Choices read with their value as it was when asked; while held, each read waits until it is let go, in turn.
    private class HeldPreferences(
        private val inner: PreferencesRepository,
    ) : PreferencesRepository by inner {
        @Volatile
        var held = false
        val waiting = mutableListOf<CompletableDeferred<Unit>>()

        override fun observeListSorts(): Flow<Map<ListStatus, TabSort>> =
            flow {
                val asked = inner.observeListSorts().first()
                if (held) CompletableDeferred<Unit>().also { waiting += it }.await()
                emit(asked)
            }
    }

    private class Subject(
        val server: DemoServer,
        val sessions: MutableStateFlow<Session>,
        val engine: ListEngine,
        val online: MutableStateFlow<Boolean>,
        val messages: ShellMessages,
        val list: ListViewModel,
    ) {
        fun ownerChanges() {
            sessions.value = Session.SignedOut
            sessions.value = Session.SignedIn(SignInId("b"))
        }
    }

    // A List over the engine, MAL being the demo's server, with a session and a connection the test sets; shown.
    private fun test(
        preferences: (PreferencesRepository) -> PreferencesRepository = { it },
        beforeShown: suspend () -> Unit = {},
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope: CoroutineScope = foregroundScope()
        val demo = Demo(set, scope)
        val server = DemoServer(set.entries, set.clock, Demo.latency)
        val sessions = MutableStateFlow<Session>(a)
        val engine = ListEngine(server, InMemoryLocalList(), sessions, set.clock, scope) { throw it }
        val online = MutableStateFlow(true)
        val session =
            object : SessionRepository {
                override fun observeSession() = sessions

                override suspend fun signOut() {
                    sessions.value = Session.SignedOut
                }
            }
        val connection =
            object : Connection {
                override fun observeOnline() = online
            }
        val messages = ShellMessages(scope, engine)
        val list =
            ListViewModel(
                engine,
                demo.anime,
                demo.airing,
                session,
                connection,
                preferences(demo.preferences),
                set.clock,
                TitleLanguage.Romaji,
                SavedStateHandle(),
                messages,
                Dispatchers.Main,
            )
        beforeShown()
        showing(list.state, list.shown)
        runCurrent()
        body(Subject(server, sessions, engine, online, messages, list))
        scope.cancel()
    }

    // The previous owner's download ends as an expired session after the new owner's began; it must not fail that list.
    @Test
    fun `a sync begun for the previous owner is discarded once the owner changed`() =
        test { s ->
            advanceUntilIdle()
            assertTrue(s.list.state.value.content is ListContent.Tabs)
            s.list.onEvent(ListEvent.Refresh)
            runCurrent()
            s.ownerChanges()
            runCurrent()
            // The previous owner's download has ended; the new owner's follows it.
            advanceTimeBy(1000.milliseconds)
            assertFalse(
                s.list.state.value.content
                    .toString(),
                s.list.state.value.content is ListContent.Failed,
            )
            assertNull(s.messages.current.value)

            advanceUntilIdle()
            val tabs = s.list.state.value.content as ListContent.Tabs
            assertEquals(set.entries.size, tabs.pages.values.sumOf { it.rows.size })
            assertEquals(b, s.sessions.value)
        }

    // The previous owner's sync reads the sorts while the owner changes: what it read was that owner's.
    @Test
    fun `a sort read begun for the previous owner is not kept for the next one`() {
        var held: HeldPreferences? = null
        val hold: suspend () -> Unit = {
            val preferences = checkNotNull(held)
            preferences.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending))
            preferences.held = true
        }
        test(preferences = { HeldPreferences(it).also { p -> held = p } }, beforeShown = hold) { s ->
            val preferences = checkNotNull(held)
            // The first sync has read the list and waits for the sorts, as the List's own read does.
            advanceTimeBy(1000.milliseconds)
            s.ownerChanges()
            preferences.setListSort(ListStatus.Completed, TabSort.defaultFor(ListStatus.Completed))
            runCurrent()
            // The reads end in the order they were asked: the previous owner's before the next owner's.
            preferences.held = false
            assertEquals(3, preferences.waiting.size)
            preferences.waiting.forEach {
                it.complete(Unit)
                runCurrent()
            }
            advanceUntilIdle()

            val tabs = s.list.state.value.content as ListContent.Tabs
            assertEquals(ListSort.LastUpdated, tabs.pages.getValue(ListStatus.Completed).sort)
        }
    }

    @Test
    fun `a download owed after a write failed offline runs when the connection returns while the List is shown`() =
        test { s ->
            advanceUntilIdle()
            assertNotNull(s.engine.observeSyncedAt().first())
            s.online.value = false
            s.server.offline.value = true
            s.engine.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertNull(s.engine.observeSyncedAt().first())
            val downloads = s.server.listDownloads

            s.server.offline.value = false
            s.online.value = true
            advanceUntilIdle()
            assertNotNull(s.engine.observeSyncedAt().first())
            assertEquals(downloads + 1, s.server.listDownloads)
        }
}
