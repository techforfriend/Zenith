package com.music.zenith.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import coil3.compose.LocalPlatformContext
import com.music.zenith.ui.graphics.FADE_CURVE_STOPS
import com.music.zenith.ui.graphics.forPixelAccess
import com.music.zenith.ui.graphics.paletteSwatches
import com.music.zenith.ui.graphics.smootherStep
import com.music.zenith.ui.graphics.toImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.zenith.ui.graphics.ColorUtils
import com.music.zenith.ui.graphics.argbPixels
import com.music.zenith.ui.graphics.imageBitmapOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val FallbackColors = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

/** The four mesh colours, wrapped so the backdrop can skip recomposition. */
@Immutable
data class MeshPalette(
    val colors: List<Color>,
    val edge: List<Color> = emptyList(),
    val light: Boolean = false,
)

/**
 * The Apple Music "Now Playing" backdrop: four luminous colour blobs sampled
 * from the album art, drawn as soft radial gradients and blurred into a mesh.
 * Colour changes on track skip crossfade over ~1.4s instead of snapping.
 *
 * The blobs drift when there is a reason to — the player opening, or
 * [trackKey] changing — and then come to rest. They used to orbit forever,
 * which meant re-blurring a full-screen layer at display refresh rate for as
 * long as the player was up: the most expensive thing in the app, for motion
 * that reads as ambient at best and is invisible while the phone is in a
 * pocket. The settled frame looks the same; only the battery drain is gone.
 */
