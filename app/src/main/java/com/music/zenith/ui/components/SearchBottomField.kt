package com.music.zenith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private val SEARCH_PILL_HEIGHT = 48.dp
private val SEARCH_PILL_GAP = 10.dp

/**
 * Field pencarian yang melayang di atas nav bar (gaya Apple Music).
 *
 * Posisinya mengikuti tinggi bar lewat [barsHeight] sehingga ikut turun saat
 * bar melipat ke mode inline, dan naik mengikuti keyboard (IME) tanpa melompat.
 * Tidak bergantung pada mode Search terpisah atau menyatu di pill.
 */
@Composable
fun SearchBottomField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    placeholder: String,
    barsHeight: () -> Int,
    onSourceMenuClick: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
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
    val glass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val ime = WindowInsets.ime

    Box(
        modifier = modifier
            .offset { IntOffset(0, -(maxOf(ime.getBottom(this), barsHeight()) + SEARCH_PILL_GAP.roundToPx())) }
            .padding(horizontal = NAV_BAR_GUTTER)
            .height(SEARCH_PILL_HEIGHT)
            .then(
                if (glass) {
                    Modifier.liquidGlass(shape = shape)
                } else {
                    Modifier
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
            ),
    ) {
        CompositionLocalProvider(LocalSearchFieldOnSurface provides true) {
            val field: @Composable () -> Unit = {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSubmit = onSubmit,
                    focusRequester = focusRequester,
                    placeholder = placeholder,
                    onFocusChange = onFocusChange,
                    onSourceMenuClick = onSourceMenuClick,
                )
            }
            if (glass) {
                val glassContent = glassContentColor()
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(
                        onBackground = glassContent,
                        onSurfaceVariant = glassContent.copy(alpha = 0.6f),
                        primary = glassContent,
                    ),
                    typography = MaterialTheme.typography,
                    shapes = MaterialTheme.shapes,
                    content = field,
                )
            } else {
                field()
            }
        }
    }
}