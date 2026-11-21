package com.music.zenith.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.Switch
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
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
    /** Tombol dan popup debug tampil hanya setelah kode rahasia diketik di pencarian. */
    val unlocked = mutableStateOf(false)

    /** Panel debug sedang terbuka. Disimpan di sini agar tidak hilang saat popup dibuat ulang. */
    val panelOpen = mutableStateOf(false)

    /** Segmen yang sedang tampil di panel. */
    val tab = mutableStateOf(DebugTab.CANVAS)

    /** Panel ditaruh di atas supaya kontrol di bawah tetap terlihat saat disetel. */
    val panelAtTop = mutableStateOf(false)

    /** Panel dibuat transparan agar layar di baliknya terlihat. */
    val translucent = mutableStateOf(false)

    /** Matikan mode debug dan lepaskan saklar sementara volume/lirik. */
    fun lock() {
        unlocked.value = false
        panelOpen.value = false
        ControlLayoutTuning.clearOverrides()
    }

    const val DEFAULT_HEIGHT_SCALE = 0.52f
    const val DEFAULT_CURVE = 0.06f
    const val DEFAULT_POWER = 1.37f
    const val DEFAULT_OFFSET_DP = 80f
    const val DEFAULT_CURVE_EXP = 4f
    const val DEFAULT_BLUR_SCALE = 1.95f

    /** Panjang fade: makin besar, batas blur makin naik. */
    val heightScale = mutableFloatStateOf(DEFAULT_HEIGHT_SCALE)

    /** Seberapa tinggi sisi kiri dan kanan larut dibanding tengah (bentuk V). */
    val curve = mutableFloatStateOf(DEFAULT_CURVE)

    /** Kekuatan kurva alpha di dalam band yang sama. */
    val power = mutableFloatStateOf(DEFAULT_POWER)

    /** Geser seluruh band fade ke bawah (+) atau ke atas (-), dalam dp. */
    val offsetDp = mutableFloatStateOf(DEFAULT_OFFSET_DP)

    /** Eksponen bentuk V: 1 = V lancip, 2 = parabola, makin besar makin datar di tengah. */
    val curveExp = mutableFloatStateOf(DEFAULT_CURVE_EXP)

    /** Pengali kekuatan blur klip. */
    val blurScale = mutableFloatStateOf(DEFAULT_BLUR_SCALE)

    fun reset() {
        heightScale.floatValue = DEFAULT_HEIGHT_SCALE
        curve.floatValue = DEFAULT_CURVE
        power.floatValue = DEFAULT_POWER
        offsetDp.floatValue = DEFAULT_OFFSET_DP
        curveExp.floatValue = DEFAULT_CURVE_EXP
        blurScale.floatValue = DEFAULT_BLUR_SCALE
    }

    /**
     * Respons cepat di awal: nilai slider rendah sudah memberi efek besar, lalu
     * melandai mendekati maksimum. Memetakan 0..[max] ke 0..[max] secara ease-out.
     */
    private fun fastCurve(value: Float, max: Float): Float {
        val n = (value / max).coerceIn(0f, 1f)
        val inv = 1f - n
        return max * (1f - inv * inv * inv)
    }

    /** Tinggi fade efektif yang dipakai klip. */
    fun effectiveHeight(): Float = fastCurve(heightScale.floatValue, 2f)

    /** Lekuk V efektif yang dipakai klip. */
    fun effectiveCurve(): Float = fastCurve(curve.floatValue, 3f)

    /** Berubah setiap kali salah satu nilai berubah; dibaca blok update view klip. */
    fun signature(): Int =
        java.util.Objects.hash(
            heightScale.floatValue,
            curve.floatValue,
            power.floatValue,
            offsetDp.floatValue,
            curveExp.floatValue,
            blurScale.floatValue,
        )
}

private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

/** Segmen panel debug: penyetel fade klip Canvas, atau jarak deck kontrol musik. */
enum class DebugTab { CANVAS, CONTROL }

/**
 * Jarak vertikal (dp) deck kontrol musik untuk satu kombinasi volume/lirik.
 * Nilai awal sama persis dengan angka yang dulu tertanam di layout.
 */
