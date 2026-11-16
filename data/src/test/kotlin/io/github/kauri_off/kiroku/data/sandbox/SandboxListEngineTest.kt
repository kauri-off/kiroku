package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.demo.DemoServer
import io.github.kauri_off.kiroku.core.list.ListEngineContract
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.RemoteList
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.TestDatabases
import io.github.kauri_off.kiroku.data.db.dispatcher
import io.github.kauri_off.kiroku.data.list.MalRemoteList
import io.github.kauri_off.kiroku.data.session.SessionFixture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock

// The engine over MAL's real client, the sandbox and its write rules, and the database; the contract's server scripts failures.
@RunWith(RobolectricTestRunner::class)
class SandboxListEngineTest : ListEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: TestScope
    private val sessions = mutableListOf<SessionFixture>()
    private val served = mutableListOf<Pair<DemoServer, SandboxMal>>()

    override fun local(scope: TestScope): LocalList {
        this.scope = scope
        return RoomLocalList(databases.open(scope), scope.backgroundScope, scope.dispatcher) { throw it }
    }

    override fun remote(server: DemoServer): RemoteList {
        val tokens = SandboxTokens(Clock.systemUTC())
        val mal = SandboxMal(DemoServerList(server), tokens)
        val client = SandboxClient(mal, io = scope.dispatcher, inline = true)
        served += server to mal
        val signedIn = client.signedIn(folder.newFolder(), tokens)
        sessions += signedIn.sessions
        server.reachedThrough = MalRemoteList(client.http, signedIn.session)
        return server
    }

    @After
    fun close() = runBlocking { sessions.forEach { it.close() } }

    // Each page of a list, write and read of one entry the scripted server let through was the client's, answered by the sandbox.
    @After
    fun answeredByTheSandbox() {
        for ((server, mal) in served) {
            val pages = mal.asked.count { it == "GET sandbox.invalid/v2/users/@me/animelist" }
            val writes = mal.asked.count { it.startsWith("PATCH ") || it.startsWith("DELETE ") }
            val entries = mal.asked.count { it.startsWith("GET sandbox.invalid/v2/anime/") }
            assertEquals(
                "pages, writes and reads of one entry",
                listOf(server.listPagesRead, server.writesAnswered, server.entryReadsAnswered),
                listOf(pages, writes, entries),
            )
        }
    }
}
