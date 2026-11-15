package com.music.zenith.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import com.music.zenith.data.lyrics.LyricLine

/**
 * Pengait renderer lirik Motion. Diisi oleh modul app (MainActivity);
 * bila null, panel memakai SweptLyricLine bawaan.
 * Argumen: baris, posisi playback, style, warna belum dinyanyikan, warna aktif, modifier.
 */
val LocalMotionLyricLine = staticCompositionLocalOf<
    (@Composable (LyricLine, () -> Long, TextStyle, Color, Color, Modifier) -> Unit)?
> { null }

/**
 * Pengait permukaan kaca untuk tombol bulat kontrol lirik (globe, terjemahan,
 * panah kembali). Diisi oleh modul app (MainActivity) supaya tombol itu
 * mengikuti pengaturan Liquid Glass app. Argumen: apakah tombol sedang aktif.
 * Mengembalikan null bila kaca mati / tidak didukung / Reduce dynamic blur
 * aktif, atau bila pengait tidak diisi (desktop): panel lalu memakai disc Haze
 * bawaan.
 */
val LocalLyricsGlassDisc = staticCompositionLocalOf<
    (@Composable (Boolean) -> Modifier?)?
> { null }

/**
 * Pengait sumber kaca tombol lirik: dipasang pada daftar lirik supaya direkam,
 * lalu dibiaskan oleh tombol bulat yang menjadi sibling-nya (lihat
 * [LocalLyricsGlassDisc]). Diisi oleh modul app. Mengembalikan modifier apa
 * adanya bila kaca mati; null / tidak diisi (desktop) berarti tanpa rekaman.
 */
val LocalLyricsGlassSource = staticCompositionLocalOf<
    (@Composable (Modifier) -> Modifier)?
> { null }

/** Satu baris pada popover menu globe di kontrol lirik. */
class LyricsMenuEntry(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * Pengait popover menu globe (gaya macOS: morph dari tombol, jelly, live blur).
 * Diisi oleh modul app (MainActivity). Argumen: apakah terbuka, bounds tombol
 * di window, aksi tutup, dan isi menu. Null / tidak diisi (desktop) berarti
 * globe memakai kipas disc bawaan.
 */
val LocalLyricsMenuPopover = staticCompositionLocalOf<
    (@Composable (Boolean, Rect?, () -> Unit, List<LyricsMenuEntry>, dev.chrisbanes.haze.HazeState?) -> Unit)?
> { null }