internal data class ControlProfile(
    /** Jarak di atas seek bar (di bawah baris lirik). */
    val scrubTop: Float = 0f,
    /** Seek bar ke tombol play. */
    val transportTop: Float = 28f,
    /** Tombol play ke blok volume. */
    val transportBottom: Float = 0f,
    /** Atas slider volume (hanya saat volume tampil). */
    val volumeTop: Float = 8f,
    /** Bawah slider volume (hanya saat volume tampil). */
    val volumeBottom: Float = 12f,
    /** Slot kosong pengganti volume (hanya saat volume disembunyikan). */
    val volumeSlot: Float = VOLUME_ROW_HEIGHT.value,
    /** Jarak sebelum baris ikon bawah. */
    val actionsTop: Float = 0f,
    /** Padding paling bawah deck. */
    val bottomPad: Float = 20f,
    /** Judul & artis tidak ikut aturan lirik atas bar; posisinya hanya dari setelan profil. */
    val titleSeparate: Boolean = true,
    /** Geser cover mode kartu (dp, + turun). */
    val artExtra: Float = 20.89f,
    /** Geser judul & artis (dp, + turun). */
    val titleExtra: Float = 25f,
)

/**
 * Penyetel posisi kontrol musik. Ada empat profil: volume tampil/sembunyi dikali
 * lirik atas bar tampil/sembunyi. Layout memilih profil sesuai kondisi saat ini.
 * Setelah nilai pas, salin lewat tombol Salin lalu jadikan default permanen.
 */
internal object ControlLayoutTuning {
    /**
     * Nilai awal tiap profil, urutan sama dengan [index]: volume tampil/lirik tampil,
     * volume tampil/lirik sembunyi, volume sembunyi/lirik tampil, volume sembunyi/lirik sembunyi.
     */
    private val DEFAULTS = arrayOf(
        ControlProfile(transportTop = 24.10f, artExtra = 8.33f, titleExtra = 19.72f),
        ControlProfile(transportTop = 21.25f, volumeBottom = 18.82f, artExtra = 20.89f, titleExtra = 41.11f),
        ControlProfile(artExtra = -4.69f, titleExtra = 16.11f),
        ControlProfile(artExtra = 20.89f, titleExtra = 44.72f),
    )

    private val profiles = Array(4) { mutableStateOf(DEFAULTS[it]) }

    /** Jumlah fase: empat kombinasi volume/lirik. */
    const val PHASE_COUNT = 4

    /** Fase 1-4 sebagai (volume disembunyikan, lirik atas bar tampil). */
    val PHASE_COMBOS = listOf(true to true, false to true, true to false, false to false)

    /** Fase yang sedang dikerjakan (mulai dari 0). */
    val phase = mutableIntStateOf(0)

    /** Fase yang sudah ditandai selesai. */
    val phaseDone = mutableStateOf<Set<Int>>(emptySet())

    fun phaseTitle(i: Int): String = when (i) {
        0 -> "Lirik tampil, volume sembunyi"
        1 -> "Lirik tampil, volume tampil"
        2 -> "Lirik sembunyi, volume sembunyi"
        else -> "Lirik sembunyi, volume tampil"
    }

    /** Pindah fase dan set saklar volume/lirik sesuai kombinasi fase itu. */
    fun goToPhase(i: Int) {
        val n = i.coerceIn(0, PHASE_COUNT - 1)
        phase.intValue = n
        hideVolumeOverride.value = PHASE_COMBOS[n].first
        lyricStripOverride.value = PHASE_COMBOS[n].second
    }

    fun markDone(i: Int) {
        phaseDone.value = phaseDone.value + i
    }

    /** null = ikuti Pengaturan; true/false = dipaksa dari panel debug. */
    val hideVolumeOverride = mutableStateOf<Boolean?>(null)
    val lyricStripOverride = mutableStateOf<Boolean?>(null)

    private fun index(volumeHidden: Boolean, lyricStrip: Boolean): Int =
        (if (volumeHidden) 2 else 0) + (if (lyricStrip) 0 else 1)

    fun profile(volumeHidden: Boolean, lyricStrip: Boolean): ControlProfile =
        profiles[index(volumeHidden, lyricStrip)].value

    fun update(
        volumeHidden: Boolean,
        lyricStrip: Boolean,
        transform: (ControlProfile) -> ControlProfile,
    ) {
        val state = profiles[index(volumeHidden, lyricStrip)]
        state.value = transform(state.value)
    }

