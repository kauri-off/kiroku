package io.github.kauri_off.kiroku.sandbox

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.sandbox.SandboxTitles
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.source.runningSource
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// The app on the sandbox, signed in, synced and written through MAL's real client, with no request leaving the device.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class SandboxAppTest {
    // A connection that asks the process's default proxy selector, as every OkHttp client without its own does: none may.
    class NoConnection : ExternalResource() {
        val asked = CopyOnWriteArrayList<URI>()
        private var before: ProxySelector? = null

        override fun before() {
            before = ProxySelector.getDefault()
            ProxySelector.setDefault(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        asked += uri
                        throw IOException("A connection was asked for")
                    }

                    override fun connectFailed(
                        uri: URI,
                        address: SocketAddress,
                        failure: IOException,
                    ) = Unit
                },
            )
        }

        override fun after() = ProxySelector.setDefault(before)
    }

    @get:Rule(order = 0)
    val keystore = TestKeystore()

    @get:Rule(order = 1)
    val connections = NoConnection()

    @get:Rule(order = 2)
    val source = StartsOn(DataSource.Sandbox)

    // The real account's files, as a sign-in and a sync left them, which the sandbox must never open.
    class AccountFiles : ExternalResource() {
        private val context get() = RuntimeEnvironment.getApplication()
        private val folders get() = listOf(context.noBackupFilesDir, context.getDatabasePath("account.db").parentFile!!)
        val seeded = mutableMapOf<File, ByteArray>()

        override fun before() {
            val files =
                listOf("tokens", "preferences_pb", "tokens.sign-in").map { File(folders[0], "account.$it") } +
                    File(folders[1], "account.db")
            for (file in files) {
                val bytes = "the real account's ${file.name}".encodeToByteArray()
                file.parentFile!!.mkdirs()
                file.writeBytes(bytes)
                seeded[file] = bytes
            }
        }

        fun files(): Map<File, ByteArray> =
            folders.flatMap { it.listFiles().orEmpty().toList() }.filter { it.name.startsWith("account.") }.associateWith { it.readBytes() }

        fun sandbox(): Set<String> = folders.flatMap { it.list().orEmpty().toList() }.filter { it.startsWith("sandbox.") }.toSet()
    }

    @get:Rule(order = 3)
    val account = AccountFiles()

    @get:Rule(order = 4)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = RuntimeEnvironment.getApplication()
    private val graph get() = context.appGraph as SandboxGraph

    // A wait that ends in time; one that does not, or fails, fails first on a connection, if it asked for one.
    private fun <T> waited(block: suspend () -> T): T =
        try {
            runBlocking { withTimeout(WAIT) { block() } }
        } catch (e: Exception) {
            assertEquals(emptyList<URI>(), connections.asked)
            throw e
        }

    // A blocking call of the image client, given up within the wait, so a client that leaves the device fails at once.
    private fun picture(address: String): Int =
        waited {
            val call = checkNotNull(graph.images).newCall(Request.Builder().url(address).build())
            call.timeout().timeout(WAIT / 2, TimeUnit.MILLISECONDS)
            withContext(Dispatchers.IO) { runInterruptible { call.execute().use { it.code } } }
        }

    private fun signIn() =
        waited {
            graph.signIn.start { error("The sandbox opened a browser") }
            val end = graph.signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed }
            assertEquals(emptyList<URI>(), connections.asked)
            assertEquals(SignInStep.SignedIn, end)
            assertEquals(Session.SignedIn::class, graph.session.observeSession().first()::class)
        }

    private fun shows(text: String) =
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `signed in, synced, written, a title refreshed and pictures asked for, no request leaves the device`() {
        assertEquals(DataSource.Sandbox, context.runningSource)
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val entries: List<ListEntry> = waited { graph.list.observeEntries().first { it.isNotEmpty() } }
        assertEquals(ListStatus.entries.toSet(), entries.map { it.status }.toSet())
        val watching = entries.first { it.status == ListStatus.Watching }
        waited { graph.list.send(ListWrite.MarkNextWatched(watching.anime)) }
        assertEquals(
            watching.values.watchedEpisodes + 1,
            waited { graph.list.observeEntry(watching.anime.id).first() }!!.values.watchedEpisodes,
        )
        assertEquals(Outcome.Success(Unit), waited { graph.anime.refresh(watching.anime.id) })
        for (address in listOf(checkNotNull(watching.anime.posterUrl), "https://cdn.myanimelist.net/images/anime/4/19644.webp")) {
            assertEquals(404, picture(address))
        }
        graph.scenarios.set(Scenario(sessionExpired = true))
        assertEquals(Outcome.Failure(DataError.SessionExpired), waited { graph.list.sync() })

        assertEquals(emptyList<URI>(), connections.asked)
        val asked =
            graph.sandbox.mal.asked
                .toSet()
        val id = watching.anime.id.value
        val expected =
            listOf(
                "POST sandbox.invalid/v1/oauth2/token",
                "GET sandbox.invalid/v2/users/@me/animelist",
                "PATCH sandbox.invalid/v2/anime/$id/my_list_status",
                "GET sandbox.invalid/v2/anime/$id",
                "GET example.invalid/kiroku/poster/$id.jpg",
                "GET cdn.myanimelist.net/images/anime/4/19644.webp",
            )
        for (request in expected) assertTrue(request, request in asked)
    }

    // A1: the sandbox keeps to its own files; the real account's stay as they were, and no new one appears.
    @Test
    fun `signed in, synced and written on the sandbox, the real account's files are as they were`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val watching = waited { graph.list.observeEntries().first { it.isNotEmpty() } }.first { it.status == ListStatus.Watching }
        waited { graph.list.send(ListWrite.MarkNextWatched(watching.anime)) }
        waited { graph.preferences.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending)) }

        val files = account.files()
        assertEquals(account.seeded.keys, files.keys)
        for ((file, bytes) in account.seeded) assertTrue(file.name, bytes.contentEquals(files.getValue(file)))
        val own = account.sandbox()
        for (kind in listOf("tokens", "db", "preferences_pb", "mal.list", "mal.tokens")) assertTrue(kind, "sandbox.$kind" in own)
    }

    // S5 by the panel: the access token expires, and the next request is rejected once, refreshed and asked again.
    @Test
    fun `the panel's access token expired is followed by one refresh and the request asked again, still signed in`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val before = graph.sandbox.mal.asked.size
        graph.scenarios.expireAccess()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val since =
            graph.sandbox.mal.asked
                .drop(before)
        assertTrue(since.toString(), "POST sandbox.invalid/v1/oauth2/token" in since)
        assertTrue(waited { graph.session.observeSession().first() } is Session.SignedIn)
        assertEquals(emptyList<URI>(), connections.asked)
    }

    // S6 by the panel: the sandbox rejects the token and then the session's refresh, and every screen shows the banner.
    @Test
    fun `the panel's session expired ends in the banner through a rejected refresh`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val title =
            waited { graph.list.observeEntries().first { it.isNotEmpty() } }
                .first { it.status == ListStatus.Watching }
                .anime.titles.romaji
        shows(title)
        val before = graph.sandbox.mal.asked.size

        graph.scenarios.set(Scenario(sessionExpired = true))
        assertEquals(Outcome.Failure(DataError.SessionExpired), waited { graph.list.sync() })
        shows("Session expired")
        assertTrue(waited { graph.session.observeSession().first() } is Session.Expired)
        val since =
            graph.sandbox.mal.asked
                .drop(before)
        assertTrue(since.toString(), "POST sandbox.invalid/v1/oauth2/token" in since)
        assertEquals(emptyList<URI>(), connections.asked)
    }

    // No user's list values and no title not safe for work reach a debug or preview build.
    @Test
    fun `the build's assets are the public answers the sandbox reads, and nothing else`() {
        assertTrue(runCatching { context.assets.open("authorization/token.json").close() }.isFailure)
        assertEquals(SandboxTitles.Answers.map { "$it.200.json" }.sorted(), context.assets.list("recorded")!!.sorted())
        assertTrue(SandboxTitles.Answers.none { it.startsWith("list_") || "adult" in it })
    }

    private companion object {
        const val WAIT = 20_000L
    }
}
