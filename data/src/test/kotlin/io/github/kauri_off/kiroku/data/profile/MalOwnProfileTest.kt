package io.github.kauri_off.kiroku.data.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.StatedFigures
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.ProfileRow
import io.github.kauri_off.kiroku.data.db.RoomLocalList
import io.github.kauri_off.kiroku.data.db.RoomProfileStore
import io.github.kauri_off.kiroku.data.db.rows
import io.github.kauri_off.kiroku.data.sandbox.Listed
import io.github.kauri_off.kiroku.data.sandbox.SandboxFixture
import io.github.kauri_off.kiroku.data.sandbox.SandboxMal
import io.github.kauri_off.kiroku.data.sandbox.SandboxTokens
import io.github.kauri_off.kiroku.data.sandbox.sandboxTitles
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

// The own profile over the app's client, the sandbox and a database in a file, as the app has them.
@RunWith(RobolectricTestRunner::class)
class MalOwnProfileTest {
    private val ids = sandboxTitles.keys.sorted().take(4)
    private val updated = Instant.parse("2026-11-01T10:00:00Z")

    @get:Rule
    internal val sandbox =
        SandboxFixture(
            starting = {
                mapOf(
                    ids[0] to Listed("completed", 8, 12, null, null, updated),
                    ids[1] to Listed("completed", 0, 3, null, null, updated),
                    ids[2] to Listed("watching", 5, 2, null, null, updated),
                    ids[3] to Listed("plan_to_watch", 0, 0, null, null, updated),
                )
            },
        )

    private val context = RuntimeEnvironment.getApplication()
    private val name = "profile.db"
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val opened = mutableListOf<KirokuDatabase>()
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun database(): KirokuDatabase = KirokuDatabase.open(context, name).also { opened += it }

    private fun profile(database: KirokuDatabase = database()): MalOwnProfile =
        MalOwnProfile(
            MalProfiles(sandbox.client.http, sandbox.session),
            RoomProfileStore(database, Dispatchers.IO, unexpected::add),
            sandbox.session.observeSession(),
            sandbox.clock,
            unexpected::add,
        )

    @After
    fun close() {
        scope.cancel()
        opened.forEach { it.close() }
        context.deleteDatabase(name)
        assertEquals(emptyList<Throwable>(), unexpected + sandbox.client.unexpected)
    }

    private val sandboxUser =
        UserProfile(
            name = SandboxMal.USER_NAME,
            pictureUrl = "https://sandbox.invalid/images/userimages/1.jpg",
            joined = LocalDate.of(2001, 1, 1),
            stated =
                StatedFigures(
                    watching = 1,
                    completed = 2,
                    onHold = 0,
                    dropped = 0,
                    planned = 1,
                    entries = 4,
                    episodes = 17,
                    rewatched = 0,
                    daysWatched = 17 * (24.0 / (24 * 60)),
                    meanScore = 6.5,
                ),
        )

    private val ownAsked get() = sandbox.mal.asked.filter { it.endsWith("/users/@me") || it.endsWith("/token") }

    private fun signInAgain() = runBlocking { sandbox.session.signIn(sandbox.tokens.granted().let { TokenGrant(it.first, it.second) }) }

    @Test
    fun `the profile read from the sandbox is stored, observed, and read by a new store over the same file`() =
        runBlocking {
            val first = database()
            val profile = profile(first)
            assertNull(profile.observeOwnProfile().first())

            assertEquals(Outcome.Success(Unit), profile.refreshOwnProfile())

            assertEquals(sandboxUser, profile.observeOwnProfile().first())
            assertEquals(listOf("GET sandbox.invalid/v2/users/@me"), ownAsked)
            first.close()
            assertEquals(sandboxUser, profile().observeOwnProfile().first())
            assertEquals(1, ownAsked.size)
        }

    @Test
    fun `a refresh that fails leaves what was stored`() =
        runBlocking {
            val profile = profile()
            profile.refreshOwnProfile()
            sandbox.list.remove(ids[0])
            sandbox.mal.offline = true

            assertEquals(Outcome.Failure(DataError.NoConnection), profile.refreshOwnProfile())

            assertEquals(sandboxUser, profile.observeOwnProfile().first())
        }