    fun reset(volumeHidden: Boolean, lyricStrip: Boolean) {
        val i = index(volumeHidden, lyricStrip)
        profiles[i].value = DEFAULTS[i]
    }

    fun resetAll() {
        DEFAULTS.forEachIndexed { i, p -> profiles[i].value = p }
    }

    fun clearOverrides() {
        hideVolumeOverride.value = null
        lyricStripOverride.value = null
    }

    private fun safe(value: Float): Float = if (value.isFinite()) value else 0f

    /** Judul terpisah dari aturan lirik atas bar, menurut profil aktif? */
    fun titleSeparate(volumeHidden: Boolean, lyricStrip: Boolean): Boolean =
        profile(volumeHidden, lyricStrip).titleSeparate

    /** Geser cover mode kartu, menurut profil aktif. */
    fun artExtra(volumeHidden: Boolean, lyricStrip: Boolean): Dp =
        safe(profile(volumeHidden, lyricStrip).artExtra).dp

    /** Geser judul & artis, menurut profil aktif. */
    fun titleExtra(volumeHidden: Boolean, lyricStrip: Boolean): Dp =
        safe(profile(volumeHidden, lyricStrip).titleExtra).dp

    /** Jarak bawaan lama layout; patokan tetap agar cover tidak ikut berubah oleh setelan kontrol. */
    private val ORIGINAL = ControlProfile(
        scrubTop = 0f,
        transportTop = 28f,
        transportBottom = 0f,
        volumeTop = 8f,
        volumeBottom = 12f,
        volumeSlot = VOLUME_ROW_HEIGHT.value,
        actionsTop = 0f,
        bottomPad = 20f,
    )

    private fun deckTotal(p: ControlProfile, volumeHidden: Boolean): Float =
        p.scrubTop + p.transportTop + p.transportBottom +
            (if (volumeHidden) p.volumeSlot else p.volumeTop + p.volumeBottom) +
            p.actionsTop + p.bottomPad

    /**
     * Selisih tinggi deck kontrol dibanding jarak bawaan lama. Ditambahkan kembali ke
     * tinggi area cover supaya ukuran dan posisi cover serta judul tidak ikut berubah.
     */
    fun deckDelta(volumeHidden: Boolean, lyricStrip: Boolean): Dp {
        val delta = deckTotal(profile(volumeHidden, lyricStrip), volumeHidden) -
            deckTotal(ORIGINAL, volumeHidden)
        return (if (delta.isFinite()) delta else 0f).dp
    }

    /** Volume disembunyikan? Dipaksa panel debug hanya selama mode debug aktif. */
    fun hideVolume(setting: Boolean): Boolean =
        if (CanvasFadeTuning.unlocked.value) hideVolumeOverride.value ?: setting else setting

    /** Baris lirik atas bar aktif? Dipaksa panel debug hanya selama mode debug aktif. */
    fun lyricStrip(setting: Boolean): Boolean =
        if (CanvasFadeTuning.unlocked.value) lyricStripOverride.value ?: setting else setting

    /** Teks siap tempel berisi semua setelan, untuk dijadikan default permanen. */
    fun exportText(hideVolume: Boolean, lyricStrip: Boolean): String = buildString {
        appendLine("== Canvas fade (jadikan DEFAULT_* di CanvasFadeTuning) ==")
        appendLine("DEFAULT_HEIGHT_SCALE = ${fmt(CanvasFadeTuning.heightScale.floatValue)}f")
        appendLine("DEFAULT_OFFSET_DP = ${fmt(CanvasFadeTuning.offsetDp.floatValue)}f")
        appendLine("DEFAULT_CURVE = ${fmt(CanvasFadeTuning.curve.floatValue)}f")
        appendLine("DEFAULT_CURVE_EXP = ${fmt(CanvasFadeTuning.curveExp.floatValue)}f")
        appendLine("DEFAULT_POWER = ${fmt(CanvasFadeTuning.power.floatValue)}f")
        appendLine("DEFAULT_BLUR_SCALE = ${fmt(CanvasFadeTuning.blurScale.floatValue)}f")
        appendLine()
        appendLine("== Kontrol player (nilai awal ControlProfile per kondisi) ==")
        appendLine(
            "Saklar saat ini: volume=${if (hideVolume) "sembunyi" else "tampil"}, " +
                "lirik atas bar=${if (lyricStrip) "tampil" else "sembunyi"}",
        )
        for (hidden in listOf(false, true)) {
            for (strip in listOf(true, false)) {
                val p = profile(hidden, strip)
                val active = if (hidden == hideVolume && strip == lyricStrip) " (aktif)" else ""
                appendLine()
                appendLine(
                    "// volume ${if (hidden) "sembunyi" else "tampil"}, " +
                        "lirik ${if (strip) "tampil" else "sembunyi"}$active",
                )
                appendLine(
                    "ControlProfile(scrubTop = ${fmt(p.scrubTop)}f, transportTop = ${fmt(p.transportTop)}f, " +
                        "transportBottom = ${fmt(p.transportBottom)}f, volumeTop = ${fmt(p.volumeTop)}f, " +
                        "volumeBottom = ${fmt(p.volumeBottom)}f, volumeSlot = ${fmt(p.volumeSlot)}f, " +
                        "actionsTop = ${fmt(p.actionsTop)}f, bottomPad = ${fmt(p.bottomPad)}f, " +
                        "titleSeparate = ${p.titleSeparate}, artExtra = ${fmt(p.artExtra)}f, " +
                        "titleExtra = ${fmt(p.titleExtra)}f)",
                )
            }
        }
    }
}

