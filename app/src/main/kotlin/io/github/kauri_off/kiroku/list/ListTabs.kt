package io.github.kauri_off.kiroku.list

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.theme.Motion
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// The pager is the only thing that moves; the indicator, the selection and the row's scroll are drawn from it.
@Stable
class ListTabMotion(
    val pager: PagerState,
    private val scope: CoroutineScope,
) {
    // The tab a tap is taking the pager to; it is selected from the tap on.
    var tapTarget by mutableStateOf<Int?>(null)
        private set

    private var tap: Any? = null

    // Fractional: 1.3 is a third of the way from the second tab to the third.
    val position: Float
        get() = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (pager.pageCount - 1).toFloat())

    // Flips half-way through a drag, and at once to a tapped tab.
    val selected: Int by derivedStateOf { tapTarget ?: pager.currentPage }

    // The page the pager has rested on for a frame: a fling starts a moment after the drag that threw it ends.
    var resting by mutableIntStateOf(pager.currentPage)
        private set

    // Keeps [resting] and tells [onRest] of each page the pager rests on.
    suspend fun followRest(onRest: (Int) -> Unit) {
        val still = { pager.currentPage.takeUnless { pager.isScrollInProgress || tapTarget != null } }
        snapshotFlow(still).filterNotNull().collectLatest { index ->
            withFrameNanos {}
            if (still() == index) {
                resting = index
                onRest(index)
            }
        }
    }

    // A page further than one away is first put next to its tab, so the last page slides in as a component's entrance.
    fun select(index: Int) {
        if (tapTarget == index || (tapTarget == null && !pager.isScrollInProgress && position == index.toFloat())) return
        val token = Any()
        tap = token
        tapTarget = index
        scope.launch {
            try {
                val from = position
                if (abs(index - from) > 1f) pager.scrollToPage(if (index > from) index - 1 else index + 1)
                pager.animateScrollToPage(index, animationSpec = Motion.componentEnter())
            } finally {
                if (tap === token) {
                    tap = null
                    tapTarget = null
                }
            }
        }
    }
}

@Composable
fun rememberListTabMotion(pager: PagerState): ListTabMotion {
    val scope = rememberCoroutineScope()
    return remember(pager, scope) { ListTabMotion(pager, scope) }
}

@Composable
internal fun ListTabs(
    motion: ListTabMotion,
    onEvent: (ListEvent) -> Unit,
) {
    val scroll = rememberScrollState()
    val selected = motion.selected
    val geometry = remember { RowGeometry() }
    // Written in composition, whose changes reach layout in the same frame; a write in measure would show a frame late.
    val position = motion.position
    val max = scroll.maxValue
    val density = LocalDensity.current
    geometry.positions?.let { scroll.follow(rowScroll(it, position, max, with(density) { Sizes.tabRowEdge.roundToPx() }, density)) }
    // The stock row scrolls itself to its index; it changes only at rest, where that scroll is already the row's.
    val resting = motion.resting
    PrimaryScrollableTabRow(
        selectedTabIndex = resting,
        scrollState = scroll,
        edgePadding = Sizes.tabRowEdge,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier
                    .tabIndicatorLayout { measurable, constraints, positions ->
                        if (positions.isEmpty()) return@tabIndicatorLayout layout(0, 0) {}
                        val position = motion.position
                        if (geometry.positions != positions) {
                            geometry.positions = positions
                            scroll.follow(rowScroll(positions, position, scroll.maxValue, Sizes.tabRowEdge.roundToPx(), this))
                        }
                        val at = between(positions, position)
                        val width = at.contentWidth.roundToPx()
                        val placeable = measurable.measure(Constraints(width, width, 0, constraints.maxHeight))
                        // The row places this box half its selected tab's width in.
                        val box = positions[resting].width.roundToPx() / 2
                        layout(0, placeable.height) { placeable.place(at.contentLeft.roundToPx() - box, 0) }
                    }.testTag(INDICATOR),
                width = Dp.Unspecified,
            )
        },
    ) {
        ListStatus.entries.forEachIndexed { index, status ->
            Tab(
                selected = index == selected,
                onClick = {
                    motion.select(index)
                    onEvent(ListEvent.SelectTab(status))
                },
                text = { Text(stringResource(status.label())) },
            )
        }
    }
}

// The tabs' places from the row's last measure; not state, as only a new place in the pager asks for a new scroll.
private class RowGeometry {
    var positions: List<TabPosition>? = null
}

// Sets the row's scroll without reading it, so a scroll by hand stays until the pager moves.
private fun ScrollState.follow(target: Int) {
    val now = Snapshot.withoutReadObservation { value }
    if (target != now) dispatchRawDelta((target - now).toFloat())
}

private class Between(
    val contentLeft: Dp,
    val contentWidth: Dp,
)

// The indicator's place and width at a fractional [position], each the straight blend of the two tabs around it.
private fun between(
    positions: List<TabPosition>,
    position: Float,
): Between {
    val (from, to, f) = around(positions, position)
    val a = positions[from]
    val b = positions[to]
    return Between(lerp(a.contentLeft, b.contentLeft, f), lerp(a.contentWidth, b.contentWidth, f))
}

private fun around(
    positions: List<TabPosition>,
    position: Float,
): Triple<Int, Int, Float> {
    val p = position.coerceIn(0f, positions.lastIndex.toFloat())
    val from = floor(p).toInt()
    return Triple(from, minOf(from + 1, positions.lastIndex), p - from)
}

private val TabPosition.contentLeft: Dp get() = left + (width - contentWidth) / 2

// The blend of the scrolls that centre the two tabs around [position], each the one the stock row would choose.
private fun rowScroll(
    positions: List<TabPosition>,
    position: Float,
    max: Int,
    edge: Int,
    density: Density,
): Int {
    val (from, to, f) = around(positions, position)
    val a = centring(positions, from, max, edge, density)
    val b = centring(positions, to, max, edge, density)
    return (a + (b - a) * f).roundToInt()
}

private fun centring(
    positions: List<TabPosition>,
    index: Int,
    max: Int,
    edge: Int,
    density: Density,
): Int =
    with(density) {
        val visible = positions.last().right.roundToPx() + edge - max
        val tab = positions[index]
        (tab.left.roundToPx() - (visible / 2 - tab.width.roundToPx() / 2)).coerceIn(0, max.coerceAtLeast(0))
    }

val INDICATOR = notShown("list.tabIndicator")
