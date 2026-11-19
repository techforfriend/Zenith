package com.music.zenith.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.music.zenith.ui.icons.SfIcons

/** The circle the bar folds down to, and the Search circle the field grows out of. */
private val MORPH_CIRCLE = 48.dp

/** Gap between the last tab's circle and the search capsule. */
private val MORPH_GAP = 14.dp

/** The capsule's own end controls: the back arrow and the source switch. */
private val MORPH_CONTROL = 44.dp

/**
 * The bar while the separate Search tab is open on liquid glass.
 *
 * The bar folds down to one circle, the last tab that was not Search, and the
 * Search circle beside it springs open into the input field, with a back arrow
 * at its start and the source switch at its end. The mini player stays above
 * it; the caller lays both out.
 */
@Composable
fun GlassSearchMorph(
    lastTab: BottomTab,
    onLastTabClick: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    placeholder: String,
    onSourceClick: () -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Starts as the Search circle alone and springs wide on the frame it
    // arrives, so the tab's own circle is what becomes the input.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val openFraction by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
        label = "searchMorphOpen",
    )

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
    val glass = Modifier.liquidGlass(shape = shape)
    val tint = contentColor.copy(alpha = 0.88f)

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = NAV_BAR_GUTTER)
            .padding(bottom = NAV_BAR_LIFT),
    ) {
        val fullWidth = maxWidth
        // The capsule's width once open: whatever the last tab's circle leaves.
        val fieldWidth = fullWidth - MORPH_CIRCLE - MORPH_GAP
        val capsuleWidth = MORPH_CIRCLE + (fieldWidth - MORPH_CIRCLE) * openFraction

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(MORPH_CIRCLE)
                .clip(shape)
                .then(glass)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        onLastTabClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = lastTab.icon,
                contentDescription = lastTab.label,
                tint = tint,
                modifier = Modifier.size(TAB_ICON_SIZE),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(capsuleWidth)
                .height(MORPH_CIRCLE)
                .clip(shape)
                .then(glass),
            contentAlignment = Alignment.CenterStart,
        ) {
            // The magnifier the Search circle keeps while it is still a circle.
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(TAB_ICON_SIZE)
                    .graphicsLayer { alpha = 1f - openFraction },
            )

            // Everything below keeps its full width the whole time, so nothing
            // reflows while the capsule springs; only its reveal changes.
            CompositionLocalProvider(LocalSearchFieldOnSurface provides true) {
                // Back: clears the query and lets go of the field.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(MORPH_CONTROL)
                        .graphicsLayer { alpha = openFraction }
                        .clickable(
                            enabled = entered,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onQueryChange("")
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SfIcons.ArrowBack,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(TAB_ICON_SIZE),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = MORPH_CONTROL)
                        .requiredWidth(fieldWidth - MORPH_CONTROL * 2)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = openFraction },
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
                        )
                    }
                }

                // Source switch: YouTube, Library, Addons in turn.
                val globe = rememberGlobeTouch()
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(MORPH_CONTROL)
                        .graphicsLayer { alpha = openFraction }
                        .clickable(
                            enabled = entered,
                            interactionSource = globe.interactionSource,
                            indication = null,
                            onClick = globe.onTap(onSourceClick),
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