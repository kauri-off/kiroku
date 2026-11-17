package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.UserLists
import io.github.kauri_off.kiroku.core.user.UserListState.None
import io.github.kauri_off.kiroku.core.user.UserListState.Reading
import io.github.kauri_off.kiroku.core.user.UserListState.Whole
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UserListKeeperTest {
    private val titles = Fixtures.reference.anime

    private fun entries(range: IntRange): List<ListEntry> =
        range.map { ListEntry(titles[it], ListValues(ListStatus.Completed, 1, null, null, null), FixtureTime.reference) }

    private fun parts(vararg ranges: IntRange): List<UserListPart> = ranges.map { UserListPart(entries(it)) }

    private val first = parts(0..2, 3..5, 6..7)
    private val second = parts(10..13, 14..15)
    private val reported = mutableListOf<Throwable>()

    private class Screen(
        val seen: List<UserListState>,
        val job: Job,
    ) {
        val last: UserListState get() = seen.last()
    }

    private fun TestScope.keeper(
        source: RemoteUserLists,
        held: Int = UserListKeeper.USERS_HELD,
    ) = UserListKeeper(source, backgroundScope, { reported += it }, held)

    private fun TestScope.screen(
        lists: UserLists,
        name: String,
    ): Screen {
        val seen = mutableListOf<UserListState>()
        val job = backgroundScope.launch { lists.observeList(name).collect { seen += it } }
        runCurrent()
        return Screen(seen, job)
    }

    private fun TestScope.refresh(
        lists: UserLists,
        name: String,
    ): Deferred<Outcome<Unit>> = backgroundScope.async { lists.refreshList(name) }.also { runCurrent() }

    // The time of one request.
    private fun TestScope.step(requests: Int = 1) {
        advanceTimeBy(requests.seconds)
        runCurrent()
    }

    private fun TestScope.settle() {
        advanceTimeBy(1.hours)
        runCurrent()
    }

    // Shows the user until the read has ended, and leaves.
    private fun TestScope.shown(
        lists: UserLists,
        name: String,
    ): UserListState {
        val screen = screen(lists, name)
        settle()
        screen.job.cancel()
        runCurrent()
        return screen.last
    }

    @Test
    fun `two screens of one user cause one read`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val profile = screen(keeper, "viewer")
            step()
            val list = screen(keeper, "VIEWER")
            settle()
            assertEquals(listOf("viewer", "viewer", "viewer"), source.asked)
            assertEquals(Whole(entries(0..7), null), profile.last)
            assertSame((profile.last as Whole).entries, (list.last as Whole).entries)
            assertEquals(Reading(entries(0..2), null, null), list.seen.first())
            assertEquals(profile.seen.drop(1), list.seen)
            screen(keeper, "Viewer")
            settle()
            assertEquals(3, source.asked.size)
        }

    @Test
    fun `progress follows each part, with the total where the source names one`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first.map { it.copy(total = 8) }) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            assertEquals(listOf<UserListState>(Reading(emptyList(), null, null)), screen.seen)
            step()
            assertEquals(Reading(entries(0..2), 8, null), screen.last)
            step()
            assertEquals(Reading(entries(0..5), 8, null), screen.last)
            step()
            assertEquals(Whole(entries(0..7), null), screen.last)
            assertEquals(4, screen.seen.size)

            source.has("another", first)
            assertEquals(null, (screen(keeper, "another").also { step() }.last as Reading).total)
        }

    @Test
    fun `a private list and an unknown name are each their own outcome and neither is asked again by itself`() =
        runTest {
            val source = ScriptedUserLists().apply { private("quiet") }
            val keeper = keeper(source)
            assertEquals(None(DataError.PrivateList), shown(keeper, "quiet"))
            assertEquals(None(DataError.UnknownUser), shown(keeper, "nobody"))
            assertEquals(listOf("quiet", "nobody"), source.asked)

            assertEquals(listOf<UserListState>(None(DataError.PrivateList)), screen(keeper, "quiet").seen)
            assertEquals(listOf<UserListState>(None(DataError.UnknownUser)), screen(keeper, "nobody").seen)
            screen(keeper, "Quiet")
            settle()
            assertEquals(2, source.asked.size)

            val asked = refresh(keeper, "quiet")
            settle()
            assertEquals(Outcome.Failure(DataError.PrivateList), asked.await())
            assertEquals(listOf("quiet", "nobody", "quiet"), source.asked)
        }

    @Test
    fun `a read that fails half-way leaves the earlier whole list and reports the failure`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            settle()
            val whole = screen.last
            source.has("viewer", second, DataError.ServerError)
            val asked = refresh(keeper, "viewer")
            assertEquals(Whole(entries(0..7), null, UserListProgress(0, null)), screen.last)
            step()
            assertEquals(Whole(entries(0..7), null, UserListProgress(4, null)), screen.last)
            settle()
            assertEquals(Outcome.Failure(DataError.ServerError), asked.await())
            assertEquals(whole, screen.last)
            assertSame((whole as Whole).entries, (screen.last as Whole).entries)
            assertEquals(6, source.asked.size)
        }

    @Test
    fun `a list read again replaces the earlier one only when it is whole`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            settle()
            source.has("viewer", second)
            val asked = refresh(keeper, "viewer")
            step()
            assertEquals(Whole(entries(0..7), null, UserListProgress(4, null)), screen.last)
            assertFalse(asked.isCompleted)
            step()
            assertEquals(Outcome.Success(Unit), asked.await())
            assertEquals(Whole(entries(10..15), null), screen.last)
        }

    @Test
    fun `a read that fails with no list held is the state, and is tried again when a screen comes`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            source.failure = DataError.NoConnection
            val screen = screen(keeper, "viewer")
            settle()
            assertEquals(listOf(Reading(emptyList(), null, null), None(DataError.NoConnection)), screen.seen)
            assertEquals(1, source.asked.size)
            screen.job.cancel()
            runCurrent()

            source.failure = null
            val again = screen(keeper, "viewer")
            assertEquals(listOf<UserListState>(Reading(emptyList(), null, null)), again.seen)
            settle()
            assertEquals(Whole(entries(0..7), null), again.last)
        }

    @Test
    fun `a failure that is not the user's answer is carried as it is and takes no list away`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            for (failure in listOf(DataError.NotFound, DataError.Refused)) {
                source.failure = failure
                assertEquals(None(failure), shown(keeper, "nobody"))
            }
            source.failure = null
            val screen = screen(keeper, "viewer")
            settle()
            source.failure = DataError.NotFound
            val asked = refresh(keeper, "viewer")
            settle()
            assertEquals(Outcome.Failure(DataError.NotFound), asked.await())
            assertEquals(Whole(entries(0..7), null), screen.last)
        }

    @Test
    fun `the user's own answer replaces a list that was held`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            settle()
            source.private("viewer")
            val asked = refresh(keeper, "viewer")
            settle()
            assertEquals(Outcome.Failure(DataError.PrivateList), asked.await())
            assertEquals(None(DataError.PrivateList), screen.last)
        }

    @Test
    fun `past the bound the user shown longest ago is forgotten`() =
        runTest {
            val source = ScriptedUserLists()
            val names = listOf("a", "b", "c", "d")
            assertEquals(names.size, UserListKeeper.USERS_HELD + 1)
            names.forEach { source.has(it, first) }
            val keeper = keeper(source)
            names.take(3).forEach { shown(keeper, it) }
            // Shown again, so "b" is now the one shown longest ago.
            shown(keeper, "a")
            assertEquals(9, source.asked.size)
            shown(keeper, "d")
            assertEquals(12, source.asked.size)
            for (name in listOf("a", "c", "d")) {
                assertEquals(name, listOf<UserListState>(Whole(entries(0..7), null)), screen(keeper, name).also { it.job.cancel() }.seen)
            }
            assertEquals(12, source.asked.size)
            assertEquals(listOf<UserListState>(Reading(emptyList(), null, null)), screen(keeper, "b").seen)
            settle()
            assertEquals(15, source.asked.size)
        }

    @Test
    fun `a user a screen waits for is not forgotten, whatever the bound`() =
        runTest {
            val source = ScriptedUserLists()
            listOf("a", "b").forEach { source.has(it, first) }
            val keeper = keeper(source, held = 1)
            val a = screen(keeper, "a")
            val b = screen(keeper, "b")
            settle()
            assertEquals(Whole(entries(0..7), null), a.last)
            assertEquals(Whole(entries(0..7), null), b.last)
            assertEquals(4, a.seen.size)
            a.job.cancel()
            runCurrent()
            assertEquals(listOf<UserListState>(Whole(entries(0..7), null)), screen(keeper, "b").seen)
            assertEquals(Reading(emptyList(), null, null), screen(keeper, "a").seen.first())
        }

    @Test
    fun `a sign-out forgets all`() =
        runTest {
            val source = ScriptedUserLists()
            listOf("a", "b", "c").forEach { source.has(it, first) }
            source.private("quiet")
            val keeper = keeper(source)
            shown(keeper, "a")
            shown(keeper, "quiet")
            val reading = screen(keeper, "b")
            step()
            reading.job.cancel()
            runCurrent()
            assertEquals(6, source.asked.size)

            keeper.forget()
            settle()
            assertEquals(6, source.asked.size)
            for (name in listOf("a", "quiet", "b")) {
                assertEquals(
                    name,
                    listOf<UserListState>(Reading(emptyList(), null, null)),
                    screen(keeper, name).also { it.job.cancel() }.seen,
                )
            }
        }

    @Test
    fun `a screen still waiting at a sign-out has the list read anew, and so has a call`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            settle()
            val asked = refresh(keeper, "viewer")
            step()
            keeper.forget()
            runCurrent()
            assertEquals(Reading(emptyList(), null, null), screen.last)
            assertFalse(asked.isCompleted)
            settle()
            assertEquals(Outcome.Success(Unit), asked.await())
            assertEquals(Whole(entries(0..7), null), screen.last)
            assertEquals(3 + 2 + 3, source.asked.size)
        }

    @Test
    fun `with no screen waiting no further part is asked, and the read goes on when one waits again`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", parts(0..2, 3..5, 6..7, 8..9)) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            step()
            assertEquals(2, source.asked.size)
            screen.job.cancel()
            settle()
            assertEquals(2, source.asked.size)

            val back = screen(keeper, "viewer")
            assertEquals(listOf<UserListState>(Reading(entries(0..5), null, null)), back.seen)
            assertEquals(3, source.asked.size)
            settle()
            assertEquals(Whole(entries(0..9), null), back.last)
            assertEquals(4, source.asked.size)
        }

    @Test
    fun `a call that asks for a read waits for it as a screen does, and joins the read under way`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val one = refresh(keeper, "viewer")
            step()
            val two = refresh(keeper, "Viewer")
            settle()
            assertEquals(listOf(Outcome.Success(Unit), Outcome.Success(Unit)), listOf(one.await(), two.await()))
            assertEquals(3, source.asked.size)
            assertEquals(listOf<UserListState>(Whole(entries(0..7), null)), screen(keeper, "viewer").seen)
        }

    @Test
    fun `a list of several thousand entries is given whole`() =
        runTest {
            val list = Fixtures.reference.otherUserList
            assertTrue(list.size > 4_000)
            val source = ScriptedUserLists().apply { has("viewer", list.chunked(1_000).map { UserListPart(it) }) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            settle()
            assertEquals(Whole(list, null), screen.last)
            val steps = screen.seen.filterIsInstance<Reading>().map { it.entries }
            assertEquals(listOf(0, 1_000, 2_000, 3_000, 4_000), steps.map { it.size })
            steps.forEach { assertEquals(list.take(it.size), it) }
        }

    @Test
    fun `a title the source gives twice is on the list once`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", parts(0..3, 2..5, 5..5)) }
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            step(2)
            assertEquals(Reading(entries(0..5), null, null), screen.last)
            settle()
            assertEquals(Whole(entries(0..5), null), screen.last)
        }

    @Test
    fun `what the source says of the user is given, and kept through a read that says nothing`() =
        runTest {
            val user = UserProfile("Viewer", null, LocalDate.of(2019, 3, 2))
            val source = ScriptedUserLists()
            source.has("viewer", listOf(first[0].copy(user = user)) + first.drop(1))
            val keeper = keeper(source)
            val screen = screen(keeper, "viewer")
            step()
            assertEquals(Reading(entries(0..2), null, user), screen.last)
            settle()
            assertEquals(Whole(entries(0..7), user), screen.last)

            source.has("viewer", second, DataError.PrivateList)
            refresh(keeper, "viewer")
            settle()
            assertEquals(None(DataError.PrivateList, user), screen.last)
            source.has("viewer", emptyList(), DataError.UnknownUser)
            refresh(keeper, "viewer")
            settle()
            assertEquals(None(DataError.UnknownUser), screen.last)
        }

    @Test
    fun `whether two names are one user is the source's answer`() =
        runTest {
            val source = ScriptedUserLists(same = { a, b -> a == b }).apply { has("viewer", first) }
            val keeper = keeper(source)
            assertTrue(keeper.sameUser("viewer", "viewer"))
            assertFalse(keeper.sameUser("viewer", "Viewer"))
            assertEquals(Whole(entries(0..7), null), shown(keeper, "viewer"))
            assertEquals(None(DataError.UnknownUser), shown(keeper, "Viewer"))
            assertEquals(listOf("viewer", "viewer", "viewer", "Viewer"), source.asked)
            assertTrue(keeper(ScriptedUserLists()).sameUser("viewer", "Viewer"))
        }

    @Test
    fun `a source that throws fails the read, and is reported unless the connection failed`() =
        runTest {
            val source = ScriptedUserLists().apply { has("viewer", first) }
            val keeper = keeper(source)
            val broken = IllegalStateException("The source failed")
            source.throwing = broken
            assertEquals(None(DataError.ServerError), shown(keeper, "viewer"))
            assertEquals(listOf<Throwable>(broken), reported)

            source.throwing = IOException()
            assertEquals(None(DataError.NoConnection), shown(keeper, "viewer"))
            assertEquals(1, reported.size)
            source.throwing = null
            assertEquals(Whole(entries(0..7), null), shown(keeper, "viewer"))
        }
}
