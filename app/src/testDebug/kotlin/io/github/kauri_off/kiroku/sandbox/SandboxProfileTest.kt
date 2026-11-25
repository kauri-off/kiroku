package io.github.kauri_off.kiroku.sandbox

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.LiveParts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val graph =
                    SandboxGraph.create(context, Clock.systemUTC()) { source, device, clock ->
                        LiveParts(context, source, SandboxMal.CLIENT_ID, clock, scope, reports::add, device = device)
                    }
                withTimeout(WAIT) { run(graph) }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

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
        val a =
            process { graph ->
                graph.signIn.start { error("The sandbox opened a browser") }
                assertEquals(SignInStep.SignedIn, graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
                val account = graph.session.observeSession().first { it is Session.SignedIn } as Session.SignedIn
                check(graph.mangaList.refresh(account.signIn) is Outcome.Success)
                account
            }

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

    private fun mangaRows(): List<Long> =
        SQLiteDatabase.openDatabase(context.getDatabasePath("sandbox.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("manga_list_state", "manga_list_entry").map { DatabaseUtils.queryNumEntries(db, it) }
        }

    private companion object {
        const val WAIT = 20_000L
    }
}
