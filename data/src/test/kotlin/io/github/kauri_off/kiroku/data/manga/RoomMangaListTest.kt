package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.network.TestClock
import io.github.kauri_off.kiroku.data.session.Attempt
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.TokenReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class RoomMangaListTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val name = "personal-manga.db"
    private var database = KirokuDatabase.open(context, name)
    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val session by lazy { sessions.open() }
    private val clock = TestClock(Instant.parse("2026-11-25T15:00:00.123456789Z"))
    private val reported = mutableListOf<Throwable>()
    private var response: suspend (SignInId) -> Outcome<List<MangaListEntry>> = { error("Unexpected fetch") }

    @After
    fun close() {
        runBlocking { sessions.close() }
        database.close()
        context.deleteDatabase(name)
    }

    private fun repository() = RoomMangaList({ database }, session, { owner, _ -> response(owner) }, clock, unexpected = { reported += it })

    private suspend fun signIn(name: String): SignInId {
        assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant(name, "refresh-$name")))
        return checkNotNull(session.currentSession()?.owner)
    }

    private fun entry(id: Long = 17) =
        MangaListEntry(
            id = MangaId(id),
            titles = Titles("Narrow Roads", "Roads", "Road", listOf("Side Road")),
            status = MangaReadingStatus("unrecognized-reading-status"),
            posterUrl = "https://example.test/cover.png",
            chaptersRead = 0,
            volumesRead = 3,
            score = 8,
            updatedAt = clock.now.minusSeconds(1),
        )

    @Test
    fun `complete snapshots replace only the current list and survive reopening`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val first = entry()
            val second = entry(22).copy(score = null, chaptersRead = null, updatedAt = null)
            assertEquals(Outcome.Success(null), list.stored(owner))

            response = { asked ->
                assertEquals(owner, asked)
                Outcome.Success(listOf(second, first))
            }
            val saved = MangaListSnapshot(owner, listOf(first, second), clock.now)
            assertEquals(Outcome.Success(saved), list.refresh(owner))
            assertEquals(Outcome.Success(saved), list.stored(owner))

            database.close()
            database = KirokuDatabase.open(context, name)
            val reopened = repository()
            assertEquals(Outcome.Success(saved), reopened.stored(owner))

            clock.now = clock.now.plusSeconds(20)
            response = { Outcome.Success(listOf(second)) }
            val reduced = MangaListSnapshot(owner, listOf(second), clock.now)
            assertEquals(Outcome.Success(reduced), reopened.refresh(owner))
            assertEquals(Outcome.Success(reduced), reopened.stored(owner))

            clock.now = clock.now.plusSeconds(20)
            response = { Outcome.Success(emptyList()) }
            val empty = MangaListSnapshot(owner, emptyList(), clock.now)
            assertEquals(Outcome.Success(empty), reopened.refresh(owner))
            assertEquals(Outcome.Success(empty), reopened.stored(owner))
        }

    @Test
    fun `a failed download duplicate or storage failure leaves both entries and freshness intact`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val older = entry()
            response = { Outcome.Success(listOf(older)) }
            val saved = list.refresh(owner)

            clock.now = clock.now.plusSeconds(60)
            response = { Outcome.Failure(DataError.NoConnection) }
            assertEquals(Outcome.Failure(DataError.NoConnection), list.refresh(owner))
            assertEquals(saved, list.stored(owner))

            response = { Outcome.Success(listOf(entry(22), entry(18), entry(22))) }
            assertEquals(Outcome.Failure(DataError.ServerError), list.refresh(owner))
            assertEquals(saved, list.stored(owner))

            response = { Outcome.Success(listOf(entry(22).copy(updatedAt = Instant.MAX))) }
            assertEquals(Outcome.Failure(DataError.StorageError), list.refresh(owner))
            assertEquals(saved, list.stored(owner))
            assertEquals(1, reported.size)
        }

    @Test
    fun `a thrown source failure preserves the snapshot and reports no answer contents`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            val saved = list.refresh(owner)

            clock.now = clock.now.plusSeconds(60)
            response = { error("private response") }
            assertEquals(Outcome.Failure(DataError.ServerError), list.refresh(owner))
            assertEquals(saved, list.stored(owner))
            assertEquals(IllegalStateException::class.java.name, reported.single().message)
            assertNull(reported.single().cause)
        }

    @Test
    fun `a new signed out owner triggers cleanup even when the collector misses the intervening sign in`() =
        runBlocking {
            val a = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            assertTrue(list.refresh(a) is Outcome.Success)
            session.signOut()

            val initial = CompletableDeferred<SessionOwner>()
            val release = CompletableDeferred<Unit>()
            val sweep =
                async {
                    session.observeOwner().take(2).collect { owner ->
                        if (initial.complete(owner)) {
                            release.await()
                        } else {
                            assertNull(owner.id)
                            assertEquals(Outcome.Success(Unit), list.forgetRetired(owner))
                        }
                    }
                }
            try {
                val old = initial.await()
                assertNull(old.id)
                signIn("b")
                assertEquals(Outcome.Failure(DataError.ListEnded), list.forgetRetired(old))
                session.signOut()
                assertEquals(Outcome.Failure(DataError.ListEnded), list.forgetRetired(old))
                assertTrue(database.mangaList().entries(a.value).isNotEmpty())
                release.complete(Unit)
                withTimeout(5_000) { sweep.await() }
                assertTrue(database.mangaList().entries(a.value).isEmpty())
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun `a retired download and cleanup cannot replace the new owner's same manga`() =
        runBlocking {
            val a = signIn("a")
            val list = repository()
            val epochA = session.observeOwner().first()
            val aEntry = entry().copy(score = 4)
            response = { Outcome.Success(listOf(aEntry)) }
            assertEquals(Outcome.Success(MangaListSnapshot(a, listOf(aEntry), clock.now)), list.refresh(a))

            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            response = {
                waiting.complete(Unit)
                release.await()
                Outcome.Success(emptyList())
            }
            val stale = async { list.refresh(a) }
            waiting.await()
            try {
                session.signOut()
                val b = signIn("b")
                assertEquals(Outcome.Failure(DataError.ListEnded), list.stored(a))
                assertEquals(Outcome.Success(null), list.stored(b))
                val bEntry = entry().copy(score = 9)
                response = { Outcome.Success(listOf(bEntry)) }
                val bSnapshot = MangaListSnapshot(b, listOf(bEntry), clock.now)
                assertEquals(Outcome.Success(bSnapshot), withTimeout(5_000) { list.refresh(b) })
                release.complete(Unit)
                assertEquals(Outcome.Failure(DataError.ListEnded), stale.await())
                assertEquals(Outcome.Failure(DataError.ListEnded), list.forgetRetired(epochA))
                assertEquals(Outcome.Success(bSnapshot), list.stored(b))
                assertNull(database.mangaList().state(a.value))
                assertTrue(database.mangaList().entries(a.value).isEmpty())

                session.signOut()
                assertEquals(Outcome.Success(Unit), list.forgetRetired(session.observeOwner().first()))
                assertNull(database.mangaList().state(b.value))
                assertTrue(database.mangaList().entries(b.value).isEmpty())
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun `refreshes run in sequence for the same repository while cached data stays readable`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val seen = mutableListOf<SignInId>()
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            response = {
                seen += it
                if (seen.size == 1) {
                    waiting.complete(Unit)
                    release.await()
                }
                Outcome.Success(listOf(entry(seen.size.toLong())))
            }
            val first = async { list.refresh(owner) }
            waiting.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { list.refresh(owner) }
            try {
                assertEquals(listOf(owner), seen)
                assertEquals(Outcome.Success(null), list.stored(owner))
            } finally {
                release.complete(Unit)
            }
            val firstSnapshot = (first.await() as Outcome.Success).value
            val secondSnapshot = (second.await() as Outcome.Success).value
            assertEquals(MangaId(1), firstSnapshot.entries.single().id)
            assertEquals(MangaId(2), secondSnapshot.entries.single().id)
            assertEquals(Outcome.Success(secondSnapshot), list.stored(owner))
        }

    @Test
    fun `the source checks visibility after waiting for the refresh queue`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val inSource = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            response = {
                calls++
                inSource.complete(Unit)
                release.await()
                Outcome.Success(listOf(entry()))
            }
            val visible = AtomicBoolean(true)
            val admitted = async { list.refresh(owner, visible::get) }
            inSource.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { list.refresh(owner, visible::get) }
            visible.set(false)
            release.complete(Unit)

            assertTrue(admitted.await() is Outcome.Success)
            assertNull(queued.await())
            assertEquals(1, calls)
        }

    @Test
    fun `an expiry before send admission keeps the snapshot and reports session expired`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            val cached = list.refresh(owner)

            sessions.answer = { TokenReply.Answer(400, "") }
            response = {
                assertEquals(Outcome.Failure(DataError.SessionExpired), session.authorised<Unit> { Attempt.Rejected })
                Outcome.Failure(DataError.ListEnded)
            }

            assertEquals(Outcome.Failure(DataError.SessionExpired), list.refresh(owner))
            assertEquals(cached, list.stored(owner))
        }

    @Test
    fun `an expired sign in still reads its own cache and starts no download`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            val cached = list.refresh(owner)

            sessions.answer = { TokenReply.Answer(400, "") }
            val expired = session.authorised<Unit> { Attempt.Rejected }
            assertEquals(Outcome.Failure(DataError.SessionExpired), expired)
            response = { error("An expired sign-in cannot fetch") }

            assertEquals(cached, list.stored(owner))
            assertEquals(Outcome.Failure(DataError.SessionExpired), list.refresh(owner))
        }
}
