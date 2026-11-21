/*
 * The selection pill's motion — the travel spring, the held lift, the
 * acceleration squash and the glass-to-flat hand-off, with their constants —
 * is ported from liquid_glass_easy's LiquidGlassAnimatedNavBar and
 * LiquidGlassLensMotion (pub.dev/packages/liquid_glass_easy, 4.3.4).
 * Copyright (c) 2025 Ahmed Gamil, MIT License.
 *
 * The rendering is Zenith's own, on the vendored backdrop library: the lens
 * refracts the tab bar's exported glass and its recorded tab row rather than a
 * screen capture.
 */
package com.music.zenith.ui.components.floatingtabbar

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.ui.components.LocalAppBackdrop
import com.music.zenith.ui.components.glassContentColor
import com.music.zenith.ui.components.isGlassSupported
import com.music.zenith.ui.components.backdrop.effects.blur
import com.music.zenith.ui.components.backdrop.effects.colorControls
import com.music.zenith.ui.components.backdrop.Backdrop
import com.music.zenith.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.zenith.ui.components.backdrop.drawBackdrop
import com.music.zenith.ui.components.backdrop.effects.lens
import com.music.zenith.ui.components.backdrop.highlight.Highlight
import com.music.zenith.ui.components.backdrop.shadow.InnerShadow
import com.music.zenith.ui.components.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/** How much taller than the bar the pill stands while it is lifted. */
internal val PILL_GROW_HEIGHT = 22.dp

/**
 
 * (DampedDragAnimation).
 *
 * One pill, a handful of [Animatable]s and nothing else: the position is a stiff,
 * critically damped spring; the press is a second one of the same kind and drives the
 * glass; the two size axes are under-damped (0.6 across, 0.7 down) so the pill overshoots
 * its raised size and settles instead of stopping dead. No per-frame integrator and no
 * hand-off timers that can drift out of step with the springs.
 *
 * A tap or a finger lifts the pill and a landing sets it down. The lift is released as
 * soon as the pill is [LAND_STEPS] from its tab, so the shrink overlaps the end of the
 * travel instead of waiting for it. The glass stays up for as long as the pill is bigger
 * than its resting size ([material] never falls faster than the lift does), so the shrink
 * is one object getting smaller and the flat pill takes over only once both are the same
 * size. Stretch comes from the speed of the move (the spring's or the finger's), never
 * from its acceleration, which is too noisy to animate.
 *
 * Positions are in tab steps: `0` is the first tab, `1` the second.
 */
