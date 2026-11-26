package io.github.kauri_off.kiroku.sandbox

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.sandbox.SandboxWrites
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import io.github.kauri_off.kiroku.wiring.liveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.URI
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

// The sandbox's own profile across processes: each graph on a scope of its own, the first ended before the second opens its files.
@RunWith(RobolectricTestRunner::class)
class SandboxProfileTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val keystore =
        object : ExternalResource() {
            override fun before() = KeysInMemory.install()

            override fun after() = KeysInMemory.uninstall()
        }

    @get:Rule(order = 1)
    val connections = SandboxAppTest.NoConnection()

    @get:Rule(order = 1)
    val network = DeviceOnline()

    // The app's own graph is the demo's, which opens none of the sandbox's files.
    @get:Rule(order = 2)
    val source = StartsOn(DataSource.Demo)

    private val context get() = RuntimeEnvironment.getApplication()
    private val reports = CopyOnWriteArrayList<Throwable>()

    // A process on the sandbox: its graph, run and then ended with all its work.
    private fun <T> process(run: suspend (SandboxGraph) -> T): T =
        sandboxScope { scope ->
            val graph =
                SandboxGraph.create(context, Clock.systemUTC()) { source, device, clock ->
                    LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, reports::add, device = device)
                }
            run(graph)
        }

    private fun <T> sandboxScope(run: suspend (CoroutineScope) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(WAIT) { run(scope) }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    private suspend fun signIn(graph: SandboxGraph): Session.SignedIn {
        graph.signIn.start { error("The sandbox opened a browser") }
        assertEquals(SignInStep.SignedIn, graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
        return graph.session.observeSession().first { it is Session.SignedIn } as Session.SignedIn
    }

    private suspend fun saveManga(graph: SandboxGraph): Session.SignedIn =
        signIn(graph).also { check(graph.mangaList.refresh(it.signIn) is Outcome.Success) }

    @Test
    fun `a new graph over the same files shows the own profile without a request`() {
        val profile =
            process { graph ->
                graph.signIn.start { error("The sandbox opened a browser") }
                assertEquals(SignInStep.SignedIn, graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
                assertEquals(Outcome.Success(Unit), graph.list.sync())
                assertEquals(Outcome.Success(Unit), graph.user.refreshOwnProfile())
                graph.user.observeOwnProfile().first { it != null }
            }

        process { graph ->
            assertEquals(profile, graph.user.observeOwnProfile().first { it != null })
            assertEquals(
                emptyList<String>(),
                graph.sandbox.mal.asked
                    .toList(),
            )
        }
        assertEquals(emptyList<URI>(), connections.asked)
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `sign-out after restart removes both manga tables without opening the list`() {
        val a = process(::saveManga)

        process { graph ->
            assertEquals(a, graph.session.observeSession().first())
            assertEquals(listOf(1L, 4L), mangaRows())
            graph.signOut(a)
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(listOf(0L, 0L), mangaRows())
        }
        assertEquals(emptyList<URI>(), connections.asked)
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `signed-out startup completes manga retirement after the token write survived an interruption`() {
        process(::saveManga)
        sandboxScope { scope ->
            val source = LiveSource("sandbox", SandboxMal.Hosts, standIn = { error("Unexpected HTTP request") })
            val parts = LiveParts(context, source, SandboxMal.CLIENT_ID, Clock.systemUTC(), scope, reports::add)
            val account = liveSession(parts)
            account.signOut()
            assertEquals(Session.SignedOut, account.currentSession())
            assertEquals(listOf(1L, 4L), mangaRows())
        }

        process { graph ->
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            awaitMangaRows(listOf(0L, 0L))
        }
        assertEquals(emptyList<URI>(), connections.asked)
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `the sandbox manga editor shares reads and isolates writable fixtures by sign in`() {
        process { graph ->
            val first = signIn(graph).signIn
            val editor = graph.mangaListEditor
            val baseline = (graph.mangaList.refresh(first) as Outcome.Success).value
            val entry = baseline.entries.first()
            val changes = MangaListEdit(MangaListStatus.Completed, score = 0, chaptersRead = 23, volumesRead = 3)

            graph.sandbox.mal.writes = SandboxWrites.Refused
            assertEquals(MangaListEditResult.Failed(DataError.Refused), editor.edit(first, entry.id, changes))
            assertEquals(Outcome.Success(baseline), graph.mangaList.stored(first))

            graph.sandbox.mal.writes = SandboxWrites.Work
            val saved = (editor.edit(first, entry.id, changes) as MangaListEditResult.Confirmed).entry
            assertEquals(changes.appliedTo(entry), saved.copy(updatedAt = entry.updatedAt))
            val observed = (editor.observe(first).first() as Outcome.Success<MangaListSnapshot?>).value
            assertEquals(saved, observed?.entries?.first())
            val refreshed = (graph.mangaList.refresh(first) as Outcome.Success).value
            assertEquals(saved, refreshed.entries.first())

            val second = signIn(graph).signIn
            assertNotEquals(first, second)
            assertEquals(MangaListEditResult.Failed(DataError.ListEnded), editor.edit(first, entry.id, changes))
            val replacement = (graph.mangaList.refresh(second) as Outcome.Success).value
            assertEquals(baseline.entries, replacement.entries)
        }
        assertEquals(emptyList<URI>(), connections.asked)
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `first manga access starts cleanup for a replacement in the same process`() {
        process { graph ->
            val first = saveManga(graph)
            assertEquals(listOf(1L, 4L), mangaRows())
            val second = signIn(graph)
            assertNotEquals(first.signIn, second.signIn)
            awaitMangaRows(listOf(0L, 0L))
        }
        assertEquals(emptyList<URI>(), connections.asked)
        assertEquals(emptyList<Throwable>(), reports)
    }

    @Test
    fun `re-sign-in retires an expired owner's manga on a restarted graph without using the reader`() {
        val first =
            process { graph ->
                val account = saveManga(graph)
                graph.sandbox.mal.rejecting = true
                assertEquals(Outcome.Failure(DataError.SessionExpired), graph.mangaList.refresh(account.signIn))
                account
            }

        val second =
            process { graph ->
                assertEquals(Session.Expired(first.signIn), graph.session.observeSession().first())
                assertEquals(listOf(1L, 4L), mangaRows())
                val account = signIn(graph)
                assertNotEquals(first.signIn, account.signIn)
                awaitMangaRows(listOf(0L, 0L))
                check(graph.mangaList.refresh(account.signIn) is Outcome.Success)
                account
            }

        process { graph ->
            assertEquals(second, graph.session.observeSession().first())
            assertEquals(listOf(1L, 4L), mangaRows())
            val snapshot = (graph.mangaList.stored(second.signIn) as Outcome.Success).value
            assertEquals(second.signIn, snapshot?.owner)
            assertEquals(listOf(1L, 4L), mangaRows())
        }
        assertEquals(emptyList<URI>(), connections.asked)
        assertEquals(emptyList<Throwable>(), reports)
    }

    private suspend fun awaitMangaRows(rows: List<Long>) {
        while (mangaRows() != rows) delay(10)
    }

    private fun mangaRows(): List<Long> =
        SQLiteDatabase.openDatabase(context.getDatabasePath("sandbox.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("manga_list_state", "manga_list_entry").map { DatabaseUtils.queryNumEntries(db, it) }
        }

    private companion object {
        const val WAIT = 20_000L
    }
}
