package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

// Screens that open and close, calls, sign-outs and failures in generated orders: what holds after every step.
@OptIn(ExperimentalCoroutinesApi::class)
class UserListKeeperInterleavingsTest {
    private val all = Fixtures.reference.otherUserList
    private val sizes = mapOf("a" to 0, "b" to 5, "c" to 23, "d" to 40, "e" to 61)
    private val lists: Map<String, List<ListEntry>> =
        sizes.entries.withIndex().associate { (index, user) -> user.key to all.subList(index * 100, index * 100 + user.value) }
    private val own = mapOf("p" to DataError.PrivateList, "x" to DataError.UnknownUser)
    private val names = lists.keys + own.keys

    private class Screen(
        val name: String,
        val seen: List<UserListState>,
        val job: Job,
    )

    private class Call(
        val name: String,
        val outcome: Deferred<Outcome<Unit>>,
    )

    private fun check(
        name: String,
        state: UserListState,
        where: String,
    ) {
        val list = lists[name.lowercase()]
        when (state) {
            is UserListState.Reading -> assertEquals(where, list.orEmpty().take(state.entries.size), state.entries)
            is UserListState.Whole -> assertEquals(where, list, state.entries)
            is UserListState.None -> assertTrue("$where $state", state.error in setOf(DataError.NoConnection, own[name.lowercase()]))
        }
    }

    @Test
    fun `generated orders of opening, closing, asking, signing out and failing keep what must hold`() {
        for (seed in 0 until RUNS) runTest { run(seed) }
    }

    private fun TestScope.run(seed: Int) {
        val random = Random(seed)
        val source = ScriptedUserLists()
        lists.forEach { (name, list) -> source.has(name, list.chunked(7).map { UserListPart(it) }.ifEmpty { listOf(UserListPart(list)) }) }
        source.private("p")
        val keeping = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        val keeper = UserListKeeper(source, keeping, { throw AssertionError(it) })
        val screens = mutableListOf<Screen>()
        val calls = mutableListOf<Call>()
        val log = mutableListOf<String>()
        repeat(STEPS) { step ->
            val name = names.random(random).let { if (random.nextBoolean()) it.uppercase() else it }
            when (random.nextInt(9)) {
                0, 1 -> {
                    val seen = mutableListOf<UserListState>()
                    screens += Screen(name, seen, backgroundScope.launch { keeper.observeList(name).collect { seen += it } })
                    log += "open $name"
                }
                2 ->
                    if (screens.isNotEmpty()) {
                        screens.removeAt(random.nextInt(screens.size)).also { it.job.cancel() }.let {
                            log +=
                                "close ${it.name}"
                        }
                    }
                3 -> {
                    calls += Call(name, backgroundScope.async { keeper.refreshList(name) })
                    log += "ask $name"
                }
                4 ->
                    if (calls.isNotEmpty()) {
                        calls.removeAt(random.nextInt(calls.size)).also { it.outcome.cancel() }.let {
                            log +=
                                "leave ${it.name}"
                        }
                    }
                5 -> {
                    keeper.forget()
                    log += "sign out"
                }
                6 -> {
                    source.failure = DataError.NoConnection.takeIf { source.failure == null }
                    log += "failing ${source.failure}"
                }
                else ->
                    random.nextLong(0, 2500).let {
                        advanceTimeBy(it.milliseconds)
                        log += "after $it ms"
                    }
            }
            runCurrent()
            val where = "seed $seed step $step ${log.takeLast(12)}"
            screens.forEach { screen -> screen.seen.forEach { check(screen.name, it, where) } }
            val waited =
                (screens.map { it.name } + calls.filter { it.outcome.isActive }.map { it.name }).mapTo(
                    mutableSetOf(),
                ) { it.lowercase() }
            assertTrue(where, keeper.holding <= maxOf(UserListKeeper.USERS_HELD, waited.size))
            // One read a user at most, and none for a user nobody waits for.
            assertTrue(where, source.reading <= waited.size)
            assertEquals(
                where,
                source.reading,
                keeping.coroutineContext.job.children
                    .count { it.isActive },
            )
        }
        source.failure = null
        advanceTimeBy(1.hours)
        runCurrent()
        val where = "seed $seed at the end ${log.takeLast(12)}"
        calls.forEach { assertTrue("$where ${it.name}", it.outcome.isCompleted) }
        screens.forEach { screen ->
            val last = screen.seen.last()
            assertTrue("$where ${screen.name} $last", last !is UserListState.Reading && (last as? UserListState.Whole)?.again == null)
        }
        screens.forEach { it.job.cancel() }
        runCurrent()
        val asked = source.asked.size
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(where, asked, source.asked.size)
        keeper.forget()
        runCurrent()
        assertEquals(
            where,
            listOf(0, 0, 0),
            listOf(
                keeper.holding,
                source.reading,
                keeping.coroutineContext.job.children
                    .count { it.isActive },
            ),
        )
    }

    private companion object {
        const val RUNS = 100
        const val STEPS = 60
    }
}
