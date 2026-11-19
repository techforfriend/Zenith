package com.music.zenith.ui.components

import androidx.compose.material.icons.rounded.Check
import com.music.zenith.ui.icons.ZenithIcons
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.music.zenith.ui.player.LocalLyricsMenuPopover
import com.music.zenith.ui.player.LyricsMenuEntry

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp as lerpDp
import com.music.zenith.ui.icons.SfIcons
import kotlinx.coroutines.flow.first

/**
 * Permintaan menu sumber pencarian dari globe di bar. Bar hanya melaporkan ini;
 * menunya digambar host di root layar, bukan di dalam Column bar (popover
 * fillMaxSize di dalam Column memakan seluruh tinggi dan mendorong bar).
 */
@androidx.compose.runtime.Stable
class SourceMenuRequest(
    val anchor: androidx.compose.ui.geometry.Rect?,
    val entries: List<LyricsMenuEntry>,
    val onDismiss: () -> Unit,
)

/** The bar at rest: the tab pill and the Search circle are both 62dp tall. */
private val BAR_ROW_HEIGHT = 62.dp
private val BAR_CIRCLE = 62.dp
private val BAR_SPACING = 8.dp

/** The bar once the field is open: a 48dp circle for the last tab and the field beside it. */
private val BAR_FIELD_HEIGHT = 48.dp
private val BAR_FIELD_CIRCLE = 48.dp
private val BAR_FIELD_GAP = 14.dp

/** The field's own end controls: the back arrow and the source switch. */
private val BAR_FIELD_CONTROL = 44.dp

/** The collapsed row's side margin (28dp) over the open bar's (19dp). */
private val BAR_INLINE_INSET = 9.dp

/**
 * The mini player's side inset in the collapsed row: gutter + circle + spacing
 * (28 + 48 + 14 = 90dp), less the pill's own 19dp margin.
 */
private val MINI_INLINE_SIDE = 71.dp

/** Isi field baru muncul setelah kapsul mulai melebar, dan hilang sebelum melipat. */
private fun searchContentAlpha(p: Float): Float = ((p - 0.3f) / 0.6f).coerceIn(0f, 1f)

/** Tab yang tidak ikut meluncur memudar cepat selagi pill menyempit. */
private fun searchTabFade(p: Float): Float = (1f - p * 2.2f).coerceIn(0f, 1f)

/**
 * Ukuran kotak yang dihitung di fase layout dari progres animasi: satu frame animasi
 * hanya mengukur ulang kotak ini, tanpa merekomposisi isinya.
 */
private fun Modifier.searchMorphSize(width: () -> Dp, height: () -> Dp): Modifier =
    layout { measurable, constraints ->
        val w = width().roundToPx().coerceIn(0, constraints.maxWidth)
        val h = height().roundToPx().coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.placeRelative(0, 0) }
    }

/**
 * Glyph search yang meluncur dari tengah lingkaran ke slot awal field. Warna dibaca
 * di sini saja, jadi hanya satu ikon ini yang direkomposisi per frame.
 */
@Composable
private fun SearchMorphGlyph(
    open: State<Float>,
    restColor: Color,
    openColor: Color,
    restStart: Dp,
    openStart: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = SfIcons.Search,
        contentDescription = null,
        tint = lerp(restColor, openColor, open.value.coerceIn(0f, 1f)),
        modifier = modifier
            .size(TAB_ICON_SIZE)
            .graphicsLayer {
                translationX = lerpDp(restStart, openStart, open.value.coerceAtLeast(0f)).toPx()
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/**
 * Moves the mini player from its slot between the two circles of the collapsed
 * row up to its place above the bar, on the same [progress] (0..1) that opens
 * the search field: it widens to full width while it rises. Read at layout and
 * draw time only, so nothing recomposes per frame.
 */
internal fun Modifier.searchMiniRise(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val side = MINI_INLINE_SIDE.roundToPx()
        val inset = (side * (1f - progress().coerceIn(0f, 1f))).toInt()
        val width = (constraints.maxWidth - inset * 2).coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minWidth = width, maxWidth = width),
        )
        // From the pill's own centre down to the row's centre line.
        val dy = placeable.height / 2f + (BAR_ROW_HEIGHT / 2 + 8.dp).toPx()
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeWithLayer(inset, 0) {
                translationY = (1f - progress().coerceAtLeast(0f)) * dy
            }
        }
    }

