package com.music.zenith.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.zenith.data.model.HEADER_ART_PX
import com.music.zenith.data.model.HomeShelf
import com.music.zenith.data.model.ShelfItem
import com.music.zenith.data.model.ShelfLayout
import com.music.zenith.data.model.Song
import com.music.zenith.data.model.artworkAt
import com.music.zenith.sharedui.resources.*
import com.music.zenith.ui.components.PAGE_GUTTER
import com.music.zenith.ui.components.RowMoreButton
import com.music.zenith.ui.components.SearchPlayingBars
import com.music.zenith.ui.components.ShelfRow
import com.music.zenith.ui.components.contextClick
import com.music.zenith.ui.components.coverFlightSource
import com.music.zenith.ui.components.hideDuringCoverFlight
import com.music.zenith.ui.components.longPressMenuClickable
import com.music.zenith.ui.components.rememberCoverFlightTap
import com.music.zenith.ui.components.thumbnailBorder
import com.music.zenith.ui.theme.rememberArtworkPanelColor
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** Judul shelf YouTube Music "Your daily discover", dicocokkan sebagian tanpa peduli huruf besar-kecil. */
private const val DAILY_DISCOVER_KEY = "daily discover"

/** Lebar kartu sebagai bagian dari lebar baris; sisanya dipakai kartu berikutnya untuk mengintip. */
private const val DAILY_CARD_FRACTION = 0.86f
private val DAILY_CARD_MAX_WIDTH = 400.dp
private val DAILY_CARD_GAP = 12.dp
private val DAILY_CARD_SHAPE = RoundedCornerShape(24.dp)

/** Skala kartu yang sudah satu kartu penuh jauhnya dari posisi snap. */
private const val DAILY_SIDE_SCALE = 0.9f

/** Zoom dasar cover saat kartu tepat di posisi snap (1f + nilai ini). */
private const val DAILY_COVER_ZOOM = 0.06f

/** Tambahan zoom yang berdenyut pelan (zoom-in lalu zoom-out terus-menerus) pada kartu aktif. */
private const val DAILY_COVER_DRIFT = 0.12f
private const val DAILY_COVER_DRIFT_MS = 4500

internal fun HomeShelf.isDailyDiscoverShelf(): Boolean =
    layout == ShelfLayout.CARDS &&
        items.isNotEmpty() &&
        title.contains(DAILY_DISCOVER_KEY, ignoreCase = true)

/**
 * Kartu besar satu per satu, seperti YouTube Music: kartu di posisi snap tampil
 * penuh, kartu tetangganya mengecil dan membesar mengikuti geseran.
 *
 * Zoom dihitung di blok graphicsLayer (fase draw), jadi tiap frame geser tidak
 * memicu recomposition sama sekali.
 */
