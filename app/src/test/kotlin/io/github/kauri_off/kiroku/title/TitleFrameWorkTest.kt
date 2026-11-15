package io.github.kauri_off.kiroku.title

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

// The title page and Related hear of the entries of the titles they show, and of no other: counted in compositions.
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class TitleFrameWorkTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Read anew for every build, so a page built again would show a new moment and be composed again.
    private val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone, step = Duration.ofMillis(1))
    private val graph = TestGraph(Demo(Fixtures.reference, scope, clock = clock))

    private val counts = mutableMapOf<String, Int>()
    private var counting = false

    private val tracer =
        object : CompositionTracer {
            override fun isTraceInProgress() = true

            override fun traceEventStart(
                key: Int,
                dirty1: Int,
                dirty2: Int,
                info: String,
            ) {
                if (counting) counts.merge(info.substringBefore(" ("), 1, Int::plus)
            }

            override fun traceEventEnd() = Unit
        }

    @After
    fun stop() {
        Composer.setTracer(null)
        scope.cancel()
    }

    private val page = FixtureAnime.exactToWatch
    private val related = FixtureDetails.full.related
    private val entries: Map<AnimeId, ListEntry> = Fixtures.reference.entries.associateBy { it.anime.id }

    // A title on the list that the page shows among its related titles, and one it does not show at all.
    private val shownEntry = related.first { it.anime.id in entries }.anime.id
    private val otherEntry = entries.keys.first { id -> id != page.id && related.none { it.anime.id == id } }

    private fun settle() =
        repeat(SETTLE_ROUNDS) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        }

    // The composables of the app's screens and components that a change to [id]'s entry composes until all is idle.
    private fun composedByAChangeTo(id: AnimeId): Map<String, Int> {
        counts.clear()
        counting = true
        scope.launch {
            val entry = checkNotNull(graph.list.observeEntry(id).first())
            val status = if (entry.status == ListStatus.OnHold) ListStatus.Dropped else ListStatus.OnHold
            graph.list.write(ListWrite.Save(entry.anime, entry.values.copy(status = status)))
        }
        settle()
        counting = false
        return counts.filterKeys { name -> SCREENS.any { name.startsWith(it) } }
    }

    private fun scrollTo(text: String) =
        compose
            .onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))

    @Test
    fun `a page of twelve related titles is not rebuilt by a change to another entry, nor is Related`() {
        assertEquals(TWELVE, related.size)
        Composer.setTracer(tracer)
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) } }
        settle()
        compose.onNodeWithText(page.titles.romaji).performClick()
        settle()

        assertEquals("the page, for another title's entry", emptyMap<String, Int>(), composedByAChangeTo(otherEntry))
        assertTrue("the page, for a related title's entry", composedByAChangeTo(shownEntry).isNotEmpty())

        scrollTo(SEE_ALL)
        compose.onNode(hasText(SEE_ALL) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        settle()
        assertEquals("Related, for another title's entry", emptyMap<String, Int>(), composedByAChangeTo(otherEntry))
        assertTrue("Related, for a related title's entry", composedByAChangeTo(shownEntry).isNotEmpty())
    }

    private companion object {
        const val TWELVE = 12
        const val SETTLE_ROUNDS = 5
        const val SETTLE_MILLIS = 1_000L
        const val SEE_ALL = "See all"
        val SCREENS =
            listOf(
                "io.github.kauri_off.kiroku.title.",
                "io.github.kauri_off.kiroku.titlesub.",
                "io.github.kauri_off.kiroku.design.component.",
            )
    }
}
