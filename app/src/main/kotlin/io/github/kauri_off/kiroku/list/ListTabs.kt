package io.github.kauri_off.kiroku.list

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

// The tab row's place between two tabs, drawn from the pager: the finger's drag and the settle move the indicator,
// the selection and the row's scroll with the page. A tap runs the page and the header on one animation.
@Stable
class ListTabMotion(
    val pager: PagerState,
    private val scope: CoroutineScope,
) {
    // The tab a tap is sliding to; while it runs, the header follows its own animation, not the pager's pre-jump.
    var tapTarget by mutableStateOf<Int?>(null)
        private set

    private val tapped = Animatable(0f)
    private var tap: Any? = null

    private val last: Float get() = (pager.pageCount - 1).toFloat()

    // Fractional: 1.3 is a third of the way from the second tab to the third. Read it only in layout, draw or effects.
    val position: Float
        get() =
            (if (tapTarget != null) tapped.value else pager.currentPage + pager.currentPageOffsetFraction)
                .coerceIn(0f, last)

    // Flips half-way through a drag, and at once to a tapped tab.
    val selected: Int by derivedStateOf { tapTarget ?: pager.currentPage }

    fun select(index: Int) {
        if (tapTarget == index || (tapTarget == null && !pager.isScrollInProgress && position == index.toFloat())) return
        val from = position
        val token = Any()
        tap = token
        tapTarget = index
        scope.launch {
            val header =
                launch {
                    tapped.snapTo(from)
                    tapped.animateTo(index.toFloat(), Motion.componentEnter())
                }
            try {
                pager.animateScrollToPage(index, animationSpec = Motion.componentEnter())
                header.join()
            } finally {
                header.cancel()
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
    val statuses = ListStatus.entries
    val scroll = rememberScrollState()
    val geometry = remember { TabGeometry() }
    val density = LocalDensity.current
    LaunchedEffect(motion, scroll, density) {
        snapshotFlow { geometry.positions to motion.position }.collect { (positions, position) ->
            if (positions.isNotEmpty()) scroll.scrollTo(centredScroll(positions, position, scroll, density))
        }
    }
    val selected = motion.selected
    PrimaryScrollableTabRow(
        selectedTabIndex = selected,
        scrollState = scroll,
        edgePadding = Sizes.tabRowEdge,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier
                    .tabIndicatorLayout { measurable, constraints, positions ->
                        if (positions.isEmpty()) return@tabIndicatorLayout layout(0, 0) {}
                        geometry.positions = positions
                        val at = between(positions, motion.position)
                        val width = at.contentWidth.roundToPx()
                        val placeable = measurable.measure(Constraints(width, width, 0, constraints.maxHeight))
                        // The row places this box half its selected tab's width in; the indicator goes where the pager is.
                        val box = (positions[motion.selected].width.roundToPx() / 2)
                        layout(0, placeable.height) { placeable.place(at.contentLeft.roundToPx() - box, 0) }
                    }.testTag(INDICATOR),
                width = Dp.Unspecified,
            )
        },
    ) {
        statuses.forEachIndexed { index, status ->
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

private class TabGeometry {
    var positions by mutableStateOf(emptyList<TabPosition>())
}

private class Between(
    val contentLeft: Dp,
    val contentWidth: Dp,
    val centre: Dp,
)

// The indicator's place and width at a fractional [position], each the straight blend of the two tabs around it.
private fun between(
    positions: List<TabPosition>,
    position: Float,
): Between {
    val p = position.coerceIn(0f, positions.lastIndex.toFloat())
    val from = floor(p).toInt()
    val to = minOf(from + 1, positions.lastIndex)
    val f = p - from
    val a = positions[from]
    val b = positions[to]
    return Between(
        contentLeft = lerp(a.contentLeft, b.contentLeft, f),
        contentWidth = lerp(a.contentWidth, b.contentWidth, f),
        centre = lerp(a.left + a.width / 2, b.left + b.width / 2, f),
    )
}

private val TabPosition.contentLeft: Dp get() = left + (width - contentWidth) / 2

// The row's scroll that puts the point between two tabs in its middle, as far as the row can scroll.
private fun centredScroll(
    positions: List<TabPosition>,
    position: Float,
    scroll: ScrollState,
    density: Density,
): Int {
    val centre = with(density) { between(positions, position).centre.toPx() }
    return (centre - scroll.viewportSize / 2f).roundToInt().coerceIn(0, scroll.maxValue)
}

val INDICATOR = notShown("list.tabIndicator")
