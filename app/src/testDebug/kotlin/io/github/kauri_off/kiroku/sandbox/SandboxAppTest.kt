package io.github.kauri_off.kiroku.sandbox

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.Freshness
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring
import io.github.kauri_off.kiroku.data.sandbox.SandboxAniListFault
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.sandbox.SandboxTenrai
import io.github.kauri_off.kiroku.data.sandbox.SandboxTitles
import io.github.kauri_off.kiroku.data.sandbox.SandboxUsers
import io.github.kauri_off.kiroku.profile.ProfileTags
import io.github.kauri_off.kiroku.scenario.AniListFault
import io.github.kauri_off.kiroku.scenario.ResetState
import io.github.kauri_off.kiroku.scenario.Scenario
import io.github.kauri_off.kiroku.scenario.WriteFault
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import io.github.kauri_off.kiroku.source.runningSource
import io.github.kauri_off.kiroku.wiring.AppEnds
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.toJavaDuration

// The app on the sandbox, signed in, synced and written through MAL's real client, and asking AniList, with no request leaving the device.
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

    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    @get:Rule(order = 0)
    val keystore =
        object : ExternalResource() {
            override fun before() = KeysInMemory.install()

            override fun after() = KeysInMemory.uninstall()
        }

    @get:Rule(order = 1)
    val connections = NoConnection()

    @get:Rule(order = 1)
    val network = DeviceOnline()

    @get:Rule(order = 2)
    val source = StartsOn(DataSource.Sandbox)

    @get:Rule(order = 2)
    val hurried = HurriedAniList()

    @get:Rule(order = 2)
    val time = MovedClock()

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
            val looper = shadowOf(Looper.getMainLooper())
            runBlocking {
                withTimeout(WAIT) {
                    val result = async(start = CoroutineStart.UNDISPATCHED) { block() }
                    while (!result.isCompleted) {
                        looper.idle()
                        delay(1)
                    }
                    result.await()
                }
            }
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

    // A query to AniList through the app's one client, which the sandbox answers as AniList answers a query it cannot run.
    private fun aniList(): Int =
        waited {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val call =
                checkNotNull(graph.images).newCall(
                    Request
                        .Builder()
                        .url("https://graphql.anilist.co")
                        .post(body)
                        .build(),
                )
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

    private fun shows(
        text: String,
        substring: Boolean = false,
    ) = compose.waitUntil(WAIT) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty()
    }

    // The rows of a table of a database file, read as another connection would.
    private fun rows(
        database: String,
        table: String,
    ): Long =
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).path, null, SQLiteDatabase.OPEN_READONLY).use {
            DatabaseUtils.queryNumEntries(it, table)
        }

    @Test
    fun `seasonal browse in Sandbox serves the selected period from fixtures without a transport request`() {
        val id = FixtureAnime.exactToWatch.id
        val fixtures = Fixtures.at(graph.clock)
        val period = checkNotNull(fixtures.details[id]?.season)
        val page = waited { graph.search.openSeason(period).load(0, includeAdult = false) } as Outcome.Success
        assertTrue(page.value.items.any { it.item.id == id && it.position == null })

        val earlier = period.copy(year = period.year - 100)
        val empty = waited { graph.search.openSeason(earlier).load(0, includeAdult = false) } as Outcome.Success
        assertTrue(empty.value.items.isEmpty())
        val requests = graph.sandbox.mal.asked
        assertTrue(requests.none { "/anime/season/" in it })
        assertEquals(emptyList<URI>(), connections.asked)
    }

    @Test
    fun `signed in, synced, written, a title refreshed, pictures and AniList asked for, no request leaves the device`() {
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
        assertEquals(400, aniList())
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
                "POST graphql.anilist.co/",
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

    private fun until(done: () -> Boolean) =
        waited {
            while (!done()) delay(POLL)
        }

    private fun asked(request: String) = request in graph.sandbox.mal.asked

    // M2-21's signed-in List on the sandbox: an edit sheet's save and removal, a failed write and the download after it,
    // a sign-out that clears the list, and a new sign-in that fills the List left open.
    @Test
    fun `the List on the sandbox saves, removes, downloads after a failed write, clears at a sign-out and fills at the next sign-in`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val entries = waited { graph.list.observeEntries().first { it.isNotEmpty() } }
        // One Piece, the first row of Watching: with no total, two more episodes always stay within it.
        val watching = entries.single { it.anime.id.value == SandboxAiring.ON_A_BREAK }
        val other = entries.first { it.status == ListStatus.Watching && it != watching }
        shows(watching.anime.titles.romaji)

        val saved = watching.values.copy(score = 9, watchedEpisodes = watching.values.watchedEpisodes + 2)
        waited { graph.list.write(ListWrite.Save(watching.anime, saved)) }
        until { asked("PATCH sandbox.invalid/v2/anime/${watching.anime.id.value}/my_list_status") }
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        assertEquals(saved, waited { graph.list.observeEntry(watching.anime.id).first() }!!.values)

        val completed = entries.first { it.status == ListStatus.Completed }
        waited { graph.list.write(ListWrite.Remove(completed.anime)) }
        until { asked("DELETE sandbox.invalid/v2/anime/${completed.anime.id.value}/my_list_status") }
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        assertEquals(null, waited { graph.list.observeEntry(completed.anime.id).first() })

        // A write MAL may have made before it failed is settled by a download, and the row shows what MAL has.
        graph.scenarios.set(Scenario(writes = WriteFault.ServerError))
        val failed = graph.sandbox.mal.asked.size
        waited { graph.list.write(ListWrite.MarkNextWatched(other.anime)) }
        until {
            val since =
                graph.sandbox.mal.asked
                    .drop(failed)
            val wrote = since.indexOf("PATCH sandbox.invalid/v2/anime/${other.anime.id.value}/my_list_status")
            wrote >= 0 && since.drop(wrote + 1).contains("GET sandbox.invalid/v2/users/@me/animelist")
        }
        graph.scenarios.set(Scenario())
        assertEquals(other.values, waited { graph.list.observeEntry(other.anime.id).first { it?.values == other.values } }!!.values)
        // The failure's message stays until it is acted on or swiped away (section 7.2): swiped, as a user would.
        shows("Could not save")
        compose.onNodeWithText("Could not save").performTouchInput { swipeRight() }
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Could not save").fetchSemanticsNodes().isEmpty()
        }

        // S9: a sign-out leaves no token, entry or sort; the List left open says to sign in, and fills at the next sign-in.
        waited { graph.preferences.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending)) }
        waited { graph.session.signOut() }
        assertEquals(Session.SignedOut, waited { graph.session.observeSession().first() })
        assertEquals(emptyList<ListEntry>(), waited { graph.list.observeEntries().first() })
        val guest = checkNotNull(waited { graph.recentTitles.observeScope().first() })
        assertEquals("\u0002${guest.key.removePrefix("guest-")}", File(context.noBackupFilesDir, "sandbox.tokens").readText())
        assertEquals(
            TabSort.defaultFor(ListStatus.Completed),
            waited {
                graph.preferences.observeListSorts().first()
            }.getValue(ListStatus.Completed),
        )
        shows("Sign in with MyAnimeList to see your list here")
        val downloads =
            graph.sandbox.mal.asked
                .count { it == "GET sandbox.invalid/v2/users/@me/animelist" }
        signIn()
        shows(watching.anime.titles.romaji)
        assertTrue(
            graph.sandbox.mal.asked
                .count { it == "GET sandbox.invalid/v2/users/@me/animelist" } > downloads,
        )
        assertEquals(saved, waited { graph.list.observeEntry(watching.anime.id).first { it != null } }!!.values)
        assertEquals(emptyList<URI>(), connections.asked)
        settled()
    }

    // The List's own download after the sign-in has ended and the screen has drawn it, so nothing runs on after the test.
    private fun settled() {
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // The List on AniList's schedules, asked through the app's own AniList client for the tracked titles alone (S9 at the end).
    @Test
    fun `signed in and synced, Watching shows to watch and the next episode from AniList asked for the tracked titles alone`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val entries = waited { graph.list.observeEntries().first { it.isNotEmpty() } }
        val onBreak = entries.single { it.anime.id.value == SandboxAiring.ON_A_BREAK }
        // The List shown asks for the airing data that its first download made stale.
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            onBreak.anime.id in waited { graph.airing.observeSchedules().first() }
        }
        val airing =
            waited { graph.airing.observeAiring().first { it[onBreak.anime.id]?.aired is AiredCount.Exact } }.getValue(onBreak.anime.id)
        val aired = (airing.aired as AiredCount.Exact).value
        val next = checkNotNull(airing.nextEpisode)
        shows("${aired - onBreak.values.watchedEpisodes}\u00A0to\u00A0watch")

        // Caught up, the row shows the next episode in its place.
        waited { graph.list.write(ListWrite.Save(onBreak.anime, onBreak.values.copy(watchedEpisodes = aired))) }
        shows("Episode\u00A0${next.number} ·", substring = true)

        val tracked = entries.filter { it.tracked }.map { it.anime.id.value }.toSet()
        assertTrue(tracked.size > 1)
        assertEquals(
            tracked,
            graph.sandbox.aniList.asked
                .flatten()
                .toSet(),
        )
        assertTrue(rows("sandbox.db", "airing") >= tracked.size)
        assertEquals(emptyList<URI>(), connections.asked)

        // S9: the sign-out leaves no airing data, and nothing is asked of AniList after it.
        waited { graph.session.signOut() }
        assertEquals(0L, rows("sandbox.db", "airing"))
        assertEquals(0L, rows("sandbox.db", "airing_episode"))
        val asked = graph.sandbox.aniList.asked.size
        assertEquals(Outcome.Success(Unit), waited { graph.airing.refresh() })
        compose.waitForIdle()
        assertEquals(asked, graph.sandbox.aniList.asked.size)
        val files = account.files()
        for ((file, bytes) in account.seeded) assertTrue(file.name, bytes.contentEquals(files.getValue(file)))
    }

    // A condition met while the main thread runs what is posted to it, as the List's own work needs.
    private fun meanwhile(done: () -> Boolean) =
        compose.waitUntil(WAIT) {
            shadowOf(Looper.getMainLooper()).idle()
            done()
        }

    private fun airing() = waited { graph.airing.observeAiring().first() }

    private fun tracked(entries: List<ListEntry>): Set<Long> = entries.filter { it.tracked }.map { it.anime.id.value }.toSet()

    // Whether AniList has been asked, since its [since]th query, for each of [ids].
    private fun aniListAsked(
        ids: Set<Long>,
        since: Int,
    ): Boolean =
        graph.sandbox.aniList.asked
            .drop(since)
            .flatten()
            .containsAll(ids)

    // AniList's data of [weekly] forgotten while AniList works, then the title added again while it fails: MAL's estimate.
    private fun addedWhileAniListFails(weekly: ListEntry): Airing {
        meanwhile { weekly.anime.id in waited { graph.airing.observeSchedules().first() } }
        waited { graph.list.write(ListWrite.Remove(weekly.anime)) }
        meanwhile { asked("DELETE sandbox.invalid/v2/anime/${weekly.anime.id.value}/my_list_status") }
        meanwhile { weekly.anime.id !in airing() }
        graph.scenarios.set(Scenario(aniList = AniListFault.Fails))
        val since = graph.sandbox.aniList.asked.size
        waited { graph.list.write(ListWrite.Save(weekly.anime, weekly.values)) }
        meanwhile { aniListAsked(setOf(weekly.anime.id.value), since) }
        meanwhile { weekly.anime.id in airing() }
        return airing().getValue(weekly.anime.id)
    }

    // Section 2.3's row for AniList, by the panel's switches; offline fails AniList as it fails MAL.
    @Test
    fun `with AniList failing alone a refresh fails, the counts stay, a title added has the estimate, MAL answers, and a reset ends it`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val entries = waited { graph.list.observeEntries().first { it.isNotEmpty() } }
        val weekly = entries.single { it.anime.id.value == SandboxAiring.WEEKLY }
        val onBreak = entries.single { it.anime.id.value == SandboxAiring.ON_A_BREAK }
        meanwhile { onBreak.anime.id in waited { graph.airing.observeSchedules().first() } }
        graph.scenarios.set(Scenario(offline = true))
        assertEquals(Outcome.Failure(DataError.NoConnection), waited { graph.airing.refresh() })
        graph.scenarios.set(Scenario())

        val added = addedWhileAniListFails(weekly)
        assertTrue("$added", added.aired is AiredCount.Estimated)
        val stored = airing()
        assertTrue(stored[onBreak.anime.id]?.aired is AiredCount.Exact)
        assertEquals(Outcome.Failure(DataError.ServerError), waited { graph.airing.refresh() })
        assertEquals(stored, airing())
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        assertEquals(Outcome.Success(Unit), waited { graph.anime.refresh(weekly.anime.id) })
        assertTrue(asked("PATCH sandbox.invalid/v2/anime/${weekly.anime.id.value}/my_list_status"))
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Could not refresh").fetchSemanticsNodes().isEmpty())

        // A reset lays the schedules out again, and the refresh after it asks for every tracked title.
        val since = graph.sandbox.aniList.asked.size
        graph.scenarios.reset()
        meanwhile { !graph.scenarios.reset.value.busy }
        assertEquals(ResetState.Idle, graph.scenarios.reset.value)
        assertEquals(Scenario(), graph.scenarios.scenario.value)
        assertEquals(SandboxAniListFault.None, graph.sandbox.aniList.fault)
        meanwhile { aniListAsked(tracked(entries), since) }
        meanwhile { airing()[weekly.anime.id]?.aired is AiredCount.Exact }
        assertEquals(emptyList<URI>(), connections.asked)
    }

    @Test
    fun `with AniList not answering a refresh waits while MAL answers, until the switch is turned back or the sandbox reset`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val requests = {
            graph.sandbox.mal.asked
                .count { it == "POST graphql.anilist.co/" }
        }
        val background = CoroutineScope(Dispatchers.IO)
        for (reset in listOf(false, true)) {
            graph.scenarios.set(Scenario(aniList = AniListFault.NoAnswer))
            val before = requests()
            val held = background.async { graph.airing.refresh() }
            meanwhile { requests() > before }
            assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
            assertTrue(!held.isCompleted)
            if (reset) graph.scenarios.reset() else graph.scenarios.set(Scenario())
            meanwhile { held.isCompleted }
            assertEquals(Outcome.Success(Unit), waited { held.await() })
            assertEquals(SandboxAniListFault.None, graph.sandbox.aniList.fault)
        }
        meanwhile { !graph.scenarios.reset.value.busy }
        assertEquals(ResetState.Idle, graph.scenarios.reset.value)
        assertEquals(emptyList<URI>(), connections.asked)
    }

    // The app below another for longer than a screen's stop timeout, then shown again.
    private fun away() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(AWAY.toMillis())
        shadowOf(Looper.getMainLooper()).idleFor(AWAY)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    // M3's decision: airing is refreshed while the session is expired, also when the List's sync fails for it.
    @Test
    fun `with the session expired and the airing stale, the List shown again asks AniList for the tracked titles, and the rows update`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        val entries = waited { graph.list.observeEntries().first { it.isNotEmpty() } }
        val weekly = entries.single { it.anime.id.value == SandboxAiring.WEEKLY }
        shows(
            entries
                .single { it.anime.id.value == SandboxAiring.ON_A_BREAK }
                .anime.titles.romaji,
        )
        assertTrue(addedWhileAniListFails(weekly).aired is AiredCount.Estimated)

        graph.scenarios.set(Scenario(sessionExpired = true))
        assertEquals(Outcome.Failure(DataError.SessionExpired), waited { graph.list.sync() })
        assertTrue(waited { graph.session.observeSession().first() } is Session.Expired)
        time.ahead = Freshness.airing.toJavaDuration().plusMinutes(1)
        val since = graph.sandbox.aniList.asked.size
        away()

        meanwhile { aniListAsked(tracked(entries), since) }
        meanwhile { airing()[weekly.anime.id]?.aired is AiredCount.Exact }
        shows("Session expired")
        assertTrue(waited { graph.session.observeSession().first() } is Session.Expired)
        assertEquals(emptyList<URI>(), connections.asked)
    }

    private fun profileTab() {
        val tab = hasText("Profile") and hasClickAction()
        meanwhile {
            compose.onAllNodes(tab).fetchSemanticsNodes().size == 1 && graph.signIn.step.value == SignInStep.None
        }
        compose.onNode(tab).performClick()
    }

    // Another user's profile by name, as the person opens it on the Profile shown.
    private fun openProfile(name: String) {
        meanwhile { compose.onAllNodesWithTag(ProfileTags.page).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("Open another profile"))
        compose.onNodeWithText("Open another profile").performClick()
        compose.onNodeWithTag(ProfileTags.nameField).performTextInput(name)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
    }

    private fun count(value: Int) = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)

    private fun <T> state(read: suspend () -> T) = waited { read() }

    private fun readList(name: String) = state { graph.user.observeList(name).first { it !is UserListState.Reading } }

    // Offline the own profile shows from the database; SandboxProfileTest shows it from a new graph over the same files.
    @Test
    fun `signed in and synced, the own profile's completed count and mean score are the sandbox's own, offline too`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        profileTab()
        shows(SandboxMal.USER_NAME)
        val profile = state { graph.user.observeOwnProfile().first { it != null } }!!
        val statistics = statisticsOf(state { graph.list.observeEntries().first() })
        assertEquals(profile.stated!!.completed, statistics.statuses.completed)
        assertEquals(profile.stated!!.meanScore!!, statistics.meanScore!!, 1e-9)
        shows(count(statistics.statuses.completed))
        assertEquals(1L, rows("sandbox.db", "own_profile"))

        graph.scenarios.set(Scenario(offline = true))
        assertEquals(Outcome.Failure(DataError.NoConnection), waited { graph.user.refreshOwnProfile() })
        assertEquals(profile, state { graph.user.observeOwnProfile().first() })
        shows(SandboxMal.USER_NAME)
        graph.scenarios.set(Scenario())

        assertEquals(emptyList<URI>(), connections.asked)
    }

    // Section 2.1 and S9: with every token rejected, a read that sent one would end the session; a sign-out closes the profile.
    @Test
    fun `other users' lists are read without a token signed in, expired and signed out, and a sign-out leaves no profile and no list`() {
        signIn()
        assertEquals(Outcome.Success(Unit), waited { graph.list.sync() })
        assertEquals(Outcome.Success(Unit), waited { graph.user.refreshOwnProfile() })
        assertEquals(1L, rows("sandbox.db", "own_profile"))

        graph.scenarios.set(Scenario(sessionExpired = true))
        profileTab()
        openProfile(SandboxUsers.PUBLIC)
        val whole = readList(SandboxUsers.PUBLIC) as UserListState.Whole
        assertTrue(whole.entries.size > 3000)
        assertEquals(
            whole.entries.size,
            whole.entries
                .map { it.anime.id }
                .toSet()
                .size,
        )
        shows(count(statisticsOf(whole.entries).statuses.completed))
        assertTrue(state { graph.session.observeSession().first() } is Session.SignedIn)

        assertEquals(Outcome.Failure(DataError.SessionExpired), waited { graph.list.sync() })
        assertTrue(state { graph.session.observeSession().first() } is Session.Expired)
        assertEquals(Outcome.Success(Unit), waited { graph.user.refreshList(SandboxUsers.PUBLIC) })
        assertEquals(UserListState.None(DataError.PrivateList), readList(SandboxUsers.PRIVATE))
        assertEquals(UserListState.None(DataError.UnknownUser), readList("kiroku-no-user-0"))
        graph.scenarios.set(Scenario())

        fun readsOf(name: String): Int {
            val request = "GET sandbox.invalid/v2/users/$name/animelist"
            val asked = graph.sandbox.mal.asked
            return asked.count { it == request }
        }

        val publicReads = readsOf(SandboxUsers.PUBLIC)
        val privateReads = readsOf(SandboxUsers.PRIVATE)
        waited { graph.session.signOut() }
        assertEquals(0L, rows("sandbox.db", "own_profile"))
        shows("Sign in with MyAnimeList to see your list here")
        compose.onNodeWithText(SandboxUsers.PUBLIC).assertDoesNotExist()
        profileTab()
        compose.onNode(hasText("Open another profile") and hasClickAction()).performClick()
        compose.onNodeWithTag(ProfileTags.nameField).performTextInput(SandboxUsers.PUBLIC)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
        until { readsOf(SandboxUsers.PUBLIC) > publicReads }
        shows(count(statisticsOf(whole.entries).statuses.completed))
        assertEquals(whole.entries, (readList(SandboxUsers.PUBLIC) as UserListState.Whole).entries)
        assertEquals(UserListState.None(DataError.PrivateList), readList(SandboxUsers.PRIVATE))
        assertEquals(privateReads + 1, readsOf(SandboxUsers.PRIVATE))
        assertEquals(emptyList<URI>(), connections.asked)
    }

    @Test
    fun `MAL's score distributions are answered on the device from Tenrai's recordings, signed out too, and offline with the panel`() {
        val recorded = waited { graph.scores.distribution(AnimeId(1)) }
        assertTrue(recorded is Outcome.Success && recorded.value!!.total > 0)
        assertEquals(Outcome.Success(null), waited { graph.scores.distribution(AnimeId(21)) })
        graph.scenarios.set(Scenario(offline = true))
        assertEquals(Outcome.Failure(DataError.NoConnection), waited { graph.scores.distribution(AnimeId(59068)) })
        graph.scenarios.set(Scenario())

        assertTrue(
            graph.sandbox.mal.asked
                .any { it == "GET api.tenrai.org/v1/anime/1/statistics" },
        )
        assertEquals(emptyList<URI>(), connections.asked)
    }

    // No user's list values and no title not safe for work reach a debug or preview build.
    @Test
    fun `the build's assets are the public answers the sandbox reads, and nothing else`() {
        assertTrue(runCatching { context.assets.open("authorization/token.json").close() }.isFailure)
        assertEquals(SandboxTitles.Answers.map { "$it.200.json" }.sorted(), context.assets.list("recorded")!!.sorted())
        assertEquals(SandboxTenrai.Files.sorted(), context.assets.list("tenrai")!!.sorted())
        assertTrue(SandboxTitles.Answers.none { it.startsWith("list_") || "adult" in it })
    }

    private companion object {
        const val WAIT = 20_000L
        const val POLL = 20L

        // Longer than a screen's stop timeout, so the List's next show is a new one.
        val AWAY: Duration = Duration.ofSeconds(10)
    }
}