@Stable
internal class GlassPillMotion(
    private val scope: CoroutineScope,
    initialIndex: Int,
    /** Time stretch for the lift: 1 is the tab pill's pace, above 1 slower and softer. */
    private val slow: Double = 1.0,
) {
    private val slowSq = (slow * slow).toFloat()
    private val valueSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)
    private val pressSpec = spring<Float>(dampingRatio = 1f, stiffness = 1000f / slowSq, visibilityThreshold = 0.001f)
    private val liftXSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 250f / slowSq, visibilityThreshold = 0.001f)
    private val liftYSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 250f / slowSq, visibilityThreshold = 0.001f)
    private val stretchSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.05f)

    private val valueAnim = Animatable(initialIndex.toFloat(), 0.001f)
    private val pressAnim = Animatable(0f, 0.001f)
    private val liftXAnim = Animatable(0f, 0.001f)
    private val liftYAnim = Animatable(0f, 0.001f)
    private val stretchAnim = Animatable(0f, 0.05f)
    private val overSpec = spring<Float>(dampingRatio = 0.45f, stiffness = 320f, visibilityThreshold = 0.001f)
    private val overAnim = Animatable(0f, 0.001f)

    private var held = false
    private var liftJob: Job? = null
    private var dropJob: Job? = null
    private var settleJob: Job? = null
    private var lastDragPos = 0f
    private var lastDragNs = 0L

    /** Where the pill is drawn, in tab steps. */
    val position: Float get() = valueAnim.value

    /** The width lift, `0` at rest and `1` at the raised size; overshoots both. */
    val liftX: Float get() = liftXAnim.value

    /** The height lift; see [liftX]. */
    val liftY: Float get() = liftYAnim.value

    /**
     * Tarikan karet melewati ujung pill, dalam langkah tab: positif melewati tab terakhir,
     * negatif melewati tab pertama. Nol di tengah; kembali ke nol dengan spring saat dilepas.
     */
    val over: Float get() = overAnim.value

    /** Jari masih menarik setelah mentok di ujung: [value] sudah diberi hambatan oleh pemanggil. */
    fun pullOver(value: Float) {
        if (reduceMotion) return
        scope.launch { overAnim.snapTo(value) }
    }

    private fun releaseOver() {
        scope.launch { overAnim.animateTo(0f, overSpec) }
    }

    /** How much of the glass is on, `0`..`1`: quick with the press, gone only as the pill shrinks back. */
    val material: Float
        get() = maxOf(pressAnim.value, (liftXAnim.value + liftYAnim.value) / 2f).coerceIn(0f, 1f)

    /** How much of the pill still reads as glass. */
    val glassPresence: Float get() = material

    /** How much of the frosted flat pill shows through, `1` at rest. */
    val blurMix: Float get() = 1f - material

    /** Current speed stretch: width scales by `1 + this`, height by `1 - this / 3`. */
    val deviation: Float get() = stretch()

    /** True when nothing is left but the flat pill at rest size. */
    var isFlat by mutableStateOf(true)
        private set

    /** Discrete: false only while the lens is fully up and the flat pill underneath is not needed. */
    var flatVisible by mutableStateOf(true)
        private set

    /** The tab the pill is headed for. */
    var index: Int = initialIndex
        private set

    /** A finger is holding the pill up: a press or a drag that has not been released yet. */
    val isHeld: Boolean get() = held

    /** One tab step in dp. */
    var stepDp: Float = 0f

    /** "Reduce animation": every move lands at once, with no lift. */
    var reduceMotion: Boolean = false

    /** Stretch multiplier from the indicator settings; 1 = default. */
    var squashScale: Float = 1f

    /** A tab was chosen: travel there, lifted. Re-choosing the current tab is a no-op. */
    fun animateTo(next: Int) {
        if (next == index) return
        if (reduceMotion) {
            snapTo(next)
            return
        }
        index = next
        held = false
        settleTo(next.toFloat(), null)
    }

    /** Place the pill on [target] at rest, with no travel and no lift. */
    fun snapTo(target: Int) {
        index = target
        held = false
        settleJob?.cancel()
        liftJob?.cancel()
        dropJob?.cancel()
        scope.launch {
            valueAnim.snapTo(target.toFloat())
            pressAnim.snapTo(0f)
            liftXAnim.snapTo(0f)
            liftYAnim.snapTo(0f)
            stretchAnim.snapTo(0f)
            flatVisible = true
            isFlat = true
        }
    }

    /**
     * Back from a tab that has no place in this row (Search): the pill starts at [from],
     * a step past the row, and travels in lifted like any other move.
     */
    fun enterFrom(from: Double, next: Int) {
        if (reduceMotion) {
            snapTo(next)
            return
        }
        index = next
        held = false
        settleTo(next.toFloat(), from.toFloat())
    }

    /** A finger has picked the pill up where it stands. */
    fun startDrag() {
        held = true
        settleJob?.cancel()
        scope.launch { valueAnim.stop() }
        lastDragPos = valueAnim.value
        lastDragNs = 0L
        if (!reduceMotion) raise()
    }

    /** The finger is over [position] tab steps. The finger owns the position: no smoothing. */
    fun dragTo(position: Float) {
        if (!held) return
        val now = System.nanoTime()
        if (lastDragNs != 0L) {
            val dt = (now - lastDragNs) / 1e9f
            if (dt > 0f) {
                val raw = (position - lastDragPos) / dt
                val smoothed = stretchAnim.value + (raw - stretchAnim.value) * 0.35f
                scope.launch { stretchAnim.snapTo(smoothed) }
            }
        }
        lastDragPos = position
        lastDragNs = now
        scope.launch { valueAnim.snapTo(position) }
    }

    /** The finger let go and the pill is to settle on [next]; it comes down as it lands. */
    fun release(next: Int) {
        if (!held) return
        held = false
        index = next
        releaseOver()
        if (reduceMotion) {
            snapTo(next)
            return
        }
        settleTo(next.toFloat(), null)
    }

    private fun settleTo(target: Float, from: Float?) {
        settleJob?.cancel()
        settleJob = scope.launch {
            raise()
            if (from != null) valueAnim.snapTo(from)
            var landed = false
            valueAnim.animateTo(target, valueSpec) {
                if (!landed && abs(value - target) < LAND_STEPS) {
                    landed = true
                    lower()
                }
            }
            if (!landed) lower()
        }
    }

    /** The press: glass on fast, size up on the two under-damped springs. */
    private fun raise() {
        dropJob?.cancel()
        liftJob?.cancel()
        isFlat = false
        liftJob = scope.launch {
            launch {
                pressAnim.animateTo(1f, pressSpec)
                flatVisible = false
            }
            launch { liftXAnim.animateTo(1f, liftXSpec) }
            launch { liftYAnim.animateTo(1f, liftYSpec) }
        }
    }

    /** The release: everything springs back to rest together. */
    private fun lower() {
        liftJob?.cancel()
        dropJob?.cancel()
        flatVisible = true
        dropJob = scope.launch {
            val a = launch { pressAnim.animateTo(0f, pressSpec) }
            val b = launch { liftXAnim.animateTo(0f, liftXSpec) }
            val c = launch { liftYAnim.animateTo(0f, liftYSpec) }
            val d = launch { stretchAnim.animateTo(0f, stretchSpec) }
            a.join()
            b.join()
            c.join()
            d.join()
            if (!held) isFlat = true
        }
    }

    /** Speed stretch, from the spring's own velocity or the finger's. */
    private fun stretch(): Float {
        // Reading the value subscribes the caller to every frame of the spring.
        if (valueAnim.value.isNaN()) return 0f
        val speed = abs(valueAnim.velocity) + abs(stretchAnim.value)
        return (speed * STRETCH_PER_STEP_PER_S).coerceAtMost(MAX_STRETCH) * squashScale
    }

    /**
     * [liveSize] for the flat pill, which is drawn inside the bar's clip: it
     * never exceeds the resting width or height, so a lifted pill cannot be
     * cut off at the top, bottom, or at the first and last tab.
     */
    fun flatSize(rest: Size, lifted: Size): Size {
        val s = liveSize(rest, lifted)
        return Size(
            s.width.coerceAtMost(rest.width),
            s.height.coerceAtMost(rest.height),
        )
    }

    /** The size the pill is drawn at this frame, between [rest] and [lifted]. */
    fun liveSize(rest: Size, lifted: Size): Size {
        val w = rest.width + (lifted.width - rest.width) * liftX
        val h = rest.height + (lifted.height - rest.height) * liftY
        val s = stretch()
        val o = abs(overAnim.value)
        // Ditarik melewati ujung: lensa memanjang ke arah tarikan dan sedikit memipih.
        return Size(
            w * (1f + s) + rest.width * OVER_WIDEN * o,
            h * (1f - s / 3f) * (1f - OVER_SQUASH * o),
        )
    }

    private companion object {
        /** Stretch per tab step a second: 12 % wider at 10 steps a second. */
        const val STRETCH_PER_STEP_PER_S = 0.012f
        const val MAX_STRETCH = 0.12f

        /** The lift is released this close (in tab steps) to the target tab. */
        const val LAND_STEPS = 0.05f

        /** Tambahan lebar lensa per langkah tab tarikan melewati ujung, sebagai bagian lebar tab. */
        const val OVER_WIDEN = 0.6f

        /** Berkurangnya tinggi lensa per langkah tab tarikan melewati ujung. */
        const val OVER_SQUASH = 0.12f
    }
}

