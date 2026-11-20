package com.music.zenith.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.R
import com.music.zenith.data.canvas.CanvasProvider
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * Which animated-cover provider is asked first, second, and so on.
 *
 * Same frosted alert as [LyricsSourcesDialog]. Drag a row by its handle to
 * move it; the order shown is the order [CanvasRepository][com.music.zenith.data.canvas.CanvasRepository]
 * tries them in.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun CanvasProvidersDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val savedOrder by AppSettings.canvasProviderOrder.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)
    val motion = rememberMacOSPopupState(onClosed = onDismiss)
    BackHandler { motion.requestClose() }
    val touch = rememberJellyMenuTouch()

    Box(
        modifier = modifier
            .fillMaxSize()
            .macOSPopupScrim(motion, SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = motion::requestClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .macOSPopupMotion(motion)
                .iosMenuJelly(touch, 0.5f, 0.5f)
                .clip(shape)
                .alertSurface(hazeState, frosted = true, shape = shape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .macOSPopupContent(motion)
                .iosMenuTouchHost(touch, iosMenuColors().pressed),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.canvas_provider_order),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.canvas_provider_order_subtitle),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            IosMenuScrollEnvironment(touch) {
                Column {
                    ReorderableProviderList(
                        order = savedOrder,
                        onReorder = AppSettings::setCanvasProviderOrder,
                    )

                    AlertRule()
                    AlertAction(
                        label = stringResource(R.string.reset_to_default),
                        emphasised = false,
                        onClick = AppSettings::resetCanvasProviderOrder,
                    )
                    AlertRule()
                    AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = motion::requestClose)
                }
            }
        }
    }
}

/**
 * The drag-reorderable list. Only four rows, so it needs neither a cap nor an
 * edge auto-scroll. The mechanics are those of the lyrics source list: the drag
 * keeps two numbers (distance travelled and the slot it began on) and derives
 * everything else from them. The finished order is written back on release only.
 */
@Composable
private fun ReorderableProviderList(
    order: List<CanvasProvider>,
    onReorder: (List<CanvasProvider>) -> Unit,
) {
    var liveOrder by remember(order) { mutableStateOf(order) }
    var dragged by remember { mutableStateOf<CanvasProvider?>(null) }
    var totalDrag by remember { mutableStateOf(0f) }
    var startIndex by remember { mutableStateOf(0) }
    var pitchPx by remember { mutableStateOf(0f) }
    var lockedPitchPx by remember { mutableStateOf(0f) }

    fun advanceDrag(delta: Float) {
        val provider = dragged ?: return
        val pitch = lockedPitchPx
        if (pitch <= 0f) return
        var index = liveOrder.indexOf(provider)
        if (index < 0) return

        totalDrag = (totalDrag + delta).coerceIn(
            -startIndex * pitch,
            (liveOrder.lastIndex - startIndex) * pitch,
        )

        while (true) {
            val travelled = totalDrag / pitch
            val moved = (index - startIndex).toFloat()
            if (travelled > moved + CANVAS_SWAP_THRESHOLD && index < liveOrder.lastIndex) {
                liveOrder = liveOrder.toMutableList().apply { add(index + 1, removeAt(index)) }
                index++
            } else if (travelled < moved - CANVAS_SWAP_THRESHOLD && index > 0) {
                liveOrder = liveOrder.toMutableList().apply { add(index - 1, removeAt(index)) }
                index--
            } else {
                break
            }
        }
    }

    Column {
        liveOrder.forEach { provider ->
            key(provider) {
                val isDragging = provider == dragged
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .onSizeChanged { pitchPx = it.height.toFloat() }
                        .graphicsLayer {
                            translationY = if (isDragging) {
                                totalDrag - (liveOrder.indexOf(provider) - startIndex) * lockedPitchPx
                            } else {
                                0f
                            }
                        },
                ) {
                    AlertRule()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ACTION_HEIGHT)
                            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = SfIcons.DragHandle,
                            contentDescription = stringResource(R.string.drag_to_reorder),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier
                                .size(40.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragged = provider
                                            totalDrag = 0f
                                            startIndex = liveOrder.indexOf(provider)
                                            lockedPitchPx = pitchPx
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            advanceDrag(delta.y)
                                        },
                                        onDragEnd = {
                                            dragged = null
                                            totalDrag = 0f
                                            onReorder(liveOrder)
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            totalDrag = 0f
                                            liveOrder = order
                                        },
                                    )
                                }
                                .padding(11.dp),
                        )
                        Text(
                            text = (liveOrder.indexOf(provider) + 1).toString(),
                            modifier = Modifier.width(22.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                        Text(
                            text = provider.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                }
            }
        }
    }
}

/** Past half a row, so a finger's shake can't flip a row back and forth. */
private const val CANVAS_SWAP_THRESHOLD = 0.6f