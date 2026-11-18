package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.user.RemoteUserLists
import io.github.kauri_off.kiroku.core.user.RemoteUserListsContract
import io.github.kauri_off.kiroku.core.user.UserListKeeper
import io.github.kauri_off.kiroku.core.user.UserListPart
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.data.db.dispatcher
import io.github.kauri_off.kiroku.data.list.MalUserLists
import io.github.kauri_off.kiroku.data.network.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// MAL's source of other users' lists over the app's client and the sandbox, on the test's scheduler.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SandboxUserListsTest : RemoteUserListsContract() {
    private val clock = TestClock(Instant.parse("2026-11-13T10:00:00Z"))
    private val list = SandboxList(sandboxTitles, clock, starting = { emptyMap() })
    private lateinit var mal: SandboxMal

    private val publicList get() = (list.usersList(SandboxUsers.PUBLIC) as UsersList.Public).entries

    // The requests for a list that is not the signed-in user's.
    private fun asked(): Int = mal.asked.count { it.endsWith("/animelist") && !it.endsWith("/@me/animelist") }

    override fun subject(scope: TestScope): Subject {
        mal = SandboxMal(list, SandboxTokens(clock))
        val client = SandboxClient(mal, io = scope.dispatcher, inline = true, clock = clock)
        return Subject(
            source = MalUserLists(client.http),
            public = SandboxUsers.PUBLIC,
            titles = publicList.map { AnimeId(it.first) },
            private = SandboxUsers.PRIVATE,
            nobody = "kiroku-no-user-0",
            asked = ::asked,
            failing = {
                mal.offline = it
                DataError.NoConnection
            },
            alias = SandboxUsers.PUBLIC.uppercase(),
        )
    }

    @Test
    fun `the list of several thousand is given whole, a page a part, each part before the next page is asked`() =
        runTest {
            val source = subject(this).source
            val parts = mutableListOf<List<ListEntry>>()
            val askedWhenHanded = mutableListOf<Int>()

            val end =
                source.read(SandboxUsers.PUBLIC) {
                    parts += it.entries
                    askedWhenHanded += asked()
                }

            assertEquals(Outcome.Success(Unit), end)
            assertTrue(publicList.size > 3000)
            assertEquals(publicList.chunked(1000).map { it.size }, parts.map { it.size })
            assertEquals((1..parts.size).toList(), askedWhenHanded)
            val entries = parts.flatten()
            assertEquals(publicList.map { it.first }, entries.map { it.anime.id.value })
            assertEquals(publicList.map { it.second.watched }, entries.map { it.values.watchedEpisodes })
        }

    @Test
    fun `through the keeper the entries read so far follow each page, and the list is whole at its end`() =
        runTest {
            val source = subject(this).source
            // A moment after each part, as a network's would be, in which the screen draws.
            val late =
                object : RemoteUserLists by source {
                    override suspend fun read(
                        name: String,
                        part: suspend (UserListPart) -> Unit,
                    ) = source.read(name) {
                        part(it)
                        delay(1.milliseconds)
                    }
                }
            val keeper = UserListKeeper(late, backgroundScope, { throw it })
            val seen = mutableListOf<UserListState>()
            backgroundScope.launch { keeper.observeList(SandboxUsers.PUBLIC).collect { seen += it } }
            advanceTimeBy(1.minutes)
            runCurrent()

            val sizes = publicList.chunked(1000).runningFold(0) { size, page -> size + page.size }
            assertEquals(sizes, seen.filterIsInstance<UserListState.Reading>().map { it.entries.size })
            val whole = seen.last() as UserListState.Whole
            assertEquals(publicList.map { it.first }, whole.entries.map { it.anime.id.value })
            assertEquals(publicList.size / 1000 + 1, asked())
        }
}