/**
 * Places a pill-sized child centred on [center] at [size], inside a parent it
 * matches the size of — so the pill can move and grow past that parent's
 * edges without ever changing its measured size. Both read on layout, not
 * composition, so a frame of motion is a relayout and nothing more.
 */
internal fun Modifier.pillPlacement(center: () -> Offset, size: () -> Size): Modifier =
    layout { measurable, constraints ->
        val s = size()
        val w = s.width.roundToInt().coerceAtLeast(1)
        val h = s.height.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(constraints.minWidth, constraints.minHeight) {
            val c = center()
            placeable.place((c.x - w / 2f).roundToInt(), (c.y - h / 2f).roundToInt())
        }
    }

/** The flat pill: a fill and nothing else. */
@Composable
internal fun FlatSelectionPill(
    color: Color,
    modifier: Modifier,
    barGlass: LayerBackdrop? = null,
) {
    val shape = remember { RoundedCornerShape(percent = 50) }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val ind by AppSettings.glassIndicator.collectAsStateWithLifecycle()
    if (reduceDynamicBlur || !isGlassSupported()) {
        Box(modifier.background(color, shape))
        return
    }
    val page = LocalAppBackdrop.current
    val blurPx = with(LocalDensity.current) { INDICATOR_BLUR.toPx() * ind.blur }
    val backdrop = remember(page, barGlass) { PageAndBarBackdrop(page, barGlass) }
    // Frosted: halaman dan kaca bar di bawah indikator di-blur, lalu diberi
    // warna fill indikator di atasnya. Tab digambar di atas pill ini, jadi
    // ikon dan labelnya tetap tajam.
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(
                    brightness = INDICATOR_BRIGHTNESS * ind.brightness,
                    saturation = INDICATOR_SATURATION * ind.saturation,
                )
                blur(blurPx)
                // Lengkung lensa tetap ada saat indikator diam, tidak hanya saat digeser.
                if (ind.restLens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(
                        refractionHeight = PILL_LENS_HEIGHT.toPx() * ind.restRefractionWidth,
                        refractionAmount = PILL_LENS_AMOUNT.toPx() * ind.restRefraction,
                        depthEffect = ind.depth,
                        chromaticAberration = false,
                    )
                }
            },
            highlight = {
                Highlight.Ambient.copy(
                    width = 1.dp,
                    alpha = (REST_RIM_ALPHA * ind.rim).coerceIn(0f, 1f),
                )
            },
            shadow = null,
            innerShadow = {
                InnerShadow(
                    radius = REST_INNER_RADIUS * ind.innerRadius,
                    offset = DpOffset(0.dp, 0.dp),
                    alpha = (REST_INNER_ALPHA * ind.innerShadow).coerceIn(0f, 1f),
                )
            },
            onDrawSurface = {
                drawRect(
                    color = color.copy(alpha = (color.alpha * ind.fill).coerceIn(0f, 1f)),
                    size = size,
                )
            },
        ),
    )
}

