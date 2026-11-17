package io.github.kauri_off.kiroku.wiring

import android.os.Looper
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.user.ScriptedUserLists
import io.github.kauri_off.kiroku.core.user.UserListPart
import io.github.kauri_off.kiroku.core.user.UserListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

// The profiles of a live source: built at their first use, and forgotten whole.
@RunWith(RobolectricTestRunner::class)
class LiveUserTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val names = ScriptedUserLists(latency = Duration.ZERO)
    private val part = listOf(UserListPart(Fixtures.reference.otherUserList.take(3)))

    private fun parts(scope: CoroutineScope) = LiveParts(context, LiveSource.Account, "0123abcd", Clock.systemUTC(), scope, unexpected::add)

    @Test
    fun `the own profile and its database are built once, at the first use, off the main thread, and telling names apart builds neither`() =
        runTest {
            val parts = parts(backgroundScope)
            val built = CopyOnWriteArrayList<Thread>()
            val user =
                liveUser(parts, liveSession(parts)) { p, session ->
                    built += Thread.currentThread()
                    ownProfileOf(p, session)
                }

            assertTrue(user.sameUser("Someone", "someone"))
            assertFalse(user.sameUser("Someone", "Someone_"))
            assertEquals(emptyList<Thread>(), built)
            assertFalse(context.getDatabasePath("account.db").exists())

            assertNull(user.observeOwnProfile().first())
            assertNull(user.observeOwnProfileFetchedAt().first())
            assertEquals(1, built.size)
            assertNotSame(Looper.getMainLooper().thread, built.single())
            assertTrue(context.getDatabasePath("account.db").isFile)
            assertEquals(emptyList<Throwable>(), unexpected)
        }

    // S9 and section 7.1: a list no screen waits for is forgotten too; a screen still waiting sees its list go, and read again.
    @Test
    fun `forgetting holds no list, also one no screen waits for, and a screen still waiting has its list read again`() =
        runTest {
            val parts = parts(backgroundScope)
            val user = LiveUser(names, backgroundScope, unexpected::add) { ownProfileOf(parts, liveSession(parts)) }
            names.has("left", part)
            names.has("shown", part)
            assertTrue(user.observeList("left").first { it !is UserListState.Reading } is UserListState.Whole)
            val shown = CopyOnWriteArrayList<UserListState>()
            val screen = launch { user.observeList("shown").collect { shown += it } }
            runCurrent()
            assertTrue(shown.last() is UserListState.Whole)

            user.forget()
            runCurrent()
            assertEquals(UserListState.Reading(emptyList(), null, null), user.observeList("left").first())
            val after = shown.drop(shown.indexOfFirst { it is UserListState.Whole } + 1)
            assertEquals(UserListState.Reading(emptyList(), null, null), after.first())
            assertEquals(UserListState.Whole(part.single().entries, null), after.last())
            assertEquals(2, names.asked.count { it == "shown" })
            screen.cancel()
            assertEquals(emptyList<Throwable>(), unexpected)
        }
}
