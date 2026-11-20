package com.music.zenith.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.music.zenith.ui.graphics.FADE_CURVE_STOPS
import com.music.zenith.ui.graphics.smootherStep
import com.music.zenith.ui.rememberIsForeground
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.music.zenith.data.Http
import com.music.zenith.data.canvas.CanvasArtwork
import com.music.zenith.data.canvas.CanvasCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.roundToInt
import java.util.Locale

private const val TAG = "CanvasArtworkPlayer"

/**
 * How far the clip's bottom dissolve is lifted at the left and right edges,
 * as a share of the fade height. The centre keeps the video's bottom, so the
 * boundary reads as a V, as in Apple Music. Android 13+ only.
 */
private val CLIP_FADE_CURVE: Float get() = CanvasFadeTuning.effectiveCurve()

/** Share of the old fade height that is kept, so the dissolve starts a little lower. */
private val CLIP_FADE_HEIGHT_SCALE: Float get() = CanvasFadeTuning.effectiveHeight()

/**
 * Kekuatan kurva fade bawah klip. 1 = smootherstep asli. Makin besar, alpha turun
 * lebih cepat dengan ekor lebih panjang di dalam band yang SAMA, jadi fade terasa
 * lebih kuat dan halus tanpa menggeser batas blur ke atas.
 */
private val CLIP_FADE_POWER: Float get() = CanvasFadeTuning.power.floatValue

/**
 * How long a clip gets to paint itself onto a surface it was just handed back
 * before the still art is brought in behind it instead. Long enough to cover a
 * decoder being re-created from cold, short enough that a clip which is never
 * coming back does not sit there as a hole for the length of a glance.
 */
private const val REPAINT_TIMEOUT_MS = 700L

/** A periodic capture slower than this doubles the wait before the next one. */
private const val FRAME_CAPTURE_BUDGET_MS = 8L

/** The longest the capture loop backs off to when readbacks are running slow. */
private const val FRAME_CAPTURE_MAX_WAIT_MS = 1_000L

/**
 * Sisi terpanjang frame yang disalin tiap frame selagi cover terbang ke/dari mini
 * player dan klip tetap berjalan: cukup tajam untuk gerakan, murah untuk disalin.
 */
private const val LIVE_STILL_MAX_SIDE = 640

/** How long after a clip leaves the screen its player is released — see its onDispose. */
private const val DEFERRED_RELEASE_MS = 1_000L

/** Sisi terpanjang frame yang diambil saat skip: cukup tajam untuk dianimasikan, murah dibaca dari GPU. */
private const val LIVE_GRAB_PX = 720

private val mainHandler = Handler(Looper.getMainLooper())

/** Whether and when a clip plays on — see the playback gate and [onResumed]. */
private class ResumeGate {
    /** Whether the last pause was for a transition; starts true for the player opening. */
    var heldForTransition = true

    /** Whether the frame on screen is one a caller holds a still of. */
    var holdingStill = false

    /** Jumlah frame video yang sudah mendarat di surface; dipakai untuk melewati salinan yang sama. */
    var videoFrames = 0L
}

/** What a clip's player needs to leave the screen without blocking it. */
private class Teardown {
    var disposed = false

    /**
     * A surface taken away while the player was closing or collapsing, kept
     * alive rather than handed back to ExoPlayer. Told the surface is gone,
     * ExoPlayer blocks the main thread until the decoder has stopped writing
     * to it, which is the decoder being torn down. Left holding a surface
     * that still exists, it can be stopped and released at leisure, or move
     * onto the next surface it is given; this one is released after either.
     */
    var orphan: SurfaceTexture? = null

    fun releaseOrphan() {
        orphan?.release()
        orphan = null
    }
}

