package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.data.session.FailingDisk
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class RoomRecentTitlesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `recent visits survive reopening the database in recency order with a bounded typed key`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            var session = sessions.open()
            val name = "recent-visits.db"
            context.deleteDatabase(name)
            val anime = anime(1, "Anime")
            val manga = RecentTitle(RecentTitleId.Manga(1), Titles("Manga"), true)
            val other = (2L..RecentTitles.LIMIT.toLong()).map { anime(it, "Anime $it") }
            try {
                assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
                KirokuDatabase.open(context, name).use { db ->
                    val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                    val scope = history.activeScope()
                    history.record(scope, anime, 1)
                    history.record(scope, manga, 2)
                    history.record(scope, anime, 3)
                    history.record(scope, anime.copy(titles = Titles("Obsolete")), 1)
                    assertEquals(listOf(anime, manga), history.observe(scope).first())
                    for ((index, title) in other.withIndex()) history.record(scope, title, (index + 4).toLong())
                    history.record(scope, anime.copy(safeForSearch = false), 3)
                    history.record(scope, manga, 2)
                }
                session = sessions.reopen()
                KirokuDatabase.open(context, name).use { db ->
                    val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                    val expected = other.reversed() + anime.copy(safeForSearch = false)
                    assertEquals(expected, history.observe(history.activeScope()).first())
                    assertEquals(RecentTitles.LIMIT.toLong(), db.rows("recent_title"))
                }
            } finally {
                sessions.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun `replay after a committed visit keeps its order and retains known safety until explicit evidence changes it`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            try {
                val session = sessions.open()
                KirokuDatabase.builder(context, null).build().use { db ->
                    val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                    val scope = history.activeScope()
                    val a = anime(1, "A").copy(safeForSearch = false)
                    val b = anime(2, "B")
                    history.record(scope, a, 1)
                    history.record(scope, b, 2)
                    history.record(scope, a.copy(safeForSearch = null), 1)
                    assertEquals(listOf(b, a), history.observe(scope).first())
                    history.record(scope, a.copy(safeForSearch = null), 3)
                    assertEquals(listOf(a, b), history.observe(scope).first())
                    history.record(scope, a.copy(safeForSearch = true), 3)
                    assertEquals(listOf(a.copy(safeForSearch = true), b), history.observe(scope).first())
                }
            } finally {
                sessions.close()
            }
        }

    @Test
    fun `replacement and sign out hide previous visits and reject late writes including from the previous guest`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            try {
                val session = sessions.open()
                KirokuDatabase.builder(context, null).build().use { db ->
                    val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                    val guest = history.activeScope()
                    history.record(guest, anime(1, "Guest"), 1)
                    assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
                    val first = history.activeScope()
                    assertEquals(emptyList<RecentTitle>(), history.observe(guest).first())
                    val firstTitle = anime(1, "First")
                    history.record(first, firstTitle, 2)
                    assertEquals(listOf(firstTitle), history.observe(first).first())
                    assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-2", "refresh-2")))
                    val replacement = history.activeScope()
                    assertEquals(emptyList<RecentTitle>(), history.observe(first).first())
                    history.record(first, firstTitle, 3)
                    assertEquals(emptyList<RecentTitle>(), history.observe(replacement).first())
                    history.record(replacement, anime(2, "Second"), 4)
                    session.signOutIfCurrent(replacement.owner) { history.clear(requireNotNull(replacement.owner)) }
                    val nextGuest = history.activeScope()
                    assertNotEquals(nextGuest, guest)
                    history.record(guest, anime(1, "Late guest"), 5)
                    assertEquals(emptyList<RecentTitle>(), history.observe(nextGuest).first())
                    assertEquals(0L, db.rows("recent_title"))
                }
            } finally {
                sessions.close()
            }
        }

    @Test
    fun `retirement retries after both sign out and the owner sweep fail without a new visit`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            try {
                val session = sessions.open()
                assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
                KirokuDatabase.builder(context, null).build().use { db ->
                    val failedClear = CompletableDeferred<Unit>()
                    val failedSweep = CompletableDeferred<Unit>()
                    var failures = 0
                    val history =
                        RoomRecentTitles({ db }, session) {
                            when (++failures) {
                                1 -> failedClear.complete(Unit)
                                2 -> failedSweep.complete(Unit)
                                else -> Unit
                            }
                        }
                    val signedIn = history.activeScope()
                    history.record(signedIn, anime(1, "Retired"), 1)
                    db.breakWrites("recent_title", "DELETE")
                    session.signOutIfCurrent(signedIn.owner) { history.clear(requireNotNull(signedIn.owner)) }
                    withTimeout(5_000) { failedClear.await() }
                    val sweep = launch { history.sweepRetired { true } }
                    try {
                        withTimeout(5_000) { failedSweep.await() }
                        assertEquals(1L, db.rows("recent_title"))
                        db.repair("recent_title", "DELETE")
                        withTimeout(5_000) {
                            while (db.rows("recent_title") != 0L) delay(20)
                        }
                        assertEquals(2, failures)
                    } finally {
                        sweep.cancelAndJoin()
                    }
                }
            } finally {
                sessions.close()
            }
        }

    @Test
    fun `guest generations and visit order survive restarting with the clock moved backwards`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            var session = sessions.open()
            val name = "guest-visits.db"
            context.deleteDatabase(name)
            val a = anime(1, "A")
            val b = anime(2, "B")
            try {
                val initialGuest =
                    KirokuDatabase.open(context, name).use { db ->
                        val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                        val initial = history.activeScope()
                        history.record(initial, a, checkNotNull(history.reserveVisit(initial)))
                        initial
                    }
                session = sessions.reopen()
                val (guest, first, second) =
                    KirokuDatabase.open(context, name).use { db ->
                        val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                        assertEquals(initialGuest, history.activeScope())
                        assertEquals(listOf(a), history.observe(initialGuest).first())

                        assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
                        val signedIn = history.activeScope()
                        session.signOutIfCurrent(signedIn.owner) { history.clear(requireNotNull(signedIn.owner)) }
                        val guest = history.activeScope()
                        assertNotEquals(initialGuest, guest)
                        assertNull(history.reserveVisit(initialGuest))
                        history.record(initialGuest, b, 1)
                        assertEquals(emptyList<RecentTitle>(), history.observe(guest).first())

                        val first = checkNotNull(history.reserveVisit(guest))
                        history.record(guest, a, first)
                        val second = checkNotNull(history.reserveVisit(guest))
                        history.record(guest, b, second)
                        Triple(guest, first, second)
                    }
                sessions.clock.advance(Duration.ofDays(-1))
                session = sessions.reopen()
                assertEquals(Session.SignedOut, session.observeSession().first())
                KirokuDatabase.open(context, name).use { db ->
                    val history = RoomRecentTitles({ db }, session) { throw AssertionError(it) }
                    assertEquals(guest, history.activeScope())
                    assertEquals(listOf(b, a), history.observe(guest).first())
                    history.record(guest, a, first)
                    assertEquals(listOf(b, a), history.observe(guest).first())
                    val reopenedVisit = checkNotNull(history.reserveVisit(guest))
                    assertTrue(reopenedVisit > second)
                    val reopened = a.copy(titles = Titles("A reopened"))
                    history.record(guest, reopened, reopenedVisit)
                    history.record(guest, a, first)
                    assertEquals(listOf(reopened, b), history.observe(guest).first())
                }
            } finally {
                sessions.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun `the same malformed token file keeps guest history across restart without rewriting the file`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            val malformed = byteArrayOf(7, 1, 2)
            sessions.file.writeBytes(malformed)
            val name = "malformed-token-visits.db"
            context.deleteDatabase(name)
            val title = anime(1, "Guest visit")
            try {
                val initial = sessions.open()
                val guest =
                    KirokuDatabase.open(context, name).use { db ->
                        val history = RoomRecentTitles({ db }, initial) { throw AssertionError(it) }
                        val scope = history.activeScope()
                        assertEquals(Session.SignedOut, initial.observeSession().first())
                        history.record(scope, title, checkNotNull(history.reserveVisit(scope)))
                        assertArrayEquals(malformed, sessions.file.readBytes())
                        scope
                    }
                val restarted = sessions.reopen()
                KirokuDatabase.open(context, name).use { db ->
                    val history = RoomRecentTitles({ db }, restarted) { throw AssertionError(it) }
                    assertEquals(guest, history.activeScope())
                    assertEquals(listOf(title), history.observe(guest).first())
                    assertArrayEquals(malformed, sessions.file.readBytes())
                }
            } finally {
                sessions.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun `a history reservation releases the session gate while its caller is paused`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            try {
                val session = sessions.open()
                KirokuDatabase.builder(context, null).build().use { db ->
                    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                        val entered = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        val paused = CompletableDeferred<Unit>()
                        val resume = CountDownLatch(1)
                        val history =
                            RoomRecentTitles({
                                entered.complete(Unit)
                                release.await()
                                db
                            }, session) { throw AssertionError(it) }
                        val scope = history.activeScope()
                        val reservation = launch(caller) { history.reserveVisit(scope) }
                        try {
                            withTimeout(5_000) {
                                entered.await()
                                caller.executor.execute {
                                    paused.complete(Unit)
                                    resume.await()
                                }
                                paused.await()
                                release.complete(Unit)
                                assertTrue(session.whileOwner(scope.owner) { true })
                            }
                        } finally {
                            resume.countDown()
                            reservation.cancelAndJoin()
                        }
                    }
                }
            } finally {
                sessions.close()
            }
        }

    @Test
    fun `an interrupted sign out cannot restore marked history and its cleanup keeps other owners`() =
        runBlocking {
            val sessions = SessionFixture(folder.root)
            val disk = FailingDisk(sessions.format(), failing = false)
            val retirement = folder.root.resolve("retired")
            val name = "retired-visits.db"
            context.deleteDatabase(name)
            try {
                val session = sessions.open(format = disk, retryAfter = { Duration.ofHours(1) })
                assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant("access-1", "refresh-1")))
                val old =
                    KirokuDatabase.open(context, name).use { db ->
                        val history = RoomRecentTitles({ db }, session) { }
                        val oldScope = history.activeScope()
                        val visit = checkNotNull(history.reserveVisit(oldScope))
                        history.record(oldScope, anime(1, "Retired"), visit)
                        db.recentTitles().save(RecentTitleRow(visit + 1, "replacement", false, 2, "Replacement", null, null, true))
                        db.breakWrites("recent_title", "DELETE")
                        disk.fullToo = true
                        val owner = requireNotNull(oldScope.owner)
                        session.signOutIfCurrent(owner, onWriteFailure = { retirement.writeText(owner.value) }) {
                            history.clear(owner)
                        }
                        assertTrue(retirement.isFile)
                        assertEquals(2L, db.rows("recent_title"))
                        oldScope
                    }

                val restored = sessions.reopen()
                assertEquals(Session.SignedIn(requireNotNull(old.owner)), restored.observeSession().first())
                KirokuDatabase.open(context, name).use { db ->
                    val history =
                        RoomRecentTitles({ db }, restored, wasRetired = { it.value == retirement.readText() }) { throw AssertionError(it) }
                    assertNull(history.observeScope().first())
                    assertEquals(emptyList<RecentTitle>(), history.observe(old).first())
                    assertNull(history.reserveVisit(old))
                    history.record(old, anime(3, "Late"), 10)
                    assertEquals(2L, db.rows("recent_title"))
                    val sweep = launch { history.sweepRetired { true } }
                    try {
                        withTimeout(5_000) {
                            while (db.rows("recent_title") != 1L) delay(20)
                        }
                        assertEquals(
                            listOf(anime(2, "Replacement")),
                            db
                                .recentTitles()
                                .observe("replacement")
                                .first()
                                .map { it.toTitle() },
                        )
                    } finally {
                        sweep.cancelAndJoin()
                    }
                }
            } finally {
                sessions.close()
                context.deleteDatabase(name)
            }
        }

    private suspend fun RoomRecentTitles.activeScope(): RecentTitleScope = requireNotNull(observeScope().first())

    private inline fun <T> KirokuDatabase.use(block: (KirokuDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private fun anime(
        id: Long,
        title: String,
    ) = RecentTitle(RecentTitleId.Anime(id), Titles(title), true)
}
