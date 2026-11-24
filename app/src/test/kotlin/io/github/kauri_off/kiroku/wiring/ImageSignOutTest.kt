package io.github.kauri_off.kiroku.wiring

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.imageDecoderEnabled
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationStore
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.data.db.RoomAiringStore
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.sandbox.Sandbox
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.design.component.routeImageLoader
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.notify.MarkWatched
import io.github.kauri_off.kiroku.notify.MarkWatchedWork
import io.github.kauri_off.kiroku.notify.Tap
import io.github.kauri_off.kiroku.notify.TestScheduledWork
import io.github.kauri_off.kiroku.profile.OtherProfileKey
import io.github.kauri_off.kiroku.shell.ActivityNavigation
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.ListRoot
import io.github.kauri_off.kiroku.shell.ProfileRoot
import io.github.kauri_off.kiroku.shell.SearchRoot
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.ShellRoute
import io.github.kauri_off.kiroku.shell.SignOutTurns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSignOutTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { runBlocking { work.cancelAndJoin() } }

    @get:Rule(order = 0)
    val keys =
        object : ExternalResource() {
            override fun before() = KeysInMemory.install()

            override fun after() = KeysInMemory.uninstall()
        }

    private val context get() = RuntimeEnvironment.getApplication()
    private val work = SupervisorJob()
    private val scope = process()
    private val png by lazy { png(OLD_COLOR) }
    private val replacement by lazy { png(NEW_COLOR) }

    private fun png(color: Int): ByteArray =
        ByteArrayOutputStream()
            .also {
                val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()

    private fun process() = CoroutineScope(SupervisorJob(work) + Dispatchers.Default)

    private fun parts(
        source: String,
        process: CoroutineScope = scope,
        mal: SandboxMal? = null,
        unexpected: (Throwable) -> Unit = { throw it },
        answer: (Interceptor.Chain) -> ResponseBody = { png.toResponseBody(PNG) },
    ): LiveParts {
        val device =
            object : Device {
                override fun isOnline() = true

                override fun observeOnline() = flowOf(true)

                override fun isMainThread() = false
            }
        val standIn =
            Interceptor { chain ->
                if (chain.request().url.host != "images.invalid") return@Interceptor checkNotNull(mal).intercept(chain)
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .body(answer(chain))
                    .build()
            }
        val live = LiveSource(source, if (mal == null) MalHosts.Real else SandboxMal.Hosts, standIn)
        return LiveParts(context, live, SandboxMal.CLIENT_ID, Clock.systemUTC(), process, unexpected, device = device)
    }

    private fun asset(path: String): String = context.assets.open(path).use { it.readBytes().decodeToString() }

    private fun sandbox(source: String) =
        Sandbox(
            read = { name -> asset("recorded/$name.200.json") },
            readTenrai = { name -> asset("tenrai/$name") },
            poster = { CACHED },
            clock = Clock.systemUTC(),
            file = { kind -> File(context.noBackupFilesDir, "$source.fixture.$kind") },
        ).mal

    private suspend fun LiveGraph.signIn(mal: SandboxMal): Session =
        withTimeout(5_000) {
            signIn.start { address -> mal.authorize(address)?.also(signIn::redirected) != null }
            assertEquals(SignInStep.SignedIn, signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed })
            session.observeSession().first { it is Session.SignedIn }
        }

    private fun graph(
        parts: LiveParts,
        files: FileSystem = FileSystem.SYSTEM,
    ) = LiveGraph(parts, listStore = { InMemoryLocalList() }, imageFiles = files)

    private fun LiveParts.seedSession() = tokenFile.writeBytes(byteArrayOf(0) + SIGN_IN.value.encodeToByteArray())

    private suspend fun LiveGraph.loader(): ImageLoader = imageLoaderFor(session.observeSession().first()).withFixtureDecoder()

    private fun ImageLoader.withFixtureDecoder() = newBuilder().imageDecoderEnabled(false).build()

    private suspend fun ImageLoader.execute(url: String) = execute(ImageRequest.Builder(context).data(url).build())

    private suspend fun ImageLoader.load(url: String): SuccessResult = execute(url) as SuccessResult

    private fun stalledDecode(
        url: String,
        decoding: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ): ImageRequest =
        ImageRequest
            .Builder(context)
            .data(url)
            .decoderFactory { _, _, _ ->
                Decoder {
                    decoding.complete(Unit)
                    check(release.await(15, TimeUnit.SECONDS))
                    DecodeResult(ColorImage(OLD_COLOR), isSampled = false)
                }
            }.build()

    private fun ImageLoader.closeCache() {
        shutdown()
        diskCache?.shutdown()
    }

    private suspend fun awaitMissing(directory: Path) {
        withTimeout(5_000) {
            while (FileSystem.SYSTEM.exists(directory)) delay(10)
        }
    }

    @Test
    fun `sign-out cancels the streaming call and removes tokens before the old body ends`() =
        runBlocking {
            val streaming = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val releaseBody = CountDownLatch(1)
            val first = AtomicBoolean(true)
            val parts =
                parts("account") { chain ->
                    if (chain.request().url.toString() == HELD && first.getAndSet(false)) {
                        object : ResponseBody() {
                            private val input =
                                object : Source {
                                    private val bytes = Buffer().write(png)

                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        streaming.complete(Unit)
                                        while (!chain.call().isCanceled() && !releaseBody.await(20, TimeUnit.MILLISECONDS)) {
                                            check(!Thread.currentThread().isInterrupted)
                                        }
                                        if (chain.call().isCanceled()) cancelled.complete(Unit)
                                        check(releaseBody.await(15, TimeUnit.SECONDS))
                                        return bytes.read(sink, byteCount)
                                    }

                                    override fun timeout() = Timeout.NONE

                                    override fun close() = Unit
                                }.buffer()

                            override fun contentLength() = png.size.toLong()

                            override fun contentType() = PNG

                            override fun source() = input
                        }
                    } else {
                        png.toResponseBody(PNG)
                    }
                }
            parts.seedSession()
            val graph = graph(parts)
            val loader = graph.loader()
            val oldDirectory = checkNotNull(loader.diskCache).directory
            val other = graph(parts("sandbox")).loader()
            val settings = NotificationSettings(on = false, alsoPlanned = true)
            graph.preferences.writeNotificationSettings(settings)
            assertEquals(DataSource.NETWORK, other.load(CACHED).dataSource)
            assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
            assertTrue(checkNotNull(loader.diskCache).size > 0)
            val held = async { loader.execute(HELD) }
            try {
                withTimeout(5_000) {
                    streaming.await()
                    graph.session.signOut()
                    cancelled.await()
                }
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                assertEquals(0L, parts.tokenFile.length())
                assertEquals(settings, graph.preferences.observeNotificationSettings().first())
                assertFalse(held.isCompleted)
                val fresh = graph.loader()
                try {
                    assertNotEquals(oldDirectory, fresh.diskCache?.directory)
                    assertEquals(DataSource.NETWORK, fresh.load(HELD).dataSource)
                    releaseBody.countDown()
                    withTimeout(5_000) { held.join() }
                    awaitMissing(oldDirectory)
                    assertTrue(checkNotNull(loader.memoryCache).keys.isEmpty())
                    assertEquals(DataSource.MEMORY_CACHE, fresh.load(HELD).dataSource)
                    assertEquals(DataSource.NETWORK, fresh.load(CACHED).dataSource)
                    assertEquals(DataSource.MEMORY_CACHE, other.load(CACHED).dataSource)
                } finally {
                    fresh.closeCache()
                }
            } finally {
                releaseBody.countDown()
                held.cancel()
                other.closeCache()
            }
        }

    @Test
    fun `a stopped shell keeps its retired loader and restarted routes can cache`() {
        val parts = parts("route")
        parts.seedSession()
        val graph = graph(parts)
        val navigator = ShellNavigator.inMemory()
        val messages = ShellMessages(scope, graph.list)
        val snackbars = SnackbarHostState()
        var captured: ImageLoader? = null
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val session = SignOutTurns(graph, navigator, messages)
                ShellRoute(
                    navigator = navigator,
                    snackbars = snackbars,
                    images = session?.let(graph::imageLoaderFor),
                    entries = { _, _ ->
                        entry<ListRoot> {
                            val loader = routeImageLoader()
                            SideEffect { captured = loader }
                        }
                        entry<SearchRoot> {}
                        entry<ProfileRoot> {}
                    },
                )
            }
        }
        val old = graph.imageLoaderFor(Session.Expired(SIGN_IN))
        compose.waitUntil(5_000) { captured === old }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        runBlocking {
            graph.session.signOut()
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0, navigator.restarts)
            val late = runCatching { checkNotNull(captured).withFixtureDecoder().execute(CACHED) }
            assertTrue(late.exceptionOrNull() is CancellationException)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val fresh = graph.imageLoaderFor(Session.SignedOut)
        compose.waitUntil(5_000) { navigator.restarts == 1 && captured === fresh }
        runBlocking {
            val loader = checkNotNull(captured).withFixtureDecoder()
            try {
                assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
                assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
            } finally {
                loader.closeCache()
            }
        }
    }

    @Test
    fun `failed recursive deletion quarantines old files across restart and retries`(): Unit =
        runBlocking {
            val firstProcess = process()
            val initial = parts("restarted", firstProcess)
            initial.seedSession()
            val oldLoader = graph(initial).loader()
            assertEquals(DataSource.NETWORK, oldLoader.load(CACHED).dataSource)
            val oldDirectory = checkNotNull(oldLoader.diskCache).directory
            oldLoader.closeCache()
            val orphan = oldDirectory / "unindexed"
            FileSystem.SYSTEM.write(orphan) { write(png) }
            firstProcess.coroutineContext.job.cancelAndJoin()

            val failed = Channel<Unit>(Channel.UNLIMITED)
            val denyDelete =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun delete(
                        path: Path,
                        mustExist: Boolean,
                    ) {
                        if (path.parent == oldDirectory || path == oldDirectory) {
                            failed.trySend(Unit)
                            throw IOException("fixture deletion failure")
                        }
                        super.delete(path, mustExist)
                    }
                }
            val secondProcess = process()
            val signOut = parts("restarted", secondProcess)
            val reused = graph(signOut).loader()
            assertEquals(DataSource.DISK, reused.load(CACHED).dataSource)
            reused.closeCache()
            secondProcess.coroutineContext.job.cancelAndJoin()

            val signOutProcess = process()
            graph(parts("restarted", signOutProcess), denyDelete).session.signOut()
            withTimeout(5_000) { failed.receive() }
            assertEquals(0L, signOut.tokenFile.length())
            assertTrue(FileSystem.SYSTEM.exists(orphan))
            signOutProcess.coroutineContext.job.cancelAndJoin()

            val thirdProcess = process()
            val quarantined = graph(parts("restarted", thirdProcess), denyDelete)
            assertEquals(Session.SignedOut, quarantined.session.observeSession().first())
            quarantined.loader().apply {
                assertNotEquals(oldDirectory, diskCache?.directory)
                assertEquals(DataSource.NETWORK, load(CACHED).dataSource)
                closeCache()
            }
            withTimeout(5_000) { failed.receive() }
            assertTrue(FileSystem.SYSTEM.exists(orphan))
            thirdProcess.coroutineContext.job.cancelAndJoin()

            graph(parts("restarted")).loader().apply {
                awaitMissing(oldDirectory)
                assertEquals(DataSource.DISK, load(CACHED).dataSource)
                closeCache()
            }
        }

    @Test
    fun `an orphan sweep delayed across sign-in preserves the replacement disk entry`() =
        runBlocking {
            val firstProcess = process()
            val mal = sandbox("sweep")
            val switched = AtomicBoolean()
            val parts =
                parts("sweep", firstProcess, mal) {
                    (if (switched.get()) replacement else png).toResponseBody(PNG)
                }
            parts.seedSession()
            val root = File(context.cacheDir, "sweep.image-caches").toOkioPath()
            val orphan = root / "z-orphan"
            FileSystem.SYSTEM.createDirectories(orphan)
            val listing = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val decoding = CompletableDeferred<Unit>()
            val releaseDecode = CountDownLatch(1)
            val swept = CompletableDeferred<Unit>()
            val files =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun listOrNull(dir: Path): List<Path>? {
                        if (dir == root) {
                            listing.complete(Unit)
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                        return super.listOrNull(dir)?.sorted()
                    }

                    override fun delete(
                        path: Path,
                        mustExist: Boolean,
                    ) {
                        super.delete(path, mustExist)
                        if (path == orphan) swept.complete(Unit)
                    }
                }
            val graph = graph(parts, files)
            val original = graph.loader()
            val held = async { original.execute(stalledDecode(CACHED, decoding, releaseDecode)) }
            try {
                withTimeout(5_000) {
                    listing.await()
                    decoding.await()
                    graph.signOut(Session.Expired(SIGN_IN))
                }
                val signedIn = graph.signIn(mal)
                switched.set(true)
                val fresh = graph.loader()
                val result = fresh.load(CACHED)
                assertEquals(DataSource.NETWORK, result.dataSource)
                assertEquals(NEW_COLOR, result.pixel())
                assertFalse(held.isCompleted)
                releaseDecode.countDown()
                withTimeout(5_000) { held.join() }
                release.countDown()
                withTimeout(5_000) { swept.await() }
                fresh.closeCache()
                firstProcess.coroutineContext.job.cancelAndJoin()

                val restarted = graph(parts("sweep", mal = mal))
                assertEquals(signedIn, restarted.session.observeSession().first())
                restarted.loader().let {
                    val retained = it.load(CACHED)
                    assertEquals(DataSource.DISK, retained.dataSource)
                    assertEquals(NEW_COLOR, retained.pixel())
                    it.closeCache()
                }
            } finally {
                releaseDecode.countDown()
                release.countDown()
            }
        }

    @Test
    fun `a retired disk stays quarantined when the token write fails and the old decoder has not drained`() =
        runBlocking {
            val firstProcess = process()
            val parts = parts("owed", firstProcess, unexpected = {})
            parts.seedSession()
            val graph = graph(parts)
            val loader = graph.loader()
            loader.load(CACHED)
            val directory = checkNotNull(loader.diskCache).directory
            val scratch = File(parts.tokenFile.path + ".tmp")
            check(scratch.mkdir())
            File(scratch, "held").writeText("fixture")
            val decoding = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val held = async { loader.execute(stalledDecode(HELD, decoding, release)) }
            val sweep = CountDownLatch(1)
            try {
                withTimeout(5_000) {
                    decoding.await()
                    graph.session.signOut()
                }
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                assertTrue(parts.tokenFile.length() > 0)
                assertFalse(held.isCompleted)
                firstProcess.coroutineContext.job.cancelAndJoin()
                assertTrue(FileSystem.SYSTEM.exists(directory))

                val restartedFiles =
                    object : ForwardingFileSystem(FileSystem.SYSTEM) {
                        override fun listOrNull(dir: Path): List<Path>? {
                            if (dir == directory.parent) check(sweep.await(15, TimeUnit.SECONDS))
                            return super.listOrNull(dir)
                        }
                    }
                val restarted = graph(parts("owed"), restartedFiles)
                assertEquals(Session.Expired(SIGN_IN), restarted.session.observeSession().first())
                val fresh = restarted.loader()
                assertEquals(DataSource.NETWORK, fresh.load(CACHED).dataSource)
                assertNull(fresh.diskCache)
                assertTrue(FileSystem.SYSTEM.exists(directory))

                release.countDown()
                withTimeout(5_000) { held.join() }
                loader.closeCache()
                sweep.countDown()
                awaitMissing(directory)
                fresh.closeCache()
            } finally {
                release.countDown()
                sweep.countDown()
            }
        }

    @Test
    fun `duplicate A cannot touch B and B can sign out while A cleanup is held`() =
        runBlocking {
            val clearing = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val mal = sandbox("concurrent")
            val parts = parts("concurrent", mal = mal)
            parts.seedSession()
            val store =
                object : NotificationStore by InMemoryNotificationStore() {
                    override suspend fun clear(owner: SignInId?) {
                        clearing.complete(Unit)
                        resume.await()
                    }
                }
            val graph = LiveGraph(parts, listStore = { InMemoryLocalList() }, notificationStore = { _, _ -> store })
            val a = Session.Expired(SIGN_IN)
            val first = async { graph.signOut(a) }
            try {
                withTimeout(5_000) { clearing.await() }
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                val b = graph.signIn(mal)
                val fresh = graph.loader()
                assertEquals(DataSource.NETWORK, fresh.load(CACHED).dataSource)
                withTimeout(5_000) { graph.signOut(a) }
                assertEquals(b, graph.session.observeSession().first())
                assertEquals(DataSource.MEMORY_CACHE, fresh.load(CACHED).dataSource)
                val second = async { graph.signOut(b) }
                try {
                    withTimeout(5_000) { graph.session.observeSession().first { it == Session.SignedOut } }
                    assertEquals(0L, parts.tokenFile.length())
                    assertFalse(first.isCompleted)
                    assertTrue(graph.imagesRetired(b))
                } finally {
                    resume.complete(Unit)
                    second.await()
                }
            } finally {
                resume.complete(Unit)
                first.await()
            }
        }

    @Test
    fun `A cleanup removes its tracked airing and queued tap while B keeps its public list and data`() =
        runBlocking {
            val clearing = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val mal = sandbox("downstream")
            val parts = parts("downstream", mal = mal)
            parts.seedSession()
            val store =
                object : NotificationStore by InMemoryNotificationStore() {
                    override suspend fun clear(owner: SignInId?) {
                        clearing.complete(Unit)
                        resume.await()
                    }
                }
            val aTitle = AnimeId(900001)
            val bTitle = AnimeId(900002)
            val fixture = Fixtures.reference.entries.first { it.status == ListStatus.Watching }
            val entry = fixture.copy(anime = fixture.anime.copy(id = aTitle))
            val list = InMemoryLocalList(listOf(entry), SIGN_IN)
            val graph = LiveGraph(parts, listStore = { list }, notificationStore = { _, _ -> store })
            val airing = RoomAiringStore(parts.database, Dispatchers.IO) { throw it }
            val schedule = StoredAiring(null, Instant.now())
            airing.write(mapOf(aTitle to schedule))
            val a = Session.Expired(SIGN_IN)
            val publicList = "Sandbox_Public"
            assertTrue(graph.user.observeList(publicList).first { it !is UserListState.Reading } is UserListState.Whole)

            TestScheduledWork.install(context)
            val workManager = WorkManager.getInstance(context)
            val aTap = workManager.queueTap(parts.source.name, aTitle, SIGN_IN)
            val first = async { graph.signOut(a) }
            try {
                withTimeout(5_000) { clearing.await() }
                val b = graph.signIn(mal)
                val bTap = workManager.queueTap(parts.source.name, aTitle, checkNotNull(b.owner))
                assertNull(workManager.getWorkInfoById(aTap).get(5, TimeUnit.SECONDS))
                assertEquals(emptyMap<AnimeId, StoredAiring>(), airing.read())
                graph.list.observeEntries().first()
                list.add(entry.anime.copy(id = bTitle), StoredEntry.settled(entry.record))
                airing.write(mapOf(bTitle to schedule))
                val bList = graph.user.observeList(publicList).first { it !is UserListState.Reading }
                assertTrue(bList is UserListState.Whole)

                withTimeout(5_000) { graph.signOut(a) }
                resume.complete(Unit)
                withTimeout(5_000) { first.await() }

                assertEquals(b, graph.session.observeSession().first())
                assertEquals(bList, graph.user.observeList(publicList).first())
                assertEquals(mapOf(bTitle to schedule), airing.read())
                assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(bTap).get(5, TimeUnit.SECONDS)?.state)
            } finally {
                resume.complete(Unit)
                first.join()
                TestScheduledWork.end()
            }
        }

    private suspend fun WorkManager.queueTap(
        source: String,
        title: AnimeId,
        owner: SignInId,
    ): UUID {
        val now = Instant.now()
        val tap = Tap(MarkWatched(source, title, 2, Shown(2, 1, 2, now, now)), now, owner)
        val request = MarkWatchedWork.tryOf(tap, Duration.ofHours(1))
        enqueue(request).await()
        return request.id
    }

    @Test
    fun `saved routes of A are removed before the restored shell binds its first B loader`() {
        val mal = sandbox("restoring")
        val parts = parts("restoring", mal = mal)
        parts.seedSession()
        val graph = graph(parts)
        val oldKey = OtherProfileKey("fixture_previous")
        val a = runBlocking { graph.session.observeSession().first() }
        val first = Robolectric.buildActivity(ComponentActivity::class.java).create()
        ActivityNavigation(first.get().savedStateRegistry).navigator =
            ShellNavigator.inMemory().apply {
                hadSession = true
                sessionOwner = a.owner
                select(Destination.Profile)
                open(oldKey)
            }
        val state = Bundle()
        first.saveInstanceState(state).destroy()
        val signedIn =
            runBlocking {
                graph.signOut(a)
                graph.signIn(mal)
            }
        assertNotEquals(SIGN_IN, signedIn.owner)
        val second = Robolectric.buildActivity(ComponentActivity::class.java).create(state)
        try {
            val restored = checkNotNull(ActivityNavigation(second.get().savedStateRegistry).restored)
            assertEquals(listOf(Destination.Profile.root, oldKey), restored.stackOf(Destination.Profile))
            assertEquals(a.owner, restored.sessionOwner)
            val bound = CompletableDeferred<ImageLoader>()
            val source =
                object : AppGraph by graph {
                    override fun imageLoaderFor(session: Session): ImageLoader =
                        graph.imageLoaderFor(session).also {
                            assertEquals(signedIn, session)
                            assertEquals(listOf(Destination.Profile.root), restored.stackOf(Destination.Profile))
                            bound.complete(it)
                        }
                }
            compose.setContent {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(source, restored) }
            }
            compose.waitUntil(5_000) { bound.isCompleted }
            assertEquals(Destination.List, restored.current)
            assertEquals(1, restored.restarts)
            runBlocking {
                val loader = bound.await().withFixtureDecoder()
                assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
                assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
                loader.closeCache()
            }
        } finally {
            second.destroy()
        }
    }

    private fun SuccessResult.pixel(): Int = image.toBitmap().getPixel(0, 0)

    private companion object {
        val SIGN_IN = SignInId("0f8fad5b-d9cb-469f-a165-70867728950e")
        val PNG = "image/png".toMediaType()
        const val CACHED = "https://images.invalid/cached.png"
        const val HELD = "https://images.invalid/held.png"
        const val OLD_COLOR = 0xffff0000.toInt()
        const val NEW_COLOR = 0xff0000ff.toInt()
    }
}
