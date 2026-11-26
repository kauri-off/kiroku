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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val recorder by lazy { MangaCaptureRecorder(lazy { session }, scope, { root }, { f, s -> writeFile(f, s) }, time) }
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
            assertEquals(setOf("patch.200.json", "read_back.200.json"), patched.list().orEmpty().toSet())
            assertEquals("""{"score":0}""", File(patched, "patch.200.json").readText())
            assertEquals(
                """{"pages":2,"complete":true,"present":true,"list_status":{"status":"reading","score":0}}""",
                File(patched, "read_back.200.json").readText(),
            )

            val baseline = load(owner, score = 0)
            arm(MangaMutationMethod.Delete)
            network.api.enqueue(answer(204, ""))
            network.api.enqueue(answer(body = page(row(19, 10))))
            assertEquals(MangaListRemovalResult.Removed, list.remove(owner, baseline))
            val deleted = completed()
            assertTrue(deleted != patched && patched.isDirectory)
            assertEquals("204\n", File(deleted, "delete.204.txt").readText())
            assertEquals("""{"pages":1,"complete":true,"present":false}""", File(deleted, "read_back.200.json").readText())
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
                recorder.disarm().join()
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
            File(pending, "delete.204.txt").writeText("204\n")
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
            recorder.disarm().join()
            arm(method)
            val first = checkNotNull(recorder.candidate(owner, id, method))
            val second = checkNotNull(recorder.candidate(owner, id, method))
            assertNull(stale.claim())
            val finish = checkNotNull(first.claim())
            assertNull(second.claim())
            finish(null)
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
            assertEquals("""{"score":8}""", File(completed(), "patch.200.json").readText())
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
