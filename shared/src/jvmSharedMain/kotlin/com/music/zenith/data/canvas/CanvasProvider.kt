package com.music.zenith.data.canvas

/**
 * The places [CanvasRepository] can look for a looping cover, in the order the
 * listener wants them asked. The first one that answers with a clip that
 * matches the track wins; the rest are never reached.
 */
enum class CanvasProvider(val label: String) {
    ZENITH("Zenith"),
    APPLE_MUSIC("Apple Music"),
    TIDAL("Tidal"),
    SPOTIFY("Spotify");

    companion object {
        /** The order the app shipped with before it became configurable. */
        val defaultOrder: List<CanvasProvider> = listOf(APPLE_MUSIC, TIDAL, ZENITH, SPOTIFY)

        /** What the old "prioritize Spotify" switch produced. */
        val spotifyFirst: List<CanvasProvider> =
            listOf(SPOTIFY) + defaultOrder.filter { it != SPOTIFY }

        /** A stored order made whole again: duplicates dropped, new providers appended. */
        fun ordered(saved: List<CanvasProvider>): List<CanvasProvider> =
            saved.distinct() + defaultOrder.filter { it !in saved }
    }
}