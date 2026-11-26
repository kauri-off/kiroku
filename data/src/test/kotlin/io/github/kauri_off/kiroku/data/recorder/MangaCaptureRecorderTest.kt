package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListRemovalResult
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.manga.RoomMangaList
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.granted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

@RunWith(RobolectricTestRunner::class)
class MangaCaptureRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val scope = CoroutineScope(SupervisorJob())
    private val context = RuntimeEnvironment.getApplication()
    private val database = KirokuDatabase.open(context, DATABASE)
    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val session by lazy { sessions.open() }
    private val root by lazy { File(folder.root, "private-manga-mutations") }
    private val id = MangaId(17)
    private val time = TestTimeSource()
    private var writeFile: (File, String) -> Unit = { file, payload -> file.writeText(payload) }
    private var eraseFiles: (File) -> Boolean = File::deleteRecursively
    private var renameDirectory: (File, File) -> Unit = ::moveCaptureDirectory
    private var markDirectory: (File) -> Unit = ::markCaptureComplete
    private val recorder by lazy {
        MangaCaptureRecorder(
            lazy { session },
            scope,
            { root },
            { file, payload -> writeFile(file, payload) },
            time,
            erase = { eraseFiles(it) },
            rename = { from, to -> renameDirectory(from, to) },
            markComplete = { markDirectory(it) },
        )
    }
    private val list by lazy { RoomMangaList({ database }, session, network.http, network.clock, recorder) { false } }

    @After
    fun close() {
        scope.cancel()
        runBlocking { sessions.close() }
        database.close()
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun `only the designated action and its own complete read back are published`() =
        runBlocking {
            val owner = signIn()
            load(owner)
            val patch = MangaMutationMethod.Patch
            arm(patch)
            assertEquals(1, network.api.requestCount)
            assertNull(recorder.candidate(owner, MangaId(19), patch))
            assertNull(recorder.candidate(owner, id, MangaMutationMethod.Delete))
            arm(MangaMutationMethod.Delete)
            assertEquals(MangaCaptureState.Armed(MangaCaptureTarget(id, patch)), recorder.state.value)

            load(owner)
            assertTrue(root.listFiles().isNullOrEmpty())

            val next = network.api.url("/v2/users/@me/mangalist?offset=1&echo=$ACCESS")
            network.api.enqueue(answer(body = """{"score":0,"private":"$ACCESS","${NetworkFixture.CLIENT_ID}":"secret"}"""))
            network.api.enqueue(answer(body = page(row(19, 10), """{"next":"$next"}""")))
            network.api.enqueue(answer(body = page(row(17, 0))))
            assertTrue(list.edit(owner, id, MangaListEdit(score = 0)) is MangaListEditResult.Confirmed)
            val patched = completed()
            assertEquals(
                setOf("manga_mutation_patch.200.json", "manga_mutation_read_back.200.json", MANGA_CAPTURE_COMPLETE),
                patched.list().orEmpty().toSet(),
            )
            assertEquals("""{"score":0}""", File(patched, "manga_mutation_patch.200.json").readText())
            assertEquals(
                """{"pages":2,"complete":true,"present":true,"list_status":{"status":"reading","score":0}}""",
                File(patched, "manga_mutation_read_back.200.json").readText(),
            )

            val baseline = load(owner, score = 0)
            arm(MangaMutationMethod.Delete)
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page(row(19, 10))))
            assertEquals(MangaListRemovalResult.Removed, list.remove(owner, baseline))
            val deleted = completed()
            assertTrue(deleted != patched && patched.isDirectory)
            assertEquals("204\n", File(deleted, "manga_mutation_delete.204.txt").readText())
            assertEquals("""{"pages":1,"complete":true,"present":false}""", File(deleted, "manga_mutation_read_back.200.json").readText())
            assertEquals(
                listOf("GET", "GET", "PATCH", "GET", "GET", "GET", "DELETE", "GET"),
                List(network.api.requestCount) { network.api.received().method },
            )
            assertEquals(0, network.signIn.requestCount)
        }

    @Test
    fun `unusable acknowledgements and mismatched read backs do not become completed captures`() =
        runBlocking {
            val owner = signIn()
            load(owner)
            for ((ack, score) in listOf("""{"score":00}""" to 8, """{"score":8}""" to 1, """{"score":8}""" to null)) {
                arm(MangaMutationMethod.Patch)
                network.api.enqueue(answer(body = ack))
                network.api.enqueue(answer(body = if (score == null) page("") else page(row(17, score))))
                list.edit(owner, id, MangaListEdit(score = 8))
                assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Unconfirmed), recorder.state.value)
                assertTrue(root.listFiles().isNullOrEmpty())
                load(owner)
                assertTrue(root.listFiles().isNullOrEmpty())
            }
            assertEquals(10, network.api.requestCount)
        }

    @Test
    fun `ambiguous delete pages cannot record absence even when the editor removes the entry`() =
        runBlocking {
            val owner = signIn()
            val next = network.api.url("/v2/users/@me/mangalist?offset=1")
            val target = row(17, 1)
            val bodies =
                listOf(
                    """{"data":[$target],"data":[],"paging":{}}""",
                    """{"data":[$target],"d\u0061ta":[],"paging":{}}""",
                    """{"data":[],"paging":{"next":"$next"},"paging":{}}""",
                    """{"data":[],"paging":{"next":"$next","ne\u0078t":null}}""",
                )
            for (body in bodies) {
                val baseline = load(owner)
                arm(MangaMutationMethod.Delete)
                network.api.enqueue(answer(204, ""))
                network.api.enqueue(answer(body = body))
                assertEquals(MangaListRemovalResult.Removed, list.remove(owner, baseline))
                assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Unconfirmed), recorder.state.value)
                assertTrue(root.listFiles().isNullOrEmpty())
            }
            assertEquals(bodies.size * 3, network.api.requestCount)
        }

    @Test
    fun `contradictory or repeated acknowledged fields block capture even when GET confirms the PATCH`() =
        runBlocking {
            val owner = signIn()
            val bodies =
                listOf(
                    """{"score":1}""",
                    """{"score":1,"sc\u006fre":8}""",
                    """{"score":"8","status":"reading"}""",
                )
            for (body in bodies) {
                load(owner)
                arm(MangaMutationMethod.Patch)
                network.api.enqueue(answer(body = body))
                network.api.enqueue(answer(body = page(row(17, 8))))
                assertTrue(list.edit(owner, id, MangaListEdit(score = 8)) is MangaListEditResult.Confirmed)
                assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Unconfirmed), recorder.state.value)
                assertTrue(root.listFiles().isNullOrEmpty())
            }
            assertEquals(bodies.size * 3, network.api.requestCount)
        }

    @Test
    fun `stored GET projection proves every sent field including signed zero`() =
        runBlocking {
            val owner = signIn()
            load(owner)
            arm(MangaMutationMethod.Patch)
            network.api.enqueue(answer(body = """{"status":"reading"}"""))
            val selected = row(17, 0).replace("\"score\":0", "\"score\":-0,\"num_volumes_read\":3")
            network.api.enqueue(answer(body = page(selected)))
            assertTrue(list.edit(owner, id, MangaListEdit(score = 0, volumesRead = 3)) is MangaListEditResult.Confirmed)
            assertEquals(
                """{"pages":1,"complete":true,"present":true,"list_status":{"status":"reading","score":0,"num_volumes_read":3}}""",
                File(completed(), "manga_mutation_read_back.200.json").readText(),
            )
        }

    @Test
    fun `an already absent deletion can settle the editor without recording a successful DELETE`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            network.api.enqueue(answer(404, ""))
            network.api.enqueue(answer(body = page("")))
            assertEquals(MangaListRemovalResult.Removed, list.remove(owner, baseline))
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Unconfirmed), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `storage failure preserves the editor outcome without publishing half a capture`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            var writes = 0
            writeFile = { file, payload ->
                file.writeText(payload)
                if (++writes == 2) throw IOException("Synthetic storage failure")
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            assertEquals(MangaListRemovalResult.Removed, list.remove(owner, baseline))
            assertEquals(2, writes)
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Storage), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `disarm during staging prevents publication while other work reaches the owner queue`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            var count = 0
            writeFile = { file, payload ->
                file.writeText(payload)
                if (++count == 2) {
                    writing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(writing.await(5, TimeUnit.SECONDS)) }
                val active = (recorder.state.value as MangaCaptureState.Capturing).target
                assertNull(recorder.candidate(owner, MangaId(19), MangaMutationMethod.Patch))
                recorder.disarm(active)
                network.api.enqueue(answer(body = page(row(19, 2))))
                assertTrue(withTimeout(3_000) { list.refresh(owner) } is Outcome.Success)
            } finally {
                release.countDown()
            }
            assertEquals(MangaListRemovalResult.Removed, deleting.await())
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(listOf("GET", "DELETE", "GET", "GET"), List(network.api.requestCount) { network.api.received().method })
        }

    @Test
    fun `a stalled final rename leaves candidate lookup and disarm responsive`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val renaming = CountDownLatch(1)
            val release = CountDownLatch(1)
            renameDirectory = { from, to ->
                moveCaptureDirectory(from, to)
                renaming.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(renaming.await(5, TimeUnit.SECONDS)) }
                val target = (recorder.state.value as MangaCaptureState.Capturing).target
                withTimeout(3_000) {
                    withContext(Dispatchers.Default) {
                        assertNull(recorder.candidate(owner, MangaId(19), MangaMutationMethod.Patch))
                        recorder.disarm(target)
                    }
                    assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
                    val moved = checkNotNull(root.listFiles()).single()
                    assertTrue(moved.name.startsWith("capture-"))
                    assertTrue(File(moved, "manga_mutation_delete.204.txt").isFile)
                    assertTrue(File(moved, "manga_mutation_read_back.200.json").isFile)
                    assertThrows(IllegalStateException::class.java) { validateMangaMutationPair(moved, null) }
                    network.api.enqueue(answer(body = page(row(19, 2))))
                    assertTrue(list.refresh(owner) is Outcome.Success)
                }
            } finally {
                release.countDown()
            }
            assertEquals(MangaListRemovalResult.Removed, deleting.await())
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(listOf("GET", "DELETE", "GET", "GET"), List(network.api.requestCount) { network.api.received().method })
        }

    @Test
    fun `caller cancellation before authorization leaves the renamed pair unusable`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val moved = CountDownLatch(1)
            val release = CountDownLatch(1)
            renameDirectory = { from, to ->
                moveCaptureDirectory(from, to)
                moved.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(moved.await(5, TimeUnit.SECONDS)) }
                deleting.cancel()
                val pair = checkNotNull(root.listFiles()).single()
                assertThrows(IllegalStateException::class.java) { validateMangaMutationPair(pair, null) }
            } finally {
                release.countDown()
                deleting.cancelAndJoin()
            }
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `the completion marker is ordered with owner changes without blocking disarm`() =
        runBlocking {
            val owner = signIn()
            val original = checkNotNull(session.signedInOwner())
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val marking = CountDownLatch(1)
            val release = CountDownLatch(1)
            markDirectory = { directory ->
                marking.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                markCaptureComplete(directory)
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(marking.await(5, TimeUnit.SECONDS)) }
                val ownerChange = async(start = CoroutineStart.UNDISPATCHED) { session.withOwner(original) { true } }
                assertTrue(!ownerChange.isCompleted)
                val state = recorder.state.value as MangaCaptureState.Capturing
                assertEquals(false, state.canDisarm)
                recorder.disarm(state.target)
                assertEquals(state, recorder.state.value)
                val pair = checkNotNull(root.listFiles()).single()
                assertThrows(IllegalStateException::class.java) { validateMangaMutationPair(pair, null) }
            } finally {
                release.countDown()
            }
            assertEquals(MangaListRemovalResult.Removed, deleting.await())
            validateMangaMutationPair(completed(), null)
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `marker creation does not recreate a retired directory`() {
        val retired = folder.newFolder()
        assertTrue(retired.delete())
        assertThrows(IOException::class.java) { markCaptureComplete(retired) }
        assertEquals(false, retired.exists())
    }

    @Test
    fun `retirement during the final rename cannot report completion for a replaced sign in`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val renaming = CountDownLatch(1)
            val release = CountDownLatch(1)
            renameDirectory = { from, to ->
                renaming.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                moveCaptureDirectory(from, to)
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(renaming.await(5, TimeUnit.SECONDS)) }
                withTimeout(3_000) { signIn() }
            } finally {
                release.countDown()
            }
            assertEquals(MangaListRemovalResult.Removed, deleting.await())
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `a paused write cannot recreate its temporary evidence under a replacement root`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val writtenTo = AtomicReference<File>()
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            writeFile = { file, payload ->
                if (writtenTo.compareAndSet(null, file)) {
                    writing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                file.writeText(payload)
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val old = async { list.remove(owner, baseline) }
            val replacement =
                try {
                    withContext(Dispatchers.IO) { assertTrue(writing.await(5, TimeUnit.SECONDS)) }
                    val run = checkNotNull(checkNotNull(writtenTo.get()).parentFile)
                    assertTrue(run.name.startsWith("run-"))
                    withContext(Dispatchers.IO) { recorder.forget() }
                    val next = signIn()
                    load(next)
                    arm(MangaMutationMethod.Patch)
                    network.api.enqueue(answer(body = """{"score":8}"""))
                    network.api.enqueue(answer(body = page(row(17, 8))))
                    assertTrue(list.edit(next, id, MangaListEdit(score = 8)) is MangaListEditResult.Confirmed)
                    completed()
                } finally {
                    release.countDown()
                }
            assertEquals(MangaListRemovalResult.Removed, old.await())
            assertEquals(replacement, completed())
            assertEquals(listOf(replacement), root.listFiles()?.toList())
            assertEquals(6, network.api.requestCount)
        }

    @Test
    fun `failed staging cleanup retries only the run containing the temporary answer`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val leftovers = AtomicReference<File>()
            val erased = AtomicInteger()
            writeFile = { file, payload ->
                check(file.delete() && file.mkdir())
                File(file, "synthetic").writeText(payload)
                leftovers.set(file.parentFile)
                throw IOException("Synthetic staging failure")
            }
            eraseFiles = { directory ->
                if (directory == leftovers.get() && erased.incrementAndGet() == 1) false else directory.deleteRecursively()
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            assertEquals(MangaListRemovalResult.Removed, list.remove(owner, baseline))
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Storage), recorder.state.value)
            val failed = leftovers.get()
            assertEquals(root, failed.parentFile)
            assertTrue(erased.get() >= 1)

            writeFile = { file, payload -> file.writeText(payload) }
            load(owner)
            arm(MangaMutationMethod.Patch)
            network.api.enqueue(answer(body = """{"score":8}"""))
            network.api.enqueue(answer(body = page(row(17, 8))))
            assertTrue(list.edit(owner, id, MangaListEdit(score = 8)) is MangaListEditResult.Confirmed)
            val replacement = completed()
            withTimeout(5_000) { while (failed.exists()) delay(20) }
            assertTrue(erased.get() >= 2)
            assertEquals(listOf(replacement), root.listFiles()?.toList())
            assertEquals(6, network.api.requestCount)
        }

    @Test
    fun `cancelling the caller with a staged pair prevents publication`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            writeFile = { file, payload ->
                file.writeText(payload)
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page("")))
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(writing.await(5, TimeUnit.SECONDS)) }
                deleting.cancel()
            } finally {
                release.countDown()
                deleting.cancelAndJoin()
            }
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(3, network.api.requestCount)
        }

    @Test
    fun `failed retirement is reported and retried without erasing a later capture`() =
        runBlocking {
            val owner = signIn()
            load(owner)
            arm(MangaMutationMethod.Patch)
            network.api.enqueue(answer(body = """{"score":8}"""))
            network.api.enqueue(answer(body = page(row(17, 8))))
            list.edit(owner, id, MangaListEdit(score = 8))
            val first = completed()

            val calls = AtomicInteger()
            eraseFiles = { directory -> calls.incrementAndGet() > 1 && directory.deleteRecursively() }
            recorder.forget()
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Storage), recorder.state.value)
            assertTrue(first.isDirectory)
            withTimeout(5_000) { while (first.exists()) delay(20) }
            assertTrue(calls.get() >= 2)

            arm(MangaMutationMethod.Patch)
            network.api.enqueue(answer(body = """{"score":7}"""))
            network.api.enqueue(answer(body = page(row(17, 7))))
            assertTrue(list.edit(owner, id, MangaListEdit(score = 7)) is MangaListEditResult.Confirmed)
            assertTrue(completed().isDirectory)
            assertEquals(5, network.api.requestCount)
        }

    @Test
    fun `a late action cannot publish after disarm and replacement sign in`() =
        runBlocking {
            val owner = signIn()
            val baseline = load(owner)
            arm(MangaMutationMethod.Delete)
            val intercepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            network.answers { request ->
                if (request.method == "DELETE") {
                    intercepted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    answer(204, "")
                } else {
                    answer(body = page(""))
                }
            }
            val deleting = async { list.remove(owner, baseline) }
            try {
                withContext(Dispatchers.IO) { assertTrue(intercepted.await(5, TimeUnit.SECONDS)) }
                recorder.disarm((recorder.state.value as MangaCaptureState.Capturing).target)
                signIn()
            } finally {
                release.countDown()
            }
            deleting.await()
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertTrue(root.listFiles().isNullOrEmpty())
            assertEquals(2, network.api.requestCount)
        }

    @Test
    fun `startup clears leftover data and expired or ineligible arms cannot claim`() =
        runBlocking {
            val owner = signIn()
            val pending = File(root, "run-dead").apply { mkdirs() }
            File(pending, "manga_mutation_delete.204.txt").writeText("204\n")
            assertTrue(recorder.initialize())
            assertTrue(root.listFiles().isNullOrEmpty())
            arm(MangaMutationMethod.Patch, MangaId(0))
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Unavailable), recorder.state.value)
            arm(MangaMutationMethod.Delete)
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.MissingEntry), recorder.state.value)
            load(owner)
            arm(MangaMutationMethod.Patch)
            time += (MANGA_CAPTURE_MINUTES + 1).minutes
            assertNull(recorder.candidate(owner, id, MangaMutationMethod.Patch))
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Ended), recorder.state.value)
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `replacement arm does not adopt an earlier action and only one matching action claims`() =
        runBlocking {
            val owner = signIn()
            load(owner)
            val method = MangaMutationMethod.Patch
            arm(method)
            val stale = checkNotNull(recorder.candidate(owner, id, method))
            val retired = (recorder.state.value as MangaCaptureState.Armed).target
            recorder.disarm(retired)
            arm(method)
            recorder.disarm(retired)
            val first = checkNotNull(recorder.candidate(owner, id, method))
            val second = checkNotNull(recorder.candidate(owner, id, method))
            assertNull(stale.claim())
            val finish = checkNotNull(first.claim())
            assertNull(second.claim())
            finish.finish()
            assertEquals(MangaCaptureState.Stopped(MangaCaptureStop.Unconfirmed), recorder.state.value)
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `same sign in token refresh leaves request budget and report scoped to the final reply`() =
        runBlocking {
            val owner = signIn()
            load(owner)
            arm(MangaMutationMethod.Patch)
            val access = "mock-renewed-access"
            sessions.answer = { granted(access, "mock-renewed-refresh") }
            network.api.enqueue(answer(401, ""))
            network.api.enqueue(answer(body = """{"score":8,"private":"$access"}"""))
            network.api.enqueue(answer(body = page(row(17, 8))))

            assertTrue(list.edit(owner, id, MangaListEdit(score = 8)) is MangaListEditResult.Confirmed)
            assertEquals("""{"score":8}""", File(completed(), "manga_mutation_patch.200.json").readText())
            assertEquals(listOf("GET", "PATCH", "PATCH", "GET"), List(network.api.requestCount) { network.api.received().method })
            assertEquals(1, sessions.grants.size)
            assertEquals(owner, session.currentSession()?.owner)
        }

    private fun completed(): File = (recorder.state.value as MangaCaptureState.Complete).folder

    private suspend fun arm(
        method: MangaMutationMethod,
        target: MangaId = id,
    ) {
        recorder.arm(session.signedInOwner(), target, method) { owner, requested ->
            val stored = list.stored(owner) as? Outcome.Success
            stored?.value?.entries?.any { it.id == requested } == true
        }
    }

    private suspend fun signIn(): SignInId {
        assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant(ACCESS, "test-refresh")))
        return checkNotNull(session.currentSession()?.owner)
    }

    private suspend fun load(
        owner: SignInId,
        score: Int = 1,
    ) = network.api.enqueue(answer(body = page(row(17, score)))).let {
        (list.refresh(owner) as Outcome.Success).value.entries.single()
    }

    private fun page(
        rows: String,
        paging: String = "{}",
    ) = """{"data":[$rows],"paging":$paging}"""

    private fun row(
        id: Int,
        score: Int,
    ) = """{"node":{"id":$id,"title":"Unretained title $id"},"list_status":{"status":"reading","score":$score,"comments":"$ACCESS"}}"""

    private companion object {
        const val ACCESS = "mock-access-56f"
        const val DATABASE = "capture-test.db"
    }
}
