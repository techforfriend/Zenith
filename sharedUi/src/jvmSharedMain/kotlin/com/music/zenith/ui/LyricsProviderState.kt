package com.music.zenith.ui

/** What the current track's provider picker already knows without another request. */
enum class LyricsProviderState {
    NOT_FETCHED,
    FETCHING,
    FOUND,
    NOT_FOUND,
}

/** Where a manual "search with another title" request stands for the current track. */
enum class ManualLyricsSearchState {
    IDLE,
    SEARCHING,
    FOUND,
    NOT_FOUND,
}