/** Blur radius of the resting selection indicator. */
private val INDICATOR_BLUR = 10.dp

/** Saturation of the page and bar glass seen through the resting indicator. */
private const val INDICATOR_SATURATION = 2.4f

/** A small lift so the blurred backdrop under the indicator reads brighter than the bar. */
private const val INDICATOR_BRIGHTNESS = 0.05f

/**
 * The resting indicator's edge: an even rim and a soft inner shadow, so its top
 * and bottom read as curving inward even when no lens is running. The lifted
 * pill fades down to exactly these values, so the hand-off has no jump.
 */
private const val REST_RIM_ALPHA = 0.45f
private val REST_INNER_RADIUS = 5.dp
private const val REST_INNER_ALPHA = 0.55f

/** Inner shadow radius of the lifted pill at full material. */
private val LIFT_INNER_RADIUS = 10.dp

/** What the resting indicator blurs: the page, then the bar's own glass over it. */
private class PageAndBarBackdrop(
    private val page: Backdrop,
    private val bar: Backdrop?,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        with(page) { drawBackdrop(density, coordinates, layerBlock) }
        bar?.let { with(it) { drawBackdrop(density, coordinates, layerBlock) } }
    }
}

/**
 * The lifted pill: a lens over the tab bar that refracts the bar's own glass
 * and its tab row, drawn above both and free to stand past the bar's edges.
 *
 * Clear while it is lifted — the selection fill fades out as the material
 * comes on and back in as it goes — with a rim and a contact shadow that are
 * the first things shed on landing, and refraction that eases out behind them.
 * At the end of that it is drawing the bar, the fill and the tabs with no lens,
 * which is exactly what [FlatSelectionPill] under the tabs draws.
 */
