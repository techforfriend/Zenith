package com.music.zenith.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.data.settings.GlassClarity
import com.music.zenith.data.settings.GlassPreset
import com.music.zenith.ui.components.backdrop.Backdrop
import com.music.zenith.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.zenith.ui.components.backdrop.backdrops.layerBackdrop

/**
 * Permukaan kaca untuk tombol bulat kontrol lirik, mengikuti pengaturan glass
 * app seperti tombol back melayang: aktif hanya bila Liquid Glass hidup dan
 * didukung, memakai nilai tint / rim / shadow dari pengaturan glass, plus rim
 * dan hairline yang sama.
 *
 * Resepnya sama persis dengan tombol back melayang ([liquidGlass]: blur, lensa,
 * saturasi, rim, shadow, frosted, clarity, semuanya dari pengaturan Liquid
 * Glass), tetapi membiaskan [source], rekaman daftar lirik, bukan halaman di
 * bawah player. Mengembalikan null bila kaca mati / tidak didukung / Reduce
 * dynamic blur aktif, supaya pemanggil memakai disc bawaannya.
 */
@Composable
fun lyricsGlassDiscSurface(active: Boolean, source: Backdrop): Modifier? {
    val glassOn = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    // Pengaturan glass tetap khusus tombol lirik, tidak mengikuti pengaturan user:
    // preset Zenith (terang/gelap), Glass clear 0%. Zona lensa dibuat sempit (~9dp
    // dari tepi, disc hanya berjari-jari 17dp) dengan pergeseran sedang, supaya
    // teks mulai membelok tepat di pinggir tombol. Zona yang melebihi jari-jari
    // membuat teks di rim tersampling dari luar rekaman (hilang) dan baru terlihat
    // di tengah.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val lyricsClarity = remember { GlassClarity(0f) }
    val lyricsCustom = remember(darkTheme) {
        GlassPreset.DEFAULT.customFor(darkTheme).copy(
            refraction = 1.2f,
            refractionWidth = 0.5f,
        )
    }
    val lift by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "lyricsGlassDiscActive",
    )
    if (!glassOn || reduceDynamicBlur) return null

    val shape = CircleShape
    val tintAlpha = ((lyricsClarity.tintAlpha + 0.10f * lift) * lyricsCustom.tint).coerceIn(0f, 1f)
    return Modifier
        .clip(shape)
        .liquidGlass(
            shape = shape,
            surfaceTint = Color(0xFF121212).copy(alpha = tintAlpha),
            source = source,
            clarityOverride = lyricsClarity,
            customOverride = lyricsCustom,
            frostedOverride = false,
        )
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * Merekam [content] (daftar lirik) ke [backdrop] supaya tombol kaca di
 * sebelahnya bisa membiaskannya. Tanpa rekaman bila kaca mati / tidak
 * didukung / Reduce dynamic blur aktif, jadi tidak ada biaya saat dimatikan.
 */
@Composable
fun lyricsGlassSource(content: Modifier, backdrop: LayerBackdrop): Modifier {
    val glassOn = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    return if (glassOn && !reduceDynamicBlur) content.layerBackdrop(backdrop) else content
}
