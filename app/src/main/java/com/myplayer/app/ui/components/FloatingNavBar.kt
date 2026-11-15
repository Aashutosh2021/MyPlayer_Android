package com.myplayer.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myplayer.app.ui.navigation.Screen
import com.myplayer.app.ui.theme.ThemeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NavItem(
    val route: String,
    val label: String,
    val activeIcon: ImageVector,
    val inactiveIcon: ImageVector
)

/**
 * Primary dock tabs inside the left expanding/collapsing pill.
 * Search is rendered as the dedicated right-side circular SearchOrb (just like the reference video!).
 */
val dockTabItems = listOf(
    NavItem(Screen.Home.route,     "Home",     Icons.Filled.Home,         Icons.Outlined.Home),
    NavItem(Screen.Trending.route, "Hotlist",  Icons.Filled.Whatshot,     Icons.Outlined.Whatshot),
    NavItem(Screen.Library.route,  "Library",  Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    NavItem(Screen.Settings.route, "Settings", Icons.Filled.Settings,     Icons.Outlined.Settings)
)

val bottomNavItems = listOf(
    NavItem(Screen.Home.route,     "Home",     Icons.Filled.Home,         Icons.Outlined.Home),
    NavItem(Screen.Search.route,   "Search",   Icons.Filled.Search,       Icons.Outlined.Search),
    NavItem(Screen.Library.route,  "Library",  Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    NavItem(Screen.Trending.route, "Hotlist",  Icons.Filled.Whatshot,     Icons.Outlined.Whatshot),
    NavItem(Screen.Settings.route, "Settings", Icons.Filled.Settings,     Icons.Outlined.Settings)
)

val CHROME_TAB_HEIGHT = 64.dp
val CHROME_MINI_EXPANDED_HEIGHT = 116.dp // Full height for MyPlayer's protruding-art MiniPlayer
private val MINI_ROW_HEIGHT = 58.dp
private val CARD_GAP = 8.dp
private val SEARCH_GAP = 8.dp
private val DOCK_INSET = 5.dp
private val LENS_SHAPE = RoundedCornerShape(26.dp)

/**
 * Scroll connection that tracks vertical list scrolling across any screen:
 * - Scrolling down (>= thresholdPx) sets `collapsed = true`:
 *   DockPill contracts left into a single circular active-tab button, SearchOrb stays on the right,
 *   and MiniPlayer glides down into the center slot between them!
 * - Scrolling up (>= thresholdPx) or tapping the collapsed left circle sets `collapsed = false`:
 *   DockPill expands back to full 4-tab width and MiniPlayer lifts back above the navbar!
 */
class ChromeScrollConnection(private val thresholdPx: Float) : NestedScrollConnection {
    var collapsed by mutableStateOf(false)
        private set
    private var accumulated = 0f

    fun expand() {
        collapsed = false
        accumulated = 0f
    }

    fun collapse() {
        collapsed = true
        accumulated = 0f
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        val dy = consumed.y
        if (dy == 0f) return Offset.Zero
        if ((accumulated > 0f && dy < 0f) || (accumulated < 0f && dy > 0f)) {
            accumulated = 0f
        }
        accumulated += dy
        if (accumulated <= -thresholdPx && !collapsed) {
            collapsed = true
            accumulated = 0f
        } else if (accumulated >= thresholdPx && collapsed) {
            collapsed = false
            accumulated = 0f
        }
        return Offset.Zero
    }
}

@Composable
fun rememberChromeScroll(threshold: Dp = 42.dp): ChromeScrollConnection {
    val px = with(LocalDensity.current) { threshold.toPx() }
    return remember(px) { ChromeScrollConnection(px) }
}

private fun lerp(a: Dp, b: Dp, t: Float): Dp = a + (b - a) * t
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun Modifier.chromeHeight(progress: () -> Float, openHeight: Dp, foldedHeight: Dp): Modifier =
    this.layout { measurable, constraints ->
        val h = lerp(openHeight, foldedHeight, progress()).roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

private fun Modifier.placed(
    progress: () -> Float,
    x: (Float) -> Dp,
    bottom: (Float) -> Dp,
    w: (Float) -> Dp,
    h: (Float) -> Dp
): Modifier =
    this.layout { measurable, constraints ->
        val t = progress()
        val pw = w(t).roundToPx().coerceAtLeast(0)
        val ph = h(t).roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(pw, ph))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(x(t).roundToPx(), constraints.maxHeight - bottom(t).roundToPx() - ph)
        }
    }

/**
 * Combined Morphing Bottom Chrome (OpenTune MinimizeChrome Choreography + MyPlayer MiniPlayer):
 * - When `collapsed == false` (`progress = 0f`):
 *   - Full-width MyPlayer MiniPlayer floats on the upper row (`bottom = CHROME_TAB_HEIGHT + CARD_GAP`).
 *   - Full 4-tab `DockPill` + right-side `SearchOrb` sit on the bottom row.
 * - When `collapsed == true` (`progress = 1f`):
 *   - `DockPill` contracts smoothly to the left into a `58.dp` circle showing only the active tab icon.
 *   - `SearchOrb` shrinks to a `58.dp` circle on the right.
 *   - MyPlayer's `MiniPlayer` drops into the center gap between the Left Nav Circle and Right Search Circle!
 */
@Composable
fun MorphingBottomChrome(
    collapsed: Boolean,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onExpand: () -> Unit,
    hasMiniPlayer: Boolean,
    miniPlayerContent: @Composable (collapseProgress: Float, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    // Non-bouncy, ultra-smooth iOS/OpenTune minimize spring (dampingRatio = 0.9f, stiffness = 300f)
    val progressState = animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.9f,
            stiffness = 300f,
            visibilityThreshold = 0.001f
        ),
        label = "morphingChromeProgress"
    )
    val progressProvider = { progressState.value }

    val selectedTabIndex = remember(currentRoute) {
        dockTabItems.indexOfFirst { it.route == currentRoute }.takeIf { it >= 0 }
    }
    val searchSelected = currentRoute == Screen.Search.route

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val totalWidth = maxWidth
        val fullDockWidth = totalWidth - CHROME_TAB_HEIGHT - SEARCH_GAP
        val openTotalHeight = if (hasMiniPlayer) {
            CHROME_MINI_EXPANDED_HEIGHT + CARD_GAP + CHROME_TAB_HEIGHT
        } else {
            CHROME_TAB_HEIGHT
        }
        val foldedTotalHeight = MINI_ROW_HEIGHT

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .chromeHeight(progressProvider, openTotalHeight, foldedTotalHeight)
        ) {
            val rowHeight = { t: Float -> lerp(CHROME_TAB_HEIGHT, MINI_ROW_HEIGHT, t) }

            // 1. MyPlayer MiniPlayer (Morphs from full-width top row -> center docked slot between orbs!)
            if (hasMiniPlayer) {
                val currentT = progressState.value
                miniPlayerContent(
                    currentT,
                    Modifier.placed(
                        progress = progressProvider,
                        x = { t -> lerp(0.dp, MINI_ROW_HEIGHT + SEARCH_GAP, t) },
                        bottom = { t -> lerp(CHROME_TAB_HEIGHT + CARD_GAP, 0.dp, t) },
                        w = { t -> lerp(totalWidth, totalWidth - (MINI_ROW_HEIGHT + SEARCH_GAP) * 2, t) },
                        h = { t -> lerp(CHROME_MINI_EXPANDED_HEIGHT, MINI_ROW_HEIGHT, t) }
                    )
                )
            }

            // 2. Left Contracting / Expanding DockPill (4 Tabs -> 1 Active Tab Circle)
            DockPill(
                tabs = dockTabItems,
                selectedIndex = selectedTabIndex,
                fullWidth = fullDockWidth,
                folded = collapsed,
                foldProgress = progressProvider,
                anchor = MINI_ROW_HEIGHT / 2,
                onSelectTab = { idx -> onNavigate(dockTabItems[idx].route) },
                onExpand = onExpand,
                modifier = Modifier.placed(
                    progress = progressProvider,
                    x = { 0.dp },
                    bottom = { 0.dp },
                    w = { t -> lerp(fullDockWidth, MINI_ROW_HEIGHT, t) },
                    h = rowHeight
                )
            )

            // 3. Right Circular SearchOrb
            SearchOrb(
                selected = searchSelected,
                onClick = {
                    if (collapsed) {
                        onExpand()
                    }
                    onNavigate(Screen.Search.route)
                },
                modifier = Modifier.placed(
                    progress = progressProvider,
                    x = { t -> totalWidth - rowHeight(t) },
                    bottom = { 0.dp },
                    w = rowHeight,
                    h = rowHeight
                )
            )
        }
    }
}

