package com.music.zenith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import com.music.zenith.data.settings.AppSettings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Kemunculan popup dengan spring jelly: kartu membesar dari ~0.8x, melewati
 * ukuran akhir sedikit, lalu bergoyang (X dan Y berlawanan fase) sebelum diam.
 * Dipasang di kartu popup (alert, popup audio) tepat setelah width, sebelum clip.
 * "Reduce animation" mematikannya.
 */
@Composable
internal fun Modifier.jellyPopIn(animateIn: Boolean = true, startAt: Float = 0f): Modifier {
    val reduce by AppSettings.reduceAnimation.collectAsState()
    // animateIn = false keeps only the rubber-band pull; the entrance is then
    // owned by MacOSPopupState (see MacOSInspiredPopup.kt).
    val progress = remember { Animatable(if (reduce || !animateIn) 1f else startAt) }
    LaunchedEffect(reduce, animateIn) {
        if (reduce || !animateIn) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 500f))
        }
    }
    // Jelly drag: the same physics as the menus (see IosMenuTouch), so every popup
    // wobbles alike: the card shifts and stretches toward the finger, then rebounds
    // on release. Events are only observed, never consumed, and movement a child
    // already took (a slider, a scrolling list) is ignored, so taps and gestures
    // inside the card behave as before.
    val scope = rememberCoroutineScope()
    val pullX = remember { Animatable(0f) }
    val pullY = remember { Animatable(0f) }
    val stretchX = remember { Animatable(1f) }
    val stretchY = remember { Animatable(1f) }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dead = with(LocalDensity.current) { 6.dp.toPx() }
    val maxShift = with(LocalDensity.current) { 12.dp.toPx() }
    return this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(reduce) {
            if (reduce) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Window space: a card that is itself moving under the finger must
                // not read as the finger dragging.
                fun window(p: Offset): Offset =
                    coords[0]?.takeIf { it.isAttached }?.localToWindow(p) ?: p
                val origin = window(down.position)
                val follow = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    if (change.isConsumed) continue
                    val pos = window(change.position)
                    fun soft(d: Float): Float = if (abs(d) <= dead) 0f else (abs(d) - dead) * sign(d)
                    val dx = soft(pos.x - origin.x)
                    val dy = soft(pos.y - origin.y)
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    val tx = (dx * 0.07f).coerceIn(-maxShift, maxShift)
                    val ty = (dy * 0.07f).coerceIn(-maxShift, maxShift)
                    val ax = (abs(dx) / w * 0.22f).coerceAtMost(0.06f)
                    val ay = (abs(dy) / h * 0.22f).coerceAtMost(0.06f)
                    scope.launch { pullX.animateTo(tx, follow) }
                    scope.launch { pullY.animateTo(ty, follow) }
                    scope.launch { stretchX.animateTo(1f + ax - ay * 0.5f, follow) }
                    scope.launch { stretchY.animateTo(1f + ay - ax * 0.5f, follow) }
                }
                val rebound = spring<Float>(dampingRatio = 0.34f, stiffness = 260f)
                scope.launch { pullX.animateTo(0f, rebound) }
                scope.launch { pullY.animateTo(0f, rebound) }
                scope.launch { stretchX.animateTo(1f, rebound) }
                scope.launch { stretchY.animateTo(1f, rebound) }
            }
        }
        .graphicsLayer {
        translationX = pullX.value
        translationY = pullY.value
        val p = progress.value
        alpha = (p * 3f).coerceIn(0f, 1f)
        val scale = 0.92f + 0.08f * p
        val wobble = (p - 1f) * p * p * 0.12f
        scaleX = (scale - wobble) * stretchX.value
        scaleY = (scale + wobble) * stretchY.value
    }
}