/**
 * The looping video that plays over a track's cover art, sized to fill and
 * clipped by whatever laid it out.
 *
 * A second, deliberately unassuming ExoPlayer: silent, with its audio track
 * switched off entirely so a clip's soundtrack is never even fetched, and no
 * audio attributes — taking focus here would duck the music this is decorating.
 * It follows the transport, so pausing the track stops the sleeve moving too.
 *
 * Nothing is drawn until the first frame arrives, and the fade in from there
 * means a failed or slow clip simply leaves the still art showing rather than
 * flashing a black square over it. [CanvasArtwork.fallbackUrl] gets one try if
 * the first rendition won't decode.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AndroidCanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    /** Align a contained portrait clip to the top; other callers retain centered FIT. */
    alignPortraitTop: Boolean = false,
    /** The decoded video's display aspect, or zero until Media3 knows it. */
    onAspectRatioChanged: (Float) -> Unit = {},
    /** The full player bounds required before a portrait hero may reveal its first frame. */
    portraitRevealBounds: IntSize = IntSize.Zero,
    /** Fades a full-player portrait clip away as the existing sleeve collapses. */
    presentationAlpha: () -> Float = { 1f },
    /** Fires once the clip has an actual frame on screen, and again if it drops back to none. */
    onRenderedChanged: (Boolean) -> Unit = {},
    /** A single frame off the playing clip, for callers that want to re-tint around it. */
    onFrameCaptured: (ImageBitmap) -> Unit = {},
    /**
     * Keep calling [onFrameCaptured] every so many milliseconds instead of
     * only once — for a caller re-tinting its backdrop off a playing clip,
     * which is worth following as it plays rather than settling on whatever
     * colours its opening frame happened to have. That holds for every
     * source, not just
     * [CanvasSource.SPOTIFY][com.music.zenith.data.canvas.CanvasSource.SPOTIFY]:
     * a clip is a clip, and one that pans or cuts changes colour under its own
     * still sleeve exactly the same way regardless of who published it. Null
     * when a caller has nothing worth re-tinting off a moving colour at all —
     * re-reading a texture off the GPU costs a frame stall, so this stays
     * opt-in rather than always-on.
     */
    refreshFrameEveryMs: Long? = null,
    /**
     * The longest edge of the bitmap [onFrameCaptured] is handed.
     *
     * This is the whole cost of following a clip. `getBitmap()` with no
     * arguments hands back a copy at the view's own size — full-bleed, so most
     * of a phone screen, five or six megabytes read back off the GPU and
     * allocated afresh on every call. Nobody wants that resolution: the one
     * caller there is averages the frame down to a handful of colours. Asking
     * for a small copy instead makes the readback scale during the blit, which
     * is what turns a refresh from something worth doing every few seconds into
     * something affordable several times a second.
     *
     * 0 for no frames at all: even a small one is read back on the main
     * thread, and a caller with no use for it should not pay that on every
     * fade-in.
     */
    frameCapturePx: Int = FRAME_CAPTURE_PX,
    /**
     * How much of whatever is behind the clip it is currently hiding: 0 while
     * nothing is drawn, ramping to 1 as the first frame fades in, and back down
     * if it drops out again.
     *
     * A caller that stacks a still image under the clip needs this to take that
     * image back out from under it, and cannot get there from
     * [onRenderedChanged] alone — that fires when the fade *starts*. It matters
     * most with [bottomFade]: one gradient over each of two stacked layers
     * leaves the lower one showing through the upper one instead of the backdrop
     * showing through both, so the still art stays half-visible over the clip
     * for as long as it is left lit underneath.
     */
    onCoverChanged: (Float) -> Unit = {},
    /**
     * Share of the clip's height, measured up from its bottom edge, over which
     * it dissolves to nothing — 0 for a hard edge. See [setBottomFade] for why
     * this is a parameter here rather than a mask the caller could draw.
     */
    bottomFade: Float = 0f,
    /** Optional end of the fade in view pixels; defaults to the view's bottom edge. */
    bottomFadeEndPx: Float? = null,
    /** Blur radius in px, applied as a RenderEffect on the TextureView's own node (API 31+). */
    blurPx: () -> Float = { 0f },
    /** Pengali [bottomFade] yang dibaca tiap frame, untuk fade yang ikut animasi. */
    bottomFadeScale: () -> Float = { 1f },
    /**
     * 0..1: seberapa jauh klip portrait yang di-contain sudah tumbuh menjadi crop
     * layar penuh. Dibaca tiap frame supaya perubahan contain -> cover mengikuti
     * progres panel, bukan berganti mode dalam satu frame.
     */
    fillProgress: () -> Float = { 0f },
    /**
     * Halts decoding for the length of a caller-driven transition — the sleeve
     * collapsing into the queue or lyrics panel and back — rather than only at
     * the two ends of it. That collapse is driven by the same clock as this
     * clip's own fade, and a decoder left running through it competes with the
     * slide for the same frame budget; the stutter that produced this flag was
     * the decode, not the animation. The clip keeps its last frame on screen
     * while paused, so there is nothing to fade back in once it lifts.
     */
    pausedForTransition: Boolean = false,
    /** See [CanvasArtworkPlayer]'s parameter of the same name. */
    resumeDelayMs: Long = 0L,
    startPositionMs: Long = 0L,
    revealInstantly: Boolean = false,
    captureStill: Boolean = false,
    onStillCaptured: (ImageBitmap?, Long) -> Unit = { _, _ -> },
    onResumed: () -> Unit = {},
    frameGrab: CanvasFrameGrab? = null,
) {
    val context = LocalContext.current

    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    // Aspect of the clip itself. Zero until the decoder reports it, which is
    // also the signal that there is nothing sensible to crop to yet.
    var clipAspect by remember(canvas) { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var textureView by remember(canvas) { mutableStateOf<TextureView?>(null) }
    // Frames are counted rather than flagged, because [rendered] cannot answer
    // the question the repaint below has to ask: "did a frame land on *this*
    // surface", not "has one ever landed".
    var frameTick by remember(canvas) { mutableIntStateOf(0) }
    // Bumped each time the view is handed a surface to replace one that was
    // taken away — which, in practice, means each time the app comes back from
    // off screen. Not bumped for the first surface of all, which arrives with
    // nothing needing doing to it. See the repaint effect below.
    var surfaceGeneration by remember(canvas) { mutableIntStateOf(0) }
    val currentContentMode by rememberUpdatedState(contentMode)
    val currentAlignPortraitTop by rememberUpdatedState(alignPortraitTop)
    val currentPortraitRevealBounds by rememberUpdatedState(portraitRevealBounds)
    val currentPresentationAlpha by rememberUpdatedState(presentationAlpha)
    val currentPausedForTransition by rememberUpdatedState(pausedForTransition)
    val currentFillProgress by rememberUpdatedState(fillProgress)
    val reportAspect by rememberUpdatedState(onAspectRatioChanged)
    val currentStartPositionMs by rememberUpdatedState(startPositionMs)
    val currentResumeDelayMs by rememberUpdatedState(resumeDelayMs)
    val reportResumed by rememberUpdatedState(onResumed)
    val teardown = remember { Teardown() }
    val gate = remember { ResumeGate() }

    // Pembaca frame hidup untuk pemanggil: dibaca sinkron di thread utama, hanya
    // sekali per skip, jadi biayanya sebatas satu frame kecil. Null bila belum
    // ada frame yang benar-benar tampil.
    DisposableEffect(frameGrab, canvas) {
        val grab: () -> ImageBitmap? = {
            val view = textureView
            if (!rendered || view == null) {
                null
            } else {
                // LIVE_GRAB_PX untuk sisi PENDEK view. View hero portrait setinggi
                // player, sedangkan isi klipnya hanya sebagian; dengan sisi panjang
                // sebagai patokan, isinya cuma ~330px lalu di-upscale 3x (buram).
                val shortSide = minOf(view.width, view.height).coerceAtLeast(1)
                val grabPx = (LIVE_GRAB_PX.toLong() * maxOf(view.width, view.height) / shortSide).toInt()
                view.captureAt(grabPx, clipAspect, currentContentMode, currentAlignPortraitTop)
                    ?.asImageBitmap()
            }
        }
        frameGrab?.grab = grab
        onDispose {
            frameGrab?.let { if (it.grab === grab) it.grab = null }
        }
    }

    val player = remember {
        ExoPlayer.Builder(context)
            // Shares the app's one OkHttp client, as everything that fetches
            // over the network here does — and wrapped in CanvasCache so a
            // loop past the first is read off disk rather than re-fetched;
            // see that object's doc for why this matters far more here than
            // it would for a clip played once.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(CanvasCache.dataSourceFactory(OkHttpDataSource.Factory(Http.client))),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .apply { CanvasPlaybackLimits.maxFrameRate?.let(::setMaxVideoFrameRate) }
                    .build()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                val aspect = if (width.isFinite() && width > 0f && videoSize.height > 0) {
                    width / videoSize.height
                } else 0f
                if (aspect != clipAspect) rendered = false
                clipAspect = aspect
                reportAspect(aspect)
                textureView?.applyContentTransform(clipAspect, currentContentMode, currentAlignPortraitTop, currentFillProgress())
            }

            override fun onPlayerError(error: PlaybackException) {
                // One retry, at the other rendition. If that is the one that
                // just failed there is nowhere left to go: leave the still
                // art up rather than looping through a broken URL.
                val alternate = canvas.fallbackUrl
                if (alternate != null && alternate != url) {
                    url = alternate
                } else {
                    rendered = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Not release(), which blocks the main thread until the decoder
            // has been torn down — 40-50ms, landing on the last frame of the
            // player closing. stop() hands the decoder back on the player's
            // own thread; the release left after that is a thread quitting,
            // and runs once the close is long over. The surface goes with it,
            // for the reason [Teardown.orphan] gives.
            teardown.disposed = true
            player.stop()
            mainHandler.postDelayed(
                {
                    player.release()
                    teardown.releaseOrphan()
                },
                DEFERRED_RELEASE_MS,
            )
        }
    }

    LaunchedEffect(url) {
        rendered = false
        clipAspect = 0f
        reportAspect(0f)
        val item = MediaItem.Builder().setUri(url)
        mimeTypeOf(url)?.let { item.setMimeType(it) }
        // From where a still of this clip was taken, if there is one, so the
        // first frame painted is that still and takes over from it unseen.
        val startAt = currentStartPositionMs
        gate.holdingStill = startAt > 0L
        player.setMediaItem(item.build(), startAt)
        player.prepare()
    }

    // Gated on the app being on screen as well as on the caller's own state.
    //
    // This is a video decoder. Left to [isPlaying] alone it goes on decoding
    // frames into a surface nobody can see for as long as the composition is
    // alive — which, with the phone in a pocket and music playing, is the whole
    // album. Worse on a detail page, whose caller passes a constant `true`
    // because "the page is only up while it's being read": true of a page being
    // looked at, not of one left open behind a locked screen.
    //
    // Held inside this component rather than asked of each caller, so no call
    // site can forget it. The player now runs continuously in the foreground
    // regardless of playback state, so coming back from background always has
    // a surface ready and `onRenderedFirstFrame()` fires naturally.
    val foreground = rememberIsForeground()
    LaunchedEffect(foreground, pausedForTransition) {
        val play = foreground && !pausedForTransition
        val afterTransition = gate.heldForTransition
        gate.heldForTransition = pausedForTransition
        // Held a moment longer on the way out of a transition, if asked: the
        // player has only just settled, and the clip starting with it reads
        // as part of the movement rather than as the clip.
        if (play && afterTransition && currentResumeDelayMs > 0L) delay(currentResumeDelayMs)
        player.playWhenReady = play
    }

    // The frame on screen, kept for the caller to stand in for the clip while
    // it moves — see [captureStill]. Read off the decoder's own output, not the
    // view: the copy runs on the render thread and leaves this one free.
    val reportStill by rememberUpdatedState(onStillCaptured)
    LaunchedEffect(captureStill, rendered) {
        if (!captureStill) return@LaunchedEffect
        // The pause was asked for this frame. Let the decoder's last frame land.
        withFrameMillis { }
        withFrameMillis { }
        // Nothing on screen to keep — a clip that has only just mounted, its
        // caller still holding the last still. Leave that one standing.
        val view = textureView
        if (!rendered || view == null) return@LaunchedEffect
        val position = player.currentPosition
        val still = view.copyStill(player.videoSize, clipAspect, contentMode, alignPortraitTop)
        if (still != null) gate.holdingStill = true
        Log.d(TAG, "still captured: ${still?.let { "${it.width}x${it.height}" }} at $position")
        reportStill(still?.asImageBitmap(), position)
        // Klip yang tidak dijeda (perjalanan ke/dari mini player) terus berjalan,
        // jadi frame yang dipakai sleeve ikut diperbarui tiap frame dan cover
        // yang terbang tetap bergerak. Untuk collapse lyrics/queue klip dijeda,
        // sehingga satu salinan di atas sudah cukup.
        var copiedFrames = gate.videoFrames
        while (!currentPausedForTransition) {
            withFrameMillis { }
            // Klip hanya 24-30 fps, sedangkan layar 60-120 Hz. Menyalin frame yang
            // sama lagi di tiap vsync cuma membuang PixelCopy dan alokasi bitmap.
            if (gate.videoFrames == copiedFrames) continue
            copiedFrames = gate.videoFrames
            val live = view.copyStill(
                player.videoSize,
                clipAspect,
                contentMode,
                alignPortraitTop,
                LIVE_STILL_MAX_SIDE,
            ) ?: continue
            reportStill(live.asImageBitmap(), player.currentPosition)
        }
    }

    // Repaint onto a surface that has just been handed back. A TextureView's
    // SurfaceTexture does not survive every background/layout transition, and
    // ExoPlayer's old "first frame rendered" callback says nothing about the
    // replacement surface. Keep the still artwork visible while waiting, ask
    // the decoder to paint at its current position, and only hand back to the
    // clip when onSurfaceTextureUpdated confirms real pixels below.
    LaunchedEffect(surfaceGeneration) {
        if (surfaceGeneration == 0) return@LaunchedEffect
        rendered = false
        val before = frameTick
        if (player.playbackState != Player.STATE_IDLE) {
            player.seekTo(player.currentPosition)
        }
        delay(REPAINT_TIMEOUT_MS)
        if (frameTick == before) {
            rendered = false
        }
    }

    val reportRendered by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(rendered) {
        reportRendered(rendered)
        if (!rendered) return@LaunchedEffect
        // Let the surface actually paint the frame that just triggered this
        // before reading it back — grabbing it the instant the callback fires
        // can still catch the previous, empty buffer.
        if (frameCapturePx <= 0) return@LaunchedEffect
        withFrameMillis { }
        val view = textureView ?: return@LaunchedEffect
        val bitmap = view.captureAt(frameCapturePx, clipAspect, contentMode, alignPortraitTop)
        if (bitmap != null) {
            Log.d(TAG, "frame captured after rendered=true, size=${bitmap.width}x${bitmap.height}")
            onFrameCaptured(bitmap.asImageBitmap())
        } else {
            Log.w(TAG, "frame capture returned null after rendered=true")
        }
    }

    // The opt-in follow-up to the capture above, for a caller that asked for
    // one — see [refreshFrameEveryMs]. A separate effect rather than a loop
    // folded into the one above: that one is keyed on [rendered] so it fires
    // again on every fade-in, and this one only needs to start once a fade-in
    // has actually happened and then keep going for as long as it holds.
    // Read through rememberUpdatedState: at a few captures a second the loop
    // must hand frames to the caller's current lambda, not the one it started with.
    val currentOnFrameCaptured by rememberUpdatedState(onFrameCaptured)
    LaunchedEffect(rendered, refreshFrameEveryMs, frameCapturePx, clipAspect, contentMode, alignPortraitTop) {
        val interval = refreshFrameEveryMs ?: return@LaunchedEffect
        if (frameCapturePx <= 0) return@LaunchedEffect
        Log.d(TAG, "periodic frame refresh started, interval=$interval")
        if (!rendered) return@LaunchedEffect
        val maxWait = maxOf(interval, FRAME_CAPTURE_MAX_WAIT_MS)
        var wait = interval
        while (isActive) {
            delay(wait)
            // Waits for a real frame, so nothing is read back while the app is
            // in the background or the screen is off.
            withFrameMillis { }
            val view = textureView ?: continue
            val startNs = System.nanoTime()
            val bitmap = view.captureAt(frameCapturePx, clipAspect, contentMode, alignPortraitTop)
            val costMs = (System.nanoTime() - startNs) / 1_000_000L
            if (bitmap != null) {
                currentOnFrameCaptured(bitmap.asImageBitmap())
            } else {
                Log.w(TAG, "periodic frame capture returned null")
            }
            // A slow readback stalls the main thread: back off instead of piling on.
            wait = if (costMs > FRAME_CAPTURE_BUDGET_MS) (wait * 2).coerceAtMost(maxWait) else interval
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = if (revealInstantly) snap() else tween(durationMillis = 160),
        label = "canvasAlpha",
    )

    // Published rather than left for the caller to mirror with a second
    // animation off [onRenderedChanged]: one fade, one account of how far along
    // it is. Zeroed on the way out, or a caller would be left holding something
    // hidden behind a clip that is no longer mounted.
    val reportCover by rememberUpdatedState(onCoverChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * currentPresentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            // The parent owns the still/canvas handoff. Never leave it holding
            // a Success from a player or TextureView that no longer exists.
            reportRendered(false)
            reportCover(0f)
            reportAspect(0f)
        }
    }

    AndroidView(
        factory = { viewContext ->
            val texture = TextureView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Blend rather than punch a hole: the still sleeve stays
                // visible underneath for the length of the fade.
                isOpaque = false
                this.alpha = 0f
                player.setVideoTextureView(this)
                // setVideoTextureView installs ExoPlayer's own listener, and
                // the player has to keep it — it is how the surface reaches
                // the video renderer at all. So wrap it rather than replace
                // it: everything is passed straight through, and the one
                // callback that matters here is noted on the way past.
                //
                // Asking the lifecycle instead would be simpler and wrong. The
                // surface comes back on the first traversal after the activity
                // is visible, which is *after* ON_RESUME — a repaint fired
                // there lands on the placeholder surface and the real one
                // arrives blank a moment later.
                val delegate = surfaceTextureListener
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    /** Whether the next surface is a replacement for one taken away. */
                    private var replacing = false

                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        delegate?.onSurfaceTextureAvailable(surface, width, height)
                        // The player has moved onto the new surface, so the
                        // one it was left holding can go.
                        teardown.releaseOrphan()
                        // The first surface needs nothing: prepare() paints it.
                        if (!replacing) return
                        replacing = false
                        Log.d(TAG, "surface recreated (gen $surfaceGeneration), waiting for frame")
                        surfaceGeneration++
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        delegate?.onSurfaceTextureSizeChanged(surface, width, height)
                        if (currentContentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect in 0f..1f) {
                            rendered = false
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        replacing = true
                        Log.d(TAG, "surface destroyed, rendered=$rendered")
                        // The old buffer is gone now, not when/if ExoPlayer
                        // later reports another first frame. Restore the still
                        // artwork immediately so an empty replacement surface
                        // can never become the only visible artwork layer.
                        rendered = false
                        // Going with the player — see [Teardown.orphan].
                        if (currentPausedForTransition || teardown.disposed) {
                            teardown.orphan = surface
                            return false
                        }
                        return delegate?.onSurfaceTextureDestroyed(surface) ?: true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        delegate?.onSurfaceTextureUpdated(surface)
                        gate.videoFrames++
                        // A frame while playing is the clip moving on from its still.
                        if (gate.holdingStill && player.playWhenReady) {
                            gate.holdingStill = false
                            reportResumed()
                        }
                        // This callback is the proof that the current
                        // TextureView, rather than some previously destroyed
                        // surface, contains a drawable video buffer.
                        // In portrait-fit mode the frame is not handed to the
                        // still artwork until its aspect and transform are ready.
                        if (!rendered) {
                            val transformed = textureView?.applyContentTransform(
                                clipAspect, currentContentMode, currentAlignPortraitTop, currentFillProgress(),
                            ) == true
                            val portrait = currentContentMode == CanvasContentMode.FIT_PORTRAIT &&
                                clipAspect > 0f && clipAspect < 1f
                            val expected = currentPortraitRevealBounds
                            val view = textureView
                            val layoutReady = !portrait ||
                                (expected != IntSize.Zero && view?.width == expected.width &&
                                    view?.height == expected.height)
                            if ((currentContentMode == CanvasContentMode.CROP || transformed) && layoutReady) {
                                rendered = true
                                frameTick++
                                Log.d(TAG, "first frame on surface (tick $frameTick, gen $surfaceGeneration)")
                            }
                        }
                    }
                }
            }
            textureView = texture
            // Wrapped on every API level so there is one view tree to reason
            // about: below API 31 the frame is what draws [bottomFade], and
            // above it the frame is just a box around the texture.
            FadingBottomFrame(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(texture)
            }
        },
        update = { frame ->
            val view = frame.getChildAt(0) as TextureView
            val applied = frame.applied
            // Set on the view itself. A Compose alpha layer over a TextureView
            // is not reliably composited, and this is the same fade either way.
            view.alpha = if (contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect <= 0f) {
                0f
            } else {
                // Called here, in the view's update, so a fade driven by the
                // player's collapse re-runs this block rather than
                // recomposing the player around it.
                alpha * presentationAlpha()
            }
            // Only when something they are made of has changed. The fade above
            // re-runs this block on every frame of a collapse or of the player
            // closing into the mini player, and each pass built a fresh matrix,
            // gradient and three RenderEffects for a picture that hadn't
            // moved — and re-setting the effect makes the node rebuild it.
            val fill = fillProgress().coerceIn(0f, 1f)
            if (applied.transformDiffers(clipAspect, contentMode, alignPortraitTop, view.width, view.height, fill)) {
                view.applyContentTransform(clipAspect, contentMode, alignPortraitTop, fill)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Read here so the blur follows the panel's progress every frame.
                val blur = (blurPx() * CanvasFadeTuning.blurScale.floatValue)
                    .let { if (it < 0.5f) 0f else it }
                val fadeNow = bottomFade * bottomFadeScale().coerceIn(0f, 1f)
                // Ujung fade ikut turun mengikuti dasar klip yang tumbuh memenuhi view.
                val fadeEnd = bottomFadeEndPx?.let { it + (bounds.height - it) * fill }
                if (applied.fadeDiffers(fadeNow, bounds, fadeEnd, blur)) {
                    view.setBottomFade(fadeNow, bounds, fadeEnd, blur)
                }
            } else {
                frame.fadeFraction = bottomFade * bottomFadeScale().coerceIn(0f, 1f)
                frame.fadeEndPx = bottomFadeEndPx
            }
        },
        modifier = modifier.onSizeChanged { bounds = it },
    )
}

