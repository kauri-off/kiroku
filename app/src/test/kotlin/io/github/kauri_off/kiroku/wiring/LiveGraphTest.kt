package io.github.kauri_off.kiroku.wiring

import android.content.ContextWrapper
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.rules.KotlinSource
import io.github.kauri_off.kiroku.rules.appSources
import io.github.kauri_off.kiroku.rules.projectDir
import io.github.kauri_off.kiroku.stale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Nothing of the network or of the list's store is built before its first use, and each source has its own files.
@RunWith(RobolectricTestRunner::class)
class LiveGraphTest {
    private val context get() = RuntimeEnvironment.getApplication()

    // The HTTP client asks whether it is on the main thread exactly when it is built; a collection of the connection
    // stands for one system callback.
    private class CountingDevice : Device {
        val clientBuilds = AtomicInteger()
        val online = MutableStateFlow(true)
        val registrations = AtomicInteger()
        val registered = AtomicInteger()

        override fun isOnline() = online.value

        override fun observeOnline(): Flow<Boolean> =
            online
                .onStart {
                    registrations.incrementAndGet()
                    registered.incrementAndGet()
                }.onCompletion { registered.decrementAndGet() }

        override fun isMainThread(): Boolean {
            clientBuilds.incrementAndGet()
            return false
        }
    }

    private val device = CountingDevice()
    private val stores = AtomicInteger()
    private val unexpected = mutableListOf<Throwable>()

    private fun parts(
        scope: CoroutineScope,
        source: LiveSource = LiveSource.Account,
    ) = LiveParts(context, source, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device)

    private fun store(): LocalList {
        stores.incrementAndGet()
        return InMemoryLocalList()
    }

    @Test
    fun `reading the session builds neither the HTTP client nor the list's store, and each is built when it is first used`() =
        runTest {
            val graph = LiveGraph(parts(backgroundScope), listStore = { store() })

            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0, device.clientBuilds.get())
            assertEquals(0, stores.get())