@Composable
internal fun DailyDiscoverShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onPlaySongs: ((List<Song>) -> Unit)?,
    isCurrentItem: (ShelfItem) -> Boolean,
    isPlaying: Boolean,
) {
    val state = rememberShelfListState("daily:${shelf.title}")
    val songs = remember(shelf.items) {
        shelf.items.mapNotNull { item ->
            item.videoId?.let { id ->
                Song(
                    videoId = id,
                    title = item.title,
                    artist = item.subtitle.substringBefore(" \u2022 "),
                    thumbnailUrl = item.thumbnailUrl,
                )
            }
        }
    }
    Column(Modifier.padding(bottom = 26.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = shelf.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (shelf.subtitle.isNotBlank()) {
                    Text(
                        text = shelf.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onPlaySongs != null && songs.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.play_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f), CircleShape)
                        .clickable { onPlaySongs(songs) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        BoxWithConstraints {
            val cardWidth = minOf(maxWidth * DAILY_CARD_FRACTION, DAILY_CARD_MAX_WIDTH)
            val spanPx = with(LocalDensity.current) { (cardWidth + DAILY_CARD_GAP).toPx() }
            ShelfRow(
                state = state,
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(DAILY_CARD_GAP),
            ) {
                itemsIndexed(
                    shelf.items,
                    key = { index, item -> "daily:$index:${item.videoId ?: item.browseId ?: item.title}" },
                    contentType = { _, _ -> "daily-card" },
                ) { index, item ->
                    DailyDiscoverCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier
                            .dailyZoom(state, index, cardWidth)
                            .width(cardWidth),
                        coverFocus = { dailyCardFocus(state, index, spanPx) },
                        isCurrent = isCurrentItem(item),
                        isPlaying = isCurrentItem(item) && isPlaying,
                    )
                }
            }
        }
    }
}

/**
 * Posisi geser dihitung dalam satuan kartu (indeks kartu pertama + sisa offset
 * dibagi lebar kartu), jadi tidak bergantung pada contentPadding. Kartu yang
 * berjarak 0 dari posisi itu berskala 1, yang berjarak satu kartu atau lebih
 * berskala [DAILY_SIDE_SCALE].
 */
private fun Modifier.dailyZoom(state: LazyListState, index: Int, cardWidth: Dp): Modifier =
    graphicsLayer {
        val span = (cardWidth + DAILY_CARD_GAP).toPx()
        if (span <= 0f) return@graphicsLayer
        val position = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / span
        val distance = abs(index - position).coerceIn(0f, 1f)
        val scale = 1f - (1f - DAILY_SIDE_SCALE) * distance
        scaleX = scale
        scaleY = scale
    }

/** Jarak kartu ke posisi snap dalam satuan kartu: 0 tepat di snap, 1 satu kartu atau lebih jauhnya. */
private fun dailyCardDistance(state: LazyListState, index: Int, spanPx: Float): Float {
    if (spanPx <= 0f) return 1f
    val position = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / spanPx
    return abs(index - position).coerceIn(0f, 1f)
}

/** Seberapa aktif kartu: 1 tepat di posisi snap, 0 satu kartu atau lebih jauhnya. */
private fun dailyCardFocus(state: LazyListState, index: Int, spanPx: Float): Float =
    1f - dailyCardDistance(state, index, spanPx)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyDiscoverCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    coverFocus: () -> Float = { 0f },
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    val token = remember { Any() }
    val openWithCover = rememberCoverFlightTap(item.browseId, token, onClick)
    val scrimBase = rememberArtworkPanelColor(item.thumbnailUrl, HEADER_ART_PX) ?: Color.Black
    val scrim = animateColorAsState(scrimBase, tween(260), label = "dailyScrim").value
    val drift = rememberInfiniteTransition(label = "dailyCoverDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(DAILY_COVER_DRIFT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dailyCoverDriftValue",
    )
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(DAILY_CARD_SHAPE)
            .thumbnailBorder(DAILY_CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressMenuClickable(
                onClick = openWithCover,
                onLongClick = onLongPress,
            )
            .contextClick(onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .coverFlightSource(token, item.thumbnailUrl.artworkAt(HEADER_ART_PX), 24.dp)
                .fillMaxSize()
                .graphicsLayer {
                    val zoom = 1f + coverFocus() * (DAILY_COVER_ZOOM + DAILY_COVER_DRIFT * drift.value)
                    scaleX = zoom
                    scaleY = zoom
                },
        )
        Box(
            modifier = Modifier
                .hideDuringCoverFlight(token)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to scrim.copy(alpha = 0.95f),
                            0.5f to scrim.copy(alpha = 0.55f),
                            1f to scrim.copy(alpha = 0f),
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .hideDuringCoverFlight(token)
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(start = 20.dp, end = 8.dp, top = 18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = localizeCardSubtitle(item.subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onLongPress != null) {
                RowMoreButton(
                    onClick = onLongPress,
                    tint = Color.White,
                )
            }
        }
        if (isCurrent && isPlaying) {
            SearchPlayingBars(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp),
            )
        }
    }
}