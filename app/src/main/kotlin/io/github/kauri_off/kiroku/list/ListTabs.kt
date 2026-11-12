package io.github.kauri_off.kiroku.list

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.design.theme.PageSettle
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.text.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// The pager alone moves the pages; the header is drawn from it, gliding on a far tap and easing back after a catch.
@Stable
class ListTabMotion(
    val pager: PagerState,
    private val scope: CoroutineScope,
    // A tap's slide.
    val pageSpec: AnimationSpec<Float>,
    // A release's settle, which the system's animation scale shortens to nothing when animations are off.
    val settleSpec: AnimationSpec<Float>,
    private val motionScheme: MotionScheme,
) {
    // The tab a tap is taking the pager to; it is selected from the tap on.
    var tapTarget by mutableStateOf<Int?>(null)
        private set

    private var tap: Any? = null

    // The last page reported as rested on, so the tab the screen hears back from it is not taken as a request.
    private var reported = pager.currentPage

    // The pages composed on each side of the one shown: its neighbours first, then one more per frame, up to all.
    var ahead by mutableIntStateOf(1)
        private set

    private val all: Int get() = pager.pageCount - 1

    // Fractional: 1.3 is a third of the way from the second tab to the third.
    val position: Float
        get() = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (pager.pageCount - 1).toFloat())

    // A tap's glide: from where the header was to the tapped tab, as the one sliding page goes from [slideFrom].
    private var glideFrom by mutableFloatStateOf(0f)
    private var slideFrom by mutableStateOf<Float?>(null)

    // After a drag catches a tap's glide, how far the header still is from the pager, in tabs; it eases to nothing.
    private var lag by mutableFloatStateOf(0f)
    private var lagReturn: Job? = null

    // Where the tab row is drawn: the pager's position, or during a tap the glide by the sliding page's progress.
    val header: Float
        get() {
            val target = tapTarget ?: return (position + lag).coerceIn(0f, all.toFloat())
            val start = slideFrom ?: return glideFrom
            val way = target - start
            val progress = if (way == 0f) 1f else ((position - start) / way).coerceIn(0f, 1f)
            return glideFrom + (target - glideFrom) * progress
        }

    // Flips half-way through a drag, and at once to a tapped tab.
    val selected: Int by derivedStateOf { tapTarget ?: pager.currentPage }

    // The page the pager rests on; null while it moves or a tap is taking it elsewhere.
    private val resting: Int?
        get() = pager.currentPage.takeUnless { pager.isScrollInProgress || tapTarget != null }

    // Tells [onRest] of each new page the pager rests on; it may rest a moment between a drag and its fling.
    suspend fun followRest(onRest: (Int) -> Unit) {
        snapshotFlow { resting }.filterNotNull().distinctUntilChanged().collect { index ->
            reported = index
            onRest(index)
        }
    }

    // One page per frame while the pager rests, so no frame composes them all; a touch composes the rest at once.
    suspend fun composeAhead() {
        while (ahead < all) {
            snapshotFlow { resting }.first { it != null }
            if (ahead < all) ahead++
            laidOut(ahead)
        }
    }

    // Composes every page in the next layout, before a gesture that comes early moves the pager.
    fun composeAll() {
        if (ahead < all) ahead = all
    }

    // A finger is on the pager; only its drag is held by [gate].
    var touching = false

    // Every page is laid out, so a scroll of the pager composes none, which can crash Compose (ROADMAP).
    private val staged: Boolean get() = pager.layoutInfo.beyondViewportPageCount >= all

    private suspend fun laidOut(count: Int) = snapshotFlow { pager.layoutInfo.beyondViewportPageCount }.first { it >= count }

    // Holds a drag's movement until every page is laid out, at most until the next layout, then gives it to the pager.
    val gate =
        object : NestedScrollConnection {
            private var held = 0f

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (touching && !staged) {
                    composeAll()
                    held += available.x
                    return Offset(available.x, 0f)
                }
                if (held == 0f) return Offset.Zero
                val given = held
                held = 0f
                return Offset(-given, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!staged) {
                    composeAll()
                    laidOut(all)
                }
                return Velocity.Zero
            }
        }

    // A tab chosen elsewhere than on this screen, as a restored one; a tap or a swipe is already there or on its way.
    fun show(index: Int) {
        if (index != reported && index != tapTarget && index != pager.targetPage) select(index)
    }

    // A page further than one away is first put next to its tab, so only the last page slides; the header glides.
    fun select(index: Int) {
        if (tapTarget == index || (tapTarget == null && !pager.isScrollInProgress && position == index.toFloat())) return
        val token = Any()
        val gliding = header != position
        glideFrom = header
        slideFrom = null
        lagReturn?.cancel()
        lag = 0f
        tap = token
        tapTarget = index
        scope.launch {
            var arrived = false
            try {
                if (ahead < all) {
                    ahead = all
                    laidOut(all)
                }
                if (tap !== token) return@launch
                val from = position
                val far = abs(index - from) > 1f
                if (far) pager.scrollToPage(if (index > from) index - 1 else index + 1)
                if (!far && !gliding) glideFrom = position
                slideFrom = position
                pager.animateScrollToPage(index, animationSpec = pageSpec)
                arrived = true
            } finally {
                if (tap === token) {
                    val shown = header
                    tap = null
                    tapTarget = null
                    slideFrom = null
                    if (!arrived && shown != position) ease(shown - position)
                }
            }
        }
    }

    // A drag that catches a glide takes the pager; the header comes back to it on the theme's slow effects spring.
    private fun ease(from: Float) {
        lag = from
        lagReturn =
            scope.launch {
                animate(lag, 0f, animationSpec = motionScheme.slowEffectsSpec()) { value, _ -> lag = value }
            }
    }
}

