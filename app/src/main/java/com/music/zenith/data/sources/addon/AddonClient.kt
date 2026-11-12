package com.music.zenith.data.sources.addon

import com.music.zenith.data.Http
import com.music.zenith.data.TrackLog
import com.music.zenith.data.settings.AppSettings
import com.music.zenith.data.sources.DeviceCodecs
import com.music.zenith.data.sources.module.SharedCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One addon server, and every question this app knows how to ask it.
 *
 * Three endpoints, all GETs returning JSON, none of them executing anything on
 * this side. Compare [ModuleManager][com.music.zenith.data.sources.module.ModuleManager],
 * which has to fetch an index, download a JavaScript file per entry and stand
 * up a QuickJS interpreter to ask the same two questions — the whole of that
 * apparatus is what this protocol replaces with a URL.
 *
 * One instance is held per configured source, so the manifest and the answers
 * shaped by it survive across tracks. Everything is shared through
 * [SharedCalls] on a scope that outlives any one caller, for the reason set out
 * there at length: the callers above this are *designed* to give up, and a
 * request abandoned after it has already reached somebody's server is work
 * their server did for nobody.
 */
class AddonClient(rawBaseUrl: String, private val pickedQuality: String? = null) {

    /** Where this addon lives, with `/manifest.json` and trailing slashes off. */
    val baseUrl: String = normalizeBase(rawBaseUrl)

    /**
     * Whether this client answers locally instead of over the network.
     *
     * True only for the Auris Hi-Res link — see [isMonoDirectUrl]. Its server
     * answers with a line of text and no manifest, so there is no addon to
     * speak to; the catalogue it names is answered here instead, from the
     * same public host Auris's own backend reads. Nothing else changes: the
     * manifest, the search rows and the stream answers are the same shapes
     * the remote protocol would have returned.
     */
    private val monoDirect: Boolean = isMonoDirectUrl(baseUrl)

    /**
     * Search results remembered briefly (id -> durationMs), so [monoStream]
     * can measure a file's real bitrate from its HEAD Content-Length. The
     * eldest entry goes when it fills; losing one only costs the measured
     * figures on that track, never the track itself.
     */
    private val monoDurations = ConcurrentHashMap<String, Long>()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val manifests = SharedCalls<AddonManifest>(MANIFEST_TTL_MS, calls, ::logReuse)
    private val searches = SharedCalls<AddonSearchResponse>(SEARCH_TTL_MS, calls, ::logReuse)
    private val streams = SharedCalls<AddonStream>(STREAM_TTL_MS, calls, ::logReuse)

    private fun logReuse(line: String) = TrackLog.d(TAG, line)

    /**
     * When this addon may be spoken to again, after it said it was being asked
     * too often.
     *
     * Per-instance and not per-request: a 429 is a statement about the server,
     * not about the one call that happened to draw it, so a search landing on
     * one holds back the stream lookup queued behind it too. Without that, a
     * fan-out simply re-trips the limiter with every sibling request and the
     * addon never gets the quiet it asked for.
     */
    @Volatile
    private var quietUntilMs = 0L

    // ── Manifest ──────────────────────────────────────────────────────────

    /**
     * What the addon says it is, or a failure explaining why it can't be used.
     *
     * The validation here is the gate the sources screen tests against, and it
     * is deliberately strict about exactly two things: an addon with no `id` is
     * not something that can be stored or told apart from another, and one that
     * does not declare both `search` and `stream` cannot take a track from a
     * query to audio. Failing at this point means the screen can say so while
     * the user is still looking at the URL they pasted, rather than the addon
     * being accepted and then silently returning nothing on every track.
     */
    suspend fun manifest(): Result<AddonManifest> =
        if (monoDirect) Result.success(MONO_MANIFEST) else manifests.get(
        key = baseUrl,
        describe = { "▶ addon manifest(${redact(baseUrl)})" },
    ) {
        fetch<AddonManifest>(manifestUrl(baseUrl)).mapCatching { manifest ->
            if (manifest.id.isBlank()) {
                throw AddonException("That URL answered, but not with an addon manifest")
            }
            if (!manifest.isPlayable) {
                val declared = manifest.resources.joinToString(", ")
                throw AddonException("This addon declares $declared — Zenith needs search")
            }
            manifest
        }.recoverCatching { failure ->
            // A manifest 404 is not "no such track", it is "no addon here" —
            // the one place the miss/fault distinction below has to be undone.
            if (failure is AddonNotFound) throw AddonException("No manifest at that URL")
            throw failure
        }
    }

