package com.music.zenith.ui.screens

import com.music.zenith.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.music.zenith.ui.components.ElasticRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.zenith.data.model.HomeShelf
import com.music.zenith.data.model.MoodGenre
import com.music.zenith.data.model.MoodGenreSection
import com.music.zenith.data.model.ShelfItem
import com.music.zenith.data.model.Song
import com.music.zenith.data.model.UiState
import com.music.zenith.data.model.withoutItemsOn
import com.music.zenith.ui.components.MessageState
import com.music.zenith.ui.components.PAGE_GUTTER
import com.music.zenith.ui.components.PullToRefresh
import com.music.zenith.ui.components.EditorialShelfSkeleton
import com.music.zenith.ui.components.SectionHeaderSkeleton
import com.music.zenith.ui.components.ShelfSkeleton
import com.music.zenith.ui.components.ShimmerBox
import com.music.zenith.ui.components.SongRowSkeleton
import com.music.zenith.ui.components.feedSkeleton
import com.music.zenith.ui.components.instantPressClickable

/**
 * Shelf editorial terakhir yang pernah ditemukan. Feed Home bisa sesaat tidak
 * memuatnya (refresh Home hanya membawa feed inti, supplement belum kembali),
 * dan kartu lebar di puncak Explore tidak boleh ikut hilang karenanya.
 */