            // Naming the list builds nothing either: its store is built by the first read.
            val list = graph.list
            assertEquals(0, stores.get())
            assertEquals(emptyList<ListEntry>(), list.observeEntries().first())
            assertEquals(emptyList<ListEntry>(), list.observeEntries().first())
            assertEquals(1, stores.get())
            assertEquals(0, device.clientBuilds.get())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    // Section 2.1: started signed out, names and the own profile ask for no HTTP client; another user's list does, with no token.
    @Test
    fun `reading the session or the list builds nothing of profiles, and signed out no HTTP client is built before a list is read`() =
        runTest {
            val sent = CopyOnWriteArrayList<Request>()
            val notFound =
                Interceptor { chain ->
                    sent += chain.request()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("")
                        .body("".toResponseBody())
                        .build()
                }
            val source = LiveSource("profiles", MalHosts("http://127.0.0.1:1/v2/", "http://127.0.0.1:1/v1/oauth2/"), notFound)
            val built = CopyOnWriteArrayList<Thread>()
            val graph =
                LiveGraph(parts(backgroundScope, source), listStore = { store() }, ownProfile = { p, session ->
                    built += Thread.currentThread()
                    ownProfileOf(p, session)
                })
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(emptyList<ListEntry>(), graph.list.observeEntries().first())

            assertSame(Looper.getMainLooper().thread, Thread.currentThread())
            assertTrue(graph.user.sameUser("Someone", "someone"))
            assertEquals(emptyList<Thread>(), built)
            assertFalse(context.getDatabasePath("profiles.db").exists())
            assertNull(graph.user.observeOwnProfile().first())
            assertNotSame(Looper.getMainLooper().thread, built.single())
            assertEquals(0, device.clientBuilds.get())

            assertEquals(UserListState.None(DataError.NotFound), graph.user.observeList("Someone").first { it is UserListState.None })
            assertTrue(device.clientBuilds.get() > 0)
            assertEquals(listOf<String?>(null), sent.map { it.header("Authorization") })
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    // S9: the profile leaves the database by its own forgetting, whatever stores the list, also when nothing asked for it.
    @Test
    fun `after a sign-out the database holds no profile, also when nothing had asked for one and the list is stored elsewhere`() =
        runTest {
            val graph = LiveGraph(parts(backgroundScope), listStore = { store() })
            graph.anime.observeAnime(AnimeId(1)).first()
            val file = context.getDatabasePath("account.db").path
            SQLiteDatabase.openDatabase(file, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO own_profile (owner, name, fetched_at) VALUES ('someone', 'Someone', 0)")
            }
            val profiles = {
                SQLiteDatabase
                    .openDatabase(
                        file,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { DatabaseUtils.queryNumEntries(it, "own_profile") }
            }
            assertEquals(1L, profiles())

            graph.session.signOut()
            assertEquals(0L, profiles())
            assertEquals(0, device.clientBuilds.get())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    // S9: the signed-in user's own profile goes too, so it is forgotten once the session no longer names its owner.
    @Test
    fun `a sign-out of an expired session leaves no profile of its own sign-in in the database`() =
        runTest {
            val parts = parts(backgroundScope)
            val signIn = "0f8fad5b-d9cb-469f-a165-70867728950e"
            parts.file("tokens").writeBytes(byteArrayOf(0) + signIn.encodeToByteArray())
            val graph = LiveGraph(parts, listStore = { store() })
            assertEquals(Session.Expired(SignInId(signIn)), graph.session.observeSession().first())
            graph.anime.observeAnime(AnimeId(1)).first()
            val file = context.getDatabasePath("account.db").path
            SQLiteDatabase.openDatabase(file, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO own_profile (owner, name, fetched_at) VALUES ('$signIn', 'Someone', 0)")
            }
            val profiles = {
                SQLiteDatabase
                    .openDatabase(
                        file,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { DatabaseUtils.queryNumEntries(it, "own_profile") }
            }
            assertEquals(1L, profiles())

            graph.session.signOut()
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0L, profiles())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `the connection is the requests' device, holding one callback while screens wait for it, none before or after`() =
        runTest {
            val graph = LiveGraph(parts(backgroundScope), listStore = { store() })
            val connection = graph.connection
            runCurrent()
            assertEquals(0, device.registrations.get())

            val first = mutableListOf<Boolean>()
            val one = backgroundScope.launch { connection.observeOnline().collect { first += it } }
            runCurrent()
            device.online.value = false
            runCurrent()
            val second = mutableListOf<Boolean>()
            val two = backgroundScope.launch { connection.observeOnline().collect { second += it } }
            runCurrent()
            assertEquals(listOf(true, false), first)
            assertEquals("the current value at once", listOf(false), second)
            assertEquals(1, device.registrations.get())

            one.cancel()
            runCurrent()
            assertEquals(1, device.registered.get())
            two.cancel()
            runCurrent()
            assertEquals(0, device.registered.get())
        }

    @Test
    fun `an area is built once, when it is first asked for`() =
        runTest {
            val graph = LiveGraph(parts(backgroundScope), listStore = { store() })
            assertSame(graph.session, graph.session)
            assertSame(graph.list, graph.list)
            assertSame(graph.anime, graph.anime)
        }

    @Test
    fun `the files of two sources differ in name, not only in place`() =
        runTest {
            val account = parts(backgroundScope).file("tokens")
            val other = parts(backgroundScope, LiveSource("sandbox", MalHosts.Real)).file("tokens")
            assertEquals("account.tokens", account.name)
            assertNotEquals(account.name, other.name)
            assertEquals(context.noBackupFilesDir, account.parentFile)
            assertTrue(other.name.startsWith("sandbox."))
        }

    @Test
    fun `the session of a source is read from that source's own file`() =
        runTest {
            val parts = parts(backgroundScope)
            // An expired sign-in, as the session writes it: a zero byte and the sign-in's id.
            val signIn = "0f8fad5b-d9cb-469f-a165-70867728950e"
            parts.file("tokens").writeBytes(byteArrayOf(0) + signIn.encodeToByteArray())
            assertEquals(Session.Expired(SignInId(signIn)), LiveGraph(parts).session.observeSession().first())
        }

    @Test
    fun `the choices of a source are kept in that source's own file, which a new process reads`() =
        runBlocking {
            val first = CoroutineScope(SupervisorJob())
            val graph = LiveGraph(parts(first), listStore = { store() })
            assertSame(Dispatchers.Default, graph.background)
            graph.preferences.setListSort(ListStatus.Completed, TabSort(ListSort.Score, SortDirection.Ascending))
            first.coroutineContext.job.cancelAndJoin()
            assertTrue(File(context.noBackupFilesDir, "account.preferences_pb").isFile)

            val second = CoroutineScope(SupervisorJob())
            val sorts = LiveGraph(parts(second), listStore = { store() }).preferences.observeListSorts().first()
            assertEquals(TabSort(ListSort.Score, SortDirection.Ascending), sorts.getValue(ListStatus.Completed))
            assertEquals(TabSort.defaultFor(ListStatus.Watching), sorts.getValue(ListStatus.Watching))
            second.coroutineContext.job.cancelAndJoin()
            assertEquals(0, stores.get())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    // Section 7.1: a title page shows what the source's own database holds and asks MAL nothing while it is fresh.
    @Test
    fun `the titles are the source's own database's, and a fresh title is shown without a request`() =
        runTest {
            val requests = mutableListOf<String>()
            val parts =
                LiveParts(
                    context,
                    LiveSource.Account,
                    "0123abcd",
                    Clock.systemUTC(),
                    backgroundScope,
                    unexpected::add,
                    requests::add,
                    device,
                )
            val titles = LiveGraph(parts, listStore = { store() }).anime
            val anime = FixtureAnime.exactToWatch
            assertNull(titles.observeAnime(anime.id).first())
            assertTrue(context.getDatabasePath("account.db").isFile)

            val fetched = Instant.now().minusSeconds(60)
            TitleStore(
                parts.database,
                Dispatchers.IO,
                unexpected::add,
            ).refresh(anime, Fixtures.reference.details.getValue(anime.id), fetched)
            assertEquals(anime, titles.observeAnime(anime.id).first())
            assertEquals(Fixtures.reference.details[anime.id], titles.observeDetails(anime.id).first())
            assertEquals(fetched, titles.observeRefreshedAt(anime.id).first())
            assertFalse(titles.stale(anime.id, null, Instant.now()))
            assertEquals(emptyList<String>(), requests)
            assertEquals(0, device.clientBuilds.get())
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    @Test
    fun `the titles' repository is built once, at the first call, off the main thread`() =
        runTest {
            val built = mutableListOf<Thread>()
            val nothing =
                object : AnimeRepository {
                    override fun observeAnime(id: AnimeId) = flowOf<Anime?>(null)

                    override fun observeDetails(id: AnimeId) = flowOf<AnimeDetails?>(null)

                    override fun observeDetailsOf(ids: Set<AnimeId>) = flowOf(emptyMap<AnimeId, AnimeDetails>())

                    override fun observeRefreshedAt(id: AnimeId) = flowOf<Instant?>(null)

                    override fun observePagesRead() = flowOf(emptySet<AnimeId>())

                    override suspend fun refresh(id: AnimeId) = Outcome.Success(Unit)
                }
            val titles = liveTitles(parts(backgroundScope)) { nothing.also { built += Thread.currentThread() } }
            assertEquals(emptyList<Thread>(), built)

            titles.observeAnime(AnimeId(1)).first()
            titles.refresh(AnimeId(1))
            assertEquals(1, built.size)
            assertNotSame(Looper.getMainLooper().thread, built.single())
        }

    // Until Settings exist, "Show adult content in search" is off, so a title's page asks without adult recommendations.
    @Test
    fun `a live title's page is asked without nsfw`() =
        runTest {
            val requests = mutableListOf<String>()
            device.online.value = false
            val source = LiveSource("closed", MalHosts("http://127.0.0.1:1/v2/", "http://127.0.0.1:1/v1/oauth2/"))
            val parts = LiveParts(context, source, "0123abcd", Clock.systemUTC(), backgroundScope, unexpected::add, requests::add, device)

            assertEquals(Outcome.Failure(DataError.NoConnection), liveTitles(parts).refresh(AnimeId(61987)))
            assertTrue(requests.toString(), requests.single().startsWith("GET 127.0.0.1/v2/anime/61987?fields "))
        }

    // The folder is made when it is missing, which is disk work the app's start does not wait for.
    @Test
    fun `making the parts of a live graph looks up no folder of its files`() =
        runTest {
            val lookups = AtomicInteger()
            val counting =
                object : ContextWrapper(context) {
                    override fun getNoBackupFilesDir(): File {
                        lookups.incrementAndGet()
                        return super.getNoBackupFilesDir()
                    }
                }
            val parts =
                LiveParts(counting, LiveSource.Account, "0123abcd", Clock.systemUTC(), backgroundScope, unexpected::add, device = device)
            assertEquals(0, lookups.get())

            parts.file("tokens")
            assertEquals(1, lookups.get())
        }

    // So that work started in a new process still leaves MAL alone (section 2.3), and no backup carries a refusal to another device.
    @Test
    fun `MAL's back-off is kept in the source's own file, which is not backed up`() =
        runTest {
            val refusing =
                Interceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("")
                        .body("".toResponseBody())
                        .build()
                }
            val parts = parts(backgroundScope, LiveSource("refused", MalHosts.Real, refusing))

            assertEquals(Outcome.Failure(DataError.Refused), liveTitles(parts).refresh(AnimeId(61987)))
            assertTrue(File(context.noBackupFilesDir, "refused.backoff").isFile)
            assertFalse(File(context.noBackupFilesDir, "account.backoff").exists())
        }

    // Every request goes through MalHttp's one client, so none can miss the sandbox's stand-in or the rules of sending.
    @Test
    fun `an HTTP client or a connection is made in MalHttp alone, in the app's code and the data module's`() {
        val making = Regex("""\bOkHttpClient\s*\(|\bopenConnection\s*\(|\bSocket\s*\(|\bHttpClient\b""")
        val chained = Regex("""\bOkHttpClient\s*\.\s*Builder\b""")
        val data =
            File(projectDir, "data/src")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
                .flatMap { it.walk().filter { file -> file.isFile && file.extension == "kt" }.toList() }
        val found =
            (appSources() + data)
                .filter { file -> KotlinSource(file.readText()).code.let { making.containsMatchIn(it) || chained.containsMatchIn(it) } }
                .map { it.name }
        assertEquals(listOf("MalHttp.kt"), found)
    }

    // So a graph that is not a live graph, the demo's, holds no HTTP client.
    @Test
    fun `the HTTP client is made in the live graph's file and nowhere else`() {
        val making = Regex("""\bMalHttp\s*\(""")
        assertEquals(listOf("LiveGraph.kt"), appSources().filter { making.containsMatchIn(it.readText()) }.map { it.name })
    }
}
