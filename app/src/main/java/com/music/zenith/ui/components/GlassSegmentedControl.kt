package com.music.zenith.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Segmented control dengan pill kaca ala iOS 26.
 *
 * Diam, pill putih (gelap di tema gelap) seperti biasa. Saat berpindah segmen,
 * pill terangkat jadi kaca: hampir bening, membelokkan track dan label di
 * bawahnya, lalu kembali pekat begitu mendarat. Label di atas pill memudar
 * selagi kaca menyala, jadi yang terlihat adalah label hasil refraksi.
 *
 * Segmen berbagi lebar sama rata. Kalau label tidak muat, atau kaca tidak
 * didukung / "Reduce dynamic blur" menyala, memakai [PillSegmentedControl].
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Mode Liquid Glass penuh: track kaca bergradasi dan pill yang selalu berupa lensa kaca. */
    liquid: Boolean = false,
) {
    if (options.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inset = with(density) { GLASS_TRACK_PADDING.toPx() }
        val labelPadding = with(density) { GLASS_LABEL_PADDING.toPx() }
        val room = constraints.maxWidth.toFloat() - inset * 2f
        val widest = remember(options, style, measurer, density) {
            options.maxOf { label ->
                measurer.measure(label, style, maxLines = 1, softWrap = false).size.width + labelPadding * 2f
            }
        }
        val label: @Composable (Int, Color) -> Unit = { index, color ->
            Text(
                text = options[index],
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (widest * options.size > room) {
            // Tidak muat sama lebar: tetap liquid, tapi track-nya bisa digeser.
            LiquidScrollSegmentedControl(
                count = options.size,
                selection = selectedIndex,
                onSelect = onSelect,
                minSegmentWidth = with(density) { widest.toDp() },
                enabled = enabled,
                content = label,
            )
            return@BoxWithConstraints
        }
        LiquidSegmentedControl(
            count = options.size,
            selection = selectedIndex,
            onSelect = onSelect,
            enabled = enabled,
            content = label,
        )
    }
}

/** Gaya segmented control app, dipasang ke komponen bersama lewat [LocalSegmentedControlStyle]. */
object AppSegmentedControlStyle : SegmentedControlStyle {
    @Composable
    override fun Render(
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier,
        enabled: Boolean,
    ) = GlassSegmentedControl(options, selectedIndex, onSelect, modifier, enabled)
}

private val GLASS_TRACK_PADDING = 4.dp
private val GLASS_LABEL_PADDING = 18.dp