@Composable
fun MeshGradientBackground(
    palette: MeshPalette,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    driftMillis: Int = 8_000,
    /**
     * Keep the blobs orbiting instead of letting them settle.
     *
     * Off everywhere the mesh fills a screen, for the reason in the class note:
     * a full-screen blur re-drawn at refresh rate is the most expensive thing
     * this app does, and nobody is looking at it. On the Replay's cards it is
     * the opposite trade — the surface is a few hundred dp of a card the user
     * has deliberately opened and is looking straight at, the motion is what
     * makes the card feel like an object rather than a picture of one, and
     * "reduce animation" still stops it dead.
     */
    continuous: Boolean = false,
    /**
     * How far the blobs are smeared. The default is sized for a full screen;
     * a small surface needs proportionally less, or the four colours blend into
     * one wash before they reach its edges.
     */
    blurRadius: Dp = 64.dp,
    /**
     * Off for a surface that should read as a still image: colours snap
     * straight to target instead of crossfading, and the blobs never drift
     * on a [trackKey] change, only settling once on first composition. The
     * Replay page and its cards use this — a grid of these redrawing a
     * blurred layer every time a card is opened or swiped past was the
     * expensive case the class note above warns about, multiplied by however
     * many cards are on screen.
     */
    animated: Boolean = true,
    /**
     * How long a palette change takes to crossfade. A track skip wants it
     * brisk; a surface that changes colour on a timer wants it long enough
     * that one fade runs into the next and the colour never sits still.
     */
    colorFadeMillis: Int = 1400,
    /**
     * Holds the blobs where they are, without the settling drift that turning
     * [continuous] off would start. For a caller that stops the mesh for a
     * while — a scroll, the player up over it — and picks it up again after.
     */
    paused: Boolean = false,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    // Cover putih/pucat: warna dibuat kalem dan terang (abu kebiruan) seperti
    // Apple Music, bukan dipaksa gelap pekat yang bentrok dengan cover.
    val light = palette.light
    val tuned = (palette.colors.ifEmpty { FallbackColors } + FallbackColors)
        .take(4)
        .map { if (light) it.tunedLight() else it.tuned() }

    // Each colour slot crossfades independently when the track (palette) changes,
    // unless "reduce animation" is on, in which case colours snap straight to target.
    // Palet asli pertama langsung snap, bukan fade lama dari warna bawaan ke warna cover.
    // Perubahan berikutnya (ganti lagu) tetap fade seperti biasa.
    val isFallbackPalette = palette.colors.isEmpty() || palette.colors == FallbackColors
    var hasRealPalette by remember { mutableStateOf(!isFallbackPalette) }
    LaunchedEffect(isFallbackPalette) { if (!isFallbackPalette) hasRealPalette = true }
    val colorSpec: AnimationSpec<Color> =
        if (reduceAnimation || !animated || !hasRealPalette) snap() else tween(colorFadeMillis)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "meshColor$index").value
    }
    val baseColor by animateColorAsState(
        if (light) tuned.first().dimmedLight() else tuned.first().dimmed(),
        colorSpec,
        label = "meshBase",
    )

    // Read in the draw lambda, not here: an Animatable read during draw
    // invalidates only the drawing, leaving composition out of the loop.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey, reduceAnimation, continuous, animated, paused) {
        when {
            !animated || reduceAnimation -> phase.snapTo(0f)
            paused -> Unit
            // A full turn at a time, restarted rather than looped with an
            // infinite spec: the blobs' speeds are irrational multiples of each
            // other, so the pattern never repeats, and a linear phase keeps the
            // orbit even instead of easing to a halt each lap.
            continuous -> while (isActive) {
                phase.animateTo(
                    targetValue = phase.value + (2 * PI).toFloat(),
                    animationSpec = tween(driftMillis * 4, easing = LinearEasing),
                )
            }
            else -> phase.animateTo(
                targetValue = phase.value + DRIFT_RADIANS,
                animationSpec = tween(driftMillis, easing = FastOutSlowInEasing),
            )
        }
    }

    // Scale up slightly so the blur's clamped edges never show, then blur the
    // whole layer (RenderEffect, API 31+; a no-op below — the radial falloff
    // already reads soft there).
    //
    // Clipped on the way out, and from a layer of its own rather than by setting
    // `clip` on the one below: that one clips what is drawn *into* it, in its own
    // coordinates, and the scale is applied after — so the overhang the scale
    // creates survives it. This has to sit outside the scale to contain it.
    //
    // The overhang is a third of the backdrop's width and it is painted, not
    // transparent: whatever this is standing in gets it. Off a full-window sheet
    // that is the far side of the window and nobody ever saw it, which is how it
    // went unnoticed; in a pane beside a page it was a hand's width of gradient
    // laid over the feed.
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .background(baseColor)
            .blur(blurRadius),
    ) {
        // Most populous colours first, so they sit low where the cover fades into
        // the backdrop; the minor ones hide behind the artwork at the top.
        val anchors = listOf(
            Offset(0.25f, 0.80f),
            Offset(0.75f, 0.75f),
            Offset(0.80f, 0.20f),
            Offset(0.20f, 0.25f),
        )
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val blobRadius = listOf(0.74f, 0.62f, 0.52f, 0.48f)
        val blobAlpha = listOf(1.1f, 0.96f, 0.72f, 0.58f)
        val drift = phase.value

        animatedColors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.12f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.12f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * blobRadius[index]
            drawCircle(
                // Along smootherstep rather than a straight falloff: a straight one
                // stops dead at the rim, and that corner showed as a ring where each
                // blob ended, even through the blur.
                brush = Brush.radialGradient(
                    colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                        val t = i / FADE_CURVE_STOPS.toFloat()
                        t to color.copy(alpha = (BLOB_ALPHA * blobAlpha[index]).coerceAtMost(1f) * (1f - smootherStep(t)))
                    },
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Light scrim only: the colours are already deep, so a heavy one
        // would turn the mesh muddy instead of Apple Music's dark plum.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = if (light) 0f else 0.02f),
                    Color.Black.copy(alpha = if (light) 0.06f else 0.14f),
                ),
            ),
        )
    }
}

/** A blob's opacity at its centre. */
private const val BLOB_ALPHA = 0.85f

/** Share of near-white pixels from which a cover counts as a light one. */
private const val LIGHT_COVER_SHARE = 0.55f

/** True when the cover is mostly white or very pale, so deep tones would clash with it. */
internal fun isLightCover(source: ImageBitmap): Boolean {
    val w = source.width
    val h = source.height
    if (w < 8 || h < 8) return false
    val pixels = source.argbPixels()
    if (pixels.size != w * h) return false
    var pale = 0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        if (lo >= 205 && hi - lo <= 40) pale++
    }
    return pale >= pixels.size * LIGHT_COVER_SHARE
}