@Composable
private fun DockPill(
    tabs: List<NavItem>,
    selectedIndex: Int?,
    fullWidth: Dp,
    folded: Boolean,
    foldProgress: () -> Float,
    anchor: Dp,
    onSelectTab: (Int) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = ThemeState.isDarkMode
    val pillShape = RoundedCornerShape(percent = 50)
    val slotWidth = (fullWidth - DOCK_INSET * 2) / tabs.size

    var restingIndex by remember { mutableIntStateOf(selectedIndex ?: 0) }
    if (selectedIndex != null) restingIndex = selectedIndex

    var dragTarget by remember { mutableStateOf<Int?>(null) }
    var suppressTap by remember { mutableStateOf(false) }
    var fingerDown by remember { mutableStateOf(false) }
    var dragCentre by remember { mutableStateOf<Dp?>(null) }
    val currentShown by rememberUpdatedState(selectedIndex)

    val glassGradient = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(Color(0xEE1F1F2E), Color(0xF512121C))
        } else {
            listOf(Color(0xF2F7F7FC), Color(0xF8EAEAF2))
        }
    )
    val borderBrush = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.06f))
        } else {
            listOf(Color.White.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.10f))
        }
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = pillShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.55f)
            )
            .clip(pillShape)
            .background(glassGradient)
            .border(BorderStroke(1.dp, borderBrush), pillShape)
            .pointerInput(tabs.size, folded, slotWidth) {
                if (!folded) {
                    val slotPx = slotWidth.toPx()
                    val insetPx = DOCK_INSET.toPx()
                    val strayPx = 48.dp.toPx()
                    fun slotAt(x: Float): Int =
                        ((x - insetPx) / slotPx).toInt().coerceIn(0, tabs.size - 1)
                    fun centreAt(x: Float): Dp {
                        val half = slotPx / 2f
                        return (x - insetPx).coerceIn(half, slotPx * tabs.size - half).toDp()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        suppressTap = false
                        try {
                            var lastX = down.position.x
                            val engaged = withTimeoutOrNull(160L) {
                                var alive = true
                                while (alive) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = e.changes.firstOrNull { it.id == down.id }
                                    if (ch == null || !ch.pressed) alive = false else lastX = ch.position.x
                                }
                                false
                            } ?: true
                            if (engaged) {
                                fingerDown = true
                                dragTarget = slotAt(lastX)
                                dragCentre = centreAt(lastX)
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        suppressTap = true
                                        val target = dragTarget
                                        val strayed = abs(change.position.y - down.position.y) > strayPx
                                        if (target != null && !strayed && target != currentShown) {
                                            onSelectTab(target)
                                        }
                                        break
                                    }
                                    change.consume()
                                    dragCentre = centreAt(change.position.x)
                                    val s = slotAt(change.position.x)
                                    if (s != dragTarget) dragTarget = s
                                }
                            }
                        } finally {
                            dragTarget = null
                            fingerDown = false
                            dragCentre = null
                        }
                    }
                }
            }
    ) {
        val (leftEdge, rightEdge) = rememberStretchingLens(
            index = dragTarget ?: restingIndex,
            slot = slotWidth,
            dragCentre = dragCentre
        )
        val lensScale by animateFloatAsState(
            targetValue = if (fingerDown) 1.08f else 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 400f),
            label = "lensScale"
        )
        val lensAlpha by animateFloatAsState(
            targetValue = if (selectedIndex != null) 1f else 0f,
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            label = "lensAlpha"
        )

        val primaryColor = MaterialTheme.colorScheme.primary

        // Inner sliding track: keeps full dock layout width while sliding active tab to [anchor] as fold -> 1f
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val inset = DOCK_INSET.roundToPx()
                val w = (fullWidth - DOCK_INSET * 2).roundToPx()
                val h = (constraints.maxHeight - inset * 2).coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                val slidePx = (anchor - DOCK_INSET - slotWidth * (restingIndex + 0.5f)).toPx() * foldProgress()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(inset + slidePx.roundToInt(), inset)
                }
            }
        ) {
            // Stretching Active Tab Pill Lens
            Box(
                modifier = Modifier
                    .offset { IntOffset(leftEdge().roundToPx(), 0) }
                    .layout { measurable, constraints ->
                        val w = (rightEdge() - leftEdge()).roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = lensAlpha * (1f - foldProgress()).coerceIn(0f, 1f)
                        scaleX = lensScale
                        scaleY = lensScale
                    }
                    .clip(LENS_SHAPE)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = if (isDark) 0.28f else 0.20f),
                                primaryColor.copy(alpha = if (isDark) 0.16f else 0.10f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = 0.48f),
                                primaryColor.copy(alpha = 0.10f)
                            )
                        ),
                        LENS_SHAPE
                    )
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { idx, item ->
                    DockTabCell(
                        item = item,
                        selected = idx == selectedIndex,
                        isDark = isDark,
                        collapse = foldProgress,
                        modifier = Modifier
                            .width(slotWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                if (idx != restingIndex) {
                                    alpha = (1f - foldProgress() * 1.65f).coerceIn(0f, 1f)
                                }
                            },
                        onClick = {
                            if (suppressTap) {
                                suppressTap = false
                            } else {
                                onSelectTab(idx)
                            }
                        }
                    )
                }
            }
        }

        // When folded into the left circle, tapping it expands the navigation bar back out!
        if (folded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpand
                    )
                    .semantics { role = Role.Button }
            )
        }
    }
}

