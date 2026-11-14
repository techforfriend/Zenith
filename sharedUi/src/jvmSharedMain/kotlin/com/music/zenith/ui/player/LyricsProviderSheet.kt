package com.music.zenith.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.music.zenith.ui.icons.ZenithIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.zenith.sharedui.resources.*
import com.music.zenith.data.lyrics.LyricsSource
import com.music.zenith.ui.LyricsProviderState
import com.music.zenith.ui.ManualLyricsSearchState
import com.music.zenith.ui.haptics.Haptic
import com.music.zenith.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState

/**
 * Manual provider chooser for the lyrics half-player.
 *
 * It deliberately uses [PlayerDrawer], [ROW_SHAPE], and the same row geometry
 * as [AudioOutputSheet], so it behaves like another player destination rather
 * than a settings dialog. A row that the automatic lookup already completed is
 * entirely local: hits switch immediately and misses cannot be requested twice.
 */
@Composable
internal fun LyricsProviderSheet(
    hazeState: HazeState,
    currentSource: LyricsSource?,
    states: Map<LyricsSource, LyricsProviderState>,
    onSelect: (LyricsSource) -> Unit,
    onDismiss: () -> Unit,
    songTitle: String = "",
    songArtist: String = "",
    manualState: ManualLyricsSearchState = ManualLyricsSearchState.IDLE,
    onManualSearch: ((title: String, artist: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val savedOrder by PlayerSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val sources = remember(savedOrder) { LyricsSource.ordered(savedOrder) }
    var requestedSource by remember { mutableStateOf<LyricsSource?>(null) }
    LaunchedEffect(currentSource, requestedSource) {
        if (requestedSource != null && currentSource == requestedSource) onDismiss()
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.choose_lyrics_provider),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onManualSearch != null) {
                ManualLyricsSearch(
                    initialTitle = songTitle,
                    initialArtist = songArtist,
                    state = manualState,
                    onSearch = onManualSearch,
                    onFound = onDismiss,
                )
                Spacer(Modifier.height(4.dp))
            }
            sources.forEach { source ->
                LyricsProviderRow(
                    source = source,
                    state = states[source] ?: LyricsProviderState.NOT_FETCHED,
                    current = source == currentSource,
                    onClick = {
                        val wasFound = states[source] == LyricsProviderState.FOUND
                        requestedSource = source
                        onSelect(source)
                        if (wasFound) onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * "Search with another title": a row in the provider drawer's own geometry that
 * opens into a title + artist form. Some songs sit in LRCLIB and friends under
 * a different title or credit than the one the player knows, so the automatic
 * lookup misses them; this asks every enabled source again with the words typed
 * here, and a hit replaces the lyrics on screen.
 */
@Composable
private fun ManualLyricsSearch(
    initialTitle: String,
    initialArtist: String,
    state: ManualLyricsSearchState,
    onSearch: (title: String, artist: String) -> Unit,
    onFound: () -> Unit,
) {
    val haptics = rememberHaptics()
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var artist by remember(initialArtist) { mutableStateOf(initialArtist) }
    // The state outlives the drawer (it belongs to the track), so only a search
    // started from this open drawer may react to it.
    var submitted by remember { mutableStateOf(false) }
    val searching = submitted && state == ManualLyricsSearchState.SEARCHING
    val canSearch = title.isNotBlank() && !searching
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "manualLyricsChevron",
    )

    LaunchedEffect(state, submitted) {
        if (submitted && state == ManualLyricsSearchState.FOUND) {
            focusManager.clearFocus()
            onFound()
        }
    }

    fun submit() {
        if (!canSearch) return
        haptics.play(Haptic.Select)
        focusManager.clearFocus()
        submitted = true
        onSearch(title.trim(), artist.trim())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(Color.White.copy(alpha = if (expanded) 0.10f else 0.05f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.play(Haptic.Select)
                    expanded = !expanded
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (expanded) 0.16f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.lyrics_manual_search_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = Color.White.copy(alpha = if (expanded) 1f else 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.lyrics_manual_search_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = ZenithIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ROW_SHAPE)
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LyricsSearchField(
                    label = stringResource(Res.string.lyrics_manual_title_label),
                    value = title,
                    onValueChange = { title = it },
                    enabled = !searching,
                    imeAction = ImeAction.Next,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                LyricsSearchField(
                    label = stringResource(Res.string.lyrics_manual_artist_label),
                    value = artist,
                    onValueChange = { artist = it },
                    enabled = !searching,
                    imeAction = ImeAction.Search,
                    onImeAction = { submit() },
                )
                if (submitted && state == ManualLyricsSearchState.NOT_FOUND) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.lyrics_manual_not_found),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = if (canSearch) 0.92f else 0.14f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = canSearch,
                        ) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searching) {
                            CircularProgressIndicator(
                                color = Color.White.copy(alpha = 0.8f),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = if (canSearch) Color.Black.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (searching) Res.string.lyrics_manual_searching
                                else Res.string.lyrics_manual_search_action,
                            ),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                searching -> Color.White.copy(alpha = 0.8f)
                                canSearch -> Color.Black.copy(alpha = 0.88f)
                                else -> Color.White.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSearchField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(start = 14.dp, end = 8.dp)
                .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { onImeAction() },
                    onSearch = { onImeAction() },
                ),
                modifier = Modifier.weight(1f),
            )
            if (value.isNotEmpty() && enabled) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderRow(
    source: LyricsSource,
    state: LyricsProviderState,
    current: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    // A running automatic request can still be selected: it is marked as the
    // requested source and applied when that same in-flight call completes.
    val enabled = !current && state != LyricsProviderState.NOT_FOUND
    val status = when {
        current -> stringResource(Res.string.lyrics_provider_current)
        state == LyricsProviderState.FOUND -> stringResource(Res.string.lyrics_provider_found)
        state == LyricsProviderState.NOT_FOUND -> stringResource(Res.string.lyrics_provider_not_found)
        state == LyricsProviderState.FETCHING -> stringResource(Res.string.lyrics_provider_fetching)
        else -> stringResource(Res.string.lyrics_provider_not_fetched)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (current) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (current) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state == LyricsProviderState.FETCHING -> CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
                current || state == LyricsProviderState.FOUND -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (current) 1f else 0.75f),
                    modifier = Modifier.size(21.dp),
                )
                state == LyricsProviderState.NOT_FOUND -> Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = Color.White.copy(alpha = if (current) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (current) 0.7f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
