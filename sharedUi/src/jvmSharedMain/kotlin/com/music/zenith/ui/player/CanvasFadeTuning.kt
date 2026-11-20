package com.music.zenith.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Nilai fade bawah klip Apple yang bisa disetel langsung lewat popup debug di
 * player utama. Dibaca oleh CanvasArtworkPlayer, jadi perubahan slider langsung
 * terlihat. Setelah nilai pas, jadikan default permanen lalu matikan [ENABLED].
 */
object CanvasFadeTuning {
    /** Tampilkan tombol dan popup debug di player utama. */
    const val ENABLED = true

    const val DEFAULT_HEIGHT_SCALE = 0.6f
    const val DEFAULT_CURVE = 0.8f
    const val DEFAULT_POWER = 1.3f

    /** Panjang fade: makin besar, batas blur makin naik. */
    val heightScale = mutableFloatStateOf(DEFAULT_HEIGHT_SCALE)

    /** Seberapa tinggi sisi kiri dan kanan larut dibanding tengah (bentuk V). */
    val curve = mutableFloatStateOf(DEFAULT_CURVE)

    /** Kekuatan kurva alpha di dalam band yang sama. */
    val power = mutableFloatStateOf(DEFAULT_POWER)

    fun reset() {
        heightScale.floatValue = DEFAULT_HEIGHT_SCALE
        curve.floatValue = DEFAULT_CURVE
        power.floatValue = DEFAULT_POWER
    }

    /** Berubah setiap kali salah satu nilai berubah; dibaca blok update view klip. */
    fun signature(): Int =
        java.util.Objects.hash(heightScale.floatValue, curve.floatValue, power.floatValue)
}

private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

/** Tombol "Fade" kecil di kanan atas player yang membuka panel penyetel fade. */
@Composable
internal fun CanvasFadeDebugTuner() {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Popup(
            alignment = Alignment.TopEnd,
            offset = with(density) { IntOffset((-16).dp.roundToPx(), 120.dp.roundToPx()) },
            properties = PopupProperties(focusable = false),
        ) {
            Text(
                text = if (open) "Tutup" else "Fade",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable { open = !open }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = with(density) { IntOffset(0, (-40).dp.roundToPx()) },
                properties = PopupProperties(focusable = false),
            ) {
                FadeTunerPanel()
            }
        }
    }
}

@Composable
private fun FadeTunerPanel() {
    val height = CanvasFadeTuning.heightScale
    val curve = CanvasFadeTuning.curve
    val power = CanvasFadeTuning.power
    Column(
        modifier = Modifier
            .width(320.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Canvas fade tuner",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        TunerRow("Tinggi (batas blur)", height.floatValue, 0.30f..1.0f) { height.floatValue = it }
        TunerRow("Lekuk V (sisi)", curve.floatValue, 0f..1.5f) { curve.floatValue = it }
        TunerRow("Power (kekuatan)", power.floatValue, 0.6f..3.0f) { power.floatValue = it }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "T ${fmt(height.floatValue)}  C ${fmt(curve.floatValue)}  P ${fmt(power.floatValue)}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Reset",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .clickable { CanvasFadeTuning.reset() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun TunerRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Text(fmt(value), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        TunerSlider(value, range, onChange)
    }
}

@Composable
private fun TunerSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    fun setAt(x: Float) {
        onChange(range.start + span * (x / widthPx).coerceIn(0f, 1f))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(range) { detectTapGestures { setAt(it.x) } }
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragStart = { setAt(it.x) }) { change, _ ->
                    setAt(change.position.x)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(Color.White, RoundedCornerShape(50)),
        )
        Box(
            Modifier
                .offset { IntOffset((fraction * widthPx - 10.dp.toPx()).roundToInt(), 0) }
                .size(20.dp)
                .background(Color.White, CircleShape),
        )
    }
}