@Composable
fun rememberListTabMotion(pager: PagerState): ListTabMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val motionScheme = MaterialTheme.motionScheme
    return remember(pager, scope, density, motionScheme) {
        val durationScale = scope.coroutineContext[MotionDurationScale]
        ListTabMotion(
            pager,
            scope,
            PageSettle(density),
            PageSettle(density) { durationScale?.scaleFactor ?: 1f },
            motionScheme,
        )
    }
}

// Stock tabs, indicator and divider in the stock scrollable row's measures, in a layout only the pager scrolls.
@Composable
internal fun ListTabs(
    motion: ListTabMotion,
    onEvent: (ListEvent) -> Unit,
) {
    val row = remember(motion) { TabRowLayout(motion) }
    val selected = motion.selected
    LaunchedEffect(row) { row.forgetHand() }
    val overscroll = rememberOverscrollEffect()
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, false)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = TabRowDefaults.primaryContainerColor, contentColor = TabRowDefaults.primaryContentColor) {
        Box(contentAlignment = Alignment.BottomStart) {
            HorizontalDivider()
            Layout(
                contents =
                    listOf(
                        {
                            ListStatus.entries.forEachIndexed { index, status ->
                                Tab(
                                    selected = index == selected,
                                    onClick = {
                                        motion.select(index)
                                        onEvent(ListEvent.SelectTab(status))
                                    },
                                    text = {
                                        // Drawn in the blend of the two colours by the header, so the stock fade has nothing to animate.
                                        BasicText(
                                            stringResource(status.label()),
                                            style = LocalTextStyle.current,
                                            color = { lerp(inactive, active, (1f - abs(index - motion.header)).coerceIn(0f, 1f)) },
                                        )
                                    },
                                    modifier = Modifier.onFocusChanged { if (it.isFocused) row.reveal(index) },
                                )
                            }
                        },
                        { TabRowDefaults.PrimaryIndicator(row.indicator.testTag(INDICATOR), width = Dp.Unspecified) },
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .selectableGroup()
                        .overscroll(overscroll)
                        .scrollable(row.byHand, Orientation.Horizontal, overscroll, reverseDirection = reverse)
                        .semantics {
                            horizontalScrollAxisRange = ScrollAxisRange({ row.scroll(motion.header) }, { row.max.toFloat() })
                            scrollBy { x, _ -> row.byHand.dispatchRawDelta(x) != 0f }
                        },
                measurePolicy = row,
            )
        }
    }
}

