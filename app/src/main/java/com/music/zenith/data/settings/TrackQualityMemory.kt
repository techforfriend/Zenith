package com.music.zenith.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.zenith.data.sources.SourceKind
import com.music.zenith.data.sources.SourceRegistry

/**
 * The best quality rung each track has actually played at, kept across
 * restarts. Replaying a track never asks for less than this, so lowering the
 * quality setting after hearing a Hi-Res copy does not downgrade that track.
 * Only HIGH and LOSSLESS are worth remembering; YouTube's own Opus is the
 * floor everywhere.
 */
object TrackQualityMemory {
    private const val PREFS = "track_quality_memory"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 1000

    private val entries = LinkedHashMap<String, AudioQuality>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        entries.clear()
        store.getString(KEY, null)?.lineSequence()?.forEach { line ->
            val cut = line.indexOf('\t')
            if (cut <= 0) return@forEach
            val quality = AudioQuality.entries.firstOrNull { it.name == line.substring(0, cut) }
                ?: return@forEach
            entries[line.substring(cut + 1)] = quality
        }
    }

    @Synchronized
    fun floorFor(track: String?): AudioQuality? = track?.let { entries[it] }

    /** Called with what the decoder is really playing for [track]. */
    fun noteSeen(track: String?, lossless: Boolean, sourceName: String?) {
        val kind = sourceName?.let { name ->
            SourceRegistry.configs.value.firstOrNull { it.displayName == name }?.kind
        }
        val rung = when {
            lossless -> AudioQuality.LOSSLESS
            kind == null || kind == SourceKind.YOUTUBE -> null
            AudioQuality.HIGH.permits(kind) -> AudioQuality.HIGH
            else -> AudioQuality.LOSSLESS
        } ?: return
        remember(track, rung)
    }

    @Synchronized
    private fun remember(track: String?, quality: AudioQuality) {
        if (track.isNullOrBlank()) return
        val known = entries[track]
        if (known != null && known.ordinal >= quality.ordinal) return
        entries.remove(track)
        entries[track] = quality
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        prefs?.edit()
            ?.putString(KEY, entries.entries.joinToString("\n") { "${it.value.name}\t${it.key}" })
            ?.apply()
    }
}