/**
 * A frame off the clip, no bigger than [maxPx] on its longest edge — see
 * [CanvasArtworkPlayer]'s `frameCapturePx`.
 *
 * The aspect is kept rather than squared off. Nothing downstream draws this,
 * but everything downstream *averages* it, and squashing one axis would quietly
 * reweight which part of the frame each average is mostly made of.
 *
 * Null whenever the view has no frame to give — it is laid out but not yet
 * measured, or its surface has gone. A caller that gets null should keep what
 * it already had; the next tick will have one.
 */
private fun TextureView.captureAt(
    maxPx: Int,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Bitmap? {
    val viewWidth = width
    val viewHeight = height
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val scale = maxPx.toFloat() / maxOf(viewWidth, viewHeight)
    return runCatching {
        // getBitmap hands back the texture as the decoder painted it, stretched
        // to the view's bounds. The view's setTransform (cover/contain) is a
        // display-only matrix and is not part of that readback, so read with an
        // identity transform and run the very same matrix over the result
        // (see shownFrame). Otherwise the frame comes out stretched, or cropped
        // from the wrong place, compared with what is on screen.
        // The pre-31 bottom mask is on the parent frame and the RenderEffect
        // fade is on the view's node; neither is baked into the sampled frame.
        val viewTransform = getTransform(null)
        setTransform(Matrix())
        val raw: Bitmap? = try {
            if (scale >= 1f) {
                getBitmap()
            } else {
                getBitmap(
                    (viewWidth * scale).roundToInt().coerceAtLeast(1),
                    (viewHeight * scale).roundToInt().coerceAtLeast(1),
                )
            }
        } finally {
            setTransform(viewTransform)
        }
        if (raw == null) return null
        val frame = shownFrame(raw, clipAspect, contentMode, alignPortraitTop)
        if (contentMode != CanvasContentMode.FIT_PORTRAIT || clipAspect >= 1f || clipAspect <= 0f) {
            return frame
        }

        // getBitmap includes the view's transform. Read back only at the small
        // requested size, then exclude the transparent contain margins before
        // handing pixels to the mesh or legacy palette (both average RGB).
        val viewAspect = viewWidth.toFloat() / viewHeight
        val contentWidth = if (clipAspect < viewAspect) frame.height * clipAspect else frame.width.toFloat()
        val contentHeight = if (clipAspect < viewAspect) frame.height.toFloat() else frame.width / clipAspect
        val left = ceil((frame.width - contentWidth) / 2f).toInt().coerceIn(0, frame.width - 1)
        val top = if (alignPortraitTop) 0 else
            ceil((frame.height - contentHeight) / 2f).toInt().coerceIn(0, frame.height - 1)
        val right = (frame.width - left).coerceAtLeast(left + 1)
        val bottom = if (alignPortraitTop) contentHeight.toInt().coerceIn(1, frame.height) else
            (frame.height - top).coerceAtLeast(top + 1)
        Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }.getOrNull()
}

/**
 * The clip's current frame at the size it is shown, for a caller to draw in
 * its place — see [AndroidCanvasArtworkPlayer]'s `captureStill`.
 *
 * Copied off the decoder's last buffer with [PixelCopy], which on API 34+
 * runs on the render thread and leaves the main thread alone, and comes back
 * as the picture itself: no view transform, no transparent margins, the
 * clip's own shape, for the caller to crop exactly as the view does. Where
 * that copy fails, read back off the view instead, as [captureAt] does.
 */
private suspend fun TextureView.copyStill(
    video: VideoSize,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
    maxSide: Int = 0,
): Bitmap? {
    val texture = surfaceTexture ?: return null
    val videoWidth = (video.width * video.pixelWidthHeightRatio).roundToInt()
    val videoHeight = video.height
    if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return null
    // No bigger than the view shows it: across its width when contained, and
    // covering it when cropped.
    val contained = contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect > 0f && clipAspect < 1f
    val scale = minOf(
        1f,
        if (contained) {
            width.toFloat() / videoWidth
        } else {
            maxOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        },
    )
    val shrink = if (maxSide > 0) minOf(1f, maxSide.toFloat() / maxOf(videoWidth, videoHeight)) else 1f
    val bitmap = Bitmap.createBitmap(
        (videoWidth * scale * shrink).roundToInt().coerceAtLeast(1),
        (videoHeight * scale * shrink).roundToInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val result = runCatching {
        suspendCancellableCoroutine { continuation ->
            val surface = Surface(texture)
            PixelCopy.request(
                surface,
                bitmap,
                { result ->
                    // Let go here rather than on cancellation: the copy may
                    // still be reading from it.
                    surface.release()
                    if (continuation.isActive) continuation.resume(result)
                },
                mainHandler,
            )
        }
    }.getOrDefault(PixelCopy.ERROR_UNKNOWN)
    if (result == PixelCopy.SUCCESS) return bitmap
    Log.w(TAG, "still copy failed ($result), reading the view instead")
    bitmap.recycle()
    return captureAt(maxOf(width, height), clipAspect, contentMode, alignPortraitTop)
}

/**
 * Big enough that averaging it is stable, small enough that reading it back off
 * the GPU is not an event. Every consumer reduces this to a handful of colours.
 */
private const val FRAME_CAPTURE_PX = 128

/**
 * A TextureView stretches its content to its own bounds. Compensate with a
 * transform: cover by default, or contain for a portrait clip when the hero
 * explicitly requests it. An unknown size clears the old transform.
 */
private fun TextureView.applyContentTransform(
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
    fill: Float = 0f,
): Boolean {
    val matrix = contentMatrix(width, height, clipAspect, contentMode, alignPortraitTop, fill)?.also { m ->
        // Geser Y positif: video ikut membesar ke bawah (zoom dari atas) agar
        // ada gambar yang bisa dilarutkan di bawah dasar klip aslinya.
        val extra = CanvasFadeTuning.offsetDp.floatValue * resources.displayMetrics.density
        if (extra > 0.5f && alignPortraitTop &&
            contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        ) {
            val v = FloatArray(9)
            m.getValues(v)
            val bottom = v[Matrix.MSCALE_Y] * height
            if (bottom > 1f) {
                val s = (bottom + extra) / bottom
                m.postScale(s, s, width / 2f, 0f)
            }
        }
    }
    if (matrix == null) {
        setTransform(Matrix())
        return false
    }
    setTransform(matrix)
    return true
}

/**
 * The cover/contain matrix for content of [clipAspect] in a [width] x [height]
 * box, or null while the size or aspect is unknown. One definition for the live
 * view and for [shownFrame], so a captured frame can never disagree with the
 * picture on screen. Every term scales with the box, so it holds at any size.
 */
private fun contentMatrix(
    width: Int,
    height: Int,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
    fill: Float = 0f,
): Matrix? {
    if (width <= 0 || height <= 0 || !clipAspect.isFinite() || clipAspect <= 0f) return null
    val viewAspect = width.toFloat() / height
    val pivotX = width / 2f
    val pivotY = if (alignPortraitTop && contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f) {
        0f
    } else height / 2f
    val fit = contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
    val base = Matrix().apply {
        if (fit && clipAspect > viewAspect) {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        } else if (fit) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else if (clipAspect > viewAspect) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        }
    }
    if (!fit || fill <= 0.001f) return base
    // Klip portrait yang di-contain tumbuh menjadi crop layar penuh: interpolasi kedua
    // matriks supaya gambar meluncur, bukan melompat saat mode berganti.
    val cover = contentMatrix(width, height, clipAspect, CanvasContentMode.CROP, false) ?: return base
    val from = FloatArray(9)
    val to = FloatArray(9)
    base.getValues(from)
    cover.getValues(to)
    val t = fill.coerceAtMost(1f)
    for (i in 0 until 9) from[i] += (to[i] - from[i]) * t
    return Matrix().apply { setValues(from) }
}

/**
 * [raw] (the decoder's frame stretched to the view's bounds) as the view shows
 * it: through the same matrix, same size, with transparent contain margins.
 * Returned as is while the clip's aspect is not known yet.
 */
private fun shownFrame(
    raw: Bitmap,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Bitmap {
    val matrix = contentMatrix(raw.width, raw.height, clipAspect, contentMode, alignPortraitTop)
        ?: return raw
    val shown = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
    Canvas(shown).drawBitmap(raw, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    raw.recycle()
    return shown
}

/**
 * Dissolves the clip's bottom edge into whatever is behind it.
 *
 * Done here, on the view's own RenderNode, rather than with a DstIn mask in the
 * caller's draw scope: a TextureView's frames are composited from its surface
 * and a Compose blend drawn over the node simply doesn't reach them — the mask
 * lands on the layer around the video and leaves the video's own hard edge
 * exactly where it was.
 *
 * [RenderEffect] is API 31+; below that [FadingBottomFrame] does the same job
 * the older way, with a saveLayer and a Porter-Duff mask.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun TextureView.setBottomFade(
    fraction: Float,
    bounds: IntSize,
    endPx: Float?,
    blurPx: Float = 0f,
) {
    val offsetPx = CanvasFadeTuning.offsetDp.floatValue * resources.displayMetrics.density
    val endY = ((endPx ?: bounds.height.toFloat()) + offsetPx)
        .coerceIn(0f, bounds.height.toFloat())
    // The blur lives on the view's own RenderNode, like the fade: a Compose
    // blur layer around a TextureView leaves its frames sharp. Blur first,
    // then the fade mask, so the edge dissolves the blurred picture.
    val content = if (blurPx > 0.5f) {
        RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    } else {
        RenderEffect.createOffsetEffect(0f, 0f)
    }
    if (fraction <= 0.001f || endY <= 0f) {
        setRenderEffect(if (blurPx > 0.5f) content else null)
        return
    }
    // Along smootherstep, as the still sleeve's banner dissolves, so the clip
    // and a still of it standing in for it end the same way.
    val fadeH = (endY * fraction.coerceAtMost(1f) * CLIP_FADE_HEIGHT_SCALE).coerceAtLeast(1f)
    val gradient: Shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        curvedFadeShader(bounds.width.toFloat(), endY, fadeH, (fadeH + endY * 0.15f) * CLIP_FADE_CURVE)
    } else {
        LinearGradient(
            0f,
            endY - fadeH,
            0f,
            endY,
            IntArray(FADE_CURVE_STOPS + 1) { i ->
                android.graphics.Color.argb(
                    Math.pow(
                        (1f - smootherStep(i / FADE_CURVE_STOPS.toFloat())).toDouble(),
                        CLIP_FADE_POWER.toDouble(),
                    ).toFloat(),
                    0f,
                    0f,
                    0f,
                )
            },
            FloatArray(FADE_CURVE_STOPS + 1) { i -> i / FADE_CURVE_STOPS.toFloat() },
            Shader.TileMode.CLAMP,
        )
    }
    // createOffsetEffect(0, 0) is the identity effect over the node's own
    // content, which is the only way to name "what this view drew" as the
    // destination of a blend.
    setRenderEffect(
        RenderEffect.createBlendModeEffect(
            content,
            RenderEffect.createShaderEffect(gradient),
            BlendMode.DST_IN,
        ),
    )
}

/**
 * The curved bottom dissolve as an AGSL alpha mask, in the view's own pixels.
 * Each column's smootherstep band ends [lift] higher the further it is from
 * the centre (a parabola), so the fade boundary dips in the middle.
 */
private const val CURVED_FADE_AGSL = """
uniform float width;
uniform float endY;
uniform float fadeH;
uniform float lift;
uniform float power;
uniform float curveExp;
half4 main(float2 p) {
    float u = clamp(p.x / width, 0.0, 1.0) * 2.0 - 1.0;
    float t = clamp((p.y - (endY - fadeH - lift * pow(abs(u), curveExp))) / fadeH, 0.0, 1.0);
    float s = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    return half4(0.0, 0.0, 0.0, pow(1.0 - s, power));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun curvedFadeShader(width: Float, endY: Float, fadeH: Float, lift: Float): RuntimeShader =
    RuntimeShader(CURVED_FADE_AGSL).apply {
        setFloatUniform("width", width.coerceAtLeast(1f))
        setFloatUniform("endY", endY)
        setFloatUniform("fadeH", fadeH.coerceAtLeast(1f))
        setFloatUniform("lift", lift)
        setFloatUniform("power", CLIP_FADE_POWER)
        setFloatUniform("curveExp", CanvasFadeTuning.curveExp.floatValue.coerceAtLeast(0.5f))
    }

/**
 * The pre-[Build.VERSION_CODES.S] bottom fade: the same dissolve
 * [setBottomFade] gets from a [RenderEffect], done the way it was done before
 * there was one.
 *
 * Draw the child into an offscreen layer, paint a gradient over that layer with
 * [PorterDuff.Mode.DST_IN], then compose the result down. Because the layer is
 * this group's — not the TextureView's own node — the video frames are inside it
 * by the time the mask lands, which is exactly what a Compose blend over the
 * texture cannot achieve.
 *
 * It costs a full-screen offscreen buffer per frame, so it stays off entirely
 * while [fadeFraction] is zero: with no fade asked for this is a plain
 * FrameLayout and `dispatchDraw` takes the ordinary path.
 */
/**
 * What the update block last applied to the clip's view, so it re-applies a
 * transform or a fade only when one of its inputs has actually changed.
 */
private class AppliedLook {
    private var aspect = Float.NaN
    private var mode: CanvasContentMode? = null
    private var alignTop = false
    private var width = -1
    private var height = -1
    private var fillT = Float.NaN
    private var offsetT = Float.NaN

    private var fade = Float.NaN
    private var fadeBounds = IntSize(-1, -1)
    private var fadeEnd: Float? = Float.NaN
    private var fadeBlur = Float.NaN
    private var fadeTune = 0

    fun transformDiffers(
        clipAspect: Float,
        contentMode: CanvasContentMode,
        alignPortraitTop: Boolean,
        viewWidth: Int,
        viewHeight: Int,
        fill: Float = 0f,
    ): Boolean {
        if (clipAspect == aspect && contentMode == mode && alignPortraitTop == alignTop &&
            viewWidth == width && viewHeight == height && fill == fillT &&
            CanvasFadeTuning.offsetDp.floatValue == offsetT
        ) return false
        offsetT = CanvasFadeTuning.offsetDp.floatValue
        aspect = clipAspect
        mode = contentMode
        alignTop = alignPortraitTop
        width = viewWidth
        height = viewHeight
        fillT = fill
        return true
    }

    fun fadeDiffers(fraction: Float, bounds: IntSize, endPx: Float?, blur: Float = 0f): Boolean {
        val tune = CanvasFadeTuning.signature()
        if (fraction == fade && bounds == fadeBounds && endPx == fadeEnd && blur == fadeBlur &&
            tune == fadeTune
        ) return false
        fadeTune = tune
        fade = fraction
        fadeBounds = bounds
        fadeEnd = endPx
        fadeBlur = blur
        return true
    }
}

private class FadingBottomFrame(context: Context) : FrameLayout(context) {
    /** See [AppliedLook]. */
    val applied = AppliedLook()

    /** Share of the height, from the bottom, over which the child dissolves. */
    var fadeFraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            // A software layer would defeat the point — the texture has to stay
            // hardware-composited — so this is only ever the invalidate.
            gradient = null
            invalidate()
        }
    /** Optional video bottom in this frame's pixels; null retains the historical view bottom. */
    var fadeEndPx: Float? = null
        set(value) {
            if (value == field) return
            field = value
            gradient = null
            invalidate()
        }

    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var gradient: LinearGradient? = null
    private var gradientHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val fade = fadeFraction
        val endY = fadeEndPx?.coerceIn(0f, height.toFloat()) ?: height.toFloat()
        if (fade <= 0.001f || endY <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        // Along smootherstep, the same dissolve the still artwork's banner uses,
        // so neither end of it shows as a line across the video.
        val shader = gradient?.takeIf { gradientHeight == height } ?: LinearGradient(
            0f,
            endY * (1f - fade),
            0f,
            endY,
            IntArray(FADE_CURVE_STOPS + 1) { i ->
                android.graphics.Color.argb(1f - smootherStep(i / FADE_CURVE_STOPS.toFloat()), 0f, 0f, 0f)
            },
            FloatArray(FADE_CURVE_STOPS + 1) { i -> i / FADE_CURVE_STOPS.toFloat() },
            Shader.TileMode.CLAMP,
        ).also {
            gradient = it
            gradientHeight = height
        }
        maskPaint.shader = shader
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
    }
}

/**
 * Apple serves HLS, Tidal and the community index serve MP4. Naming the type
 * saves ExoPlayer a sniff, and an unrecognised URL is left for it to work out.
 */
private fun mimeTypeOf(url: String): String? {
    val path = url.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

/** The phone's decoder behind [CanvasArtworkPlayer] — see [PlayerHost.CanvasVideo]. */
@Composable
internal fun AndroidCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    AndroidCanvasArtworkPlayer(
        canvas = spec.canvas,
        isPlaying = spec.isPlaying,
        modifier = modifier,
        contentMode = spec.contentMode,
        alignPortraitTop = spec.alignPortraitTop,
        onAspectRatioChanged = spec.onAspectRatioChanged,
        portraitRevealBounds = spec.portraitRevealBounds,
        presentationAlpha = spec.presentationAlpha,
        onRenderedChanged = spec.onRenderedChanged,
        onFrameCaptured = spec.onFrameCaptured,
        refreshFrameEveryMs = spec.refreshFrameEveryMs,
        frameCapturePx = spec.frameCapturePx,
        onCoverChanged = spec.onCoverChanged,
        bottomFade = spec.bottomFade,
        bottomFadeEndPx = spec.bottomFadeEndPx,
        blurPx = spec.blurPx,
        bottomFadeScale = spec.bottomFadeScale,
        fillProgress = spec.fillProgress,
        pausedForTransition = spec.pausedForTransition,
        resumeDelayMs = spec.resumeDelayMs,
        startPositionMs = spec.startPositionMs,
        revealInstantly = spec.revealInstantly,
        captureStill = spec.captureStill,
        onStillCaptured = spec.onStillCaptured,
        onResumed = spec.onResumed,
        frameGrab = spec.frameGrab,
    )
}

/**
 * A ceiling on the frame rate canvas videos are decoded at, or null for none.
 * Left unset on the phone; the Android TV app sets it, where a looping 60 fps
 * clip behind the player costs GPU the television does not have to spare.
 */
object CanvasPlaybackLimits {
    @Volatile
    var maxFrameRate: Int? = null
}