/**
 * Spacer vertikal yang tingginya dibaca dari profil aktif saat layout, jadi
 * menggeser slider tidak me-recompose seluruh player. [extra] ditambahkan apa adanya.
 */
@Composable
internal fun ControlGap(
    volumeHidden: Boolean,
    lyricStrip: Boolean,
    extra: Dp = 0.dp,
    pick: (ControlProfile) -> Float,
) {
    Layout(content = {}) { _, constraints ->
        val value = pick(ControlLayoutTuning.profile(volumeHidden, lyricStrip))
        val safe = if (value.isFinite()) value else 0f
        val px = (safe.dp.roundToPx() + extra.roundToPx()).coerceAtLeast(0)
        layout(constraints.minWidth, px) {}
    }
}

/** Tombol "Debug" kecil di kanan atas player yang membuka panel penyetel (segmen Canvas dan Kontrol). */
@Composable
internal fun CanvasFadeDebugTuner(
    canvasActive: Boolean = true,
    hideVolume: Boolean = false,
    lyricStrip: Boolean = true,
    lyricStripAvailable: Boolean = true,
) {
    val open = CanvasFadeTuning.panelOpen.value
    val atTop = CanvasFadeTuning.panelAtTop.value
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Popup(
            alignment = Alignment.TopEnd,
            offset = with(density) { IntOffset((-16).dp.roundToPx(), 120.dp.roundToPx()) },
            properties = PopupProperties(focusable = false),
        ) {
            Text(
                text = if (open) "Tutup" else "Debug",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .clickable { CanvasFadeTuning.panelOpen.value = !CanvasFadeTuning.panelOpen.value }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (open) {
            Popup(
                alignment = if (atTop) Alignment.TopCenter else Alignment.BottomCenter,
                offset = with(density) {
                    IntOffset(0, if (atTop) 168.dp.roundToPx() else (-40).dp.roundToPx())
                },
                properties = PopupProperties(focusable = false),
            ) {
                DebugTunerPanel(
                    atTop = atTop,
                    canvasActive = canvasActive,
                    hideVolume = hideVolume,
                    lyricStrip = lyricStrip,
                    lyricStripAvailable = lyricStripAvailable,
                )
            }
        }
    }
}

@Composable
private fun DebugTunerPanel(
    atTop: Boolean,
    canvasActive: Boolean,
    hideVolume: Boolean,
    lyricStrip: Boolean,
    lyricStripAvailable: Boolean,
) {
    val tab = CanvasFadeTuning.tab.value
    val translucent = CanvasFadeTuning.translucent.value
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val summary = if (tab == DebugTab.CANVAS) {
        "T ${fmt(CanvasFadeTuning.heightScale.floatValue)} Y ${fmt(CanvasFadeTuning.offsetDp.floatValue)} " +
            "C ${fmt(CanvasFadeTuning.curve.floatValue)} E ${fmt(CanvasFadeTuning.curveExp.floatValue)} " +
            "P ${fmt(CanvasFadeTuning.power.floatValue)} B ${fmt(CanvasFadeTuning.blurScale.floatValue)}"
    } else {
        val p = ControlLayoutTuning.profile(hideVolume, lyricStrip)
        "S ${fmt(p.scrubTop)} T ${fmt(p.transportTop)}/${fmt(p.transportBottom)} " +
            (if (hideVolume) "V ${fmt(p.volumeSlot)}" else "V ${fmt(p.volumeTop)}/${fmt(p.volumeBottom)}") +
            " A ${fmt(p.actionsTop)} B ${fmt(p.bottomPad)}"
    }
    Column(
        modifier = Modifier
            .width(320.dp)
            .heightIn(max = if (atTop) 340.dp else 460.dp)
            .background(
                Color.Black.copy(alpha = if (translucent) 0.4f else 0.75f),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Debug player",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelChip(if (atTop) "Ke bawah" else "Ke atas") {
                    CanvasFadeTuning.panelAtTop.value = !CanvasFadeTuning.panelAtTop.value
                }
                PanelChip(if (translucent) "Pekat" else "Tembus") {
                    CanvasFadeTuning.translucent.value = !CanvasFadeTuning.translucent.value
                }
            }
        }
        SegmentedTabs(tab) { CanvasFadeTuning.tab.value = it }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (tab) {
                DebugTab.CANVAS -> CanvasTab(canvasActive)
                DebugTab.CONTROL -> ControlTab(hideVolume, lyricStrip, lyricStripAvailable)
            }
        }
        Text(
            text = summary,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelChip("Reset") {
                if (tab == DebugTab.CANVAS) {
                    CanvasFadeTuning.reset()
                } else {
                    ControlLayoutTuning.reset(hideVolume, lyricStrip)
                }
            }
            PanelChip(if (copied) "Tersalin" else "Salin") {
                val text = ControlLayoutTuning.exportText(hideVolume, lyricStrip)
                runCatching { clipboard.setText(AnnotatedString(text)) }
                    .onSuccess { copied = true }
            }
            Box(Modifier.weight(1f))
            PanelChip("Nonaktifkan", Color(0xFFB3261E).copy(alpha = 0.8f)) {
                CanvasFadeTuning.lock()
            }
        }
    }
}