    /**
     * Whether the search endpoint answers, asked without reference to a
     * manifest.
     *
     * The manifest is a *description* of an addon and the endpoints are the
     * addon. Most of the time the description is there and is accurate, and
     * reading it is cheaper than probing — but an addon that never published
     * one, or published it under a name nothing guessed, is still a perfectly
     * working addon, and refusing it because a document was missing is refusing
     * it for the wrong reason. So this exists as the second opinion: ask
     * `/search` and see whether something addon-shaped comes back.
     *
     * Returns the number of tracks in the answer. Zero is a success — an addon
     * that holds nothing for one query is answering correctly — so callers must
     * read the [Result] rather than the count.
     */
    suspend fun probeSearch(): Result<Int> =
        if (monoDirect) monoSearch(PROBE_QUERY).map { it.tracks.size }
        else fetch<AddonSearchResponse>(endpoint(listOf("search"), mapOf("q" to PROBE_QUERY)))
            .map { it.tracks.size }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Tracks the addon holds for [query].
     *
     * The protocol has no limit parameter — an addon returns what it returns —
     * so trimming the list is the caller's job. Nothing else here is negotiable
     * either: the spec fixes the path, the parameter name and the shape coming
     * back.
     */
    suspend fun search(query: String, tier: String): Result<List<AddonTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())
        val params = settingsFor(tier)
        if (monoDirect) {
            return searches.get(
                key = keyOf(trimmed, params.toString()),
                describe = { "▶ mono search q=\"$trimmed\" tier=$tier" },
            ) { monoSearch(trimmed) }.map { it.tracks }
        }
        return searches.get(
            // The parameters are in the key because they are in the request:
            // the same query at a different quality is a different question,
            // and an addon whose catalogue differs by tier would otherwise
            // answer the second one with the first one's rows.
            key = keyOf(trimmed, params.toString()),
            describe = { "▶ addon search(${redact(baseUrl)}) q=\"$trimmed\" tier=$tier" },
        ) {
            fetch<AddonSearchResponse>(endpoint(listOf("search"), params + ("q" to trimmed)))
        }.map { it.tracks }
    }

    // ── Stream ────────────────────────────────────────────────────────────

    /**
     * A playable URL for one of this addon's own track ids.
     *
     * [trackId] goes in as a path *segment* rather than being interpolated into
     * the string: an addon's ids are whatever its backend uses, and one holding
     * a `/` or a `?` would otherwise rewrite the request into a different
     * endpoint entirely.
     *
     * [stereoOnly] withholds the `?atmos=` hint — see [settingsFor]. It is for
     * a stereo row the listener picked by hand from the Addons search: there
     * `auto` is not an offer but an override, and the addon answers with the
     * Atmos sibling of the very row that was chosen over it.
     */
    suspend fun stream(trackId: String, tier: String, stereoOnly: Boolean = false): Result<AddonStream> {
        val params = settingsFor(tier, stereoOnly)
        if (monoDirect) {
            return streams.get(
                key = keyOf(trackId, params.toString()),
                describe = { "▶ mono stream id=$trackId tier=$tier" },
            ) { monoStream(trackId) }
        }
        return streams.get(
            key = keyOf(trackId, params.toString()),
            describe = { "▶ addon stream(${redact(baseUrl)}) id=$trackId tier=$tier" },
        ) {
            fetch<AddonStream>(endpoint(listOf("stream", trackId), params))
        }
    }

    // ── Mono direct ─────────────────────────────────────────────────────
    //
    // The Auris Hi-Res link is not an addon server: pasting it used to fail
    // at the manifest, because its host answers with a line of text ("This
    // addon is only compatible with Auris") and no manifest, search or
    // stream. The catalogue it names is real, though — the same public
    // direct-FLAC host Auris's own open-source backend queries, with no key
    // and no account — so this mode answers the three protocol questions
    // here, on the phone, instead of asking a server that has nothing to
    // say. No deploy and no gateway are needed for it: the phone reaches
    // the catalogue host directly, exactly as Auris's backend does.
    //
    // The mapping is the same one the gateway server uses, kept in step with
    // it on purpose: search rows carry no stream URL (so the measured
    // figures below are not skipped), the bitrate is measured from the
    // file's HEAD Content-Length against the duration seen at search time,
    // and a file below the lossless floor is a miss, not a stream.

    /**
     * Tracks the Mono catalogue holds for [query], in addon rows.
     *
     * The upstream answers `{tracks: [...]}`; a row that says it is not
     * playable, or carries no id, is dropped. The duration is in
     * milliseconds upstream and seconds in the row. A catalogue that does
     * not answer, or answers with something unreadable, is a transient
     * failure — the next source is tried, and the addon stays switched on.
     *
     * Transient failures are retried, like the generic transport: a dropped
     * connection, a 5xx, or a 429 gets two more attempts with backoff, and a
     * 429 sets the shared quiet period so the whole client backs off. A 4xx
     * that is not a 429, an empty body, or unreadable JSON is not retried —
     * asking again for the same bytes would answer the same way. Each track
     * is decoded on its own, so one malformed row cannot cost the whole
     * search its results.
     */
    private suspend fun monoSearch(query: String): Result<AddonSearchResponse> =
        withContext(Dispatchers.IO) {
            val url = MONO_UPSTREAM.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("search")
                ?.addPathSegment("tracks")
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("limit", MONO_SEARCH_LIMIT.toString())
                ?.build().toString()
                ?: return@withContext Result.failure(
                    AddonException("Mono catalogue address is not usable"),
                )
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", MONO_USER_AGENT)
                .build()
            var attempt = 0
            while (true) {
                (quietUntilMs - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) }
                val code: Int
                val payload: String
                val retryAfter: String?
                try {
                    client.newCall(request).execute().use { response ->
                        code = response.code
                        payload = response.body?.string().orEmpty()
                        retryAfter = response.header("Retry-After")
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (attempt >= MAX_RETRIES) {
                        return@withContext Result.failure(
                            AddonUnavailable("Mono catalogue unreachable: ${failure.message}"),
                        )
                    }
                    delay(retryAfterMs(null, attempt))
                    attempt++
                    continue
                }
                when {
                    code == 429 -> {
                        if (attempt >= MAX_RETRIES) {
                            return@withContext Result.failure(
                                AddonUnavailable("Mono catalogue is rate limiting Zenith"),
                            )
                        }
                        quietUntilMs = System.currentTimeMillis() + retryAfterMs(retryAfter, attempt)
                        attempt++
                        continue
                    }
                    code >= 500 -> {
                        if (attempt >= MAX_RETRIES) {
                            return@withContext Result.failure(
                                AddonUnavailable("Mono catalogue answered HTTP $code"),
                            )
                        }
                        delay(retryAfterMs(null, attempt))
                        attempt++
                        continue
                    }
                    code != 200 -> {
                        return@withContext Result.failure(
                            AddonUnavailable("Mono catalogue answered HTTP $code"),
                        )
                    }
                    payload.isBlank() -> {
                        return@withContext Result.failure(
                            AddonUnavailable("Mono catalogue answered empty"),
                        )
                    }
                    else -> return@withContext monoRows(payload)
                        .map { AddonSearchResponse(it) }
                }
            }
            // Unreachable: every path out of the loop returns.
            @Suppress("UNREACHABLE_CODE")
            Result.failure(AddonException("unreachable"))
        }

    /**
     * The addon rows in a catalogue search answer, decoded one track at a time.
     *
     * The top level is `{tracks: [...]}` — a bare array is accepted too, for a
     * host that answers with one. A row that fails to decode is skipped rather
     * than costing the whole search: the catalogue holds thousands of tracks
     * and one malformed entry is not a reason to return nothing. A top level
     * that is neither shape is unreadable, and fails the search.
     */
    private fun monoRows(payload: String): Result<List<AddonTrack>> {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull()
            ?: return Result.failure(AddonUnavailable("Mono catalogue returned bad JSON"))
        val elements = when (root) {
            is JsonObject -> root["tracks"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return Result.failure(AddonUnavailable("Mono catalogue returned bad JSON"))
        val tracks = elements.mapNotNull { element ->
            val track = runCatching {
                // No reified overload here in this kotlinx version — the
                // explicit serializer is the call that resolves.
                json.decodeFromJsonElement(MonoUpstreamTrack.serializer(), element)
            }.getOrNull() ?: return@mapNotNull null
            val id = (track.id as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if ((track.playable as? JsonPrimitive)?.content == "false") {
                return@mapNotNull null
            }
            val durationMs = (track.duration as? JsonPrimitive)?.content
                ?.toDoubleOrNull()?.takeIf { it > 0 }?.toLong()
            val artists = (track.artistNames as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            if (durationMs != null) monoMemoize(id, durationMs)
            AddonTrack(
                id = id,
                title = track.title,
                artist = artists.joinToString(", "),
                album = track.albumTitle ?: track.releaseTitle ?: "",
                duration = durationMs?.let { it / 1000.0 },
                artworkURL = track.artwork,
                format = "flac",
                audioQuality = "LOSSLESS",
            )
        }
        return Result.success(tracks)
    }

    /**
     * A playable URL for one of the Mono catalogue's own track ids.
     *
     * The file is found by [monoFileSizeBytes]: its size against the duration
     * remembered from the search is the file's real bitrate, measured the
     * way Auris's backend measures it. A file below the lossless floor is
     * a transcode in a FLAC container and is refused as a miss, exactly as
     * that backend refuses it. A size that could not be measured does not fail
     * the track — the search already proved the id exists, so the URL is
     * handed over unmeasured and the player tries it.
     */
    private suspend fun monoStream(trackId: String): Result<AddonStream> =
        withContext(Dispatchers.IO) {
            // Monochrome ids are numeric strings. Anything else can never be a track.
            if (trackId.isEmpty() || trackId.any { !it.isDigit() }) {
                return@withContext Result.failure(AddonNotFound())
            }
            val fileUrl = "$MONO_UPSTREAM/track/$trackId"
            val lengthBytes = monoFileSizeBytes(fileUrl)
                ?: return@withContext Result.failure(AddonNotFound())
            val durationMs = monoDurations[trackId]
            val kbps = if (lengthBytes > 0 && durationMs != null && durationMs > 0) {
                (lengthBytes * 8.0 / durationMs).roundToInt()
            } else {
                null
            }
            if (kbps != null && kbps < MONO_MIN_KBPS) {
                return@withContext Result.failure(AddonNotFound())
            }
            Result.success(
                AddonStream(
                    url = fileUrl,
                    format = "flac",
                    quality = monoQualityLabel(kbps),
                    audioQuality = "LOSSLESS",
                    codec = "flac",
                    container = "flac",
                    manifest = "none",
                    mimeType = "audio/flac",
                    encrypted = JsonPrimitive(false),
                    bitrate = kbps?.toDouble(),
                    bitDepth = kbps?.let { if (it > MONO_CD_KBPS) 24.0 else 16.0 },
                    sampleRate = kbps?.let { if (it > MONO_HIRES_KBPS) 96000.0 else 44100.0 },
                ),
            )
        }

    /**
     * The file's size in bytes, or null when the host says there is no such file.
     *
     * HEAD's Content-Length is the first try, but the host fronts Cloudflare and
     * the header is not always sent — verified live, a HEAD that answers 200 can
     * carry no Content-Length at all. A ranged GET of one byte is the reliable
     * one: its Content-Range total is the file's size whether HEAD sent a length
     * or not. A HEAD that fails outright gets the same ranged try before the
     * track is handed over unmeasured. 0 is a file whose size could not be
     * measured, which still plays, just without measured figures.
     *
     * A 429 or 5xx from HEAD is the host being busy, not the file being gone,
     * so it is treated like a HEAD that never answered rather than a miss.
     * Called only from [monoStream], which runs on Dispatchers.IO — the calls
     * below block, as that path already does.
     */
    private fun monoFileSizeBytes(fileUrl: String): Long? {
        var headStatus: Int? = null
        var lengthBytes = 0L
        try {
            val request = Request.Builder().url(fileUrl)
                .head()
                .header("User-Agent", MONO_USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                headStatus = response.code
                if (headStatus == 200 || headStatus == 206) {
                    lengthBytes = response.header("Content-Length")?.toLongOrNull() ?: 0L
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // HEAD never answered; the ranged try below decides.
        }
        val status = headStatus
        if (status != null && status != 200 && status != 206) {
            if (status == 429 || status >= 500) {
                // Transient: the host is busy, not saying the file is gone.
                headStatus = null
            } else {
                // A 4xx here is the host saying it has no such file — a miss,
                // which the caller reads as "try the next source".
                return null
            }
        }
        if (lengthBytes > 0) return lengthBytes
        var rangedMissing = false
        try {
            val request = Request.Builder().url(fileUrl)
                .header("User-Agent", MONO_USER_AGENT)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404 && headStatus == null) {
                    // The host says there is no such file, and HEAD never
                    // confirmed otherwise.
                    rangedMissing = true
                } else {
                    // A 206 carries the total in Content-Range; a 200 that
                    // ignored the range carries the whole file in
                    // Content-Length. A 206 without Content-Range must not
                    // fall back to its Content-Length, which is the one
                    // ranged byte, not the file.
                    lengthBytes = when (response.code) {
                        206 -> response.header("Content-Range")
                            ?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                        200 -> response.header("Content-Length")?.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The size stays unmeasured; the track still plays.
        }
        if (rangedMissing) return null
        return lengthBytes
    }

    /**
     * The quality label for a measured bitrate, in Auris's own words.
     *
     * CD PCM is 1411.2 kbps and hi-res PCM 2116.8 kbps, so those are the
     * lines a file is judged against; unmeasured, it is lossless and
     * nothing more specific is claimed.
     */
    private fun monoQualityLabel(kbps: Int?): String {
        if (kbps == null) return "FLAC · Lossless"
        val bits = if (kbps > MONO_CD_KBPS) 24 else 16
        val rate = if (kbps > MONO_HIRES_KBPS) "96 kHz" else "44.1 kHz"
        return "FLAC · $bits-bit · $rate"
    }

    /** Remember a search result's duration for [monoStream]; an arbitrary entry goes when it fills. */
    private fun monoMemoize(id: String, durationMs: Long) {
        if (monoDurations.size >= MONO_MAX_MEMOIZED) {
            monoDurations.keys.firstOrNull()?.let { monoDurations.remove(it) }
        }
        monoDurations[id] = durationMs
    }

    // ── Settings passthrough ──────────────────────────────────────────────

    /**
     * The query parameters that travel with every request.
     *
     * Two layers, and the order between them is the point. The addon's own
     * declared defaults go on first, because that is what the reference host
     * sends when the user has changed nothing, and an addon is entitled to
     * assume it. Then `quality` is set to what this app is actually asking for,
     * overriding a declared default of the same key — a request for lossless
     * that arrived carrying the manifest's `"default": "high"` is a request for
     * lossless that comes back at 320kbps.
     *
     * The value sent for `quality` is negotiated rather than asserted. This
     * app's tiers are `LOSSLESS`/`HIGH`/`LOW`, and an addon declaring a
     * `quality` select of `high|normal|low` understands none of them — sending
     * `LOSSLESS` there is not a strong ask, it is an invalid value that lands on
     * the addon's fallback. So when the manifest enumerates options, the closest
     * one is chosen from that list; only when it does not is the tier name sent
     * as-is, which is what the reference host does unconditionally.
     *
     * The manifest is read best-effort. It is normally already in hand — it was
     * fetched to add the addon in the first place, and is shared and cached —
     * but an addon whose manifest is briefly unreachable while its search
     * endpoint is fine should still be searchable, one tier hint the poorer,
     * rather than failing outright on a document nothing here strictly needs.
     *
     * Last comes `atmos`, which is the one parameter here that is not about
     * quality. See [atmosHint].
     */
    private suspend fun settingsFor(tier: String, stereoOnly: Boolean = false): Map<String, String> {
        val manifest = manifest().getOrNull()
        val declared = manifest?.settings.orEmpty()
        val params = LinkedHashMap<String, String>()
        declared.forEach { setting ->
            val key = setting.key.takeIf { it.isNotBlank() } ?: return@forEach
            setting.defaultValue?.let { params[key] = it }
        }
        if (tier.isNotBlank()) {
            val options = declared.firstOrNull { it.key == QUALITY_KEY }
                ?.options.orEmpty()
                .mapNotNull { it.stringValue }
            // Asking for the best it can get, the app sends the listener's pick
            // for this addon, or the first option listed when there is none. A
            // pick the addon has since stopped listing is dropped, since it
            // would land on the addon's fallback; with no manifest in hand
            // there is nothing to judge it against, so it goes as chosen.
            // A lower tier is a setting saying what a file may cost (a
            // download kept at High, a capped connection), and a pick does not
            // get to overrule it, so that is still negotiated by [matchTier].
            val chosen = when {
                tier != TIER_LOSSLESS -> matchTier(tier, options)
                manifest == null -> pickedQuality
                else -> pickedQuality?.takeIf { it in options } ?: options.firstOrNull()
            }
            params[QUALITY_KEY] = chosen ?: tier
        }
        if (stereoOnly) {
            // Asked for the stereo mix by name, so nothing that lets the addon
            // swap in an immersive one: no hint, and a declared `atmos`
            // setting pinned to its "off" value rather than its default.
            val atmosSetting = declared.firstOrNull { it.key == ATMOS_KEY }
            if (atmosSetting == null) {
                params.remove(ATMOS_KEY)
            } else {
                params[ATMOS_KEY] = atmosSetting.options
                    .mapNotNull { it.stringValue }
                    .firstOrNull { ATMOS_OFF.matches(it) }
                    ?: ATMOS_NEVER
            }
        } else {
            atmosHint()?.let { params.putIfAbsent(ATMOS_KEY, it) }
        }
        return params
    }

    /**
     * `auto` when this device and this user both want immersive audio, null
     * otherwise — the standard `?atmos=` hint the spec defines for hosts.
     *
     * Without it an addon holding an Atmos mix has no way to know it may serve
     * one, so it takes its default branch and sends stereo. That is the whole
     * of why Zenith never heard Atmos from an addon: not a detection bug —
     * [AddonStream.isDolbyAtmos] reads every spelling an addon sends — but a
     * question that was never asked.
     *
     * Three things about the shape of this are deliberate:
     *
     *  - **The value is exactly `auto`.** `1`/`true` select the spec's *strict*
     *    mode, where a track with no immersive mix is an error rather than a
     *    stereo answer — which would turn every ordinary track on an
     *    Atmos-capable addon into a miss. `auto` is try-Atmos-then-stereo.
     *  - **It is asked for, not asserted.** Both gates are the same two the
     *    playback side enforces in
     *    [ModuleSource.unplayable][com.music.zenith.data.sources.ModuleSource.unplayable],
     *    so an addon is only ever offered Atmos when an Atmos answer would
     *    actually be accepted. With either gate shut the parameter is absent
     *    entirely and the addon's own default — stereo — stands.
     *  - **[putIfAbsent], never an override.** An addon that declared its own
     *    `atmos` setting has said what it wants; the manifest's default wins
     *    and this adds nothing. Addons that have never heard of the parameter
     *    ignore it, which is the same basis on which `quality` is already sent
     *    unconditionally.
     *
     * Nothing here names a particular addon. The decoder probe is cached per
     * process and the settings read is a flow's current value, so this costs
     * nothing on the IO thread it already runs on.
     */
    private fun atmosHint(): String? {
        val wanted = DeviceCodecs.atmosWanted
        return if (wanted) ATMOS_AUTO else null
    }

    /**
     * The option in [options] that best answers a request for [tier], or null
     * when the addon enumerated nothing to choose from.
     *
     * Matched on what an option *says* rather than on its position, so an addon
     * listing its tiers best-first and one listing them worst-first are both
     * read correctly. Failing a word match it falls to the first option listed,
     * which an addon that bothered to order them makes its recommended tier.
     */
    private fun matchTier(tier: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.equals(tier, ignoreCase = true) }?.let { return it }
        val wanted = when (tier.uppercase()) {
            TIER_LOSSLESS -> LOSSLESS_WORDS
            TIER_LOW -> LOW_WORDS
            else -> HIGH_WORDS
        }
        return options.firstOrNull { option -> wanted.any { it in option.lowercase() } }
            ?: options.first()
    }

    // ── Transport ─────────────────────────────────────────────────────────

    /** `{base}/{segments…}?{params}`, with every part properly encoded. */
    private fun endpoint(segments: List<String>, params: Map<String, String>): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw AddonException("That is not a usable address")
        val builder = base.newBuilder()
        segments.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> fetch(url: String): Result<T> =
        runCatching { json.decodeFromString<T>(body(url)) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                // A miss is not worth a line. Everything else is: this is the
                // only place that knows why an addon didn't answer.
                if (failure !is AddonNotFound) {
                    TrackLog.w(TAG, "  ✗ addon call failed ${redact(url)}: ${failure.message}")
                }
            }

    /**
     * One GET, with the addon's own back-pressure honoured.
     *
     * A 429 is waited out rather than surfaced, because the alternative is a
     * source that drops out of the walk the moment it gets busy — and every
     * caller above this reads a failure as "this addon does not have the
     * track", which is a different and wrong conclusion. `Retry-After` is
     * believed when sent; otherwise the wait doubles. Both are capped, so a
     * rate-limited addon delays a track rather than stalling it.
     *
     * A 404 is deliberately its own exception. On `/stream/{id}` it is the
     * protocol's way of saying this addon does not hold that recording, which
     * is precisely the miss the caller is equipped to handle and not something
     * to log as a fault.
     */
    private suspend fun body(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            (quietUntilMs - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) }

            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()

            val wait = client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful ->
                        return@withContext response.body?.string()?.takeIf { it.isNotBlank() }
                            ?: throw AddonException("Empty response")
                    response.code == 404 -> throw AddonNotFound()
                    // A 5xx is a server having a bad minute and will be tried
                    // again on the next track; a 4xx is this app asking for
                    // something it will never be given — a wrong URL, a token
                    // that has expired — and no amount of retrying fixes it.
                    // The sources screen renders the two differently, and
                    // painting a transient outage as a configuration error
                    // sends people to re-paste a URL that was always correct.
                    response.code >= 500 -> throw AddonUnavailable("HTTP ${response.code}")
                    response.code != 429 -> throw AddonException("HTTP ${response.code}")
                    attempt >= MAX_RETRIES -> throw AddonUnavailable("This addon is rate limiting Zenith")
                    else -> retryAfterMs(response.header("Retry-After"), attempt)
                }
            }
            quietUntilMs = System.currentTimeMillis() + wait
            attempt++
        }
        // Unreachable: every path out of the loop returns or throws.
        @Suppress("UNREACHABLE_CODE")
        throw AddonException("unreachable")
    }

    /** How long to wait after a 429, from the header when it sent one. */
    private fun retryAfterMs(header: String?, attempt: Int): Long {
        val stated = header?.trim()?.toDoubleOrNull()?.times(1000)?.toLong()
        return (stated ?: (BACKOFF_BASE_MS shl attempt)).coerceIn(BACKOFF_BASE_MS, BACKOFF_CAP_MS)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Everything held about this addon, dropped.
     *
     * Reached when the source is edited or removed, at which point the manifest
     * and every answer shaped by it describe a server that is no longer the one
     * selected.
     */
    fun clear() {
        manifests.clear()
        searches.clear()
        streams.clear()
        quietUntilMs = 0L
    }

    /**
     * Makes the next catalogue and stream lookups real requests without
     * disturbing a request already in flight.
     *
     * Used only for the listener's explicit "Upgrade quality" action. Normal
     * playback keeps those caches, but a manual retry must be able to recover
     * immediately from an addon which answered empty while one of its backends
     * was unavailable or handed out a URL that did not work.
     */
    fun clearCompletedTrackCalls() {
        searches.clearCompleted()
        streams.clearCompleted()
    }

    /** Parts joined length-prefixed, so no byte a part may contain can act as a delimiter. */
    private fun keyOf(vararg parts: String) = parts.joinToString("|") { "${it.length}:$it" }

    companion object {
        private const val TAG = "Zenith"

        /**
         * The Auris Hi-Res link, and the host it lives on.
         *
         * The link itself is a static refusal page — "This addon is only
         * compatible with Auris" — with no manifest behind it, so no fetch
         * of it could ever settle as an addon. This fork answers any address
         * on that host locally instead ([monoDirect]), serving the catalogue
         * the link names from the public host Auris's own backend reads.
         * Matching the host rather than the one slug covers the link as
         * pasted, the bare host, and a trailing slash; the host publishes
         * nothing else this app could mistake for an addon.
         */
        const val AURIS_ADDON_HOST = "auris.shehriyar.ink"

        /** Whether [raw] names the Auris link, in whatever form it was pasted. */
        fun isMonoDirectUrl(raw: String): Boolean {
            val url = normalizeBase(raw.trim()).toHttpUrlOrNull() ?: return false
            return url.host == AURIS_ADDON_HOST
        }

        /**
         * The manifest the Auris link is answered with.
         *
         * The same shape the gateway server publishes, so a source added
         * from that link and one added from a deployed gateway are the same
         * addon by id, name and quality options — only where the answers
         * come from differs.
         */
        val MONO_MANIFEST = AddonManifest(
            id = "zenith-mono-hires",
            name = "Mono Hi-Res Lossless",
            version = "1.0.0",
            resources = listOf("search", "stream"),
            settings = listOf(
                AddonSetting(
                    key = QUALITY_KEY,
                    default = JsonPrimitive("LOSSLESS"),
                    options = listOf(
                        AddonSettingOption(JsonPrimitive("LOSSLESS"), JsonPrimitive("Lossless")),
                        AddonSettingOption(JsonPrimitive("HIGH"), JsonPrimitive("High")),
                        AddonSettingOption(JsonPrimitive("LOW"), JsonPrimitive("Low")),
                    ),
                ),
            ),
        )

        /** The direct-FLAC catalogue, reached from the phone with no key and no account. */
        private const val MONO_UPSTREAM = "https://tracks.monochrome.st"

        /** The neutral browser string Auris's own backend sends. The catalogue host answers it. */
        private const val MONO_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val MONO_SEARCH_LIMIT = 25
        private const val MONO_MAX_MEMOIZED = 256

        /** Bitrate floors, mirrored from Auris's backend: CD PCM 1411.2 kbps, hi-res PCM 2116.8 kbps. */
        private const val MONO_CD_KBPS = 1411
        private const val MONO_HIRES_KBPS = 2116

        /** Below this a "FLAC" is a transcode in a lossless container, and is refused. */
        private const val MONO_MIN_KBPS = 300

        /** The tiers [AddonSource][com.music.zenith.data.sources.AddonSource] asks in. */
        const val TIER_LOSSLESS = "LOSSLESS"
        const val TIER_HIGH = "HIGH"
        const val TIER_LOW = "LOW"

        internal const val QUALITY_KEY = "quality"

        /** The spec's immersive-audio hint: `auto` normally, an "off" only for a stereo row picked by hand. */
        private const val ATMOS_KEY = "atmos"
        private const val ATMOS_AUTO = "auto"
        private const val ATMOS_NEVER = "0"
        private val ATMOS_OFF = Regex("0|false|off|no|none|never|stereo", RegexOption.IGNORE_CASE)

        private val LOSSLESS_WORDS = listOf("lossless", "flac", "hifi", "hi-res", "hires", "max", "best")
        private val HIGH_WORDS = listOf("high", "320", "normal", "standard")
        private val LOW_WORDS = listOf("low", "96", "128", "min")

        private const val MANIFEST_SUFFIX = "/manifest.json"

        /**
         * A base URL from whatever the user pasted.
         *
         * Both forms the spec names are accepted — `https://host/` and
         * `https://host/manifest.json` — because both are what people copy: an
         * addon's page lists one and the browser's address bar shows the other.
         * A token-bearing URL is left entirely intact apart from that filename,
         * since the token is *in the path* and every later call has to carry it.
         *
         * Any `.json` filename is stripped, not only `manifest.json`. The spec
         * names that one, but the box this comes from now accepts any JSON
         * document and identifies it — see [SourceFormats] — so a manifest
         * someone serves as `addon.json` should give the same base rather than
         * a base with a document on the end of it, which would send `/search`
         * to `…/addon.json/search`.
         */
        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val lastSegment = trimmed.substringAfterLast('/', "")
            return if (lastSegment.endsWith(".json", ignoreCase = true) && trimmed.contains("://")) {
                trimmed.dropLast(lastSegment.length).trimEnd('/')
            } else {
                trimmed
            }
        }

        fun manifestUrl(base: String): String = "$base$MANIFEST_SUFFIX"

        /**
         * An addon URL with its path hidden, for logging.
         *
         * The path is what is sensitive here, which is the opposite of the
         * module index's redaction and for a concrete reason: this protocol
         * puts a user's private token in the path — `https://host/{token}/…` —
         * so a log line carrying the path carries the credential. The host is
         * kept, because it is what makes a line traceable to an addon at all
         * and it is not the secret.
         */
        fun redact(url: String): String =
            url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/***" } ?: "***"

        /**
         * How long a manifest is trusted.
         *
         * The spec's own update story is "just update your server" — the host
         * re-fetches the manifest and users never reinstall — so this cannot be
         * cached for the session. Ten minutes picks up a new settings schema or
         * declared resource within one sitting, while costing one fetch across a
         * run of tracks.
         */
        const val MANIFEST_TTL_MS = 10 * 60 * 1000L

        /** Whether a catalogue holds a recording is not a fact that turns over minute to minute. */
        const val SEARCH_TTL_MS = 10 * 60 * 1000L

        /**
         * How long a stream URL is reused — short, because its expiry is not
         * ours to set. The spec has addons state `expiresAt`, and the reference
         * host assumes an hour when they don't; five minutes sits far enough
         * inside either to be certain, while still covering what actually
         * repeats: the two candidates one match tries in turn, and the second
         * look arriving at the same track moments later.
         */
        const val STREAM_TTL_MS = 5 * 60 * 1000L

        private const val MAX_RETRIES = 2
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 8_000L

        private val USER_AGENT = "Zenith/v${com.music.zenith.BuildConfig.VERSION_NAME}"

        /**
         * What [probeSearch] asks for. Deliberately an ordinary word rather
         * than a nonsense string: an addon is entitled to return nothing for a
         * query nobody could match, and a probe that reliably comes back empty
         * proves less than one that usually comes back full.
         */
        private const val PROBE_QUERY = "music"

        /**
         * The shared client, with a ceiling on the whole call.
         *
         * Derived from [Http.client] rather than built beside it, so the
         * connection pool, dispatcher and DNS stay shared with the rest of the
         * app. What is added is [OkHttpClient.Builder.callTimeout]: the base
         * client bounds each *read*, which a server dribbling a byte at a time
         * never trips, and an addon doing that would otherwise hold a
         * source-resolution slot indefinitely. The spec asks addons to answer
         * search in under five seconds and streams in under three; the ceiling
         * is wider than that because a TorBox-backed addon cold-caching a long
         * FLAC spends twenty to thirty seconds before it can answer at all —
         * thirty-two was the worst a real Nebula addon took, and 35 s left no
         * room once the call itself is added to the wait. The read timeout is
         * lifted above the ceiling for the same reason: that wait is silence,
         * not a slow trickle, so an inherited read timeout would fire first and
         * the ceiling here would never be reached.
         */
        private val client: OkHttpClient by lazy {
            Http.client.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}

/**
 * Something about this addon or its URL that trying again will not fix — a
 * document that is not a manifest, an addon that cannot stream, a token that
 * is no longer accepted. Surfaced as
 * [SourceHealth.Rejected][com.music.zenith.data.sources.SourceHealth.Rejected],
 * with a line fit to show a user.
 */
class AddonException(message: String) : Exception(message)

/**
 * The addon is momentarily not answering — down, overloaded, rate limiting.
 * Worth keeping switched on and worth asking again on the next track, which is
 * the whole distinction
 * [SourceHealth][com.music.zenith.data.sources.SourceHealth] draws.
 */
class AddonUnavailable(message: String) : Exception(message)

/** The addon answered, and does not have what was asked for. A miss, not a fault. */
class AddonNotFound : Exception("Not held by this addon")

/**
 * The Monochrome catalogue's own shapes, as [AddonClient.monoSearch] reads them.
 *
 * Kept separate from the addon protocol's shapes above: these are what the
 * catalogue host sends, and the mapping into addon rows is the client's job.
 * The loosely-typed fields are deliberate — `id`, `duration`, `playable`
 * and `artistNames` arrive in more than one spelling across the host's
 * answers, and a row that costs the whole search to parse is a row that
 * should have been skipped instead.
 */
@Serializable
private data class MonoUpstreamTrack(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("title") val title: String = "",
    @SerialName("artistNames") val artistNames: JsonElement? = null,
    @SerialName("albumTitle") val albumTitle: String? = null,
    @SerialName("releaseTitle") val releaseTitle: String? = null,
    /** Milliseconds upstream; seconds in the addon row. */
    @SerialName("duration") val duration: JsonElement? = null,
    @SerialName("artwork") val artwork: String? = null,
    @SerialName("playable") val playable: JsonElement? = null,
)