// The tabs' places from their last measure: [lefts] has one more entry, the right end of the last tab.
private data class TabPlaces(
    val lefts: List<Int>,
    val labels: List<Int>,
    val viewport: Int,
) {
    val max = (lefts.last() + lefts.first() - viewport).coerceAtLeast(0)

    val width = lefts.last() + lefts.first()

    private val centre = viewport / 2

    fun width(index: Int) = lefts[index + 1] - lefts[index]

    fun labelLeft(index: Int) = lefts[index] + (width(index) - labels[index]) / 2

    // The scroll the stock row chooses for a selected tab: centred where the row can scroll that far.
    fun centring(index: Int) = (lefts[index] - (centre - width(index) / 2)).coerceIn(0, max)

    // At a fractional pager [position], the straight blend of the centring scrolls of the two tabs around it.
    fun scroll(position: Float) =
        if (labels.isEmpty()) 0f else around(position, labels.size) { from, to, f -> blend(centring(from), centring(to), f) }
}

// The tabs are measured only when they change; the pager's position is read only in placement and by the indicator.
internal class TabRowLayout(
    private val motion: ListTabMotion,
) : MultiContentMeasurePolicy {
    // A scroll by hand, on top of the pager's, made at [handAt]; it fades out as the pager moves a page from there.
    private var hand by mutableFloatStateOf(0f)
    private var handAt by mutableFloatStateOf(0f)

    private var places by mutableStateOf(TabPlaces(listOf(0), emptyList(), 0))

    val max: Int get() = places.max

    fun scroll(position: Float): Float {
        val fade = (1f - abs(position - handAt)).coerceAtLeast(0f)
        return (places.scroll(position) + hand * fade).coerceIn(0f, places.max.toFloat())
    }

    val byHand =
        ScrollableState { delta ->
            val position = motion.header
            val now = scroll(position)
            val next = (now + delta).coerceIn(0f, places.max.toFloat())
            hand = next - places.scroll(position)
            handAt = position
            next - now
        }

    // A tab given focus by a key is centred like a selected one.
    fun reveal(index: Int) {
        val position = motion.header
        hand = places.centring(index) - places.scroll(position)
        handAt = position
    }

    // Once the pager is a page or more from where the hand left the row, its scroll is the pager's alone again.
    suspend fun forgetHand() {
        snapshotFlow { hand != 0f && abs(motion.header - handAt) >= 1f }.collect { if (it) hand = 0f }
    }

    // A full-width child of fixed size: the stock indicator inside it is measured at the blend of the two labels' widths.
    val indicator =
        Modifier.layout { measurable, constraints ->
            val places = places
            val (left, width) =
                if (places.labels.isEmpty()) {
                    0f to 0f
                } else {
                    around(motion.header, places.labels.size) { from, to, f ->
                        blend(places.labelLeft(from), places.labelLeft(to), f) to blend(places.labels[from], places.labels[to], f)
                    }
                }
            val bar = measurable.measure(Constraints.fixedWidth(width.roundToInt()))
            layout(constraints.maxWidth, bar.height) { bar.placeRelative(left.roundToInt(), 0) }
        }

    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val (tabs, indicator) = measurables
        val height = tabs.maxOf { it.maxIntrinsicHeight(Constraints.Infinity) }
        val tab = Constraints(minWidth = TabRowDefaults.ScrollableTabRowMinTabWidth.roundToPx(), minHeight = height, maxHeight = height)
        val placeables = tabs.map { it.measure(tab) }
        val lefts = IntArray(tabs.size + 1)
        lefts[0] = Sizes.tabRowEdge.roundToPx()
        placeables.forEachIndexed { index, placeable -> lefts[index + 1] = lefts[index] + placeable.width }
        val padding = Sizes.tabLabelPadding.roundToPx() * 2
        val labels = List(tabs.size) { tabs[it].maxIntrinsicWidth(Constraints.Infinity) - padding }
        val measured = TabPlaces(lefts.toList(), labels, constraints.maxWidth)
        places = measured
        val bar = indicator.map { it.measure(Constraints(minWidth = measured.width, maxWidth = measured.width, maxHeight = height)) }
        return layout(constraints.maxWidth, height) {
            val scroll = scroll(motion.header).roundToInt()
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(measured.lefts[index] - scroll, 0) }
            bar.forEach { it.placeRelative(-scroll, height - it.height) }
        }
    }
}

private fun <T> around(
    position: Float,
    count: Int,
    at: (from: Int, to: Int, fraction: Float) -> T,
): T {
    val p = position.coerceIn(0f, (count - 1).toFloat())
    val from = floor(p).toInt()
    return at(from, minOf(from + 1, count - 1), p - from)
}

private fun blend(
    a: Int,
    b: Int,
    f: Float,
): Float = a + (b - a) * f

val INDICATOR = notShown("list.tabIndicator")