/** Muted, mid-light version of a swatch: keeps the hue, drops most of the saturation. */
private fun Color.tunedLight(): Color {
    val hsl = hsl()
    hsl[1] = (hsl[1] * 0.45f).coerceIn(0.08f, 0.26f)
    hsl[2] = 0.44f + 0.12f * hsl[2].coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmedLight(): Color {
    val hsl = hsl()
    hsl[2] = 0.38f
    return Color(ColorUtils.HSLToColor(hsl))
}

private const val EDGE_BINS = 8
private const val EDGE_ROWS_SHARE = 0.04f
private const val EDGE_SIDE_SHARE = 0.08f
private const val EDGE_MAX_LIGHTNESS = 0.52f
private const val EDGE_MEAN_MIX = 0.35f
private const val EDGE_RISE_START = 0.55f
private const val EDGE_DROP_SHARE = 0.34f
private const val EDGE_TINT_MIX = 0.3f
private const val EDGE_SMOOTH_MILLIS = 380f

internal fun edgeColorsOf(source: ImageBitmap): List<Color> {
    val w = source.width
    val h = source.height
    if (w < EDGE_BINS * 2 || h < 8) return emptyList()
    val pixels = source.argbPixels()
    if (pixels.size != w * h) return emptyList()
    val rows = (h * EDGE_ROWS_SHARE).toInt().coerceIn(1, h)
    val side = (w * EDGE_SIDE_SHARE).toInt()
    val span = w - side * 2
    val bins = List(EDGE_BINS) { bin ->
        val from = side + span * bin / EDGE_BINS
        val stop = (side + span * (bin + 1) / EDGE_BINS).coerceAtLeast(from + 1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in h - rows until h) {
            for (x in from until stop) {
                val p = pixels[y * w + x]
                red += (p shr 16) and 0xFF
                green += (p shr 8) and 0xFF
                blue += p and 0xFF
                count++
            }
        }
        val n = count.coerceAtLeast(1)
        Color((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
    }
    val mean = Color(
        (bins.sumOf { it.red.toDouble() } / bins.size).toFloat(),
        (bins.sumOf { it.green.toDouble() } / bins.size).toFloat(),
        (bins.sumOf { it.blue.toDouble() } / bins.size).toFloat(),
    )
    return bins.map { androidx.compose.ui.graphics.lerp(it, mean, EDGE_MEAN_MIX).edgeTuned() }
}

private fun Color.edgeTuned(): Color {
    val lightness = (maxOf(red, green, blue) + minOf(red, green, blue)) / 2f
    if (lightness <= EDGE_MAX_LIGHTNESS) return copy(alpha = 1f)
    val scale = EDGE_MAX_LIGHTNESS / lightness
    return Color(red * scale, green * scale, blue * scale)
}

@Composable
fun CoverEdgeBackdrop(
    edge: List<Color>,
    seam: () -> Float,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    frame: () -> ImageBitmap? = { null },
    frozen: Boolean = false,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    // Warna tepi dari frame klip yang sedang diputar. Frame dibaca lewat lambda
    // supaya layar induk tidak ikut recompose setiap frame baru.
    var liveEdge by remember { mutableStateOf<List<Color>>(emptyList()) }
    val currentFrame by androidx.compose.runtime.rememberUpdatedState(frame)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { currentFrame() }.collect { grabbed ->
            if (grabbed == null) {
                liveEdge = emptyList()
                return@collect
            }
            val computed = withContext(Dispatchers.Default) {
                if (isLikelyBlackFrame(grabbed)) emptyList() else edgeColorsOf(grabbed)
            }
            if (computed.isNotEmpty()) liveEdge = computed
        }
    }
    val target = remember(edge, liveEdge, tint) {
        val source = liveEdge.ifEmpty { edge }
        val mix = tint?.tuned()
        if (mix == null) {
            source
        } else {
            source.map { androidx.compose.ui.graphics.lerp(it, mix, EDGE_TINT_MIX) }
        }
    }
    // Warna yang benar-benar digambar: mendekati target tiap frame secara
    // eksponensial, jadi perubahan antar pengambilan frame tidak melompat.
    val shown = remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(target, reduceAnimation, frozen) {
        if (target.size < 2 || reduceAnimation || frozen || shown.value.size != target.size) {
            shown.value = target
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dtNs = if (last == 0L) 16_000_000L else (now - last).coerceAtMost(64_000_000L)
            last = now
            val k = 1f - kotlin.math.exp(-(dtNs / 1_000_000f) / EDGE_SMOOTH_MILLIS)
            val next = shown.value.mapIndexed { i, c ->
                androidx.compose.ui.graphics.lerp(c, target[i], k)
            }
            shown.value = next
            val settled = next.indices.all { i ->
                abs(next[i].red - target[i].red) < 0.004f &&
                    abs(next[i].green - target[i].green) < 0.004f &&
                    abs(next[i].blue - target[i].blue) < 0.004f
            }
            if (settled) {
                shown.value = target
                break
            }
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val drawn = shown.value
        if (drawn.size < 2) return@Canvas
        val seamY = seam().coerceIn(0f, size.height)
        if (seamY < 1f) return@Canvas
        val top = seamY * EDGE_RISE_START
        val bottom = (seamY + size.height * EDGE_DROP_SHARE).coerceAtMost(size.height)
        if (bottom - top < 1f) return@Canvas
        val seamAt = ((seamY - top) / (bottom - top)).coerceIn(0.05f, 1f)
        val riseEnd = seamAt * 0.6f
        val steps = 8
        val stops = ArrayList<Pair<Float, Color>>()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            stops += (riseEnd * t) to Color.Black.copy(alpha = smootherStep(t))
        }
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val keep = 1f - smootherStep(t)
            stops += (seamAt + (1f - seamAt) * t) to Color.Black.copy(alpha = keep * keep)
        }
        drawRect(brush = Brush.horizontalGradient(colors = drawn, startX = 0f, endX = size.width))
        drawRect(
            brush = Brush.verticalGradient(colorStops = stops.toTypedArray(), startY = top, endY = bottom),
            blendMode = BlendMode.DstIn,
        )
    }
}

/**
 * How long each palette in a [cyclingMeshPalette] is held, which is also how
 * long the fade into it takes — pass it as `colorFadeMillis` so one crossfade
 * runs straight into the next and the colour is never at rest.
 */
const val MESH_CYCLE_MILLIS = 3_600

/**
 * Zenith's own colours, for the surfaces that have no artwork to take them
 * from: the welcome pages and the sign-in card on Home. Starts on Apple
 * Music's red and wanders off from there.
 */
val WelcomeMeshPalettes: List<MeshPalette> = listOf(
    // Red, violet, ember.
    MeshPalette(listOf(Color(0xFFFA2D48), Color(0xFF7B2FF7), Color(0xFFFF7A45), Color(0xFF2E1A8A))),
    // Sunset.
    MeshPalette(listOf(Color(0xFFFF4E8A), Color(0xFFFF9A3C), Color(0xFFE0245E), Color(0xFF6A1B9A))),
    // Ocean.
    MeshPalette(listOf(Color(0xFF1E88E5), Color(0xFF00BFA5), Color(0xFF5E35B1), Color(0xFF0D47A1))),
    // Aurora.
    MeshPalette(listOf(Color(0xFF00C853), Color(0xFF00B8D4), Color(0xFF7C4DFF), Color(0xFF1A237E))),
    // Grape and rose.
    MeshPalette(listOf(Color(0xFFD500F9), Color(0xFFFF4081), Color(0xFF651FFF), Color(0xFF4A148C))),
    // Gold.
    MeshPalette(listOf(Color(0xFFFFB300), Color(0xFFFF5252), Color(0xFFF06292), Color(0xFF6D2E46))),
)

/**
 * Steps through [palettes] for as long as the caller is composed, one every
 * [stepMillis]. "Reduce animation" would make every step a snap, so there it
 * stays on the first palette instead. While [running] is off it holds the
 * palette it is on, and carries on from there once it is back.
 */
@Composable
fun cyclingMeshPalette(
    palettes: List<MeshPalette>,
    stepMillis: Int = MESH_CYCLE_MILLIS,
    running: Boolean = true,
): MeshPalette {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(reduceAnimation, stepMillis, running) {
        if (reduceAnimation || !running) return@LaunchedEffect
        while (isActive) {
            delay(stepMillis.toLong())
            step++
        }
    }
    return palettes[step % palettes.size]
}

/**
 * Palet cover yang sudah dihitung, per URL. Jadi nilai awal rememberArtworkColors supaya
 * mesh yang baru masuk composition (player dibuka) langsung memakai warna cover, bukan
 * mulai dari FallbackColors lalu fade pelan. Hanya diakses dari main thread.
 */
private val artworkPaletteCache = object : LinkedHashMap<String, MeshPalette>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MeshPalette>?): Boolean =
        size > 64
}