@Composable
internal fun GlassSelectionPill(
    motion: GlassPillMotion,
    fillColor: Color,
    barGlass: LayerBackdrop,
    tabRow: LayerBackdrop,
    modifier: Modifier,
    /**
     * Colour the tab row is masked to inside the pill: the accent copy of the
     * row that Kyant's LiquidBottomTabs records for its lens. Unspecified keeps
     * the row's own colours.
     */
    iconTint: Color = Color.Unspecified,
) {
    val page = LocalAppBackdrop.current
    val ind by AppSettings.glassIndicator.collectAsStateWithLifecycle()
    val shape = remember { RoundedCornerShape(percent = 50) }
    // White rim on a dark bar, dark rim on a light one: a white edge over a
    // light page is invisible, so the lens would have nothing to bend.
    val darkGlass = glassContentColor() == Color.White
    val edge = if (darkGlass) {
        Color.White.copy(alpha = BAR_RIM_ALPHA)
    } else {
        Color.Black.copy(alpha = BAR_RIM_ALPHA * 0.7f)
    }
    // The tab row is drawn on top of the lens, not through it: refracting it
    // stretched the glyphs and flipped the labels near the pill's edge.
    val iconLiftMul = ind.iconLift
    val fillMul = ind.fill
    val tabsBackdrop = remember(tabRow, iconTint, motion, iconLiftMul) {
        PillTabsBackdrop(tabRow, iconTint) { 1f + PILL_ICON_LIFT_SCALE * iconLiftMul * motion.material }
    }
    val backdrop = remember(page, barGlass, tabsBackdrop, fillColor, edge, fillMul) {
        SelectionPillBackdrop(page, barGlass, tabsBackdrop, Color.Unspecified, edge) {
            fillColor.copy(alpha = (fillColor.alpha * fillMul * (1f - motion.material)).coerceIn(0f, 1f))
        }
    }
    Box(
        // Cross-fades into the blurred flat pill underneath as the blur returns.
        modifier.graphicsLayer { alpha = 1f - motion.blurMix }.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Dari lengkung saat diam ke lengkung saat terangkat, tanpa lompatan
                    // ke pil datar yang mengambil alih sesudah mendarat.
                    val restAmount = if (ind.restLens) PILL_LENS_AMOUNT.toPx() * ind.restRefraction else 0f
                    val restHeight = if (ind.restLens) PILL_LENS_HEIGHT.toPx() * ind.restRefractionWidth else 0f
                    val liftAmount = PILL_LENS_AMOUNT.toPx() * ind.liftRefraction
                    val liftHeight = PILL_LENS_HEIGHT.toPx() * ind.liftRefractionWidth
                    lens(
                        refractionHeight = restHeight + (liftHeight - restHeight) * motion.glassPresence,
                        refractionAmount = restAmount + (liftAmount - restAmount) * motion.material,
                        depthEffect = ind.depth,
                        chromaticAberration = ind.rainbow > 0f,
                        chromaticStrength = PILL_CHROMATIC_STRENGTH * ind.rainbow * motion.material,
                    )
                }
            },
            highlight = {
                val live = motion.material * rimOf(motion.glassPresence)
                Highlight.Ambient.copy(
                    width = 1.dp,
                    alpha = restBlend((REST_RIM_ALPHA * ind.rim).coerceIn(0f, 1f), live),
                )
            },
            shadow = {
                val alpha = motion.material * rimOf(motion.glassPresence)
                if (alpha > 0f) PILL_SHADOW.copy(alpha = (alpha * ind.shadow).coerceIn(0f, 1f)) else null
            },
            innerShadow = {
                val live = motion.material * rimOf(motion.glassPresence)
                InnerShadow(
                    radius = (REST_INNER_RADIUS + (LIFT_INNER_RADIUS - REST_INNER_RADIUS) * live) * ind.innerRadius,
                    offset = DpOffset(0.dp, 0.dp),
                    alpha = restBlend((REST_INNER_ALPHA * ind.innerShadow).coerceIn(0f, 1f), live),
                )
            },
        ),
    )
}