    @Test
    fun `a new sign-in sees nothing of the last one's, and the list's change of hands forgets it`() =
        runBlocking {
            val database = database()
            val profile = profile(database)
            profile.refreshOwnProfile()
            val list = RoomLocalList(database, scope, Dispatchers.IO) { throw it }
            val owner = (sandbox.session.observeSession().first() as Session.SignedIn).signIn
            list.clear(owner)
            assertEquals(1L, database.rows(ProfileRow.TABLE))

            signInAgain()

            assertNull(profile.observeOwnProfile().first())
            val next = (sandbox.session.observeSession().first() as Session.SignedIn).signIn
            list.clear(next)
            assertEquals(0L, database.rows(ProfileRow.TABLE))
            assertEquals(Outcome.Success(Unit), profile.refreshOwnProfile())
            assertEquals(sandboxUser, profile.observeOwnProfile().first())
        }

    @Test
    fun `a sign-out shows no profile, and the list's clearing leaves none in the database`() =
        runBlocking {
            val database = database()
            val profile = profile(database)
            profile.refreshOwnProfile()

            sandbox.session.signOut()

            assertNull(profile.observeOwnProfile().first())
            RoomLocalList(database, scope, Dispatchers.IO) { throw it }.clear(null)
            assertEquals(0L, database.rows(ProfileRow.TABLE))
            assertEquals(Outcome.Failure(DataError.SessionExpired), profile.refreshOwnProfile())
            assertEquals(1, ownAsked.size)
        }

    @Test
    fun `an answer that comes after a sign-out is not stored`() =
        runBlocking {
            val database = database()
            val profile = profile(database)
            sandbox.mal.delayMillis = 500
            val refresh = async(Dispatchers.IO) { profile.refreshOwnProfile() }
            while (ownAsked.isEmpty()) delay(10)

            sandbox.session.signOut()

            assertEquals(Outcome.Failure(DataError.ListEnded), refresh.await())
            assertEquals(0L, database.rows(ProfileRow.TABLE))
        }

    @Test
    fun `a rejected token causes one refresh and one retry`() =
        runBlocking {
            val profile = profile()
            sandbox.mal.expireAccess()

            assertEquals(Outcome.Success(Unit), profile.refreshOwnProfile())

            assertEquals(
                listOf("GET sandbox.invalid/v2/users/@me", "POST sandbox.invalid/v1/oauth2/token", "GET sandbox.invalid/v2/users/@me"),
                ownAsked,
            )
            assertEquals(sandboxUser, profile.observeOwnProfile().first())
        }

    @Test
    fun `with the session expired the stored profile is read and MAL is not asked`() =
        runBlocking {
            val profile = profile()
            profile.refreshOwnProfile()
            sandbox.clock.now += SandboxTokens.REFRESH_LIFETIME
            assertEquals(Outcome.Failure(DataError.SessionExpired), profile.refreshOwnProfile())
            assertTrue(sandbox.session.observeSession().first() is Session.Expired)
            val asked = sandbox.mal.asked.size

            assertEquals(Outcome.Failure(DataError.SessionExpired), profile.refreshOwnProfile())

            assertEquals(sandboxUser, profile.observeOwnProfile().first())
            assertEquals(asked, sandbox.mal.asked.size)
        }

    @Test
    fun `no download, write or restore of the list touches the profile`() =
        runBlocking {
            val database = database()
            val profile = profile(database)
            profile.refreshOwnProfile()
            val list = RoomLocalList(database, scope, Dispatchers.IO) { throw it }
            val stored = database.profile().stored()
            val anime = FixtureAnime.exactToWatch
            val entry = StoredEntry.settled(checkNotNull(Fixtures.reference.entryFor(anime.id)).record)

            list.clear((sandbox.session.observeSession().first() as Session.SignedIn).signIn)
            list.download(listOf(anime), mapOf(anime.id to entry), Instant.EPOCH)
            list.add(FixtureAnime.film, entry)
            list.write(mapOf(anime.id to null))
            list.restore(mapOf(FixtureAnime.film.id to null))

            assertEquals(stored, database.profile().stored())
            assertNotNull(profile.observeOwnProfile().first())
        }
}