/**
 * Loads the artwork with Coil (software bitmap, thumbnail-sized) and pulls a
 * 4-colour palette out of it. Recomputes when [imageUrl] changes.
 *
 * A track's motion artwork is frequently lit nothing like its still sleeve —
 * a different shot, a different grade. [canvasFrame], a frame captured off
 * the playing clip once one is available, is quantised the same way and
 * takes over from there, crossfading in exactly like a track skip.
 */
@Composable
fun rememberArtworkColors(imageUrl: String?, canvasFrame: ImageBitmap? = null): MeshPalette {
    val context = LocalPlatformContext.current
    // Carried over from the last cover instead of reset to the stock colours, so a
    // track change fades from one real palette to the next rather than flashing
    // through the fallback purple and orange first.
    val heldPalette = remember { mutableStateOf(MeshPalette(FallbackColors)) }
    var palette by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { artworkPaletteCache[it] } ?: heldPalette.value)
    }
    LaunchedEffect(palette) { heldPalette.value = palette }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null) return@LaunchedEffect
        // Sudah dihitung sebelumnya: nilai awal di atas sudah benar, tak perlu muat ulang.
        if (artworkPaletteCache.containsKey(imageUrl)) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(128) // palette quality is fine at thumbnail size, and it's fast
            .forPixelAccess() // Palette needs pixel access
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toImageBitmap() ?: return@LaunchedEffect
        val computed = withContext(Dispatchers.Default) {
            MeshPalette(paletteOf(bitmap), edgeColorsOf(bitmap), isLightCover(bitmap))
        }
        artworkPaletteCache[imageUrl] = computed
        palette = computed
    }

    LaunchedEffect(canvasFrame) {
        val frame = canvasFrame ?: return@LaunchedEffect
        // Reject near-black frames (first read after surface recreation, before
        // ExoPlayer decodes real content).  A dark sleeve will still exceed the
        // threshold because compression noise pushes mean luminance above ~12.
        if (isLikelyBlackFrame(frame)) return@LaunchedEffect
        palette = withContext(Dispatchers.Default) {
            MeshPalette(paletteOf(frame), edgeColorsOf(frame), isLightCover(frame))
        }
    }
    return palette
}