/**
 * The pill's lens, with the numbers of Kyant's Glass Bottom Bar tutorial:
 * `lens(16dp, 32dp)`. The amount is twice the height, so the band along the
 * edge is narrow but bends hard, which is what makes the bar's top and bottom
 * edges visibly warp where the pill stands past them.
 */
private val PILL_LENS_HEIGHT = 16.dp
private val PILL_LENS_AMOUNT = 32.dp

/** Ikon dan label di dalam pill terangkat membesar sebesar ini pada material penuh (Kyant: 1 -> 1.2). */
private const val PILL_ICON_LIFT_SCALE = 0.2f

/**
 * Rainbow fringe of the lifted pill, a fraction of the shader's full strength.
 * At 1 the dispersion paints a thick rainbow around the corners; this keeps
 * only a faint hint of it.
 */
private const val PILL_CHROMATIC_STRENGTH = 0.15f

/** The rim and its shadow are gone by the time the hand-off is 55% through. */
private fun rimOf(presence: Float): Float = ((presence - 0.45f) / 0.55f).coerceIn(0f, 1f)

/** [rest] at `live` 0, up to 1 at `live` 1. */
private fun restBlend(rest: Float, live: Float): Float = (rest + (1f - rest) * live).coerceIn(0f, 1f)

/** The bar's own edge, drawn into what the lens refracts so the lens bends it too. */
private val BAR_RIM_WIDTH = 1.dp
private const val BAR_RIM_ALPHA = 0.35f

/** How far the bar's shadow halo reaches past its edge, and how dark it starts. */
private val BAR_HALO_REACH = 8.dp
private const val BAR_HALO_ALPHA = 0.10f
private const val BAR_HALO_STEPS = 7

private val PILL_SHADOW = Shadow(
    radius = 12.dp,
    offset = DpOffset(0.dp, 2.dp),
    color = Color.Black.copy(alpha = 0.3f),
)

/**
 * What the pill refracts, bottom to top: the page, the bar's glass, the
 * selection fill, and the tab row. The fill sits under the tabs here for the
 * same reason the flat pill does — over them it would grey the glyphs.
 */
