package io.github.kauri_off.kiroku.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleRemoval
import io.github.kauri_off.kiroku.core.repository.RecentTitleRemovalResult
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.DemoRecentTitles
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecentTitleRoutesTest {
    private val main = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val compose = composeRule()

    @get:Rule
    val rule: RuleChain = RuleChain.outerRule(MainDispatcherRule(main)).around(registered(compose) { scope.cancel() })

    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val messages = ShellMessages(scope, graph.list)
    private val history = DemoRecentTitles(graph.session)
    private val owner get() = runBlocking { history.observeScope().first() }
    private val title = RecentTitle(RecentTitleId.Anime(1), Titles("Delayed title"), true)
    private val onTop = mutableStateOf(true)
    private val details = mutableStateOf<RecentTitle?>(null)

    @Test
    fun `Retry suppresses late details for an opening waiting to reserve at the first swipe`() = lateDetails(knownCutoff = true)

    @Test
    fun `Retry suppresses late details for an opening when the initial cutoff was unreadable`() = lateDetails(knownCutoff = false)

    @Test
    fun `Retry suppresses a previously issued order when the initial cutoff was unreadable`() =
        lateDetails(knownCutoff = false, reserveBeforeSwipe = true)

    private fun lateDetails(
        knownCutoff: Boolean,
        reserveBeforeSwipe: Boolean = false,
    ) {
        val active = owner
        val original = seed()
        val release = CompletableDeferred<Unit>()
        var next: Long? = null
        var attempts = 0
        val source =
            object : RecentTitles by history {
                override suspend fun reserveVisit(scope: RecentTitleScope): Long? {
                    release.await()
                    return history.reserveVisit(scope).also { next = it }
                }

                override suspend fun remove(removal: RecentTitleRemoval): RecentTitleRemovalResult =
                    if (++attempts == 1) RecentTitleRemovalResult.Failed(original.takeIf { knownCutoff }) else history.remove(removal)
            }
        show(source)
        if (reserveBeforeSwipe) {
            release.complete(Unit)
            settle()
        }
        compose.runOnIdle { messages.viewedTitles.remove(source, RecentTitleRemoval(active, title.key, original)) {} }
        settle()
        release.complete(Unit)
        settle()
        assertEquals(original + 1, next)
        compose.runOnIdle { messages.ended(checkNotNull(messages.current.value), actionPerformed = true) }
        settle()
        assertEquals(2, attempts)
        assertTrue(runBlocking { history.observe(active).first().isEmpty() })

        details.value = title
        settle()
        assertTrue(runBlocking { history.observe(active).first().isEmpty() })
    }

    @Test
    fun `a transient reservation failure recovers when the same opening is shown again`() {
        var attempts = 0
        val source =
            object : RecentTitles by history {
                override suspend fun reserveVisit(scope: RecentTitleScope): Long? =
                    if (++attempts == 1) null else history.reserveVisit(scope)
            }
        details.value = title
        show(source)
        assertEquals(1, attempts)
        onTop.value = false
        settle()
        onTop.value = true
        settle()
        assertEquals(2, attempts)
        assertEquals(listOf(title), runBlocking { history.observe(owner).first().map { it.title } })
    }

    @Test
    fun `restoring an admitted opening with no known order does not allocate a fresh order`() {
        val active = owner
        var attempts = 0
        val source =
            object : RecentTitles by history {
                override suspend fun reserveVisit(scope: RecentTitleScope): Long? {
                    attempts++
                    return history.reserveVisit(scope)
                }
            }
        val restored = RecentTitleVisitViewModel(SavedStateHandle(mapOf("visitOwner" to active.key)))
        restored.start(source, messages.viewedTitles, active, title.key)
        main.scheduler.advanceUntilIdle()
        assertNull(restored.visit.value)
        assertEquals(0, attempts)
    }

    @Test
    fun `a known failed reservation can restore only for its original owner`() {
        val active = owner
        var attempts = 0
        val source =
            object : RecentTitles by history {
                override suspend fun reserveVisit(scope: RecentTitleScope): Long? {
                    attempts++
                    return history.reserveVisit(scope)
                }
            }
        val saved = SavedStateHandle(mapOf("visitOwner" to active.key, "retryVisitReservation" to true))
        val restored = RecentTitleVisitViewModel(saved)
        restored.start(source, messages.viewedTitles, active.copy(key = "other-owner"), title.key)
        main.scheduler.advanceUntilIdle()
        assertEquals(0, attempts)
        restored.start(source, messages.viewedTitles, active, title.key)
        main.scheduler.advanceUntilIdle()
        assertEquals(1, attempts)
        assertEquals(1L, restored.visit.value)
    }

    @Test
    fun `two failed swipes leave only one Retry for the scoped visit`() {
        val active = owner
        val target = RecentTitleRemoval(active, title.key, seed())
        val source =
            object : RecentTitles by history {
                override suspend fun remove(removal: RecentTitleRemoval) = RecentTitleRemovalResult.Failed()
            }
        messages.viewedTitles.remove(source, target) {}
        main.scheduler.advanceUntilIdle()
        val first = checkNotNull(messages.current.value)
        messages.viewedTitles.remove(source, target) {}
        main.scheduler.advanceUntilIdle()
        assertEquals(first, messages.current.value)
        messages.ended(first, actionPerformed = true)
        main.scheduler.advanceUntilIdle()
        val retry = checkNotNull(messages.current.value)
        messages.ended(retry, actionPerformed = false)
        assertNull(messages.current.value)
    }

    private fun seed(): Long =
        runBlocking {
            val active = history.observeScope().first()
            checkNotNull(history.reserveVisit(active)).also { history.record(active, title, it) }
        }

    private fun show(source: RecentTitles) {
        val app =
            object : AppGraph by graph {
                override val recentTitles = source
            }
        compose.setContent {
            CompositionLocalProvider(LocalOnTop provides onTop.value) {
                RecordTitleVisit(app, messages.viewedTitles, title.key, Fixtures.reference.session, details.value)
            }
        }
        settle()
    }

    private fun settle() {
        repeat(4) {
            main.scheduler.advanceUntilIdle()
            compose.waitForIdle()
        }
    }
}
