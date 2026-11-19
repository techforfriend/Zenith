package com.music.zenith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.music.zenith.data.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Jelly untuk tombol kaca ala Apple Music: membesar secara fluid saat
 * ditekan, meregang dan bergeser mengikuti jari saat digeser, lalu memantul
 * (spring redaman rendah) saat dilepas. Event hanya diamati, tidak pernah
 * dikonsumsi, jadi klik dan gestur anak tidak berubah. Pasang di awal rantai
 * modifier tombol, sebelum size dan permukaan kaca, supaya seluruh kaca ikut
 * bergoyang. "Reduce animation" mematikannya.
 */
@Composable
internal fun Modifier.glassJelly(pressScale: Float = GLASS_JELLY_PRESS): Modifier {
    val reduce by AppSettings.reduceAnimation.collectAsState()
    val scope = rememberCoroutineScope()
    // press 0..1 = progres membesar; stX/stY = regangan tambahan (delta skala).
    val press = remember { Animatable(0f) }
    val offX = remember { Animatable(0f) }
    val offY = remember { Animatable(0f) }
    val stX = remember { Animatable(0f) }
    val stY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val maxShift = with(density) { 2.dp.toPx() }
    val growPx = with(density) { GLASS_JELLY_GROW.toPx() }
    val maxGrow = (pressScale - 1f).coerceAtLeast(0f)
    val releaseJob = remember { arrayOfNulls<Job>(1) }
    return this
        .pointerInput(reduce) {
            if (reduce) return@pointerInput
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                releaseJob[0]?.cancel()
                var lastUp = down.uptimeMillis
                // Pegas lembut, overshoot kecil: membesar mengalir tanpa loncat.
                scope.launch { press.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 450f)) }
                // Pegas pengikut jari: animateTo mewarisi kecepatan sebelumnya,
                // jadi tidak ada lompatan seperti snapTo.
                val follow = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)
                var total = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) lastUp = change.uptimeMillis
                    if (change == null || !change.pressed) break
                    if (change.isConsumed) continue
                    total += change.positionChange()
                    fun soft(d: Float): Float = if (abs(d) <= slop) 0f else (abs(d) - slop) * sign(d)
                    val dx = soft(total.x)
                    val dy = soft(total.y)
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    val ax = (abs(dx) / w * 0.12f).coerceAtMost(0.012f)
                    val ay = (abs(dy) / h * 0.12f).coerceAtMost(0.012f)
                    val tx = (dx * 0.05f).coerceIn(-maxShift, maxShift)
                    val ty = (dy * 0.05f).coerceIn(-maxShift, maxShift)
                    scope.launch { offX.animateTo(tx, follow) }
                    scope.launch { offY.animateTo(ty, follow) }
                    scope.launch { stX.animateTo(ax - ay * 0.5f, follow) }
                    scope.launch { stY.animateTo(ay - ax * 0.5f, follow) }
                }
                val wobble = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)
                // Ketukan singkat tetap sempat membesar sebelum kembali.
                val held = lastUp - down.uptimeMillis
                releaseJob[0] = scope.launch {
                    if (held < GLASS_JELLY_MIN_HOLD_MS) delay(GLASS_JELLY_MIN_HOLD_MS - held)
                    launch { press.animateTo(0f, wobble) }
                    launch { offX.animateTo(0f, wobble) }
                    launch { offY.animateTo(0f, wobble) }
                    launch { stX.animateTo(0f, wobble) }
                    launch { stY.animateTo(0f, wobble) }
                }
            }
        }
        .graphicsLayer {
            // Pembesaran dibatasi dalam dp (bukan rasio) supaya pil lebar tidak
            // melebar berlebihan; maxGrow tetap menjadi batas atas untuk tombol kecil.
            val p = press.value
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            translationX = offX.value
            translationY = offY.value
            scaleX = 1f + p * minOf(maxGrow, growPx / w) + stX.value
            scaleY = 1f + p * minOf(maxGrow, growPx / h) + stY.value
        }
}

/** Skala tombol saat ditekan (membesar), sebelum regangan mengikuti jari. */
internal const val GLASS_JELLY_PRESS = 1.06f

/** Skala untuk elemen lebar (kolom search, pil Play/Shuffle) agar tidak terlalu melebar. */
internal const val GLASS_JELLY_PRESS_WIDE = 1.04f

/** Lama minimal fase membesar sebelum memantul, supaya ketukan singkat tetap terasa. */
internal const val GLASS_JELLY_MIN_HOLD_MS = 90L

/** Pertambahan ukuran maksimum per sumbu saat ditekan, dalam dp, agar pil lebar dan tombol bulat terasa sama. */
internal val GLASS_JELLY_GROW = 5.dp