@Composable
private fun SegmentedTabs(selected: DebugTab, onSelect: (DebugTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        DebugTab.values().forEach { item ->
            val active = item == selected
            Text(
                text = if (item == DebugTab.CANVAS) "Canvas" else "Kontrol",
                color = if (active) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(if (active) Color.White else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onSelect(item) }
                    .padding(vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun CanvasTab(canvasActive: Boolean) {
    val height = CanvasFadeTuning.heightScale
    val curve = CanvasFadeTuning.curve
    val power = CanvasFadeTuning.power
    val offset = CanvasFadeTuning.offsetDp
    val curveExp = CanvasFadeTuning.curveExp
    val blurScale = CanvasFadeTuning.blurScale
    if (!canvasActive) {
        Caption("Klip Canvas tidak tampil di lagu ini. Nilai tetap bisa disetel dan dipakai saat klip muncul.")
    }
    TunerRow("Tinggi (batas blur)", height.floatValue, 0f..2.0f, 0.01f) { height.floatValue = it }
    TunerRow("Geser Y (dp, + turun)", offset.floatValue, -200f..400f, 1f) { offset.floatValue = it }
    TunerRow("Lekuk V (sisi)", curve.floatValue, 0f..3.0f, 0.01f) { curve.floatValue = it }
    TunerRow("Bentuk V (eksponen)", curveExp.floatValue, 1f..4f, 0.01f) { curveExp.floatValue = it }
    TunerRow("Power (kekuatan)", power.floatValue, 0.3f..4.0f, 0.01f) { power.floatValue = it }
    TunerRow("Kekuatan blur", blurScale.floatValue, 0f..3.0f, 0.01f) { blurScale.floatValue = it }
}

@Composable
private fun ControlTab(hideVolume: Boolean, lyricStrip: Boolean, lyricStripAvailable: Boolean) {
    val phase = ControlLayoutTuning.phase.intValue
    val done = ControlLayoutTuning.phaseDone.value
    val last = ControlLayoutTuning.PHASE_COUNT - 1
    // Saklar fase diterapkan setiap kali segmen ini dibuka.
    LaunchedEffect(Unit) { ControlLayoutTuning.goToPhase(ControlLayoutTuning.phase.intValue) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0..last) {
            val active = i == phase
            Text(
                text = if (i in done) "${i + 1}\u2713" else "${i + 1}",
                color = if (active) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Color.White else Color.White.copy(alpha = 0.18f),
                        RoundedCornerShape(50),
                    )
                    .clickable { ControlLayoutTuning.goToPhase(i) }
                    .padding(vertical = 6.dp),
            )
        }
    }
    Text(
        text = "Fase ${phase + 1} dari ${ControlLayoutTuning.PHASE_COUNT}: ${ControlLayoutTuning.phaseTitle(phase)}",
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phase > 0) {
            PanelChip("Kembali") { ControlLayoutTuning.goToPhase(phase - 1) }
        }
        Box(Modifier.weight(1f))
        PanelChip(if (phase < last) "Selesai, lanjut" else "Selesai") {
            ControlLayoutTuning.markDone(phase)
            if (phase < last) ControlLayoutTuning.goToPhase(phase + 1)
        }
    }

    val combo = ControlLayoutTuning.PHASE_COMBOS[phase]
    val matches = hideVolume == combo.first && lyricStrip == combo.second
    if (matches) {
        PhaseSliders(hideVolume, lyricStrip)
    } else if (!lyricStripAvailable && combo.second) {
        Caption("Lirik tersinkron mati di Pengaturan, jadi fase ini tidak bisa ditampilkan. Lewati ke fase lain.")
    } else {
        Caption("Menyiapkan fase...")
    }
    PanelChip("Reset semua") { ControlLayoutTuning.resetAll() }
}

/** Slider jarak deck kontrol untuk profil kombinasi volume/lirik yang sedang aktif. */
@Composable
private fun PhaseSliders(hideVolume: Boolean, lyricStrip: Boolean) {
    val p = ControlLayoutTuning.profile(hideVolume, lyricStrip)
    val update: ((ControlProfile) -> ControlProfile) -> Unit = { transform ->
        ControlLayoutTuning.update(hideVolume, lyricStrip, transform)
    }
    TunerRow("Jarak atas seek bar", p.scrubTop, 0f..80f, 1f) { v -> update { it.copy(scrubTop = v) } }
    TunerRow("Seek bar ke tombol play", p.transportTop, 0f..120f, 1f) { v ->
        update { it.copy(transportTop = v) }
    }
    TunerRow("Tombol play ke bawah", p.transportBottom, 0f..120f, 1f) { v ->
        update { it.copy(transportBottom = v) }
    }
    if (hideVolume) {
        TunerRow("Slot volume (kosong)", p.volumeSlot, 0f..100f, 1f) { v ->
            update { it.copy(volumeSlot = v) }
        }
    } else {
        TunerRow("Volume: jarak atas", p.volumeTop, 0f..60f, 1f) { v ->
            update { it.copy(volumeTop = v) }
        }
        TunerRow("Volume: jarak bawah", p.volumeBottom, 0f..60f, 1f) { v ->
            update { it.copy(volumeBottom = v) }
        }
    }
    TunerRow("Jarak ke ikon bawah", p.actionsTop, 0f..80f, 1f) { v -> update { it.copy(actionsTop = v) } }
    TunerRow("Padding paling bawah", p.bottomPad, 0f..80f, 1f) { v -> update { it.copy(bottomPad = v) } }
    SwitchRow("Judul tidak ikut aturan lirik mati", p.titleSeparate) { v -> update { it.copy(titleSeparate = v) } }
    TunerRow("Cover turun (mode kartu)", p.artExtra, -30f..60f, 1f) { v -> update { it.copy(artExtra = v) } }
    TunerRow("Judul/artis/bintang turun", p.titleExtra, -120f..120f, 1f) { v -> update { it.copy(titleExtra = v) } }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
}

@Composable
private fun PanelChip(
    text: String,
    color: Color = Color.White.copy(alpha = 0.18f),
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun TunerRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 0.01f,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "\u2212",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .clickable { onChange((value - step).coerceIn(range)) }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Text(fmt(value), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = "+",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .clickable { onChange((value + step).coerceIn(range)) }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
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
    // Gesture di bawah hanya dibuat ulang saat range berubah. Tanpa ini, lambda
    // lama (mis. profil kontrol sebelum saklar diganti) ikut tertinggal.
    val currentOnChange by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    fun setAt(x: Float) {
        currentOnChange(range.start + span * (x / widthPx).coerceIn(0f, 1f))
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