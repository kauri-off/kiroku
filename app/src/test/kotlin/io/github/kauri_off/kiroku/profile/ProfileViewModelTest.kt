package io.github.kauri_off.kiroku.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoUserLists
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureUser
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.Freshness
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.ScriptedUserLists
import io.github.kauri_off.kiroku.core.user.UserListKeeper
import io.github.kauri_off.kiroku.core.user.UserListPart
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val fixtures = Fixtures.reference
    private val ownName = fixtures.user.name
    private val otherName = fixtures.otherUserName
    private val privateName = fixtures.privateUserName

    // One whole read of the public user's list: a request a part.
    private val wholeRead = (fixtures.otherUserList.size + DemoUserLists.PART_SIZE - 1) / DemoUserLists.PART_SIZE

    private class Opened(
        val graph: TestGraph,
        val messages: ShellMessages,
        val opened: OpenedProfiles,
    )

    private fun TestScope.opened(scope: CoroutineScope = foregroundScope()): Opened {
        val graph = TestGraph(Demo(fixtures, scope))
        return Opened(graph, ShellMessages(scope, graph.list), OpenedProfiles())
    }

    private fun Opened.profile(
        owner: ProfileOwner,
        saved: SavedStateHandle = SavedStateHandle(),
        user: UserRepository = graph.user,
    ) = ProfileViewModel(
        owner,
        graph.session,
        graph.list,
        user,
        graph.connection,
        graph.clock,
        graph.background,
        saved,
        messages,
        opened,
    )

    private val Opened.requests get() = graph.demo.userLists.requests

    // The signed-in user's profile as a store holds it: [stored], read at [fetchedAt], until a refresh stores the fixture
    // user, kept after a sign-out until something forgets it.
    private class OwnStore(
        inner: UserRepository,
        stored: UserProfile?,
        private val answer: Outcome<Unit> = Outcome.Success(Unit),
        private val latency: Duration = Duration.ZERO,
        fetchedAt: Instant = Fixtures.reference.now,
    ) : UserRepository by inner {
        val held = MutableStateFlow(stored)
        val fetched = MutableStateFlow(fetchedAt)
        var refreshes = 0

        override fun observeOwnProfile(): Flow<UserProfile?> = held

        override fun observeOwnProfileFetchedAt(): Flow<Instant?> = combine(held, fetched) { held, at -> at.takeIf { held != null } }

        override suspend fun refreshOwnProfile(): Outcome<Unit> {
            refreshes++
            delay(latency)
            if (answer is Outcome.Success) {
                fetched.value = Fixtures.reference.now
                held.value = Fixtures.reference.user
            }
            return answer
        }
    }

    private suspend fun Opened.ownStatistics() = statisticsOf(graph.list.observeEntries().first())

    private fun ProfileViewModel.shownContent() = state.value.content as ProfileContent.Shown

    @Test
    fun `the own profile shows the calculator's values over the stored list, and a +1 that completes a title changes them`() =
        runTest(dispatcher) {
            val app = opened()
            val me = app.profile(ProfileOwner.Me)
            showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ProfileContent.Shown(fixtures.user, app.ownStatistics()), me.state.value.content)
            val syncs = app.graph.syncs
            val last =
                app.graph.list
                    .observeEntries()
                    .first()
                    .first { it.status == ListStatus.Watching && it.anime.malEpisodes == it.values.watchedEpisodes + 1 }
            val completed =
                me
                    .shownContent()
                    .statistics.statuses.completed
            app.graph.list.write(ListWrite.MarkNextWatched(last.anime))
            runCurrent()
            assertEquals(
                completed + 1,
                me
                    .shownContent()
                    .statistics.statuses.completed,
            )
            assertEquals(app.ownStatistics(), me.shownContent().statistics)
            assertEquals(syncs, app.graph.syncs)
            assertTrue(app.requests.isEmpty())
        }

    @Test
    fun `offline the own profile shows from the stored list, and the refresh that failed is a message`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.offline.value = true
            val me = app.profile(ProfileOwner.Me)
            showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(app.ownStatistics(), me.shownContent().statistics)
            app.graph.demo.server.latency = 10.milliseconds
            me.onEvent(ProfileEvent.Refresh)
            runCurrent()
            assertTrue(me.state.value.refreshing)
            advanceUntilIdle()
            assertFalse(me.state.value.refreshing)
            assertEquals(
                ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)),
                app.messages.current.value
                    ?.message,
            )
            assertEquals(app.ownStatistics(), me.shownContent().statistics)
        }

    @Test
    fun `signed out the own profile says so and asks nothing`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.session.signOut()
            val me = app.profile(ProfileOwner.Me)
            showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            val syncs = app.graph.syncs
            me.onEvent(ProfileEvent.Refresh)
            advanceUntilIdle()
            assertEquals(ProfileContent.SignedOut, me.state.value.content)
            assertEquals(syncs, app.graph.syncs)
            assertNull(app.messages.current.value)
        }

    @Test
    fun `another user's profile shows how far the read is, as one value, and then the statistics of the whole list`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.latency = 10.milliseconds
            val other = app.profile(ProfileOwner.Other(otherName))
            val seen = mutableListOf<ProfileState>()
            backgroundScope.launch { other.state.collect { seen += it } }
            showing(other.shown)
            runCurrent()
            advanceUntilIdle()
            runCurrent()
            val read = seen.filter { it.content == ProfileContent.ListLoading }.map { it.reading!!.loaded }
            assertEquals((0 until wholeRead).map { it * DemoUserLists.PART_SIZE }, read.distinct())
            assertTrue(seen.none { it.content is ProfileContent.Shown && it.reading == null && it !== seen.last() })
            assertEquals(ProfileContent.Shown(null, statisticsOf(fixtures.otherUserList)), seen.last().content)
            assertEquals(wholeRead, app.requests.size)
        }

    @Test
    fun `a pull reads the list again, shows its progress over the list held, and keeps the list when it fails`() =
        runTest(dispatcher) {
            val app = opened()
            val other = app.profile(ProfileOwner.Other(otherName))
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            val whole = other.shownContent()
            app.graph.demo.server.latency = 10.milliseconds
            other.onEvent(ProfileEvent.Refresh)
            advanceTimeBy(25.milliseconds)
            runCurrent()
            assertEquals(whole, other.state.value.content)
            assertTrue(other.state.value.refreshing)
            assertEquals(UserListProgress(2 * DemoUserLists.PART_SIZE, null), other.state.value.reading)
            app.graph.demo.server.failingLoads.value = true
            advanceUntilIdle()
            assertEquals(whole, other.state.value.content)
            assertFalse(other.state.value.refreshing)
            assertNull(other.state.value.reading)
            assertEquals(
                ShellMessage(ShellMessage.Text.RefreshFailed(DataError.ServerError)),
                app.messages.current.value
                    ?.message,
            )
        }

    @Test
    fun `a read that fails with nothing held is the error with Retry, which reads it again`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.failingLoads.value = true
            val other = app.profile(ProfileOwner.Other(otherName))
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ProfileContent.Failed(DataError.ServerError), other.state.value.content)
            app.graph.demo.server.failingLoads.value = false
            other.onEvent(ProfileEvent.Retry)
            advanceUntilIdle()
            assertEquals(statisticsOf(fixtures.otherUserList), other.shownContent().statistics)
            assertNull(app.messages.current.value)
        }

    @Test
    fun `a read that failed offline is repeated once when the connection returns`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.offline.value = true
            val other = app.profile(ProfileOwner.Other(otherName))
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ProfileContent.Failed(DataError.NoConnection), other.state.value.content)
            app.graph.demo.server.offline.value = false
            advanceUntilIdle()
            assertEquals(statisticsOf(fixtures.otherUserList), other.shownContent().statistics)
            assertEquals(1 + wholeRead, app.requests.size)
        }

    @Test
    fun `the private user and an unknown name each show their own state, and neither is asked again by itself`() =
        runTest(dispatcher) {
            val app = opened()
            val private = app.profile(ProfileOwner.Other(privateName))
            val unknown = app.profile(ProfileOwner.Other(FixtureUser.unknownName))
            val screens = showing(private.state, private.shown, unknown.state, unknown.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ProfileContent.Failed(DataError.PrivateList), private.state.value.content)
            assertEquals(ProfileContent.Failed(DataError.UnknownUser), unknown.state.value.content)
            assertEquals(2, app.requests.size)
            app.graph.demo.server.offline.value = true
            advanceUntilIdle()
            app.graph.demo.server.offline.value = false
            screens.cancel()
            advanceTimeBy(STOPPED_AFTER)
            showing(private.state, private.shown, unknown.state, unknown.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(2, app.requests.size)
            assertEquals(ProfileContent.Failed(DataError.PrivateList), private.state.value.content)
            assertNull(app.messages.current.value)
        }

    @Test
    fun `the Profile and a second screen of one user cause one read`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.latency = 10.milliseconds
            val first = app.profile(ProfileOwner.Other(otherName))
            val second = app.profile(ProfileOwner.Other(otherName.uppercase()))
            showing(first.state, first.shown, second.state, second.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(first.state.value.content, second.state.value.content)
            assertEquals(wholeRead, app.requests.size)
        }

    @Test
    fun `nothing is read before the screen counts as shown, and a rotation does not read again`() =
        runTest(dispatcher) {
            val app = opened()
            val other = app.profile(ProfileOwner.Other(otherName))
            val drawn = showing(other.state)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ProfileContent.Loading, other.state.value.content)
            assertTrue(app.requests.isEmpty())
            val shown = showing(other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(wholeRead, app.requests.size)
            drawn.cancel()
            shown.cancel()
            advanceTimeBy(ROTATION)
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(wholeRead, app.requests.size)
        }

    @Test
    fun `after a new process the typed name is there`() =
        runTest(dispatcher) {
            val app = opened()
            val saved = SavedStateHandle()
            val before = app.profile(ProfileOwner.Me, saved)
            before.onEvent(ProfileEvent.StartOpenProfile())
            before.onEvent(ProfileEvent.TypeName("sample_vie"))
            before.viewModelScope.cancel()
            val after = app.profile(ProfileOwner.Me, SavedStateHandle(saved.keys().associateWith { saved[it] }))
            assertEquals("sample_vie", after.openProfile?.name)
            after.onEvent(ProfileEvent.DismissOpenProfile)
            assertNull(after.openProfile)
        }

    @Test
    fun `signed out, another user's profile opens`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.session.signOut()
            val other = app.profile(ProfileOwner.Other(otherName))
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(statisticsOf(fixtures.otherUserList), other.shownContent().statistics)
        }

    @Test
    fun `the own name opened as another user's is the own profile, in any letter case, and asks for no list`() =
        runTest(dispatcher) {
            val app = opened()
            val me = app.profile(ProfileOwner.Me)
            val other = app.profile(ProfileOwner.Other(ownName.uppercase()))
            showing(me.state, me.shown, other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertTrue(me.isOwnName(ownName.uppercase()))
            assertFalse(me.isOwnName(otherName))
            assertTrue(other.own.value)
            assertEquals(ProfileContent.Shown(fixtures.user, app.ownStatistics()), other.state.value.content)
            assertTrue(app.requests.isEmpty())
            assertTrue(app.opened.names.isEmpty())
        }

    @Test
    fun `the names opened earlier hold only users, each once as the source tells users`() =
        runTest(dispatcher) {
            val app = opened()
            for (name in listOf(FixtureUser.unknownName, privateName, otherName, otherName.uppercase())) {
                val other = app.profile(ProfileOwner.Other(name))
                val screen = showing(other.state, other.shown)
                runCurrent()
                advanceUntilIdle()
                screen.cancel()
            }
            assertEquals(listOf(otherName.uppercase(), privateName), app.opened.names)
            val me = app.profile(ProfileOwner.Me)
            me.onEvent(ProfileEvent.StartOpenProfile())
            assertEquals(OpenProfile("", listOf(otherName.uppercase(), privateName)), me.openProfile)
        }

    // A rotation is shorter than the stop timeout, so the read goes on and its progress does not start again.
    @Test
    fun `a rotation during a read neither restarts it nor takes its progress back`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.latency = 10.milliseconds
            val other = app.profile(ProfileOwner.Other(otherName))
            val screen = showing(other.state, other.shown)
            runCurrent()
            advanceTimeBy(25.milliseconds)
            runCurrent()
            val before =
                other.state.value.reading!!
                    .loaded
            assertTrue(before > 0)
            screen.cancel()
            advanceTimeBy(ROTATION)
            showing(other.state, other.shown)
            runCurrent()
            assertTrue(
                other.state.value.content is ProfileContent.Shown ||
                    other.state.value.reading!!
                        .loaded >= before,
            )
            advanceUntilIdle()
            assertEquals(wholeRead, app.requests.size)
            assertEquals(statisticsOf(fixtures.otherUserList), other.shownContent().statistics)
        }

    @Test
    fun `a profile left for good during a read asks for no further part`() =
        runTest(dispatcher) {
            val app = opened()
            app.graph.demo.server.latency = 10.milliseconds
            val other = app.profile(ProfileOwner.Other(otherName))
            showing(other.state, other.shown)
            runCurrent()
            advanceTimeBy(25.milliseconds)
            runCurrent()
            val asked = app.requests.size
            assertTrue(asked in 1 until wholeRead)
            other.viewModelScope.cancel()
            advanceUntilIdle()
            assertEquals(asked, app.requests.size)
        }

    @Test
    fun `with no own profile stored, the own name asks for the own profile and reads no list and records no name`() =
        runTest(dispatcher) {
            val app = opened()
            val user = OwnStore(app.graph.user, stored = null, latency = 10.milliseconds)
            val other = app.profile(ProfileOwner.Other(ownName.uppercase()), user = user)
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(1, user.refreshes)
            assertTrue(other.own.value)
            assertEquals(ProfileContent.Shown(fixtures.user, app.ownStatistics()), other.state.value.content)
            assertTrue(app.requests.isEmpty())
            assertTrue(app.opened.names.isEmpty())
        }

    @Test
    fun `with no own profile stored, another name waits for the own profile's one ask and then reads the list`() =
        runTest(dispatcher) {
            val app = opened()
            val user = OwnStore(app.graph.user, stored = null, answer = Outcome.Failure(DataError.NoConnection), latency = 10.milliseconds)
            val other = app.profile(ProfileOwner.Other(otherName), user = user)
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(1, user.refreshes)
            assertFalse(other.own.value)
            assertEquals(statisticsOf(fixtures.otherUserList), other.shownContent().statistics)
            assertEquals(wholeRead, app.requests.size)
        }

    // A pull that ends in the user's own answer shows it as the state, never also as a message.
    @Test
    fun `a pull that finds the list private or the name gone is the state, and no message`() =
        runTest(dispatcher) {
            val app = opened()
            val part = UserListPart(fixtures.otherUserList.take(3))
            for (answer in listOf(DataError.PrivateList, DataError.UnknownUser)) {
                val source = ScriptedUserLists(latency = 10.milliseconds).apply { has(otherName, listOf(part)) }
                val user = UserListKeeper(source, foregroundScope(), unexpected = { throw it }).beside(app.graph.user)
                val other = app.profile(ProfileOwner.Other(otherName), user = user)
                val screen = showing(other.state, other.shown)
                runCurrent()
                advanceUntilIdle()
                assertTrue(other.state.value.content is ProfileContent.Shown)
                source.has(otherName, emptyList(), answer)
                other.onEvent(ProfileEvent.Refresh)
                advanceUntilIdle()
                assertEquals(ProfileContent.Failed(answer), other.state.value.content)
                assertNull(app.messages.current.value)
                screen.cancel()
                other.viewModelScope.cancel()
                advanceUntilIdle()
            }
        }

    @Test
    fun `the own Profile shown again with a fresh list and a stored profile asks for nothing`() =
        runTest(dispatcher) {
            val app = opened()
            val user = OwnStore(app.graph.user, stored = fixtures.user)
            val me = app.profile(ProfileOwner.Me, user = user)
            var screen = showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            val syncs = app.graph.syncs
            val refreshes = user.refreshes
            screen.cancel()
            advanceTimeBy(STOPPED_AFTER)
            runCurrent()
            screen = showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(syncs, app.graph.syncs)
            assertEquals(refreshes, user.refreshes)
        }

    // The header is read by its own rule: a day after it was read, and not with each download of a stale list.
    @Test
    fun `the own Profile shown asks for the profile alone once it was read a day ago, and not before`() =
        runTest(dispatcher) {
            val app = opened()
            val day = Freshness.ownProfile.toJavaDuration()
            val user = OwnStore(app.graph.user, stored = fixtures.user, fetchedAt = fixtures.now - day + 1.minutes.toJavaDuration())
            val me = app.profile(ProfileOwner.Me, user = user)
            var screen = showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            assertTrue(app.graph.syncs > 0)
            assertEquals(0, user.refreshes)
            val syncs = app.graph.syncs
            screen.cancel()
            user.fetched.value = fixtures.now - day
            advanceTimeBy(STOPPED_AFTER)
            runCurrent()
            screen = showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(syncs, app.graph.syncs)
            assertEquals(1, user.refreshes)
        }

    @Test
    fun `the own name is dropped at a sign-out, though the store forgets the profile later`() =
        runTest(dispatcher) {
            val app = opened()
            val user = OwnStore(app.graph.user, stored = fixtures.user)
            val me = app.profile(ProfileOwner.Me, user = user)
            showing(me.state, me.shown)
            runCurrent()
            advanceUntilIdle()
            assertTrue(me.isOwnName(ownName))
            app.graph.session.signOut()
            advanceUntilIdle()
            assertEquals(fixtures.user, user.held.value)
            assertFalse(me.isOwnName(ownName))
        }

    @Test
    fun `a pull that failed offline over a held list is repeated once when the connection returns`() =
        runTest(dispatcher) {
            val app = opened()
            val other = app.profile(ProfileOwner.Other(otherName))
            showing(other.state, other.shown)
            runCurrent()
            advanceUntilIdle()
            app.graph.demo.server.offline.value = true
            advanceUntilIdle()
            other.onEvent(ProfileEvent.Refresh)
            advanceUntilIdle()
            assertEquals(wholeRead + 1, app.requests.size)
            assertTrue(other.state.value.content is ProfileContent.Shown)
            app.graph.demo.server.offline.value = false
            advanceUntilIdle()
            assertEquals(2 * wholeRead + 1, app.requests.size)
        }

    private companion object {
        val STOPPED_AFTER = 5_001.milliseconds
        val ROTATION = 1_000.milliseconds
    }
}
