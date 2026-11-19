package com.music.zenith.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Touch feedback for the search source globe: it shrinks slightly while held,
 * then springs back with a small bounce.
 */
class GlobeTouch internal constructor(
    val interactionSource: MutableInteractionSource,
    internal val pressed: State<Boolean>,
) {
    /** Kept so callers wrap their tap the same way; the tap itself adds no motion. */
    fun onTap(action: () -> Unit): () -> Unit = action
}

@Composable
fun rememberGlobeTouch(): GlobeTouch {
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    return remember(source) { GlobeTouch(source, pressed) }
}

@Composable
fun Modifier.globeTouch(touch: GlobeTouch): Modifier {
    val scale = animateFloatAsState(
        targetValue = if (touch.pressed.value) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "globeScale",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}