/**
 * How far the blobs travel in one settle. A shade under half a turn: enough
 * that the backdrop visibly reacts to a track change, short of a full orbit
 * that would land the blobs back where they started.
 */
private const val DRIFT_RADIANS = (PI * 0.45f).toFloat()

/**
 * The player's four mesh colours for [bitmap], for surfaces that draw the mesh
 * somewhere this code doesn't run — the watch app is sent these rather than
 * running Palette on a watch.
 */
fun artworkMeshColors(bitmap: ImageBitmap): List<Color> = paletteOf(bitmap)

/**
 * Four mesh colours drawn from the artwork.
 *
 * The named swatches — vibrant, muted and friends — are a convenience over the
 * full set, and on dark or desaturated sleeves every vibrant slot comes back
 * null: Karan Aujla's marble interior fills two of the five. Topping the rest
 * up from [FallbackColors] is what left those covers sitting under the stock
 * purple. So the whole swatch list is read instead, and any shortfall is
 * derived from the art's own colours rather than borrowed.
 */
private fun paletteOf(source: ImageBitmap): List<Color> {
    val bitmap = source.bottomWeighted()
    fun swatchesOf(clearFilters: Boolean): List<Color> {
        val all = paletteSwatches(bitmap, 24, clearFilters)
        val total = all.sumOf { it.population }.coerceAtLeast(1)
        // Only specks are dropped; a small but vivid accent (the orange of a logo)
        // still deserves a blob. Ranking is by share weighted by vividness, so a
        // dominant black sleeve no longer pushes the coloured notes out.
        val major = all.filter { it.population >= total * MIN_SWATCH_SHARE }
        return major.ifEmpty { all }
            .sortedByDescending { swatchScore(it.rgb, it.population.toFloat() / total) }
            .map { Color(it.rgb) }
    }

    val found = swatchesOf(clearFilters = false).ifEmpty {
        // The default filter discards near-black and near-white, which on a
        // monochrome sleeve can be everything there is.
        swatchesOf(clearFilters = true)
    }

    val distinct = found.distinctEnough()
    return when {
        distinct.isEmpty() -> FallbackColors
        distinct.size >= 4 -> distinct.take(4)
        else -> distinct.expandedToFour()
    }
}