@Composable
private fun DockTabCell(
    item: NavItem,
    selected: Boolean,
    isDark: Boolean,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.90f
            selected -> 1.06f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "dockTabScale"
    )
    val fgColor by animateColorAsState(
        targetValue = when {
            selected -> if (isDark) Color.White else MaterialTheme.colorScheme.primary
            isDark -> Color.White.copy(alpha = 0.78f)
            else -> Color(0xFF5A5A66)
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "dockTabFg"
    )

    Column(
        modifier = modifier
            .clip(LENS_SHAPE)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Crossfade(
            targetState = selected,
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            label = "dockIconCrossfade"
        ) { isOn ->
            Icon(
                imageVector = if (isOn) item.activeIcon else item.inactiveIcon,
                contentDescription = item.label,
                tint = fgColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        val c = collapse()
                        val s = scale * (1f + 0.05f * c)
                        scaleX = s
                        scaleY = s
                        // Drops 9.dp to vertical center as the text label folds away
                        translationY = 9.dp.toPx() * c
                    }
            )
        }
        Text(
            text = item.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = fgColor,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 2.dp)
                .graphicsLayer {
                    val c = collapse()
                    alpha = (1f - c * 2.5f).coerceIn(0f, 1f)
                    translationY = 6.dp.toPx() * c
                }
        )
    }
}