private var lastEditorial: HomeShelf? = null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    /** The charts page's shelves; the mood and genre cards now live on the Search tab. */
    chartsState: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: ElasticRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
    /**
     * The home feed, read only for the release shelf (Apple's "New" style
     * cards) that leads this page. Home leaves that shelf out.
     */
    homeState: UiState<List<HomeShelf>>? = null,
    onShelfItemClick: ((ShelfItem, String) -> Unit)? = null,
    onShelfItemLongPress: ((ShelfItem) -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    val editorial = remember(homeState) {
        val found = (homeState as? UiState.Success)?.data?.let { shelves ->
            editorialShelfIndex(shelves)?.let { shelves[it] }
        }
        if (found != null) lastEditorial = found
        found ?: lastEditorial
    }
    // Cards the Home tab already shows are left out here, so New is not Home again.
    val homeShelves = (homeState as? UiState.Success)?.data
    val chartShelves = remember(chartsState, homeShelves, editorial) {
        (chartsState as? UiState.Success)?.data
            ?.filter { it.title != editorial?.title }
            ?.withoutItemsOn(homeShelves.orEmpty())
            .orEmpty()
    }
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        topInset = contentPadding.calculateTopPadding(),
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                // The heading sits where Home's does: the whole page is lifted
                // by the same amount, not just the title.
                contentPadding = if (showTitle) {
                    PaddingValues(
                        top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp),
                        bottom = contentPadding.calculateBottomPadding(),
                    )
                } else {
                    contentPadding
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            // 14dp under the title, the same gap Library leaves
                            // above its first card (8dp title + 6dp Replay row).
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
                if (editorial != null && onShelfItemClick != null) {
                    editorialShelf(editorial, onShelfItemClick, onShelfItemLongPress)
                } else if (onShelfItemClick != null && homeState is UiState.Loading) {
                    // The release shelf is still on the wire: hold its place so
                    // the mood grid doesn't jump down when it lands.
                    item(key = "editorial-skeleton") { EditorialShelfSkeleton() }
                }
                when (chartsState) {
                    UiState.Loading -> {
                        if (editorial == null) item(key = "charts-skeleton") { EditorialShelfSkeleton() }
                        // Bagian di bawah shelf editorial (Latest songs dan kartu chart)
                        // belum datang: tahan tempatnya supaya halaman tidak kosong.
                        item(key = "charts-skeleton-latest") {
                            Column(Modifier.padding(bottom = 26.dp)) {
                                SectionHeaderSkeleton()
                                repeat(4) { index -> SongRowSkeleton(index = index) }
                            }
                        }
                        item(key = "charts-skeleton-shelf") { ShelfSkeleton(index = 1) }
                    }
                    is UiState.Error -> {
                        if (editorial == null) item(key = "charts-error") {
                            MessageState(chartsState.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                        }
                    }
                    is UiState.Success -> {
                        chartShelves.forEachIndexed { index, shelf ->
                            item(key = "chart:$index:${shelf.title}") {
                                if (shelf.layout == com.music.zenith.data.model.ShelfLayout.LIST) {
                                    RecentShelf(
                                        shelf = shelf,
                                        onItemClick = { onShelfItemClick?.invoke(it, shelf.title) },
                                        onItemLongPress = onShelfItemLongPress,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                    )
                                } else {
                                    Shelf(
                                        shelf = shelf,
                                        onItemClick = { onShelfItemClick?.invoke(it, shelf.title) },
                                        onItemLongPress = onShelfItemLongPress,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEach { item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                modifier = Modifier.weight(1f),
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MoodGenreSleeve(
        item = item,
        modifier = modifier
            .aspectRatio(MOOD_CARD_ASPECT)
            .instantPressClickable(shape = MOOD_CARD_SHAPE, onClick = onClick),
    )
}

/**
 * The category's cover filling the whole card, redrawn as a duotone of the
 * category's colour so every card reads as one tinted sleeve, with the title
 * at the bottom left. No solid stripe: the artwork runs edge to edge.
 *
 * Only the artwork: the caller sizes, clips and makes it clickable, which is
 * how the TV app wraps the same sleeve in its own focus treatment.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val tone = remember(item.stripeColor, item.title) { moodTone(item) }
    Box(modifier = modifier.background(tone.placeholder)) {
        item.thumbnailUrl?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = tone.duotone,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // A soft shade rising from the bottom keeps white type legible on
        // pale artwork.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(.32f to Color.Transparent, 1f to Color.Black.copy(alpha = .5f))),
        )
        Text(
            text = item.title,
            style = titleStyle.copy(
                shadow = Shadow(Color.Black.copy(alpha = .35f), offset = Offset(0f, 1f), blurRadius = 6f),
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private class MoodTone(val stripe: Color, val placeholder: Color, val duotone: ColorFilter)

private fun moodTone(item: MoodGenre): MoodTone {
    val given = item.stripeColor?.let { Color(it.toInt()) }
    // Greys carry no hue to boost, so they take one of the vivid fallbacks.
    val base = if (given == null || chroma(given) < .12f) fallbackMoodColor(item.title) else given
    // YouTube's palette is pastel: lift the saturation only slightly, then ramp from a
    // deep shadow to a soft highlight so the sleeve stays rich but easy on the eyes.
    val vivid = saturate(base, 1.05f)
    val stripe = lerp(vivid, Color.Black, .05f)
    val shadow = lerp(vivid, Color.Black, .78f)
    val highlight = lerp(vivid, Color.Black, .12f)
    return MoodTone(
        stripe = stripe,
        placeholder = lerp(shadow, highlight, .55f),
        duotone = duotone(shadow, highlight),
    )
}

private fun chroma(c: Color): Float = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

/** Pushes each channel away from the colour's own luminance by [amount] (1 = unchanged). */
private fun saturate(c: Color, amount: Float): Color {
    val l = c.red * .299f + c.green * .587f + c.blue * .114f
    return Color(
        (l + (c.red - l) * amount).coerceIn(0f, 1f),
        (l + (c.green - l) * amount).coerceIn(0f, 1f),
        (l + (c.blue - l) * amount).coerceIn(0f, 1f),
    )
}

/**
 * Maps each pixel's luminance onto the ramp [shadow] to [highlight]. Offsets
 * are in 0..255, which Compose honours on Skia too (it rescales them).
 */
private fun duotone(shadow: Color, highlight: Color): ColorFilter {
    // Luminance is stretched around mid grey before it is mapped onto the ramp.
    val contrast = 1.1f
    fun channel(from: Float, to: Float): FloatArray {
        val span = to - from
        val k = span * contrast
        return floatArrayOf(k * .299f, k * .587f, k * .114f, 0f, (from + span * (.5f - .5f * contrast)) * 255f)
    }
    return ColorFilter.colorMatrix(
        ColorMatrix(
            channel(shadow.red, highlight.red) +
                channel(shadow.green, highlight.green) +
                channel(shadow.blue, highlight.blue) +
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
        ),
    )
}

/** For a button that came without YouTube's colour, or with a grey one: a vivid hue. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFFF5A3C)
    1 -> Color(0xFFFF4F8B)
    2 -> Color(0xFF8F5BFF)
    3 -> Color(0xFF3D7BFF)
    4 -> Color(0xFFFFA11F)
    5 -> Color(0xFF18B5D6)
    6 -> Color(0xFF1FCB8A)
    else -> Color(0xFF3FD05A)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(18.dp)

/** Width over height; a two-column phone row lands at about 100dp tall. */
const val MOOD_CARD_ASPECT = 1.72f



/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

/** The column count the mood and genre grid uses for a list [available] wide. */
internal fun moodGenreColumns(available: Dp): Int = moodColumns(available)

/**
 * The mood and genre cards as one grid, for a page that draws them inside its
 * own list (the Search tab's Browse Categories). The server's grouping only
 * decides the order.
 */
internal fun LazyListScope.moodGenreGrid(
    state: UiState<List<MoodGenreSection>>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        UiState.Loading -> item(key = "mood-skeleton") { ExploreSkeletonRows(columns) }
        is UiState.Error -> item(key = "mood-error") {
            MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
        }
        is UiState.Success -> {
            val rows = state.data
                .flatMap(MoodGenreSection::items)
                .distinctBy { it.browseId to it.params }
                .chunked(columns)
            items(rows, key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                MoodGenreRow(row = row, columns = columns, onCategoryClick = onCategoryClick)
            }
        }
    }
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