/**
 * Folds the mini player away while the keyboard is up: it sinks toward the bar
 * and fades, and its layout height closes with it so the page behind gets the
 * room. [hide] is 0 = shown, 1 = gone; read at layout and draw time only.
 */
internal fun Modifier.searchMiniHide(hide: () -> Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val reported = (placeable.height * (1f - hide().coerceIn(0f, 1f))).toInt()
        layout(placeable.width, reported) {
            placeable.placeWithLayer(0, 0) {
                val h = hide().coerceIn(0f, 1f)
                alpha = 1f - h
                translationY = h * placeable.height * 0.6f
                val s = 1f - 0.08f * h
                scaleX = s
                scaleY = s
            }
        }
    }

/**
 * The separate Search tab's bar on liquid glass, with ONE progress driving it.
 *
 * At rest (progress 0) it is shaped like the expanded [GlassNavBar]: a tab pill
 * and the Search circle. Opening, the Search circle shrinks and grows to the
 * left into the input field while the pill is pushed left and shrinks to a
 * single circle holding the last tab that was not Search. Closing runs the same
 * progress back, then calls [onClosed] so the caller can swap [GlassNavBar] back
 * in. [open] is the target; the caller keeps this composed until [onClosed].
 */
@Composable
fun GlassSearchBar(
    tabs: List<BottomTab>,
    lastTab: BottomTab,
    open: Boolean,
    onLastTabClick: () -> Unit,
    onClose: () -> Unit,
    onClosed: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    placeholder: String,
    sourceLabels: List<String>,
    sourceIndex: Int,
    onSourceSelect: (Int) -> Unit,
    /** Melaporkan menu sumber yang sedang dibuka (null = tertutup); host yang menggambarnya. */
    onSourceMenu: (SourceMenuRequest?) -> Unit = {},
    /** true = the bar opens out of the collapsed row: two 48dp circles with the mini player between. */
    startInline: Boolean = false,
    /** Reports the open fraction each frame so the mini player above can rise on the same spring. */
    onOpenFraction: (Float) -> Unit = {},
    /** Hands the bar's own open progress up (null once it leaves), so the mini player reads the very same value. */
    onOpenState: (State<Float>?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Starts at rest and springs open on the frame it arrives.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    // Critically damped (no overshoot, so nothing clips) but stiff, so both the
    // open and the close settle quickly and the bar swap is not delayed. The mini
    // player's density spring in GlassNowPlaying is kept on the same stiffness.
    val openState = animateFloatAsState(
        targetValue = if (open && entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 700f),
        label = "searchBarOpen",
    )
    val currentOnClosed by rememberUpdatedState(onClosed)
    val currentOnOpenFraction by rememberUpdatedState(onOpenFraction)
    val currentOnOpenState by rememberUpdatedState(onOpenState)
    LaunchedEffect(openState) { currentOnOpenState(openState) }
    DisposableEffect(Unit) { onDispose { currentOnOpenState(null) } }
    val inlineStart = remember { startInline }
    // Fraksi dilaporkan ke atas lewat snapshotFlow, tanpa merekomposisi bar tiap frame.
    LaunchedEffect(Unit) {
        snapshotFlow { openState.value }.collect { currentOnOpenFraction(it) }
    }
    // The resting row is as tall as GlassNavBar's own tab row: glyph + gap + label,
    // padded. That is whatever the label measures at this font scale, not a fixed
    // 62dp, so it is measured the same way here and the mini player above does not
    // shift when the nav bar and this bar swap places.
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = tabLabelStyle()
    val restHeight = remember(labelStyle, density) {
        val labelPx = textMeasurer.measure(text = "Ag", style = labelStyle, maxLines = 1).size.height
        PILL_INSET * 2 + TAB_VERTICAL_PADDING * 2 + TAB_ICON_SIZE + TAB_ICON_LABEL_GAP +
            with(density) { labelPx.toDp() }
    }
    LaunchedEffect(open) {
        if (!open) {
            snapshotFlow { openState.value }.first { it <= 0.001f }
            currentOnClosed()
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
            onFocusHandled()
        }
    }
    DisposableEffect(Unit) {
        onDispose { onFocusChange(false) }
    }

    val shape = remember { RoundedCornerShape(percent = 50) }
    val contentColor = glassContentColor()
    val tint = contentColor.copy(alpha = 0.88f)
    // Source picker: the same globe popover as the lyrics controls, anchored to this button.
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var sourceAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // Ikon yang sama dengan yang dipakai app: tab Library, dan ikon sumber YouTube / Addons.
    val sourceIcons = listOf(SfIcons.PlayCircle, ZenithIcons.TabLibrary, SfIcons.Extension)
    val sourceEntries = sourceLabels.mapIndexed { index, label ->
        LyricsMenuEntry(
            label = label,
            icon = sourceIcons.getOrElse(index) { Icons.Rounded.Public },
            enabled = true,
            onClick = {
                sourceMenuOpen = false
                onSourceSelect(index)
            },
            selected = index == sourceIndex,
        )
    }
    val currentOnSourceMenu by rememberUpdatedState(onSourceMenu)
    LaunchedEffect(sourceMenuOpen, if (sourceMenuOpen) sourceAnchor else null, sourceIndex) {
        currentOnSourceMenu(
            if (sourceMenuOpen) {
                SourceMenuRequest(
                    anchor = sourceAnchor,
                    entries = sourceEntries,
                    onDismiss = { sourceMenuOpen = false },
                )
            } else {
                null
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { currentOnSourceMenu(null) }
    }
    val selectedColor = if (contentColor == Color.White) {
        lerp(MaterialTheme.colorScheme.primary, Color.White, 0.06f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    // Controls only answer once the bar has opened, not while it is folding.
    val interactive by remember(open) { derivedStateOf { open && openState.value > 0.9f } }

    // Progres hanya dibaca di fase layout/draw (lambda di bawah), jadi satu frame animasi
    // tidak merekomposisi bar dan field-nya: yang berubah cuma ukuran, posisi, dan alpha.
    val progress: () -> Float = { openState.value.coerceAtLeast(0f) }
    val lastIndex = remember(tabs, lastTab) { tabs.indexOf(lastTab) }
    // Selisih tinggi ikon di pill dengan titik tengah lingkaran: setengah (label + jaraknya).
    val tabRise = (restHeight - PILL_INSET * 2 - TAB_VERTICAL_PADDING * 2 - TAB_ICON_SIZE) / 2

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = NAV_BAR_GUTTER)
            .padding(bottom = NAV_BAR_LIFT)
            // Fixed at the resting height: the 48dp pieces centre on the same
            // line, so the mini player above does not move while this folds.
            .height(restHeight),
    ) {
        // From the collapsed row the sides start 9dp further in and ease out to
        // the open bar's margin as the field widens.
        val inset: (Float) -> Dp = { p ->
            if (inlineStart) lerpDp(BAR_INLINE_INSET, 0.dp, p).coerceAtLeast(0.dp) else 0.dp
        }
        val fullWidth: (Float) -> Dp = { p -> maxWidth - inset(p) * 2 }
        // The field's width once fully open; fixed so nothing reflows while it springs.
        val fieldWidth = maxWidth - BAR_FIELD_CIRCLE - BAR_FIELD_GAP
        // The pill's width at rest: whatever the Search circle leaves.
        val restLeft = maxWidth - restHeight - BAR_SPACING
        val gap: (Float) -> Dp = { p ->
            if (inlineStart) BAR_FIELD_GAP else lerpDp(BAR_SPACING, BAR_FIELD_GAP, p)
        }
        val leftWidth: (Float) -> Dp = { p ->
            if (inlineStart) BAR_FIELD_CIRCLE else lerpDp(restLeft, BAR_FIELD_CIRCLE, p)
        }
        val rightWidth: (Float) -> Dp = { p ->
            val room = fullWidth(p) - leftWidth(p) - gap(p)
            val w = if (inlineStart) {
                minOf(maxOf(BAR_FIELD_CIRCLE, lerpDp(BAR_FIELD_CIRCLE, fieldWidth, p)), room)
            } else {
                room
            }
            w.coerceAtLeast(0.dp)
        }
        val rowHeight: (Float) -> Dp = { p ->
            if (inlineStart) BAR_FIELD_HEIGHT else lerpDp(restHeight, BAR_FIELD_HEIGHT, p)
        }
        // Pusat slot tab terakhir terhadap pusat baris tab (baris tab selalu terpusat di
        // kotak yang menyempit): sejauh itu ikonnya meluncur ke tengah lingkaran.
        val tabSlot = (restLeft - PILL_INSET * 2) / tabs.size.coerceAtLeast(1)
        val slotShift = PILL_INSET + tabSlot * (lastIndex.coerceAtLeast(0) + 0.5f) - restLeft / 2

        // Left: the tab pill, folding down to the last tab's circle.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(inset(progress()).roundToPx(), 0) }
                .searchMorphSize(
                    width = { leftWidth(progress()) },
                    height = { rowHeight(progress()) },
                )
                .clip(shape)
                .then(Modifier.liquidGlass(shape = shape))
                .clickable(
                    enabled = interactive,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onQueryChange("")
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onLastTabClick()
                },
        ) {
            if (inlineStart || lastIndex < 0) {
                // Collapsed start: the one tab is already a lone glyph in its circle.
                Icon(
                    imageVector = lastTab.icon,
                    contentDescription = lastTab.label,
                    tint = tint,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(TAB_ICON_SIZE)
                        .graphicsLayer {
                            alpha = if (inlineStart) 1f else ((progress() - 0.45f) * 2.2f).coerceIn(0f, 1f)
                        },
                )
            }
            if (!inlineStart) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredWidth(restLeft)
                        .requiredHeight(restHeight)
                        .padding(PILL_INSET),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val moving = index == lastIndex
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Ikon tab terakhir adalah SATU ikon yang sama: tidak memudar,
                            // hanya meluncur ke tengah lingkaran. Tab lain memudar.
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier
                                    .size(TAB_ICON_SIZE)
                                    .graphicsLayer {
                                        val p = progress()
                                        if (moving) {
                                            translationX = -slotShift.toPx() * p
                                            translationY = tabRise.toPx() * p
                                        } else {
                                            alpha = searchTabFade(p)
                                        }
                                    },
                            )
                            Text(
                                text = tab.label,
                                style = tabLabelStyle(),
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = TAB_ICON_LABEL_GAP)
                                    .graphicsLayer { alpha = searchTabFade(progress()) },
                            )
                        }
                    }
                }
            }
        }

        // Right: the Search circle, growing leftward into the field.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(-inset(progress()).roundToPx(), 0) }
                .searchMorphSize(
                    width = { rightWidth(progress()) },
                    height = { rowHeight(progress()) },
                )
                .clip(shape)
                .then(Modifier.liquidGlass(shape = shape)),
            contentAlignment = Alignment.CenterStart,
        ) {
            // One search glyph for both states: it rests centred in the Search circle
            // and, as the capsule opens, slides left with the capsule's edge into the
            // field's start slot. It is never swapped for a second icon or faded.
            val restRight = if (inlineStart) BAR_FIELD_CIRCLE else BAR_CIRCLE
            SearchMorphGlyph(
                open = openState,
                restColor = selectedColor,
                openColor = tint,
                restStart = ((restRight - TAB_ICON_SIZE) / 2).coerceAtLeast(0.dp),
                openStart = ((BAR_FIELD_CONTROL - TAB_ICON_SIZE) / 2).coerceAtLeast(0.dp),
                enabled = interactive && query.isNotBlank(),
                onClick = {
                    onSubmit()
                    focusManager.clearFocus()
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )

            // Everything below keeps its full width the whole time, so nothing
            // reflows while the capsule springs; only its reveal changes.
            CompositionLocalProvider(LocalSearchFieldOnSurface provides true) {
                // The search glyph is the one drawn above, sliding in from the circle.

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = BAR_FIELD_CONTROL)
                        .requiredWidth(fieldWidth - BAR_FIELD_CONTROL * 2)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = searchContentAlpha(progress()) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    MaterialTheme(
                        colorScheme = MaterialTheme.colorScheme.copy(
                            onBackground = contentColor,
                            onSurfaceVariant = contentColor.copy(alpha = 0.6f),
                            primary = contentColor,
                        ),
                        typography = MaterialTheme.typography,
                        shapes = MaterialTheme.shapes,
                    ) {
                        SearchField(
                            query = query,
                            onQueryChange = onQueryChange,
                            onSubmit = onSubmit,
                            focusRequester = focusRequester,
                            placeholder = placeholder,
                            onFocusChange = onFocusChange,
                            showSearchIcon = false,
                        )
                    }
                }

                // Source switch: YouTube, Library, Addons in turn.
                val globe = rememberGlobeTouch()
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(BAR_FIELD_CONTROL)
                        .graphicsLayer { alpha = searchContentAlpha(progress()) }
                        .onGloballyPositioned { sourceAnchor = it.boundsInWindow() }
                        .clickable(
                            enabled = interactive,
                            interactionSource = globe.interactionSource,
                            indication = null,
                            onClick = globe.onTap { sourceMenuOpen = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(TAB_ICON_SIZE).globeTouch(globe),
                    )
                }
            }
        }
    }
}