@Composable
private fun SearchOrb(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = ThemeState.isDarkMode
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "searchOrbScale"
    )
    val glassGradient = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(Color(0xEE1F1F2E), Color(0xF512121C))
        } else {
            listOf(Color(0xF2F7F7FC), Color(0xF8EAEAF2))
        }
    )
    val borderBrush = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.06f))
        } else {
            listOf(Color.White.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.10f))
        }
    )
    val innerFill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent,
        animationSpec = spring(stiffness = 500f),
        label = "searchOrbFill"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            if (isDark) Color.White else MaterialTheme.colorScheme.primary
        } else if (isDark) {
            Color.White.copy(alpha = 0.88f)
        } else {
            Color(0xFF4A4A56)
        },
        label = "searchOrbFg"
    )

    Box(
        modifier = modifier
            .sizeIn(maxWidth = CHROME_TAB_HEIGHT, maxHeight = CHROME_TAB_HEIGHT)
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.55f)
            )
            .clip(CircleShape)
            .background(glassGradient)
            .border(BorderStroke(1.dp, borderBrush), CircleShape)
            .padding(DOCK_INSET)
            .clip(CircleShape)
            .background(innerFill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Search",
            tint = iconTint,
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
internal fun rememberStretchingLens(
    index: Int,
    slot: Dp,
    dragCentre: Dp? = null
): Pair<() -> Dp, () -> Dp> {
    val currentIndex by rememberUpdatedState(index)
    val currentSlot by rememberUpdatedState(slot)
    val currentCentre by rememberUpdatedState(dragCentre)
    var leftPos by remember { mutableFloatStateOf(slot.value * index) }
    var rightPos by remember { mutableFloatStateOf(slot.value * (index + 1)) }
    var leftVel by remember { mutableFloatStateOf(0f) }
    var rightVel by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableStateOf(false) }
    var stretchValue by remember { mutableFloatStateOf(1f) }
    var stretchVel by remember { mutableFloatStateOf(0f) }

    val trackSpring = spring<Float>(dampingRatio = 1f, stiffness = 2800f)
    val rubberLead = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)
    val rubberTrail = spring<Float>(dampingRatio = 0.45f, stiffness = 240f)
    val stretchLead = spring<Float>(dampingRatio = 1f, stiffness = 520f)
    val stretchFollow = spring<Float>(dampingRatio = 1f, stiffness = 170f)
    val stretchTrack = spring<Float>(dampingRatio = 0.5f, stiffness = 520f)
    val stretchReturn = spring<Float>(dampingRatio = 0.4f, stiffness = 300f)

    LaunchedEffect(Unit) {
        var lastFrame = 0L
        var prevCentreFrame: Float? = null
        var asleep = false
        while (true) {
            if (asleep) {
                val at = Triple(currentIndex, currentSlot, currentCentre)
                snapshotFlow { Triple(currentIndex, currentSlot, currentCentre) }.first { it != at }
                asleep = false
                lastFrame = 0L
            }
            withFrameNanos { frame ->
                val dt = if (lastFrame == 0L) 0.016f else ((frame - lastFrame) / 1e9f).coerceIn(0f, 0.032f)
                lastFrame = frame
                val slotV = currentSlot.value
                val centre = currentCentre
                val stretchTarget: Float
                val stretchSpring: SpringSpec<Float>
                if (centre != null) {
                    dragged = true
                    val speed = abs(centre.value - (prevCentreFrame ?: centre.value))
                    prevCentreFrame = centre.value
                    stretchTarget = (1f + speed / slotV * 0.9f).coerceAtMost(1.45f)
                    stretchSpring = stretchTrack
                } else {
                    prevCentreFrame = null
                    stretchTarget = 1f
                    stretchSpring = stretchReturn
                }
                val toLeft: Float
                val toRight: Float
                val lead: SpringSpec<Float>
                val follow: SpringSpec<Float>
                if (centre != null) {
                    val half = slotV / 2f * stretchValue
                    toLeft = centre.value - half
                    toRight = centre.value + half
                    lead = trackSpring
                    follow = trackSpring
                } else {
                    toLeft = slotV * currentIndex
                    toRight = slotV * (currentIndex + 1)
                    val pair = if (dragged) rubberLead to rubberTrail else stretchLead to stretchFollow
                    lead = pair.first
                    follow = pair.second
                }
                val movingRight = toLeft > leftPos
                val lSpec = if (movingRight) follow else lead
                val rSpec = if (movingRight) lead else follow
                val steps = (dt / 0.0075f).toInt().coerceIn(1, 6)
                val h = dt / steps
                repeat(steps) {
                    val sAccel = stretchSpring.stiffness * (stretchTarget - stretchValue) -
                        2f * stretchSpring.dampingRatio * sqrt(stretchSpring.stiffness) * stretchVel
                    stretchVel += sAccel * h
                    stretchValue += stretchVel * h

                    val lAccel = lSpec.stiffness * (toLeft - leftPos) -
                        2f * lSpec.dampingRatio * sqrt(lSpec.stiffness) * leftVel
                    leftVel += lAccel * h
                    leftPos += leftVel * h

                    val rAccel = rSpec.stiffness * (toRight - rightPos) -
                        2f * rSpec.dampingRatio * sqrt(rSpec.stiffness) * rightVel
                    rightVel += rAccel * h
                    rightPos += rightVel * h
                }
                if (!leftPos.isFinite() || !rightPos.isFinite() || abs(leftPos) > 10000f || abs(rightPos) > 10000f) {
                    leftPos = toLeft
                    rightPos = toRight
                    leftVel = 0f
                    rightVel = 0f
                }
                if (centre == null &&
                    abs(toLeft - leftPos) < 0.01f &&
                    abs(toRight - rightPos) < 0.01f &&
                    abs(leftVel) < 0.05f &&
                    abs(rightVel) < 0.05f
                ) {
                    leftPos = toLeft
                    rightPos = toRight
                    leftVel = 0f
                    rightVel = 0f
                    dragged = false
                    if (abs(stretchValue - 1f) < 0.001f && abs(stretchVel) < 0.01f) {
                        stretchValue = 1f
                        stretchVel = 0f
                        asleep = true
                    }
                }
            }
        }
    }
    return ({ leftPos.dp } to { rightPos.dp })
}

/**
 * Backward-compatible standalone FloatingNavBar if referenced directly anywhere else.
 */
@Composable
fun FloatingNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MorphingBottomChrome(
        collapsed = false,
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        onExpand = {},
        hasMiniPlayer = false,
        miniPlayerContent = { _, _ -> },
        modifier = modifier
    )
}