/** A swatch below this share of the sleeve is treated as noise. */
private const val MIN_SWATCH_SHARE = 0.03f

/**
 * Ranks a swatch by how much of the sleeve it covers and how visible its colour
 * is. Near-black and washed-out swatches score low however common they are, so
 * the mesh follows the colours the eye actually reads on the cover.
 */
private fun swatchScore(rgb: Int, share: Float): Float {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(rgb, it) }
    val vividness = hsl[1] * (1f - abs(2f * hsl[2] - 1f))
    return share.pow(0.6f) * (0.3f + vividness)
}

/** Drop near-duplicates, so the four blobs don't collapse into one wash. */
private fun List<Color>.distinctEnough(): List<Color> {
    val kept = mutableListOf<Color>()
    forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
    return kept
}

private fun Color.isCloseTo(other: Color): Boolean {
    val a = hsl()
    val b = other.hsl()
    val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
    return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
}

/** Fill the empty slots off the art itself, fanning hue and lightness out. */
private fun List<Color>.expandedToFour(): List<Color> {
    val out = toMutableList()
    var step = 1
    while (out.size < 4) {
        out += this[(out.size - size) % size].shifted(12f * step, 0.08f * step)
        step++
    }
    return out
}

/** The share of the sleeve, from its foot, that the palette leans on. */
private const val BOTTOM_PALETTE_SHARE = 0.4f

/** How many extra times that foot is counted, so the colours track where the cover meets the backdrop. */
private const val BOTTOM_PALETTE_COPIES = 1

/**
 * The same picture with its bottom rows repeated, so the quantiser sees more of
 * them. The backdrop meets the cover at its foot, so those are the colours that
 * should join up; the rest of the sleeve still counts, just for less.
 */
private fun ImageBitmap.bottomWeighted(): ImageBitmap {
    val w = width
    val h = height
    if (w < 2 || h < 8) return this
    val pixels = argbPixels()
    if (pixels.size != w * h) return this
    val rows = (h * BOTTOM_PALETTE_SHARE).toInt().coerceIn(1, h)
    val tail = pixels.copyOfRange((h - rows) * w, pixels.size)
    val out = IntArray(pixels.size + tail.size * BOTTOM_PALETTE_COPIES)
    pixels.copyInto(out)
    repeat(BOTTOM_PALETTE_COPIES) { tail.copyInto(out, pixels.size + it * tail.size) }
    return imageBitmapOf(out, w, h + rows * BOTTOM_PALETTE_COPIES)
}

private fun Color.shifted(hue: Float, lightness: Float): Color {
    val hsl = hsl()
    hsl[0] = (hsl[0] + hue) % 360f
    hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.hsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

/**
 * Apple Music's Now Playing mesh: the cover's own hues, taken down to a deep,
 * moderately saturated band instead of a luminous one. Lightness keeps the
 * source's ordering (brighter notes stay brighter) inside 0.16..0.32, and
 * neutral swatches stay neutral rather than being given a hue.
 */
private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = if (hsl[1] < 0.12f) hsl[1] else (hsl[1] * 1.15f).coerceIn(0.48f, 0.80f)
    hsl[2] = 0.20f + 0.16f * hsl[2].coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.14f
    return Color(ColorUtils.HSLToColor(hsl))
}