private class SelectionPillBackdrop(
    private val page: Backdrop,
    private val bar: LayerBackdrop,
    private val tabs: Backdrop,
    private val tint: Color,
    private val edge: Color,
    private val fill: () -> Color,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        with(page) { drawBackdrop(density, coordinates, layerBlock) }
        with(bar) { drawBackdrop(density, coordinates, layerBlock) }
        drawBarRim(bar, coordinates, edge)
        val color = fill()
        if (color.alpha > 0f) {
            // Past every edge: the recorded area includes the lens's padding.
            drawRect(
                color = color,
                topLeft = Offset(-size.width, -size.height),
                size = Size(size.width * 3, size.height * 3),
            )
        }
        if (tint != Color.Unspecified) {
            // The tab row, masked to one colour: what the pill shows of the
            // tabs under it, refracted by the lens.
            drawTinted(tint) { with(tabs) { drawBackdrop(density, coordinates, layerBlock) } }
        } else {
            with(tabs) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}

/**
 * The exported bar glass has no rim (it is recorded without one), so the lens
 * would refract a bar with no visible edge. This strokes the bar's pill edge at
 * the bar's own position, inside the pill's coordinate space, before the lens
 * bends everything.
 */
private fun DrawScope.drawBarRim(bar: LayerBackdrop, coordinates: LayoutCoordinates?, edge: Color) {
    val barCoordinates = bar.layerCoordinates ?: return
    val here = coordinates ?: return
    if (!barCoordinates.isAttached || !here.isAttached) return
    val offset = try {
        barCoordinates.localPositionOf(here)
    } catch (_: Exception) {
        here.positionInWindow() - barCoordinates.positionInWindow()
    }
    val barSize = barCoordinates.size.toSize()
    if (barSize.width <= 0f || barSize.height <= 0f) return
    val strokeWidth = BAR_RIM_WIDTH.toPx()
    val radius = minOf(barSize.width, barSize.height) / 2f
    val haloReach = BAR_HALO_REACH.toPx()
    val ring = haloReach / BAR_HALO_STEPS
    // Bright along the top and bottom edges, dim between.
    val rim = Brush.verticalGradient(
        colors = listOf(edge, edge.copy(alpha = edge.alpha * 0.3f), edge),
        startY = 0f,
        endY = barSize.height,
    )
    translate(-offset.x, -offset.y) {
        // The bar's soft shadow, which the exported glass leaves out: a dark
        // halo fading outward from the edge, so the pill's lens has something
        // to bend on the far side of the edge as well as on the near side.
        for (i in 1..BAR_HALO_STEPS) {
            val t = i / BAR_HALO_STEPS.toFloat()
            val grow = haloReach * t
            drawRoundRect(
                color = Color.Black.copy(alpha = BAR_HALO_ALPHA * (1f - t)),
                topLeft = Offset(-grow, -grow),
                size = Size(barSize.width + grow * 2f, barSize.height + grow * 2f),
                cornerRadius = CornerRadius(radius + grow, radius + grow),
                style = Stroke(width = ring),
            )
        }
        drawRoundRect(
            brush = rim,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = Size(barSize.width - strokeWidth, barSize.height - strokeWidth),
            cornerRadius = CornerRadius(radius - strokeWidth / 2f, radius - strokeWidth / 2f),
            style = Stroke(width = strokeWidth),
        )
    }
}

/**
 * The tab row under the lifted pill, masked to [tint] when set, with no
 * refraction. Digambar diperbesar terhadap pusat pill sebesar [iconScale],
 * jadi ikon dan label di dalam lens ikut membesar saat pill terangkat,
 * seperti LiquidBottomTab Kyant (1 -> 1.2 mengikuti tekanan).
 */
private class PillTabsBackdrop(
    private val tabs: Backdrop,
    private val tint: Color,
    private val iconScale: () -> Float = { 1f },
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val s = iconScale()
        val draw: DrawScope.() -> Unit = {
            scale(s, pivot = center) {
                with(tabs) { drawBackdrop(density, coordinates, layerBlock) }
            }
        }
        if (tint != Color.Unspecified) drawTinted(tint, draw) else draw()
    }
}

/** Draws [block] and recolours everything it painted to [tint], keeping its alpha. */
private fun DrawScope.drawTinted(tint: Color, block: DrawScope.() -> Unit) {
    val paint = Paint().apply { colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn) }
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(-size.width, -size.height, size.width * 2f, size.height * 2f), paint)
    block()
    canvas.restore()
}
