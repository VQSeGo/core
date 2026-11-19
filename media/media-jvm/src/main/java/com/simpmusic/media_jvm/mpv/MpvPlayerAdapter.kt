package com.simpmusic.media_jvm.mpv

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.AppOnScreen
import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.AudioOutputKind
import com.maxrave.domain.data.player.EightDEffect
import com.maxrave.domain.data.player.EightDOrbit
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.mediaservice.player.MusicEnd
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.simpmusic.media_jvm.audio.ReverbIrFiles
import com.simpmusic.media_jvm.cache.ListenCache
import com.simpmusic.media_jvm.download.getDownloadPath
import com.simpmusic.media_jvm.memory.MemoryTrimmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "MpvPlayerAdapter"

/** The longest precaching waits for the current track, see awaitCurrentTrackUnderway. */
private const val PRECACHE_WAIT_MS = 20_000L
private const val PRECACHE_POLL_MS = 250L

/**
 * mpv (libmpv via JNA) implementation of [MediaPlayerInterface].
 *
 * Structural port of `VlcPlayerAdapter`: the queue, shuffle order, repeat modes, precaching,
 * crossfade stepping, the DJ/BPM/Camelot-key system, URL resolution and 403 retry handling are
 * carried over unchanged. Only the native-call layer differs — see [MpvPlayer] for the vlcj →
 * libmpv mapping, and [buildEdlUrl] for the `:input-slave` → `edl://` replacement.
 *
 * Differences from the VLC backend that are inherent to mpv rather than choices:
 *  - There is no factory object; each [MpvPlayer] owns its own `mpv_handle`, so there is nothing
 *    to release globally in [release].
 *  - Events are pulled on a per-handle pump thread instead of pushed from a native thread, so the
 *    "never call stop()/release() from inside a callback" hazard that VLC has does not exist here.
 *  - Video frames come from mpv's software render API on a thread this backend owns, rather than
 *    from a vlcj render callback — see [MpvVideoFrameSource].
 */
class MpvPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val downloadHandler: DownloadHandler,
    private val listenCache: ListenCache,
) : MediaPlayerInterface {
    // Internal state enum for proper state machine
    private enum class InternalState {
        IDLE,
        PREPARING,
        READY,
        PLAYING,
        PAUSED,
        ENDED,
        ERROR,
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    init {
        if (MpvLibrary.INSTANCE == null) {
            Logger.e(TAG, "libmpv not available — playback will fail until mpv is installed.")
        }

        // Load crossfade settings
        coroutineScope.launch {
            dataStoreManager.crossfadeEnabled.collect { enabled ->
                crossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Crossfade enabled: $crossfadeEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeDuration.collect { duration ->
                crossfadeDurationMs = duration
                Logger.d(TAG, "Crossfade duration: $crossfadeDurationMs ms")
            }
        }

        coroutineScope.launch {
            dataStoreManager.watchVideoInsteadOfPlayingAudio.collect { enabled ->
                watchVideoEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Watch video enabled: $watchVideoEnabled")
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeSkipAlbum.collect { enabled ->
                skipCrossfadeInAlbum = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Skip crossfade inside album: $skipCrossfadeInAlbum")
            }
        }

        // No window on screen: stop decoding video on every handle that has it, and pick it back up
        // when a window returns. Handles created meanwhile get the same in applyPlaybackLevels().
        // drop(1): the current value needs nothing done, and acting on it here could run before the
        // handle collections below are initialised.
        coroutineScope.launch {
            AppOnScreen.onScreen.drop(1).collect { onScreen ->
                forEachLiveHandle { it.setVideoSuspended(!onScreen) }
            }
        }
    }

    // ========== State Management ==========
    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: MpvPlayer? = null

    // Tracks whether the current player is actually rendering video
    // (based on PlayableSource.isVideo, not GenericMediaItem metadata)
    @Volatile
    private var currentPlayerIsVideo = false

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var internalVolume = 1.0f

    /** Sleep-timer fade gain, kept apart from [internalVolume] so the user's level survives it. */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    private val eightDOrbit = EightDOrbit()
    private val _eightDAvailable = MutableStateFlow(true)
    override val eightDAvailable: StateFlow<Boolean> = _eightDAvailable.asStateFlow()
    private var eightDCleanupJob: Job? = null

    /**
     * Pitch shift the user asked for, 1.0 = unchanged.
     *
     * Only applied while crossfade is off. The crossfade wipes the pitch entry of mpv's filter
     * chain when a transition ends, so the two cannot both drive it. The UI locks the pitch
     * control whenever crossfade is enabled.
     */
    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    // Position tracking
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    // Position update job (fallback polling for crossfade detection)
    private var positionUpdateJob: Job? = null

    // Precaching system
    private class PrecachedPlayer(
        val player: MpvPlayer,
        val mediaItem: GenericMediaItem,
    ) {
        /** Set on the event pump thread the moment mpv reports the load failed, so a promotion racing the
         *  retry sees it; cleared again when a retry has loaded the file. */
        @Volatile
        var failed = false

        /** Retries spent on this handle, up to [maxRetryCount]. */
        var attempts = 0
    }

    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // Crossfade system
    @Volatile
    private var crossfadeEnabled = false

    /** See MediaPlayerInterface.crossfadeSuppressed — set while in a Listen Together room. */
    override var crossfadeSuppressed: Boolean = false

    @Volatile
    private var crossfadeDurationMs = 5000

    // Whether video content plays as video (watch-video setting) — the same condition
    // extractPlayableUrl uses to include the video stream in the edl:// merged source.
    @Volatile
    private var watchVideoEnabled = false

    /** User setting: leave transitions inside an album alone. */
    @Volatile
    private var skipCrossfadeInAlbum = false

    /** Set by the handler when an album is loaded; empty for every other kind of queue. */
    @Volatile
    private var internalAlbumTrackIds: Set<String> = emptySet()

    @Volatile
    private var secondaryPlayer: MpvPlayer? = null

    @Volatile
    private var crossfadeJob: Job? = null

    @Volatile
    private var isCrossfading = false

    /** Index we're crossfading from; used when cancelling to revert localCurrentMediaItemIndex. */
    @Volatile
    private var crossfadeFromIndex = -1

    /** Last index a crossfade was refused for, so the refusal is logged once instead of per poll. */
    private var lastBlockedCrossfadeIndex = -1

    /**
     * Measured music ends by video id ([MpvMusicEndReader]); -1 when it could not be told. Read only
     * while skip silence is in force.
     */
    private val musicEnds = ConcurrentHashMap<String, Long>()

    /** Video ids whose music end has been asked for in this session, so each is measured once. */
    private val musicEndRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The handle [checkMusicEnd] already ended at its music end, so it is ended once. */
    private var musicEndHandledFor: MpvPlayer? = null

    /**
     * Set only while [commitIncomingAsCurrent] tears down a fade. [performCrossfade]'s
     * cancellation handler releases the incoming player by default; during a commit we are
     * promoting that very player, so it must be kept alive.
     */
    @Volatile
    private var committingIncomingPlayer = false

    private fun setCrossfading(value: Boolean) {
        if (isCrossfading != value) {
            isCrossfading = value
            notifyListeners { onCrossfadeStateChanged(value) }
        }
    }

    // Retry system - mirrors Android CrossfadeExoPlayerAdapter retry logic
    private var retryCount = 0
    private var retryVideoId: String? = null

    // Tracks whose downloaded file was already dropped this session (see healCorruptDownload).
    private val healedVideoIds = mutableSetOf<String>()

    /**
     * Which track and source each handle was opened with, whichever path opened it (a load, a
     * precache, a crossfade): the listening cache saves from that URL, and a failing handle on a
     * cached file is how a broken cache file is recognised. Weak, so released handles fall out.
     */
    private val playerOrigins: MutableMap<MpvPlayer, Pair<String, PlayableSource>> =
        Collections.synchronizedMap(WeakHashMap())

    // Listening time of the current handle, for the listening cache (see trackListening).
    private var listenedPlayer: MpvPlayer? = null
    private var listenedMs = 0L
    private var listenTickNs = 0L
    private var listenOffered = false

    /** The track whose load started last and when, for the `resolve <id> sound` timing line. */
    @Volatile
    private var soundPending: Pair<String, Long>? = null

    // Where a stream last died early, so a track that always dies at one spot is eventually let go.
    private var earlyEndVideoId: String? = null
    private var earlyEndPositionMs = 0L
    private var earlyEndRetries = 0
    private val maxRetryCount = 2

    // Playlist management
    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    /**
     * Tracks that keep their place right after the current one when a track is appended under
     * shuffle: one put there with Play next, and the one a crossfade has picked. Held by identity,
     * since a queue can hold the same song twice.
     */
    private val pinnedNextItems: MutableSet<GenericMediaItem> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<GenericMediaItem, Boolean>()))

    // Loading management
    private var currentLoadJob: Job? = null

    fun getCurrentPlayer(): MpvPlayer? = currentPlayer

    // Video surface state - UI collects this to display video.
    private val _currentVideoFrames = MutableStateFlow<MpvVideoFrameSource?>(null)

    /** Frame source of the player whose video should be on screen, or null when the current track has no video. */
    val currentVideoFrames: StateFlow<MpvVideoFrameSource?> = _currentVideoFrames.asStateFlow()

    // ========== Playback Source ==========
    private data class PlayableSource(
        val isVideo: Boolean,
        val url: String,
        val audioSlaveUrl: String? = null, // For merging: audio URL, becomes a second edl:// stream
        // A live broadcast's HLS playlist; its handle is opened differently (MpvPlayer.create).
        val isLive: Boolean = false,
    )

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (current state: $internalState)")
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        // At the end of the queue the handle is parked at EOF, and telling mpv to
                        // play there does nothing — the press would look ignored. Rewind first so
                        // the last track replays, which is what the Play button is offering.
                        if (internalState == InternalState.ENDED) {
                            Logger.d(TAG, "Play: replaying from the start after end of queue")
                            player.seekTo(0L)
                            cachedPosition = 0L
                        }
                        Logger.d(TAG, "Play: calling mpv play")
                        player.play()
                        transitionToState(InternalState.PLAYING)
                        internalPlayWhenReady = true
                    } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                }

                InternalState.PREPARING -> {
                    if (!cachedIsLoading) {
                        cachedIsLoading = true
                        notifyListeners { onIsLoadingChanged(true) }
                    }
                    internalPlayWhenReady = true
                    Logger.d(TAG, "Play: During PREPARING - will auto-play when ready")
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                    cachedIsLoading = false
                }

                InternalState.ERROR -> {
                    // The track failed to load and nothing is left to unpause: load it again. If
                    // it fails again the handler, seeing Play was asked for, moves on.
                    Logger.d(TAG, "Play: retrying the track that failed")
                    internalPlayWhenReady = true
                    seekTo(localCurrentMediaItemIndex, cachedPosition.coerceAtLeast(0L))
                }

                else -> {
                    Logger.w(TAG, "Play: Called in invalid state: $internalState")
                }
            }
        }
    }

    override fun pause() {
        Logger.d(TAG, "pause() called (current state: $internalState)")
        coroutineScope.launch {
            try {
                // Pausing mid-crossfade stays on A+1 — the track the UI already shows — and freezes
                // it in place via the when(internalState) block below. It does NOT jump back to A.
                if (isCrossfading) {
                    Logger.d(TAG, "Pause: committing incoming (A+1) and pausing in place")
                    commitIncomingAsCurrent()
                }

                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            Logger.d(TAG, "Pause: calling mpv pause")
                            player.pause()
                            transitionToState(InternalState.PAUSED)
                            internalPlayWhenReady = false
                        }
                    }

                    InternalState.PREPARING -> {
                        internalPlayWhenReady = false
                        Logger.d(TAG, "Pause: During PREPARING - will not auto-play")
                    }

                    InternalState.ERROR -> {
                        // Nothing to pause after a track failed, but the intent still counts, as on
                        // Android: the next track loaded must not start on its own, and a failure
                        // there must not skip on. (Not IDLE: a play() queued right behind this
                        // pause would find IDLE too and could not set the intent back.)
                        internalPlayWhenReady = false
                        Logger.d(TAG, "Pause: after an error - will not auto-play")
                    }

                    else -> {
                        Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                    }
                }
            } finally {
                // Playback has genuinely stopped by this point, so a sleep-timer attenuation has
                // served its purpose and is cleared here rather than by the caller. The caller
                // cannot do it safely: pause() is asynchronous and this task suspends at
                // commitIncomingAsCurrent() above, so anything the caller queued afterwards would
                // run during that suspension and re-open the mixer over the last of the audio.
                //
                // In `finally` because that suspension point can also throw — committing a
                // crossfade joins a job that may be cancelled. Skipping this would leave the mixer
                // attenuated with nothing to ever restore it.
                if (internalSleepFadeFactor < 1f) {
                    internalSleepFadeFactor = 1f
                    forEachLiveHandle { it.setSleepFadeVolume(100) }
                }
            }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.stop()
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        // A live broadcast is followed at its live edge: its seek bar shows LIVE, not a length.
        if (isCurrentTrackLive()) return
        // Updated here rather than inside the coroutine so the UI reflects the seek immediately.
        cachedPosition = positionMs
        // The mpv call hops to the player thread, same as the volume setters: reaching into a
        // handle from the caller's thread races the release that happens on the player thread, and
        // a handle destroyed between the isReleased check and the native call is a use-after-free.
        coroutineScope.launch {
            // Seeking mid-crossfade: commit the incoming track (A+1) as current first, the same way
            // pause() does. The progress bar the user just dragged belongs to A+1 — that is the
            // track the UI shows and the one position updates are read from during a crossfade.
            // Without this the seek lands on the *outgoing* track while the crossfade carries on,
            // so the old song keeps playing underneath and the seek appears to do nothing.
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: committing incoming (A+1) before seeking")
                commitIncomingAsCurrent()
            }
            currentPlayer?.let { player ->
                try {
                    player.seekTo(positionMs)
                    Logger.d(TAG, "Seeked to position: $positionMs")
                    // Same contract as the Android adapter: every seek is reported, whoever asked
                    // for it (seek bar, lyric tap, ±5 s, Control Center).
                    notifyListeners { onSeeked(positionMs) }
                } catch (e: Exception) {
                    Logger.e(TAG, "Seek exception: ${e.message}", e)
                }
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return

        coroutineScope.launch {
            val shouldPlay = internalPlayWhenReady

            // Cancel any ongoing crossfade and await completion
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: Cancelling crossfade")
                cancelCrossfadeAndCleanup(revertIndex = false)
            }

            // Cancel any ongoing load
            currentLoadJob?.cancel()

            localCurrentMediaItemIndex = mediaItemIndex
            unpinCurrent()
            currentPlayer?.release()
            currentPlayer = null
            currentPlayerIsVideo = false
            _currentVideoFrames.value = null
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (cachedPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val newPosition = (cachedPosition + 5000).coerceAtMost(cachedDuration)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        coroutineScope.launch {
            // During crossfade A→A+1, "next" commits A+1 as current (the UI already shows A+1)
            // and then advances to A+2. Outside a crossfade it advances normally. hasNextMediaItem()
            // is evaluated AFTER the commit so it is relative to A+1, not A.
            val wasCrossfading = isCrossfading
            if (wasCrossfading) {
                Logger.d(TAG, "seekToNext: committing incoming (A+1), then advancing to A+2")
                commitIncomingAsCurrent()
            }
            if (hasNextMediaItem()) {
                seekTo(getNextMediaItemIndex(), 0)
            } else if (wasCrossfading) {
                // A+1 was the last track — stay on it; it is already promoted and announced.
                Logger.d(TAG, "seekToNext: A+1 was the last track — staying on it")
            }
        }
    }

    override fun seekToPrevious() {
        coroutineScope.launch {
            // Commit the incoming track (A+1) as current FIRST, so the 3-second rule below
            // evaluates against A+1's position and index rather than A's.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPrevious: committing incoming (A+1) first")
                commitIncomingAsCurrent()
            }

            // Standard music player behavior:
            // Position > 3s → seek to start of current track
            // Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            // A live broadcast has no start to go back to, so Previous always means the previous track.
            if (cachedPosition > positionThresholdMs && !isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms > ${positionThresholdMs}ms — seeking to start")
                currentPlayer?.seekTo(0)
                cachedPosition = 0
            } else if (hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${cachedPosition}ms <= ${positionThresholdMs}ms — going to previous track")
                val prevIndex = getPreviousMediaItemIndex()
                seekTo(prevIndex, 0)
            } else if (!isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: No previous item, seeking to start")
                currentPlayer?.seekTo(0)
                cachedPosition = 0
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            // Mirror seekToPrevious(): commit the incoming track (A+1) as current first, so
            // "previous" means the track before A+1 (i.e. A), not the one before A.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPreviousMediaItem: committing incoming (A+1) first")
                commitIncomingAsCurrent()
            }

            // Always go to the previous track — skips the 3-second "seek to start"
            // rule used by seekToPrevious().
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    /**
     * An empty playlist that gains an item points at it, as ExoPlayer's own does. clearMediaItems()
     * leaves the index at -1 and nothing else moves it, so prepare() below loaded nothing and Add to
     * queue with nothing loaded (fresh start, or after closing the mini player) never played.
     */
    private fun pointAtFirstItemIfUnset() {
        if (localCurrentMediaItemIndex < 0 && playlist.isNotEmpty()) localCurrentMediaItemIndex = 0
    }

    override fun prepare() {
        if (playlist.isNotEmpty() && localCurrentMediaItemIndex >= 0) {
            coroutineScope.launch {
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        coroutineScope.launch {
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)
        pointAtFirstItemIfUnset()

        if (internalShuffleModeEnabled) {
            insertAppendedIntoShuffleOrder()
        }

        notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

        // Re-precache only when the appended track is one of the next ones Next plays. Under shuffle that
        // is its distance in the shuffled order, not in the playlist; re-triggering on every append of a
        // batch would cancel an in-flight extraction each time. The trigger keeps the precaches Next still
        // reaches and releases the rest, so it needs no clear first (that threw away the next player).
        val landsInPrecache =
            if (internalShuffleModeEnabled) {
                val gap =
                    (shuffleIndices.getOrNull(playlist.lastIndex) ?: -1) -
                        (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1)
                gap in 1..maxPrecacheCount
            } else {
                playlist.size - 1 - currentMediaItemIndex <= maxPrecacheCount
            }
        if (landsInPrecache) {
            coroutineScope.launch { triggerPrecachingInternal() }
        }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index in 0..playlist.size) {
            val currentIndexBeforeInsert = localCurrentMediaItemIndex

            playlist.add(index, mediaItem)

            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
            pointAtFirstItemIfUnset()

            if (internalShuffleModeEnabled) {
                if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                    insertIntoShuffleOrder(index, currentShufflePos)
                    pinnedNextItems.add(mediaItem)
                } else {
                    createShuffleOrder()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // Same as the append above: under shuffle the insert may reorder what Next plays.
            if (internalShuffleModeEnabled || index - 1 - currentMediaItemIndex <= maxPrecacheCount) {
                coroutineScope.launch { triggerPrecachingInternal() }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        coroutineScope.launch {
            // Bounds check MUST run inside the launch, on the same player thread/queue as removeAt.
            // If it sits outside (on the caller thread), another queued op (clearMediaItems/setMediaItem)
            // can empty the playlist between the check and removeAt → IndexOutOfBounds (issue #2156 /
            // SIMPMUSIC-DESKTOP-3Y: "Index 0 out of bounds for length 0").
            if (index !in playlist.indices) return@launch
            // Where the track sat in the shuffled order, read before the order loses it.
            val shuffledPos = if (internalShuffleModeEnabled) shuffleIndices.getOrNull(index) ?: -1 else -1
            val track = playlist.removeAt(index)
            // Only this track leaves the shuffled order, before anything below reads the order.
            // Rebuilding it here (createShuffleOrder) reshuffled the whole queue every time one track
            // was removed with shuffle on.
            if (internalShuffleModeEnabled) removeFromShuffleOrder(index)
            pinnedNextItems.remove(track)

            precachedPlayers.remove(track.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }

                index == localCurrentMediaItemIndex -> {
                    if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                        // The track after it in the shuffled order takes over, not the next one in
                        // the playlist: the removal shifted that track into the removed one's slot.
                        localCurrentMediaItemIndex = shuffleOrder[shuffledPos.coerceIn(0, shuffleOrder.lastIndex)]
                    } else if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    unpinCurrent()
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        cleanupCurrentPlayerInternal()
                    }
                }

                else -> {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    /**
     * Drops `[fromIndex, toIndex)` of already-played tracks in one pass: one precache pass, one
     * timeline notification — the per-item versions of those are what the queue trim must not
     * repeat 50 times.
     *
     * Indices count the UNSHUFFLED playlist, like [removeMediaItem] and [currentMediaItemIndex].
     * Only a range strictly BELOW the current track is accepted; anything else is refused rather
     * than guessed at, because getting it wrong would stop playback. [onRemoved] hears back exactly
     * once either way, from inside this block — the listeners here are called through a queue
     * ([notifyListeners]), so a caller that waited for the timeline event would see snapshots taken
     * before the removal.
     */
    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
        onRemoved: (removedIds: List<String>) -> Unit,
    ) {
        coroutineScope.launch {
            // Re-checked inside the launch, on the player thread, for the reason in removeMediaItem
            // above (issue #2156).
            val refused =
                fromIndex < 0 ||
                    toIndex <= fromIndex ||
                    toIndex > playlist.size ||
                    toIndex > localCurrentMediaItemIndex ||
                    // crossfadeFromIndex is a position in this same playlist and is NOT shifted here;
                    // a cancelled fade would revert to a track ~toIndex positions away.
                    isCrossfading ||
                    // With shuffle on, the tracks before the current one in this unshuffled list are
                    // not the played ones — they are part of what is still to come.
                    internalShuffleModeEnabled
            if (refused) {
                onRemoved(emptyList())
                return@launch
            }

            val removed = playlist.subList(fromIndex, toIndex).toList()
            playlist.subList(fromIndex, toIndex).clear()
            localCurrentMediaItemIndex -= removed.size
            // Before anything else can run, so the caller cuts its copy of the queue in this same step.
            onRemoved(removed.map { it.mediaId })

            removed.forEach { track ->
                precachedPlayers.remove(track.mediaId)?.let { cached ->
                    cleanupPlayerInternal(cached.player)
                }
            }
            // The removed tracks are all behind the current one and precache is keyed by mediaId,
            // so the window ahead needs no rebuild — clearing it here would throw away the handle
            // the next crossfade is about to use. This only tops it back up, which also restores
            // any handle an id repeated in the dropped history took with it.
            triggerPrecachingInternal()

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        coroutineScope.launch {
            // Same reason as removeMediaItem (issue #2156): re-check bounds inside the launch, since the
            // playlist can be emptied by another queued op between an outside-launch guard and removeAt.
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return@launch
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> {
                        toIndex
                    }
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex - 1
                    }
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex + 1
                    }
                    else -> {
                        localCurrentMediaItemIndex
                    }
                }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun moveShuffledItem(
        fromShuffledIndex: Int,
        toShuffledIndex: Int,
    ) {
        coroutineScope.launch {
            // Same reason as removeMediaItem (issue #2156): checked against the order as it is now.
            if (!internalShuffleModeEnabled) return@launch
            if (fromShuffledIndex !in shuffleOrder.indices || toShuffledIndex !in shuffleOrder.indices) return@launch
            // Only the play order moves. Playlist indices stay where they were, and with them the
            // current index and the position a running crossfade would revert to.
            shuffleOrder.add(toShuffledIndex, shuffleOrder.removeAt(fromShuffledIndex))
            rebuildShuffleIndices()
            // Put right after the current track (Move to play next, or a drag), it keeps that slot
            // the way a Play next track does; moved anywhere else, it no longer holds one.
            playlist.getOrNull(shuffleOrder[toShuffledIndex])?.let { moved ->
                if (toShuffledIndex == (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1) + 1) {
                    pinnedNextItems.add(moved)
                } else {
                    pinnedNextItems.remove(moved)
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            // What plays next may have changed.
            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        coroutineScope.launch {
            // Same reason as removeMediaItem (issue #2156): a queued removal — a radio trimming its
            // history — can shrink the playlist between an outside check and this body.
            if (index !in playlist.indices) return@launch
            playlist[index] = mediaItem

            precachedPlayers.remove(mediaItem.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> =
        if (internalShuffleModeEnabled) {
            shuffleOrder.mapNotNull { shuffledIndex -> playlist.getOrNull(shuffledIndex) }
        } else {
            playlist.toList()
        }

    override fun getUnshuffledIndex(shuffledIndex: Int): Int =
        if (internalShuffleModeEnabled) {
            shuffleOrder.getOrNull(shuffledIndex) ?: -1
        } else {
            shuffledIndex
        }

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = internalState == InternalState.PLAYING

    override val currentPosition: Long
        get() = cachedPosition

    override val duration: Long
        get() = cachedDuration

    override val bufferedPosition: Long
        get() = cachedBufferedPosition

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            if (dur <= 0) return 0
            return ((bufferedPosition * 100) / dur).toInt().coerceIn(0, 100)
        }

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int
        get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = cachedPosition

    override val playbackState: Int
        get() =
            when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }

    // ========== Navigation ==========

    // Repeat One only loops a track that ends on its own (handleTrackEndInternal);
    // Next and Previous move through the queue as they do with repeat off.
    override fun hasNextMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> true
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos =
                        shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    currentShufflePos >= 0 && currentShufflePos < shuffleOrder.lastIndex
                } else {
                    localCurrentMediaItemIndex < playlist.size - 1
                }
        }

    override fun hasPreviousMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> true
            else ->
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos =
                        shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1
                    currentShufflePos > 0
                } else {
                    localCurrentMediaItemIndex > 0
                }
        }

    /** The index Next plays after [from]; [from] itself when nothing follows it. [from] defaults to the
     *  current track, and precaching walks it forward to reach the track after next. */
    private fun getNextMediaItemIndex(from: Int = localCurrentMediaItemIndex): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(from) ?: 0
                    val nextShufflePos = (currentShufflePos + 1) % shuffleOrder.size
                    shuffleOrder.getOrNull(nextShufflePos) ?: from
                } else {
                    if (from < playlist.size - 1) {
                        from + 1
                    } else {
                        0
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(from) ?: 0
                    val nextShufflePos = currentShufflePos + 1
                    if (nextShufflePos < shuffleOrder.size) {
                        shuffleOrder.getOrNull(nextShufflePos) ?: from
                    } else {
                        from
                    }
                } else {
                    (from + 1).coerceAtMost(playlist.size - 1)
                }
            }
        }

    private fun getPreviousMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos =
                        if (currentShufflePos > 0) {
                            currentShufflePos - 1
                        } else {
                            shuffleOrder.size - 1
                        }
                    shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex > 0) {
                        localCurrentMediaItemIndex - 1
                    } else {
                        playlist.size - 1
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos = currentShufflePos - 1
                    if (prevShufflePos >= 0) {
                        shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
                }
            }
        }

    // ========== Playback Modes ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            if (internalShuffleModeEnabled == value) return

            internalShuffleModeEnabled = value

            if (value) {
                createShuffleOrder()
            } else {
                clearShuffleOrder()
            }

            val mediaItemList = getShuffledMediaItemList()
            notifyListeners { onShuffleModeEnabledChanged(value, mediaItemList) }
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            notifyListeners { onRepeatModeChanged(value) }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        set(value) {
            internalPlaybackSpeed = value.speed
            internalPlaybackPitch = value.pitch
            // Hopped to the player thread for the same reason as seekTo and the volume setters.
            coroutineScope.launch {
                // Every live handle, not just the current one: these are playback-wide settings, so
                // the precached handles have to carry them too or the next track starts back at 1.0x.
                forEachLiveHandle {
                    try {
                        it.setRate(value.speed)
                        it.applyPitch()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to set playback speed/pitch: ${e.message}")
                    }
                }
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = 0 // mpv doesn't provide an audio session ID

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.d(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            // Same hop as sleepFadeFactor below, and for the same reason: writing mpv properties
            // straight from the caller's thread races the player thread that releases handles, and
            // a handle destroyed between the isReleased check and the property write is a
            // use-after-free. The field itself is set synchronously so the getter reads back what
            // was just written; only the mpv calls are deferred.
            coroutineScope.launch {
                // mpv volume: 0-100 (100 = unattenuated). Map our 0.0-1.0 to 0-100.
                val percent = (internalVolume * 100).toInt()
                forEachLiveHandle { it.setMasterVolume(percent) }
            }
            notifyListeners { onVolumeChanged(internalVolume) }
        }

    override var albumTrackIds: Set<String>
        get() = internalAlbumTrackIds
        set(value) {
            internalAlbumTrackIds = value
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
            // Hop onto the player thread instead of writing from the caller's — the sleep timer
            // ramps this from Main. Three separate things depend on that hop:
            //  - [MpvPlayer.applyVolume] reads three fields and issues two native calls, so a
            //    second writer can interleave and publish a stale combination.
            //  - A handle can be released between its `isReleased` check and mpv_set_property,
            //    which is a use-after-free that has already produced a SIGSEGV here once (see the
            //    join comment in MpvPlayer.release). Releases happen on this same thread, so
            //    sharing it removes the window entirely.
            //  - pause() queues onto this same single-threaded scope. Going through it is what
            //    guarantees the timer's closing restore to 1f lands *after* playback has actually
            //    stopped, instead of briefly re-opening the mixer over the last of the audio.
            coroutineScope.launch {
                // Re-read rather than capture: if the queue backs up, the pending writes collapse
                // onto the newest value instead of replaying a stale ramp.
                val percent = (internalSleepFadeFactor * 100).toInt()
                forEachLiveHandle { it.setSleepFadeVolume(percent) }
            }
        }

    /**
     * Every handle that can currently reach the audio device.
     *
     * `ao-volume` is not per-handle: on Windows they all land in the process' default WASAPI
     * session (mpv passes a NULL AudioSessionGuid), so one ISimpleAudioVolume covers the lot, and
     * a handle left holding an older level re-asserts it on its own AUDIO_RECONFIG and overwrites
     * everyone else's. Missing one is therefore not "that handle stays wrong", it is "that handle
     * silently undoes the others".
     *
     * [secondaryPlayer] — the track fading in during a crossfade — is the easy one to miss: it is
     * removed from [precachedPlayers] before being promoted, so it belongs to neither collection.
     */
    private fun forEachLiveHandle(action: (MpvPlayer) -> Unit) {
        currentPlayer?.let(action)
        secondaryPlayer?.let(action)
        precachedPlayers.values.forEach { action(it.player) }
    }

    /**
     * Push the playback-wide settings onto a handle that is about to become audible.
     *
     * A fresh mpv handle starts at 100/100 volume and 1.0x speed, so anything already in force has
     * to be re-asserted here — otherwise a fade in progress jumps back to full volume mid-ramp, and
     * a track started while the user has speed turned up plays at normal speed instead.
     *
     * The two volume levels go down in one call: setting them separately would publish a
     * full-volume master onto the shared `ao-volume` before the fade is applied, which every other
     * handle hears.
     */
    private fun MpvPlayer.applyPlaybackLevels() {
        setVolumeLevels(
            master = (internalVolume * 100).toInt(),
            sleep = (internalSleepFadeFactor * 100).toInt(),
        )
        setRate(internalPlaybackSpeed)
        // A fresh handle has no correction, and this is the one the handler last set for the track now playing.
        setLoudnessGain(internalLoudnessGainDb)
        // A fresh handle has an empty filter chain, so skip silence has to be put back on it.
        if (internalSkipSilence) setSkipSilence(true)
        applyPitch()
        // A fresh handle starts with an empty filter chain, so the curve has to be re-applied or
        // the equalizer silently stops working at the next track — including on secondaryPlayer,
        // which belongs to neither collection while it is being promoted.
        if (internalEqualizerBands.isNotEmpty() || internalEqualizerPreamp != 0f) {
            setEqualizer(internalEqualizerBands, internalEqualizerPreamp)
        }
        // Same reasoning for the effects tier, and the same handle to miss: an empty chain means a
        // track started mid-session would play dry while the switches still read "on".
        val effects = internalAudioEffects
        if (effects != AudioEffects.NONE) {
            setAudioEffects(effects, ensureReverbIr(effects))
        }
        if (_eightDAvailable.value && eightDOrbit.snapshot().effect != null) {
            if (!setEightD(eightDOrbit)) markEightDUnavailable()
        }
        // And the output: a fresh handle opens the system default, so without this the next track
        // would quietly leave the device the user picked.
        preferredAudioDevice?.let { setAudioDevice(it) }
        // A track that starts while no window is on screen starts without video, like the others.
        if (!AppOnScreen.onScreen.value) setVideoSuspended(true)
    }

    /**
     * Put the user's pitch shift on this handle, if pitch is available at all right now.
     *
     * Skipped entirely while crossfade is enabled: a transition clears this entry when it ends, so
     * the shift would be wiped. The UI locks the pitch control in that case, so the value stays at
     * 1.0 anyway.
     */
    private fun MpvPlayer.applyPitch() {
        if (crossfadeEnabled) return
        if (internalPlaybackPitch == 1.0f) {
            clearAudioFilters()
        } else {
            // rubberband also absorbs the tempo change once present, so setRate keeps working.
            // Only drive the shift if mpv actually took the filter — a build without rubberband
            // rejects it, and af-command against a filter that is not in the chain fails every time.
            val chain = installCrossfadeChain(pitchShift = true)
            if (chain.pitchShift) {
                setPitchScale(internalPlaybackPitch)
            } else {
                Logger.w(TAG, "Pitch shift unavailable: mpv rejected the rubberband filter")
            }
        }
    }

    /** Skip silence is on and this libmpv can do it, so tracks end where their music does. */
    private fun isSkipSilenceInForce(): Boolean = internalSkipSilence && MpvPlayer.silenceFilterSupported

    /** Whether skip silence is on, so a handle created later starts with it. */
    @Volatile
    private var internalSkipSilence = false

    override var skipSilenceEnabled: Boolean
        get() = internalSkipSilence
        set(value) {
            internalSkipSilence = value
            // Every live handle, not only the newest: the one fading out keeps its silence skipped too.
            coroutineScope.launch { forEachLiveHandle { it.setSkipSilence(value) } }
        }

    /**
     * The loudness correction of the track that is playing, so a handle created later starts with it.
     *
     * Written to the newest handle only: during a crossfade that is the track fading in, while the
     * one fading out keeps its own correction instead of jumping to the next track's.
     */
    @Volatile
    private var internalLoudnessGainDb = 0f

    override var loudnessGainDb: Float
        get() = internalLoudnessGainDb
        set(value) {
            internalLoudnessGainDb = value
            // Same hop as volume and the equalizer: mpv properties are written on the player thread.
            coroutineScope.launch { (secondaryPlayer ?: currentPlayer)?.setLoudnessGain(value) }
        }

    /** Current curve, so a handle created later can be brought up to the same setting. */
    @Volatile
    private var internalEqualizerBands: List<Float> = emptyList()

    @Volatile
    private var internalEqualizerPreamp: Float = 0f

    override fun setEqualizer(
        bandsDb: List<Float>,
        preampDb: Float,
    ) {
        internalEqualizerBands = bandsDb
        internalEqualizerPreamp = preampDb
        // Same hop as volume and sleepFadeFactor: mpv properties are written on the player thread,
        // because a handle released between the isReleased check and the write is a use-after-free.
        coroutineScope.launch {
            forEachLiveHandle { it.setEqualizer(bandsDb, preampDb) }
        }
    }

    /** Current effects, so a handle created later can be brought up to the same setting. */
    @Volatile
    private var internalAudioEffects: AudioEffects = AudioEffects.NONE

    /**
     * Whether the reverb entry is actually in the chain right now.
     *
     * A mix change is delivered with `af-command`, which fails outright when the filter it names is
     * not there — and it fails silently as far as the user is concerned, since the slider still
     * moves. So this is set from what the handles REPORTED, never from what was asked of them: it
     * stays false when the impulse response could not be written, when libmpv has no convolution
     * filters, and when there were no live handles to install anything on. That last case is why it
     * cannot be inferred from the request — with nothing playing, every input to the decision looks
     * healthy while the chain is empty. A false here only costs the next mix change its shortcut.
     */
    @Volatile
    private var reverbEntryLive: Boolean = false

    override fun setAudioEffects(effects: AudioEffects) {
        val previous = internalAudioEffects
        internalAudioEffects = effects
        // Same hop as setEqualizer, for the same use-after-free reason.
        coroutineScope.launch {
            val mix = effects.reverb?.mix
            if (mix != null && reverbEntryLive && isReverbMixOnlyChange(previous, effects)) {
                forEachLiveHandle { it.setReverbMix(mix) }
                return@launch
            }
            // Resolved before any handle is touched: generating the impulse response is file I/O,
            // and `amovie` opens that path while mpv is parsing the chain, so it has to be on disk
            // already or the whole `af` write fails — equalizer included.
            val irPath = ensureReverbIr(effects)
            // Derived from what the handles answered, not from what was asked of them: with
            // nothing playing this loop runs zero times, and every input to the decision still
            // looks healthy while the chain is empty. `accepted` is read into its own local first
            // so the call cannot be short-circuited away once one handle has already said yes.
            var installedOnAny = false
            forEachLiveHandle { handle ->
                val accepted = handle.setAudioEffects(effects, irPath)
                installedOnAny = installedOnAny || accepted
            }
            reverbEntryLive = irPath != null && installedOnAny
        }
    }

    override fun setEightD(effect: EightDEffect?) {
        val normalized = effect?.normalized()
        coroutineScope.launch {
            val previous = eightDOrbit.snapshot().effect
            if (previous == normalized) return@launch
            eightDCleanupJob?.cancel()
            eightDOrbit.setEffect(normalized)
            if (!_eightDAvailable.value) {
                forEachLiveHandle { it.setEightD(null) }
            } else if (normalized != null) {
                // Switching on fades in, also inside a switch-off's fade; a retune does not.
                var refused = false
                forEachLiveHandle { if (!it.setEightD(eightDOrbit, fadeIn = previous == null)) refused = true }
                if (refused) markEightDUnavailable()
            } else if (previous != null) {
                forEachLiveHandle { it.setEightD(eightDOrbit, fadeOutEffect = previous) }
                eightDCleanupJob = coroutineScope.launch {
                    delay(EightDEffect.TRANSITION_MS)
                    if (eightDOrbit.snapshot().effect == null) {
                        forEachLiveHandle { it.setEightD(null) }
                    }
                }
            }
        }
    }

    /** A refused filter is a fact about this libmpv: stop asking every handle; the other tiers stay. */
    private fun markEightDUnavailable() {
        _eightDAvailable.value = false
        forEachLiveHandle { it.setEightD(null) }
    }

    /** 8D bookkeeping every listened-to handle needs: the first install, and the live phase after each seek. */
    private open inner class EightDSyncListener : MpvPlayerEventAdapter() {
        override fun restarted(player: MpvPlayer) = syncEightD(player)

        override fun seeking(player: MpvPlayer) = syncEightD(player)

        private fun syncEightD(player: MpvPlayer) {
            // MpvPlayer ignores the call once released, so no ownership check is needed.
            coroutineScope.launch { if (!player.syncEightD()) markEightDUnavailable() }
        }
    }

    /** @return the impulse response path for [effects], or null when there is no reverb to install. */
    private fun ensureReverbIr(effects: AudioEffects): String? = effects.reverb?.let { ReverbIrFiles.ensure(it.preset)?.absolutePath }

    /**
     * Whether [next] differs from [previous] only in how much reverb is mixed in.
     *
     * Everything else — a different preset, a changed delay, an effect switched on or off — needs
     * a new graph, because the impulse response and the echo taps are baked into the filter string
     * at parse time. The mix alone is a live `amix` weight.
     */
    private fun isReverbMixOnlyChange(
        previous: AudioEffects,
        next: AudioEffects,
    ): Boolean {
        val before = previous.reverb ?: return false
        val after = next.reverb ?: return false
        return previous.delay == next.delay && before.preset == after.preset
    }

    // ========== Audio Output ==========

    private val _audioOutputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    override val audioOutputs: StateFlow<List<AudioOutput>> = _audioOutputs.asStateFlow()

    /** The mpv device the user picked, or null for mpv's `auto` (the system default). */
    @Volatile
    private var preferredAudioDevice: String? = null

    override suspend fun loadWaveform(mediaId: String): FloatArray? {
        if (mediaId.isBlank() || LiveStreamRegistry.isLive(mediaId)) return null
        return MpvWaveformReader.read(mediaId) { waveformSourceUrl(mediaId) }
    }

    /**
     * The track's audio alone, found the way [extractPlayableUrl] finds it: a download first, then the
     * format's own audio URL. No fallback extraction: a waveform is not worth another extraction.
     */
    private suspend fun waveformSourceUrl(videoId: String): String? {
        downloadedFile(videoId)?.let { return it.absolutePath }
        listenCache.cachedFile(videoId)?.let { return it.absolutePath }
        val audioUrl = streamRepository.getNewFormat(videoId).lastOrNull()?.audioUrl?.takeIf { it.isNotEmpty() } ?: return null
        return audioUrl.takeIf { streamRepository.is403Url(it).firstOrNull() == false }
    }

    override fun selectAudioOutput(id: String?) {
        preferredAudioDevice = id?.takeIf { it != MPV_AUTO_DEVICE }
        // Same hop as setEqualizer: mpv properties are written on the player thread, and every live
        // handle has to move together or the two halves of a crossfade play out of different speakers.
        coroutineScope.launch {
            forEachLiveHandle { it.setAudioDevice(preferredAudioDevice ?: MPV_AUTO_DEVICE) }
            readAudioOutputs()
        }
    }

    override fun refreshAudioOutputs() {
        coroutineScope.launch { readAudioOutputs() }
    }

    /** The driver the outputs belong to. Player thread only, like every read below. */
    private var audioOutputDriver: String? = MpvPlayer.pinnedAudioOutputDriver

    // mpv only answers for a handle, so with nothing loaded yet the list stays as it was. Its driver
    // is only named while the handle's audio is up — and switching device tears that down, so the
    // read right after selectAudioOutput finds no `current-ao`. The driver seen last stands in;
    // without it that read published an empty list, which emptied the sheet and the caption.
    private fun readAudioOutputs() {
        val handle = currentPlayer ?: return
        handle.currentAudioOutputDriver()?.let { audioOutputDriver = it }
        val driver = audioOutputDriver ?: return
        val json = handle.audioDeviceListJson() ?: return
        _audioOutputs.value = parseAudioOutputs(json, driver, handle.audioDevice())
    }

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        eightDCleanupJob?.cancel()
        eightDOrbit.setPlaying(false)
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()
        crossfadeJob?.cancel()

        secondaryPlayer?.release()
        secondaryPlayer = null
        isCrossfading = false
        crossfadeFromIndex = -1

        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()

        // No factory to release: libmpv has no factory concept, each handle is destroyed by its
        // own MpvPlayer.release() via mpv_terminate_destroy().
    }

    // ========== Internal Methods ==========

    /**
     * Transition internal state and notify listeners.
     * Listeners are called on the service thread (not Main) because
     * JvmMediaPlayerHandlerImpl uses thread-safe StateFlow updates
     * and contains runBlocking calls that would deadlock on Main.
     */
    private fun transitionToState(
        newState: InternalState,
        error: PlayerError? = null,
    ) {
        if (internalState == newState) return

        val oldState = internalState
        internalState = newState
        eightDOrbit.setPlaying(newState == InternalState.PLAYING)

        Logger.d(TAG, "State transition: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Query duration from mpv
        currentPlayer?.let { player ->
            val dur = player.length
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners on the service thread — safe because JvmMediaPlayerHandlerImpl
        // uses thread-safe StateFlow/MutableStateFlow for all state updates.
        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                trimNativeMemory("paused")
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                trimNativeMemory("idle")
            }

            InternalState.PREPARING -> {
                Logger.d(TAG, "transitionToState PREPARING -> isLoading=true")
                cachedIsLoading = true
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
                listeners.forEach { it.onIsLoadingChanged(true) }
            }

            InternalState.READY -> {
                Logger.d(TAG, "transitionToState READY -> isLoading=false")
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                Logger.d(TAG, "transitionToState PLAYING -> isLoading=false")
                cachedIsLoading = false
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            InternalState.ENDED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                // One failure, one report: a caller that knows what went wrong passes it here
                // instead of reporting it too. Two reports used to mean two toasts, and now
                // would mean two skips.
                val reported =
                    error ?: PlayerError(
                        errorCode = 403,
                        errorCodeName = "ERROR_UNKNOWN",
                        message = "Can not extract playable URL or playback error",
                    )
                listeners.forEach { it.onPlayerError(reported) }
            }
        }
    }

    /**
     * Give the C allocator's free pages back to the OS now that playback has gone quiet.
     *
     * Only ever called from the PAUSED / IDLE transitions: the underlying call walks the heap while
     * holding the allocator lock, so anything that mallocs — mpv's decoder threads included — stalls
     * until it returns. See [MemoryTrimmer] for the measurements that motivate this.
     *
     * Dispatched off the single service thread because that thread also drives the transport; a
     * heap walk there would show up as a laggy pause button. [MemoryTrimmer] throttles itself, so
     * firing this on every pause is cheap.
     */
    private fun trimNativeMemory(reason: String) {
        coroutineScope.launch(Dispatchers.IO) {
            MemoryTrimmer.trim(reason)
        }
    }

    /**
     * Load and play track - MUST run on coroutineScope
     */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
        announceTransition: Boolean = true,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId

        currentLoadJob?.cancel()

        currentLoadJob =
            coroutineScope.launch {
                try {
                    // Up front, not after the load: a load that fails is retried from here, and
                    // must not pick up where the previous track had got to.
                    cachedPosition = startPositionMs
                    soundPending = videoId to System.nanoTime()
                    transitionToState(InternalState.PREPARING)

                    // Notify media item transition (fire-and-forget to avoid
                    // blocking the service thread with runBlocking inside handler)
                    if (announceTransition) {
                        notifyListeners {
                            onMediaItemTransition(
                                mediaItem,
                                PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }

                    // Detach event listener from old player IMMEDIATELY before
                    // spending time on URL extraction. This prevents stale events
                    // (error/finished) from the old player seeing the updated
                    // playlist/index and interfering with the new track load.
                    cleanupEventListenerInternal()
                    stopPositionUpdates()

                    // Extract URL on IO thread (network), mpv native calls stay on service thread
                    val cachedPrecache = takeHealthyPrecachedPlayer(videoId)
                    var resolvedSource: PlayableSource? = null
                    val player =
                        if (cachedPrecache?.player != null) {
                            cachedPrecache.player
                        } else {
                            var source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            if (source == null || source.url.isEmpty()) {
                                Logger.w(TAG, "First extract failed for $videoId, invalidating and retrying...")
                                withContext(Dispatchers.IO) {
                                    streamRepository.invalidateFormat(videoId)
                                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                                }
                                source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }
                            }
                            if (source == null || source.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract playable URL for $videoId after retry")
                                transitionToState(InternalState.ERROR)
                                return@launch
                            }
                            resolvedSource = source
                            createMediaPlayerInternal(source, videoId)
                        }

                    if (player == null) {
                        Logger.e(TAG, "Failed to create mpv player for $videoId")
                        transitionToState(InternalState.ERROR)
                        return@launch
                    }

                    // mpv native calls on the service thread
                    cleanupCurrentPlayerInternal()
                    currentPlayer = player
                    currentPlayerIsVideo = player.videoFrames != null
                    _currentVideoFrames.value = player.videoFrames
                    setupPlayerEventsInternal(player)
                    player.applyPlaybackLevels()

                    if (cachedPrecache != null) {
                        Logger.i("Offline", "resolve $videoId precached")
                        // The precached handle already has the file loaded and held paused;
                        // unpausing is all that is needed (VLC needed an explicit play() here too).
                        if (startPositionMs > 0) {
                            player.seekTo(startPositionMs)
                        }
                        if (shouldPlay) {
                            player.play()
                        }
                        Logger.d(TAG, "Playing from precache for $videoId")
                    } else {
                        val source = resolvedSource
                        if (source != null) {
                            // The position rides on the load: time-pos is unavailable until the file
                            // is open, so seeking after this is refused and the track starts from 0.
                            player.loadFile(buildPlaybackUrl(source), startPaused = !shouldPlay, startMs = startPositionMs)
                        } else {
                            Logger.e(TAG, "resolvedSource is null — should not happen")
                            transitionToState(InternalState.ERROR)
                            return@launch
                        }
                    }

                    // Always transition to READY first so UI receives
                    // isLoading=false via STATE_READY. The READY handler
                    // will auto-call play() when internalPlayWhenReady is true.
                    transitionToState(InternalState.READY)

                    // Start position updates
                    startPositionUpdates()

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Load track error: ${e.message}", e)
                        transitionToState(InternalState.ERROR)
                    }
                }
            }
    }

    /**
     * Resolve the single URL handed to `loadfile`.
     *
     * Replaces VLC's `buildVlcOptions` + `:input-slave`. The rest of what that function did —
     * `:no-video`, `:network-caching`, `:http-reconnect` — are handle-level options in mpv and
     * live in [MpvPlayer.create] instead.
     *
     * When YouTube gives separate adaptive audio and video URLs they are merged into one
     * `edl://` URL so mpv plays both streams simultaneously, which is exactly what
     * `:input-slave` did for VLC and what MergingMediaSource does on Android.
     *
     * An audio-only source never carries a slave URL (see [extractPlayableUrl]), so audio playback
     * always resolves to a single audio URL and never opens a video stream.
     */
    private fun buildPlaybackUrl(source: PlayableSource): String {
        val audioSlave = source.audioSlaveUrl
        return if (audioSlave != null) buildEdlUrl(source.url, audioSlave) else source.url
    }

    /**
     * Create an mpv player instance for [source].
     *
     * Video sources get a handle with mpv's software render API attached — see
     * [MpvVideoFrameSource], the successor of `VlcVideoSurfacePanel`. Audio sources get a
     * headless handle with `vid=no`/`vo=null`, so no video stream is ever decoded or fetched.
     */
    private fun createMediaPlayerInternal(
        source: PlayableSource,
        videoId: String,
    ): MpvPlayer? =
        createMediaPlayerHandle(source)?.also { playerOrigins[it] = videoId to source }

    private fun createMediaPlayerHandle(source: PlayableSource): MpvPlayer? {
        // VLC used --network-caching=10000 globally and :network-caching=15000 for video.
        val cacheSeconds = if (source.isVideo) 15 else 10
        return if (source.isVideo) {
            Logger.d(TAG, "Creating video player with software render surface")
            MpvPlayer.create(audioOnly = false, networkCacheSeconds = cacheSeconds, liveStream = source.isLive)
        } else {
            Logger.d(TAG, "Creating audio-only player")
            MpvPlayer.create(audioOnly = true, networkCacheSeconds = cacheSeconds, liveStream = source.isLive)
        }
    }

    /**
     * Setup mpv event listeners for a player.
     *
     * Unlike VLC — whose callbacks arrive on a native thread where calling stop()/release()
     * deadlocks — mpv events are pulled by [MpvPlayer]'s own pump thread. Work is still dispatched
     * to [coroutineScope] so the state machine stays serialized on one thread.
     */
    private fun setupPlayerEventsInternal(player: MpvPlayer) {
        // Remove old listener
        cleanupEventListenerInternal()

        val listener =
            object : EightDSyncListener() {
                override fun finished(player: MpvPlayer) {
                    Logger.d(TAG, "End of stream reached")
                    coroutineScope.launch {
                        // Ignore events from a player that is no longer current
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring finished() from stale player")
                            return@launch
                        }
                        // During crossfade, the old player will naturally finish near the end.
                        // Ignore it - the crossfade is already handling the transition.
                        if (isCrossfading) {
                            Logger.d(TAG, "Ignoring finished() during crossfade (old player ended)")
                            return@launch
                        }
                        // Already ended at its music end; this is the silence after it running out.
                        if (musicEndHandledFor === player) {
                            Logger.d(TAG, "Ignoring finished() after the track was ended at its music end")
                            return@launch
                        }
                        if (resumeAfterEarlyEnd(player)) return@launch
                        transitionToState(InternalState.ENDED)
                        handleTrackEndInternal()
                    }
                }

                override fun error(player: MpvPlayer) {
                    Logger.e(TAG, "mpv playback error")
                    coroutineScope.launch {
                        // Ignore errors from a player that is no longer current.
                        // This can happen when the old player fires error() after
                        // a new track has started loading (playlist/index already updated).
                        if (currentPlayer !== player) {
                            Logger.w(TAG, "Ignoring error() from stale player")
                            return@launch
                        }
                        // During crossfade, ignore errors from the old player -
                        // it's fading out and will be released soon anyway.
                        if (isCrossfading) {
                            Logger.w(TAG, "Ignoring error() during crossfade (old player error)")
                            return@launch
                        }
                        val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                        if (currentVideoId != null) {
                            // Reset retry count for new track
                            if (retryVideoId != currentVideoId) {
                                retryVideoId = currentVideoId
                                retryCount = 0
                            }
                            // A cached copy is only a copy: dropped on its first failure, and the
                            // reload streams the track instead.
                            if (healCorruptCache(player, currentVideoId, "mpv failed on the cached file")) {
                                try {
                                    reloadCurrentTrack(currentVideoId)
                                    return@launch
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Reload after dropping the cached file failed: ${e.message}", e)
                                }
                            }
                            // The reload below reads the same file again: from the second failure on a
                            // downloaded track is broken on disk, not unlucky.
                            if (retryCount >= 1 && healCorruptDownload(currentVideoId)) {
                                try {
                                    reloadCurrentTrack(currentVideoId)
                                    return@launch
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Reload after healing failed: ${e.message}", e)
                                }
                            }
                            if (retryCount < maxRetryCount) {
                                retryCount++
                                Logger.w(TAG, "Retrying playback (attempt $retryCount/$maxRetryCount) for $currentVideoId")
                                try {
                                    reloadCurrentTrack(currentVideoId)
                                    return@launch
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                                }
                            }
                            Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                        }
                        val error =
                            PlayerError(
                                errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                                errorCodeName = "MPV_ERROR",
                                message = "Playback error",
                            )
                        transitionToState(InternalState.ERROR, error)
                    }
                }

                override fun playing(player: MpvPlayer) {
                    coroutineScope.launch {
                        if (currentPlayer !== player) return@launch
                        soundPending?.let { (id, started) ->
                            if (id == playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId) {
                                Logger.i("Offline", "resolve $id sound ${(System.nanoTime() - started) / 1_000_000}")
                            }
                            soundPending = null
                        }
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            // Reset retry counter on successful playback
                            retryCount = 0
                            retryVideoId = null
                        }
                        // Audio is up, so mpv can name its driver: the output caption has its answer
                        // before anyone opens the sheet, and again once a device switch has settled.
                        readAudioOutputs()
                    }
                }

                override fun paused(player: MpvPlayer) {
                    coroutineScope.launch {
                        if (currentPlayer !== player) return@launch
                        if (internalState == InternalState.PLAYING) {
                            transitionToState(InternalState.PAUSED)
                        }
                    }
                }

                override fun timeChanged(
                    player: MpvPlayer,
                    newTimeMs: Long,
                ) {
                    // During crossfade, this fires from the OLD player — ignore it.
                    // Position updates come from the poll loop using secondaryPlayer's time.
                    if (!isCrossfading) {
                        cachedPosition = newTimeMs
                    }
                }

                override fun lengthChanged(
                    player: MpvPlayer,
                    newLengthMs: Long,
                ) {
                    // During crossfade, this fires from the OLD player — ignore it.
                    if (!isCrossfading && newLengthMs > 0) {
                        cachedDuration = newLengthMs
                    }
                }

                override fun buffering(
                    player: MpvPlayer,
                    newCache: Float,
                ) {
                    // During crossfade, ignore buffering events from old player
                    if (isCrossfading) return

                    // Update buffered position first
                    if (cachedDuration > 0) {
                        cachedBufferedPosition = (cachedDuration * newCache / 100f).toLong()
                    }

                    // Only report loading when buffer is actually behind the playhead
                    // AND the player intends to play. Ignore buffering while paused
                    // to avoid showing a loading spinner when user has explicitly paused.
                    val isStalled = newCache < 100f && cachedBufferedPosition <= cachedPosition && internalPlayWhenReady
                    if (isStalled != cachedIsLoading) {
                        Logger.d(TAG, "isLoading changed: $cachedIsLoading -> $isStalled")
                        cachedIsLoading = isStalled
                        // A stall stops the audio but not the PLAYING state; the orbit must stop
                        // with the audio, or the next handle joins ahead of what was heard.
                        eightDOrbit.setPlaying(internalState == InternalState.PLAYING && !isStalled)
                        notifyListeners { onIsLoadingChanged(isStalled) }
                    }
                }

                override fun opening(player: MpvPlayer) {
                    Logger.d(TAG, "mpv opening media")
                }
            }

        player.setEventListener(listener)
    }

    /**
     * Clean up event listener from current player
     */
    private fun cleanupEventListenerInternal() {
        currentPlayer?.setEventListener(null)
    }

    /**
     * Cleanup a player instance
     */
    private fun cleanupPlayerInternal(player: MpvPlayer) {
        try {
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    /**
     * Cleanup current player
     */
    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupEventListenerInternal()

        crossfadeJob?.cancel()
        crossfadeJob = null
        setCrossfading(false)

        // The crossfade handle has to die with its job. This runs on the load path (next / prev /
        // picking another track), which never passes through cancelCrossfadeAndCleanup, so nothing
        // else would release it: the following crossfade overwrites `secondaryPlayer` and the old
        // handle loses its last reference while still holding a demuxer, a decoder, an audio output
        // and its event pump thread.
        secondaryPlayer?.let { cleanupPlayerInternal(it) }
        secondaryPlayer = null

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
        currentPlayerIsVideo = false
        _currentVideoFrames.value = null
    }

    /**
     * Crossfade is skipped when the NEXT track will play as a video (video content with
     * the watch-video setting on — the same condition [extractPlayableUrl] uses to include
     * the video stream). The merged edl:// source resolves two stream URLs and is
     * error-prone to prepare mid-fade, and a video should start from its first frame
     * instead of fading in under the outgoing song — so the transition takes the normal
     * (non-crossfade) path.
     */
    private fun isNextTrackVideo(): Boolean = watchVideoEnabled && playlist.getOrNull(getNextMediaItemIndex())?.isVideo() == true

    /** Same skip rule for the CURRENT track: a video should play out to its last frame instead of fading out under the incoming song. */
    private fun isCurrentTrackVideo(): Boolean = watchVideoEnabled && currentMediaItem?.isVideo() == true

    /**
     * A live broadcast never reaches an end to fade out of. mpv reports its duration as the length of
     * the window it can seek in, so "time remaining" is only how far behind the live edge playback
     * is — near the edge that looks like the last seconds of a song and would set off a crossfade.
     */
    private fun isCurrentTrackLive(): Boolean = currentMediaItem?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /** Nor does a live broadcast fade in: started early in the background, it would begin behind the live edge. */
    private fun isNextTrackLive(): Boolean = playlist.getOrNull(getNextMediaItemIndex())?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /**
     * Crossfade needs a track long enough that both sides of the blend are still worth hearing. At
     * the default 5s fade a 20s track would spend half its length fading in or out, and a longer
     * fade setting swallows it whole — so the bar scales with the fade rather than being fixed.
     *
     * Only the current track is measured: the next one has not been loaded yet, so its duration is
     * unknown until it becomes current.
     */
    private fun isCurrentTrackTooShortForCrossfade(): Boolean {
        val currentDuration = duration
        if (currentDuration <= 0L) return false
        return currentDuration < maxOf(MIN_CROSSFADE_TRACK_MS, crossfadeDurationMs * 3L)
    }

    /**
     * True when both this track and the next came from the album loaded in the queue, and the user
     * asked for albums to play through uninterrupted.
     *
     * Requiring *both* sides is what keeps the edges intact: the last album track into the first
     * track endless queue appended still crossfades, because that one is not in the set.
     */
    private fun isWithinAlbum(): Boolean {
        if (!skipCrossfadeInAlbum) return false
        val ids = internalAlbumTrackIds
        if (ids.isEmpty()) return false
        val current = currentMediaItem?.mediaId ?: return false
        val next = playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: return false
        return current in ids && next in ids
    }

    /**
     * Handle track end
     */
    /**
     * Picks a stream back up after mpv reported an end of file that cannot be the track's end.
     *
     * A stream cut mid-track that cannot reconnect, because its URL has expired, reaches us as a
     * plain EOF and not as an error (measured against libmpv: a 403 on the reconnect, at 0:27 of
     * 4:00, gives MPV_END_FILE_REASON_EOF). Taken at face value that skips to the next track, or
     * under Repeat One restarts this one.
     *
     * A file that was read to its end did end, wherever the position stopped: with skip silence on,
     * `silenceremove` drops the closing silence and mpv reports EOF where the music stopped. Taken for
     * a cut stream, that tail was reloaded twice (the last half second heard three times, and the
     * listening cache of a good track dropped) before the track moved on.
     *
     * @return true when a reload was started and the end must not be handled.
     */
    private suspend fun resumeAfterEarlyEnd(player: MpvPlayer): Boolean {
        val videoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: return false
        val positionMs = cachedPosition
        if (cachedDuration - positionMs <= EARLY_END_MARGIN_MS || isCurrentTrackLive()) return false
        if (cachedDuration - player.demuxedUntilMs <= EARLY_END_MARGIN_MS) return false
        // Getting further than last time counts as recovering, so only a stream that keeps dying
        // at the same place uses up its attempts.
        if (videoId != earlyEndVideoId || positionMs - earlyEndPositionMs > EARLY_END_MARGIN_MS) {
            earlyEndRetries = 0
        }
        earlyEndVideoId = videoId
        earlyEndPositionMs = positionMs
        if (earlyEndRetries >= maxRetryCount) {
            Logger.e(TAG, "Stream for $videoId keeps ending at ${positionMs}ms of ${cachedDuration}ms, moving on")
            return false
        }
        earlyEndRetries++
        Logger.w(TAG, "Stream for $videoId ended at ${positionMs}ms of ${cachedDuration}ms, resuming (attempt $earlyEndRetries/$maxRetryCount)")
        // A cached file that ends early is cut short: the reload streams the track instead.
        currentPlayer?.let { healCorruptCache(it, videoId, "the cached file ended at ${positionMs}ms of ${cachedDuration}ms") }
        reloadCurrentTrack(videoId)
        return true
    }

    /** Reloads the current track from a fresh URL at the position it had reached. */
    private suspend fun reloadCurrentTrack(videoId: String) {
        streamRepository.invalidateFormat(videoId)
        streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
        // Evict stale precache
        precachedPlayers.remove(videoId)?.player?.release()
        loadAndPlayTrackInternal(
            localCurrentMediaItemIndex,
            startPositionMs = if (isCurrentTrackLive()) 0L else cachedPosition,
            shouldPlay = internalPlayWhenReady,
            // Still the same track: announcing it would log a second listen and restart Discord's bar.
            announceTransition = false,
        )
    }

    private fun handleTrackEndInternal() {
        // If crossfade is already in progress (triggered by position update before track ended),
        // don't interrupt it. The old player ending is expected — the crossfade will complete
        // and finalizeCrossfade() will handle the transition.
        if (isCrossfading) {
            Logger.d(TAG, "handleTrackEndInternal: crossfade in progress, ignoring track end")
            return
        }

        val shouldCrossfade =
            crossfadeEnabled &&
                // The track has already ended. With skip silence on, its closing silence was cut, so
                // the music stopped before the position the fade is timed from: a crossfade now would
                // only fade the next track in from nothing for seconds after the old one went quiet.
                // The next track starts at once instead, as on Android. The fade that runs ahead of
                // the end, off the position loop, is untouched.
                !isSkipSilenceInForce() &&
                !crossfadeSuppressed &&
                // Repeat One loops this track through the branch below instead of fading into the next.
                internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                hasNextMediaItem() &&
                !isCurrentTrackVideo() &&
                !isNextTrackVideo() &&
                !isCurrentTrackLive() &&
                !isNextTrackLive() &&
                !isCurrentTrackTooShortForCrossfade() &&
                !isWithinAlbum()

        if (shouldCrossfade) {
            val nextIndex = getNextMediaItemIndex()
            triggerCrossfadeTransition(nextIndex)
        } else {
            when (internalRepeatMode) {
                PlayerConstants.REPEAT_MODE_ONE -> {
                    seekTo(localCurrentMediaItemIndex, 0)
                }

                PlayerConstants.REPEAT_MODE_ALL -> {
                    if (hasNextMediaItem()) {
                        seekToNext()
                    }
                }

                else -> {
                    if (hasNextMediaItem()) {
                        seekToNext()
                    }
                }
            }
        }
    }

    /**
     * Trigger crossfade to next track
     */
    private fun triggerCrossfadeTransition(nextIndex: Int) {
        if (nextIndex !in playlist.indices || isCrossfading) {
            // Says which of the two blocked it — an index the playlist no longer holds, or a
            // crossfading flag left set. Reported once per blocked index rather than on every poll:
            // the position loop reaches here every 200 ms for as long as the track stays inside the
            // trigger window, and a line repeating five times a second buries everything else.
            if (lastBlockedCrossfadeIndex != nextIndex) {
                lastBlockedCrossfadeIndex = nextIndex
                Logger.d(
                    TAG,
                    "Crossfade not started: nextIndex=$nextIndex, playlistSize=${playlist.size}, " +
                        "isCrossfading=$isCrossfading, currentIndex=$localCurrentMediaItemIndex",
                )
            }
            return
        }
        lastBlockedCrossfadeIndex = -1

        // Pinned now, not inside the launch: the target is already chosen, and an append that runs
        // before the fade makes it current must not take its slot.
        if (internalShuffleModeEnabled) pinnedNextItems.add(playlist[nextIndex])

        crossfadeJob =
            coroutineScope.launch {
                try {
                    setCrossfading(true)
                    val nextMediaItem = playlist[nextIndex]
                    val nextVideoId = nextMediaItem.mediaId

                    Logger.d(TAG, "Starting crossfade to track $nextIndex ($nextVideoId)")

                    // Extract URL on IO thread (network), mpv native calls stay on service thread
                    val cachedPrecache = takeHealthyPrecachedPlayer(nextVideoId)
                    val nextPlayer: MpvPlayer? =
                        if (cachedPrecache?.player != null) {
                            Logger.d(TAG, "Using precached player for crossfade")
                            cachedPrecache.player
                        } else {
                            val nextSource = withContext(Dispatchers.IO) { extractPlayableUrl(nextMediaItem) }
                            if (nextSource == null || nextSource.url.isEmpty()) {
                                Logger.e(TAG, "Failed to extract URL for crossfade")
                                null
                            } else {
                                createMediaPlayerInternal(nextSource, nextVideoId)?.also { newPlayer ->
                                    newPlayer.loadFile(buildPlaybackUrl(nextSource), startPaused = true)
                                }
                            }
                        }

                    if (nextPlayer != null) {
                        // Setup secondary player with its OWN listener.
                        // DO NOT call setupPlayerEventsInternal() here - that would remove
                        // the event listener from the current player (which is still playing).
                        secondaryPlayer = nextPlayer
                        nextPlayer.setEventListener(
                            object : EightDSyncListener() {
                                override fun error(player: MpvPlayer) {
                                    Logger.e(TAG, "Secondary player error during crossfade")
                                    coroutineScope.launch {
                                        crossfadeJob?.cancel()
                                        secondaryPlayer?.release()
                                        secondaryPlayer = null
                                        setCrossfading(false)
                                        seekTo(nextIndex, 0)
                                    }
                                }
                            },
                        )
                        nextPlayer.setMute(true)
                        nextPlayer.applyPlaybackLevels()
                        nextPlayer.setFadeVolume(0)
                        nextPlayer.play()
                        delay(50)
                        nextPlayer.setMute(false)
                    }

                    if (nextPlayer == null) {
                        setCrossfading(false)
                        seekTo(nextIndex, 0)
                        return@launch
                    }

                    // Capture current index BEFORE advancing localCurrentMediaItemIndex
                    crossfadeFromIndex = localCurrentMediaItemIndex

                    // Update now playing and video frames immediately. Set unconditionally: the
                    // old null-guard kept the OUTGOING player's frames on screen when the incoming
                    // track has no video, leaving a dead surface from a soon-released player on
                    // screen (the "black video until next/prev" bug).
                    localCurrentMediaItemIndex = nextIndex
                    unpinCurrent()
                    _currentVideoFrames.value = nextPlayer.videoFrames
                    notifyListeners {
                        onMediaItemTransition(
                            nextMediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    Logger.d(TAG, "Now playing updated to track $nextIndex during crossfade")

                    val resolvedConfigDurationMs = crossfadeDurationMs

                    // Calculate effective crossfade duration based on ACTUAL remaining time.
                    // Remaining up to where the outgoing music ends: the music end with skip silence on.
                    var fadeEndLog = ""
                    val actualTimeRemaining =
                        currentPlayer?.let { player ->
                            val dur = player.length
                            val pos = player.time
                            if (dur > 0 && pos >= 0) {
                                val fadeEnd = fadeEndMs(player, dur)
                                if (fadeEnd < dur) fadeEndLog = ", timed to the music end at ${fadeEnd}ms of ${dur}ms (position ${pos}ms)"
                                MusicEnd.remainingMs(pos, fadeEnd)
                            } else {
                                resolvedConfigDurationMs.toLong()
                            }
                        } ?: resolvedConfigDurationMs.toLong()

                    val effectiveCrossfadeDurationMs = MusicEnd.effectiveFadeMs(resolvedConfigDurationMs.toLong(), actualTimeRemaining)

                    // Info, not debug: whether a fade was timed to the music shows in a release log too.
                    Logger.i(
                        TAG,
                        "Crossfade duration: configured=${resolvedConfigDurationMs}ms, " +
                            "actualRemaining=${actualTimeRemaining}ms, effective=${effectiveCrossfadeDurationMs}ms$fadeEndLog",
                    )

                    performCrossfade(
                        nextIndex,
                        nextPlayer,
                        effectiveCrossfadeDurationMs,
                    )
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Crossfade error: ${e.message}", e)
                        setCrossfading(false)
                        seekTo(nextIndex, 0)
                    }
                }
            }
    }

    /**
     * Cancel the ongoing crossfade, await its completion, and clean up state.
     * Uses [Job.cancelAndJoin] to ensure the crossfade coroutine's catch block
     * has finished before the caller proceeds, preventing race conditions.
     *
     * @param revertIndex If true, revert [localCurrentMediaItemIndex] to the track
     *   that was playing before the crossfade started.
     */
    private suspend fun cancelCrossfadeAndCleanup(revertIndex: Boolean) {
        val job = crossfadeJob
        crossfadeJob = null
        job?.cancel()
        job?.join()
        // secondaryPlayer may already be released by performCrossfade's catch block,
        // but with isReleased guard in MpvPlayer, this is safe.
        secondaryPlayer?.release()
        secondaryPlayer = null
        if (revertIndex && crossfadeFromIndex >= 0) {
            localCurrentMediaItemIndex = crossfadeFromIndex
            playlist.getOrNull(crossfadeFromIndex)?.let { mediaItem ->
                notifyListeners {
                    onMediaItemTransition(
                        mediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                    )
                }
            }
        }
        crossfadeFromIndex = -1
        setCrossfading(false)
    }

    /**
     * Abort an in-progress crossfade by committing the INCOMING track (A+1) as the new current
     * player — the mid-fade counterpart of [finalizeCrossfade]. Invoked when the user interacts
     * during a crossfade (next / previous / pause).
     *
     * Direction: "crossfade means we have moved to A+1", so A+1 is kept and A is dropped. This
     * mirrors CrossfadeExoPlayerAdapter.commitIncomingAsCurrentInternal() on Android — both
     * platforms must resolve mid-crossfade transport commands the same way.
     *
     * [localCurrentMediaItemIndex] already equals A+1 and its onMediaItemTransition was emitted
     * by [triggerCrossfadeTransition], so neither is touched here: the UI already shows A+1.
     *
     * Position polling is deliberately left running, unlike the Android counterpart. On this
     * adapter the poll loop also drives crossfade detection and [play] never restarts it, so
     * stopping it here would freeze progress and disable every later crossfade.
     *
     * Does NOT change [internalState] and does NOT seek — the caller decides what happens next
     * (pause in place, advance to A+2, go to previous, ...).
     */
    private suspend fun commitIncomingAsCurrent() {
        val incoming = secondaryPlayer
        if (incoming == null) {
            // The fade already collapsed (the secondary player errored out and was released).
            // Nothing to promote, so revert to A instead — that keeps index and player in sync.
            Logger.w(TAG, "commitIncomingAsCurrent: no incoming player, reverting to outgoing track")
            cancelCrossfadeAndCleanup(revertIndex = true)
            return
        }

        committingIncomingPlayer = true
        try {
            val job = crossfadeJob
            crossfadeJob = null
            job?.cancel()
            job?.join()
        } finally {
            committingIncomingPlayer = false
        }

        // Drop the outgoing track (A).
        currentPlayer?.let { outgoing ->
            try {
                outgoing.release()
            } catch (e: Exception) {
                Logger.w(TAG, "commitIncomingAsCurrent: error releasing outgoing player: ${e.message}")
            }
        }

        // Promote the incoming track (A+1).
        currentPlayer = incoming
        currentPlayerIsVideo = incoming.videoFrames != null
        _currentVideoFrames.value = incoming.videoFrames
        secondaryPlayer = null

        // Until now it only carried the minimal crossfade error listener (see
        // triggerCrossfadeTransition); give it the full one now that it is the current player.
        setupPlayerEventsInternal(incoming)

        // It was mid fade-in, so its ramp sits somewhere below full — open it back up. The master
        // volume is untouched by the crossfade and already holds whatever the user set.
        incoming.applyPlaybackLevels()
        incoming.setFadeVolume(100)

        crossfadeFromIndex = -1
        setCrossfading(false)
        Logger.d(TAG, "commitIncomingAsCurrent: committed track index $localCurrentMediaItemIndex")
    }

    /**
     * Perform the actual crossfade animation
     *
     * @param effectiveDurationMs The actual crossfade duration to use. May be shorter than
     *   the configured [crossfadeDurationMs] if URL resolution / buffering consumed
     *   part of the crossfade window.
     */
    private suspend fun performCrossfade(
        nextIndex: Int,
        nextPlayer: MpvPlayer,
        effectiveDurationMs: Int,
    ) {
        val steps = 50
        val delayPerStep = (effectiveDurationMs / steps).coerceAtLeast(20)
        // A pure 0..100 attenuation per handle: the user's level rides on the master volume and must
        // come out of the crossfade exactly as it went in.
        val targetVolume = 100
        val outgoingPlayer = currentPlayer

        Logger.d(
            TAG,
            "Crossfade animation: ${effectiveDurationMs}ms, $steps steps, ${delayPerStep}ms/step, " +
                "internalVolume=$internalVolume",
        )

        try {
            for (step in 0..steps) {
                currentCoroutineContext().ensureActive()

                val progress = step.toFloat() / steps
                val angle = progress * Math.PI / 2.0

                val fadeOutVolume = (targetVolume * kotlin.math.cos(angle)).toInt()
                currentPlayer?.setFadeVolume(fadeOutVolume)

                val fadeInVolume = (targetVolume * kotlin.math.sin(angle)).toInt()
                nextPlayer.setFadeVolume(fadeInVolume)

                delay(delayPerStep.toLong())
            }

            finalizeCrossfade(nextIndex, nextPlayer)
        } catch (e: CancellationException) {
            // Undo everything this fade did to the audio of BOTH handles. Whichever one survives
            // the cancel must not be left off-tempo; for the one that gets released it
            // is a harmless no-op (MpvPlayer guards on isReleased).
            outgoingPlayer?.endCrossfadeAudio()
            nextPlayer.endCrossfadeAudio()

            if (committingIncomingPlayer) {
                // commitIncomingAsCurrent() is promoting nextPlayer to current — releasing it
                // here would kill the track the user just chose to keep.
                Logger.d(TAG, "Crossfade cancelled to commit incoming track — keeping its player")
            } else {
                Logger.d(TAG, "Crossfade cancelled")
                nextPlayer.release()
                secondaryPlayer = null
                setCrossfading(false)
            }
        }
    }

    /**
     * Finalize crossfade: swap players and cleanup
     */
    private fun finalizeCrossfade(
        nextIndex: Int,
        nextPlayer: MpvPlayer,
    ) {
        Logger.d(TAG, "Crossfade complete, swapping players")

        stopPositionUpdates()

        // Cleanup old current player
        currentPlayer?.let { oldPlayer ->
            try {
                oldPlayer.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error cleaning up old player: ${e.message}")
            }
        }

        // Promote secondary to current
        currentPlayer = nextPlayer
        currentPlayerIsVideo = nextPlayer.videoFrames != null
        _currentVideoFrames.value = nextPlayer.videoFrames
        secondaryPlayer = null

        // Now set up the full event listener on the new current player
        // (replaces the minimal crossfade error listener)
        setupPlayerEventsInternal(nextPlayer)

        // Ensure correct volume and untouched playback. (Its speed/pitch were never moved — only the
        // outgoing track is adjusted — but restore them anyway, mirroring the Android finalize path.)
        currentPlayer?.applyPlaybackLevels()
        currentPlayer?.setFadeVolume(100)
        currentPlayer?.endCrossfadeAudio()

        // Reset state
        setCrossfading(false)
        crossfadeFromIndex = -1
        transitionToState(InternalState.PLAYING)

        // Start position tracking
        startPositionUpdates()

        // Trigger next precache
        triggerPrecachingInternal()
    }

    companion object {
        /** Floor for the shortest track worth crossfading, in ms. */
        private const val MIN_CROSSFADE_TRACK_MS = 20_000L

        /** Played time after which a track heard from the network is saved to the listening cache. */
        private const val CACHE_AFTER_LISTENED_MS = 30_000L

        /** An end of file further than this from the track's length means the stream died, not that it finished. */
        private const val EARLY_END_MARGIN_MS = 5_000L
    }

    /**
     * Return a handle to untouched playback: no crossfade filters, natural speed. Clearing the
     * filter chain is also what drops the pitch shift (see [MpvPlayer.clearAudioFilters]).
     */
    private fun MpvPlayer.endCrossfadeAudio() {
        clearAudioFilters()
        setRate(internalPlaybackSpeed)
    }

    // ========== Position Updates ==========

    /**
     * Start position updates (periodic polling for crossfade detection).
     * mpv's observed `time-pos` handles position caching, but this loop is still needed
     * for crossfade trigger detection.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != null) {
                    try {
                        if (internalState == InternalState.PLAYING ||
                            internalState == InternalState.READY ||
                            internalState == InternalState.PAUSED
                        ) {
                            // During crossfade, show the incoming track's timeline exclusively
                            if (isCrossfading) {
                                val nextPlayer = secondaryPlayer
                                if (nextPlayer != null) {
                                    val pos = nextPlayer.time
                                    val dur = nextPlayer.length
                                    if (pos > 0) cachedPosition = pos
                                    if (dur > 0) cachedDuration = dur
                                }
                            } else {
                                val player = currentPlayer
                                if (player != null) {
                                    val pos = player.time
                                    val dur = player.length
                                    if (pos > 0) cachedPosition = pos
                                    if (dur > 0) cachedDuration = dur
                                    if (internalState == InternalState.PLAYING) trackListening(player)
                                }
                            }

                            // Check if should trigger crossfade.
                            // Use currentPlayer's time (not the timeline player) for trigger detection.
                            // Block when paused (internalPlayWhenReady=false) to prevent
                            // crossfade during queue restore.
                            if (crossfadeEnabled &&
                                !crossfadeSuppressed &&
                                internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                                !isCrossfading &&
                                internalPlayWhenReady &&
                                !isCurrentTrackVideo() &&
                                !isNextTrackVideo() &&
                                !isCurrentTrackLive() &&
                                !isNextTrackLive() &&
                                !isCurrentTrackTooShortForCrossfade() &&
                                !isWithinAlbum()
                            ) {
                                val player = currentPlayer
                                if (player != null) {
                                    val dur = player.length
                                    val pos = player.time
                                    if (dur > 0 && pos > 0) {
                                        // Up to the music end when skip silence is on and it is known,
                                        // so the old track fades out over its last music, not its silence.
                                        val timeRemaining = MusicEnd.remainingMs(pos, fadeEndMs(player, dur))
                                        val nextVideoId = playlist.getOrNull(getNextMediaItemIndex())?.mediaId
                                        val isPrecached = nextVideoId != null && precachedPlayers.containsKey(nextVideoId)
                                        val preparationBufferMs = if (isPrecached) 0L else 3000L
                                        if (MusicEnd.shouldStartCrossfade(timeRemaining, crossfadeDurationMs.toLong(), preparationBufferMs)) {
                                            if (hasNextMediaItem()) {
                                                val nextIndex = getNextMediaItemIndex()
                                                triggerCrossfadeTransition(nextIndex)
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isCrossfading) currentPlayer?.let { checkMusicEnd(it) }
                        }
                    } catch (_: Exception) {
                        // Ignore query errors
                    }

                    delay(50) // Update every 50ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // ========== Music end (skip silence) ==========

    /** The measured music end of [videoId], or null when it is not known. */
    private fun musicEndOf(videoId: String): Long? = musicEnds[videoId]?.takeIf { it > 0L }

    /**
     * Where a crossfade out of [player] should finish: its music end while skip silence is in force
     * and that is known, the file's end otherwise (see [MusicEnd.fadeEndMs]).
     */
    private fun fadeEndMs(
        player: MpvPlayer,
        durationMs: Long,
    ): Long {
        if (!isSkipSilenceInForce()) return durationMs
        val videoId = playerOrigins[player]?.first ?: return durationMs
        return MusicEnd.fadeEndMs(durationMs, musicEndOf(videoId))
    }

    /**
     * With skip silence in force, measures where the current track's music ends once it nears its end
     * ([MusicEnd.shouldMeasure]), and ends the track there when nothing else has (no crossfade, or one
     * not allowed here). `silenceremove` only cuts a silence once [silenceRemoveGraph]'s two seconds of
     * it have played, so without this a short tail would be heard whole and a long one for two seconds.
     * When the music end is not known the filter's own cut and mpv's end of file still end the track.
     * Called from the position loop, outside a crossfade.
     */
    private fun checkMusicEnd(player: MpvPlayer) {
        if (!isSkipSilenceInForce() || isCurrentTrackLive() || isCurrentTrackVideo()) return
        val videoId = playerOrigins[player]?.first ?: return
        val dur = player.length
        val pos = player.time
        if (dur <= 0L || pos < 0L) return
        if (videoId !in musicEndRequested && MusicEnd.shouldMeasure(dur, pos)) {
            musicEndRequested += videoId
            coroutineScope.launch {
                val end =
                    try {
                        MpvMusicEndReader.read(videoId, dur) { waveformSourceUrl(videoId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Music end of $videoId not measured: ${e.message}")
                        null
                    }
                musicEnds[videoId] = end ?: -1L
            }
        }
        val musicEnd = musicEndOf(videoId)
        if (musicEndHandledFor === player) {
            // Back before the music end on the same handle (a seek): it may end there again.
            if (pos < MusicEnd.fadeEndMs(dur, musicEnd)) musicEndHandledFor = null
            return
        }
        if (internalState == InternalState.PLAYING && MusicEnd.isPastMusicEnd(pos, dur, musicEnd)) {
            musicEndHandledFor = player
            Logger.i(TAG, "$videoId: music ended at ${musicEnd}ms, position ${pos}ms of ${dur}ms; ending the track instead of playing its silence")
            transitionToState(InternalState.ENDED)
            handleTrackEndInternal()
        }
    }

    /**
     * Trigger precaching
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty()) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    awaitCurrentTrackUnderway()
                    // The tracks Next will actually play, in Next's own order: shuffle-aware, wrapping
                    // under Repeat All. Picking `index + i` in playlist order precached tracks a shuffled
                    // queue never reached, and nothing released them until the queue was edited (#2619).
                    // Read before the loop below first suspends, so an edit landing meanwhile cannot shift them.
                    val targets = mutableListOf<GenericMediaItem>()
                    var from = localCurrentMediaItemIndex
                    for (i in 1..maxPrecacheCount) {
                        val next = getNextMediaItemIndex(from)
                        if (next == from || next == localCurrentMediaItemIndex) break
                        playlist.getOrNull(next)?.let { targets += it }
                        from = next
                    }

                    // Release every precache Next no longer reaches: the shuffle picks it skipped, the
                    // tracks ahead of a Previous or a jump. The current track's entry stays: a trigger
                    // can run between the index moving and that track's load taking its entry.
                    val targetIds = targets.mapTo(HashSet()) { it.mediaId }
                    val currentId = currentMediaItem?.mediaId
                    precachedPlayers.entries.removeIf { (id, cached) ->
                        val stale = id !in targetIds && id != currentId
                        if (stale) cleanupPlayerInternal(cached.player)
                        stale
                    }

                    for (mediaItem in targets) {
                        if (!isActive) break

                        // Checked here, not when the targets were picked: one track can be both targets
                        // (a radio repeats songs), and a second insert would overwrite the first player
                        // without releasing it. A live broadcast is not precached: buffered ahead, it
                        // would start behind the live edge by however long it waited in the queue.
                        if (precachedPlayers.containsKey(mediaItem.mediaId) || LiveStreamRegistry.isLive(mediaItem.mediaId)) {
                            continue
                        }

                        // Network extraction on IO, mpv native calls on the service thread
                        val source = withContext(Dispatchers.IO) { extractPlayableUrl(mediaItem) }

                        if (source != null && source.url.isNotEmpty()) {
                            try {
                                // mpv has no separate "parse without playing" call like vlcj's
                                // media().prepare(); loading held at pause=yes is the equivalent,
                                // and additionally warms the demuxer cache.
                                val player = createMediaPlayerInternal(source, mediaItem.mediaId)
                                if (player == null) {
                                    Logger.w(TAG, "Precaching skipped for ${mediaItem.mediaId}: could not create mpv player")
                                } else {
                                    player.applyPlaybackLevels()
                                    // In the map and listened to before the load starts: a failure that
                                    // arrives at once must find its entry (see precacheListener).
                                    val entry = PrecachedPlayer(player, mediaItem)
                                    player.setEventListener(precacheListener(mediaItem.mediaId, entry))
                                    precachedPlayers[mediaItem.mediaId] = entry
                                    player.loadFile(buildPlaybackUrl(source), startPaused = true)
                                    Logger.d(TAG, "Precached player for ${mediaItem.mediaId}")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Precaching error for ${mediaItem.mediaId}: ${e.message}")
                            }
                        }

                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Precaching error: ${e.message}")
                    }
                }
            }
    }

    /**
     * Removes and returns the precached handle of [videoId], or null if there is none or its load
     * already failed: swapped in, it would start silent (the error came before anyone listened) and
     * the track would never play. A failed one is released and the caller builds the track afresh.
     */
    private fun takeHealthyPrecachedPlayer(videoId: String): PrecachedPlayer? {
        val cached = precachedPlayers.remove(videoId) ?: return null
        cached.player.setEventListener(null)
        if (cached.failed || cached.player.isReleased) {
            Logger.w(TAG, "Discarding failed precached player for $videoId")
            cleanupPlayerInternal(cached.player)
            return null
        }
        return cached
    }

    /** Watches a precached handle while it waits for its turn; taken off when it is promoted. */
    private fun precacheListener(
        videoId: String,
        entry: PrecachedPlayer,
    ): MpvPlayerEventAdapter =
        object : MpvPlayerEventAdapter() {
            override fun error(player: MpvPlayer) {
                entry.failed = true
                coroutineScope.launch { retryOrEvictPrecache(videoId, entry) }
            }
        }

    /**
     * A precached load that failed (a stale or rejected stream URL, a network drop) is tried again on
     * the same handle with a fresh URL, like the playing handle is, up to [maxRetryCount] times. A
     * handle that keeps failing leaves the map and is released, so it is never swapped in and the
     * track is precached again at the next trigger.
     */
    private suspend fun retryOrEvictPrecache(
        videoId: String,
        entry: PrecachedPlayer,
    ) {
        // Promoted, released or replaced since the error was raised: nothing left to retry.
        if (precachedPlayers[videoId] !== entry) return
        if (entry.attempts >= maxRetryCount) {
            evictPrecache(videoId, entry, "retries exhausted")
            return
        }
        entry.attempts++
        Logger.w(TAG, "Precache load failed (attempt ${entry.attempts}/$maxRetryCount) for $videoId")
        try {
            val source =
                withContext(Dispatchers.IO) {
                    streamRepository.invalidateFormat(videoId)
                    streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                    extractPlayableUrl(entry.mediaItem)
                }
            if (precachedPlayers[videoId] !== entry || entry.player.isReleased) return
            if (source == null || source.url.isEmpty()) {
                evictPrecache(videoId, entry, "no stream URL")
                return
            }
            playerOrigins[entry.player] = videoId to source
            entry.failed = false
            entry.player.loadFile(buildPlaybackUrl(source), startPaused = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Precache retry failed for $videoId: ${e.message}")
            evictPrecache(videoId, entry, "retry threw")
        }
    }

    private fun evictPrecache(
        videoId: String,
        entry: PrecachedPlayer,
        reason: String,
    ) {
        if (!precachedPlayers.remove(videoId, entry)) return
        Logger.w(TAG, "Evicting failed precached player for $videoId: $reason")
        cleanupPlayerInternal(entry.player)
    }

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    /**
     * Precaching waits until the current track plays (or has failed or ended), or [PRECACHE_WAIT_MS]
     * at most. Each precache resolves a stream of its own; on Android they were measured taking the
     * network from a link opened at launch (2026-11-16), and the paused queue restored at launch is
     * the same here: the link replaces it seconds later. Not precached until it plays or the wait ends.
     */
    private suspend fun awaitCurrentTrackUnderway() {
        withTimeoutOrNull(PRECACHE_WAIT_MS) {
            while (true) {
                if (currentPlayer == null) break
                when (internalState) {
                    InternalState.PLAYING, InternalState.ENDED, InternalState.ERROR, InternalState.IDLE -> break
                    else -> delay(PRECACHE_POLL_MS)
                }
            }
        }
    }

    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        precachedPlayers.entries.removeIf { (videoId, cached) ->
            if (videoId != currentMediaItem?.mediaId) {
                cleanupPlayerInternal(cached.player)
                true
            } else {
                false
            }
        }
    }

    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        precachedPlayers.values.forEach { cleanupPlayerInternal(it.player) }
        precachedPlayers.clear()
    }

    /**
     * Dispatch listener notifications on the service thread.
     * Listeners (JvmMediaPlayerHandlerImpl) use thread-safe StateFlow updates
     * and contain runBlocking calls that would deadlock on Main/Swing EDT.
     */
    private fun notifyListeners(block: MediaPlayerListener.() -> Unit) {
        coroutineScope.launch {
            listeners.forEach(block)
        }
    }

    // ========== Shuffle Management ==========

    private fun createShuffleOrder() {
        // A rebuilt order puts no track in any particular place, the pinned ones included.
        pinnedNextItems.clear()
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }

        val indices = playlist.indices.toMutableList()
        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) {
            indices.removeAt(currentIndex)
        }

        indices.shuffle()

        if (currentIndex in playlist.indices) {
            indices.add(0, currentIndex)
        }

        shuffleOrder.clear()
        shuffleOrder.addAll(indices)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            shuffleIndices[originalIndex] = shuffledPos
        }

        Logger.d(TAG, "Created shuffle order: $shuffleOrder")
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
        pinnedNextItems.clear()
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) return

        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) {
                shuffleOrder[i]++
            }
        }

        val insertPos = (afterShufflePos + 1).coerceIn(0, shuffleOrder.size)
        shuffleOrder.add(insertPos, insertedOriginalIndex)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, origIndex ->
            if (origIndex < shuffleIndices.size) {
                shuffleIndices[origIndex] = shuffledPos
            }
        }
    }

    /**
     * Gives the track just appended to [playlist] a random place after the current one, leaving
     * every other track where it was. Rebuilding the order here (createShuffleOrder) reshuffled the
     * whole queue on every Add to queue and on every track an endless queue appended, and put tracks
     * already played back in front of the listener.
     *
     * The slots right after the current track that hold [pinnedNextItems] are skipped: a Play next
     * track, and the track a crossfade has picked. The fade chooses its next index first and only
     * makes it current after resolving and loading it, so a track inserted ahead of it meanwhile
     * would end up behind Now Playing. Every other slot stays open, so a queue built by appends is
     * still a fair shuffle, its second track included.
     *
     * The full rebuild stays as the fallback for an order that no longer covers the playlist. Appends
     * arrive on the caller's thread while the player thread edits the order, and rebuilding is what
     * used to repair such an order on the next append.
     */
    private fun insertAppendedIntoShuffleOrder() {
        val appendedIndex = playlist.lastIndex
        if (!shuffleOrderCovers(appendedIndex)) {
            createShuffleOrder()
            return
        }
        val orderSize = shuffleOrder.size
        val currentShufflePos =
            (shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: -1).coerceIn(-1, orderSize - 1)
        var firstOpenPos = currentShufflePos + 1
        while (firstOpenPos < orderSize) {
            val item = shuffleOrder.getOrNull(firstOpenPos)?.let { playlist.getOrNull(it) } ?: break
            if (item !in pinnedNextItems) break
            firstOpenPos++
        }
        insertIntoShuffleOrder(appendedIndex, Random.nextInt(firstOpenPos, orderSize + 1) - 1)
    }

    /** Whether [shuffleOrder] is a permutation of the playlist indices `0 until size`. */
    private fun shuffleOrderCovers(size: Int): Boolean {
        if (shuffleOrder.size != size || shuffleIndices.size != size) return false
        val seen = BooleanArray(size)
        for (i in 0 until size) {
            val index = shuffleOrder.getOrNull(i) ?: return false
            if (index !in 0 until size || seen[index]) return false
            seen[index] = true
        }
        return true
    }

    /**
     * Releases the pin of the track that just became current. It has had its turn: still pinned, it
     * would hold every later append back once the queue wraps around to it again.
     */
    private fun unpinCurrent() {
        playlist.getOrNull(localCurrentMediaItemIndex)?.let { pinnedNextItems.remove(it) }
    }

    /**
     * Takes [removedOriginalIndex] — already removed from the playlist — out of the shuffled order,
     * renumbering the playlist indices after it, so every other track keeps its place in the order.
     */
    private fun removeFromShuffleOrder(removedOriginalIndex: Int) {
        shuffleOrder.remove(removedOriginalIndex)
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] > removedOriginalIndex) {
                shuffleOrder[i]--
            }
        }
        rebuildShuffleIndices()
    }

    /** Re-derives the playlist index → shuffled position map from [shuffleOrder]. */
    private fun rebuildShuffleIndices() {
        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            if (originalIndex in shuffleIndices.indices) {
                shuffleIndices[originalIndex] = shuffledPos
            }
        }
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        notifyListeners { onTimelineChanged(list, reason) }
    }

    fun setPrecachingEnabled(enabled: Boolean) {
        precacheEnabled = enabled
        if (!enabled) {
            clearPrecacheExceptCurrentInternal()
        } else {
            triggerPrecachingInternal()
        }
    }

    // ========== URL Extraction ==========

    /**
     * Extract playable URL for a media item.
     * Returns both video AND audio URLs when available; they are merged into a single
     * `edl://` URL by [buildPlaybackUrl] (mpv's equivalent of VLC's `--input-slave`
     * and Android's MergingMediaSource).
     */
    /**
     * The finished download of [videoId]: the file by its exact name. DownloadUtils writes unfinished ones
     * under `.partial` and renames them here whole, so a file at this path is complete.
     */
    private fun downloadedFile(videoId: String): File? =
        File(getDownloadPath(), "$videoId.webm").takeIf { it.isFile && it.length() > 0 }

    override fun isLocallyPlayable(mediaId: String): Boolean = downloadedFile(mediaId) != null || listenCache.cachedFile(mediaId) != null

    /**
     * Counts the time [player] has actually played (paused time and seeks do not count) and, past
     * [CACHE_AFTER_LISTENED_MS], hands its stream to the listening cache once. A track skipped
     * sooner is never saved. Called from the position loop while playing, every ~50 ms.
     */
    private fun trackListening(player: MpvPlayer) {
        val now = System.nanoTime()
        if (listenedPlayer !== player) {
            listenedPlayer = player
            listenedMs = 0L
            listenOffered = false
            listenTickNs = now
            return
        }
        // A gap longer than a second is a pause or a stall, not listening.
        listenedMs += ((now - listenTickNs) / 1_000_000).coerceIn(0L, 1_000L)
        listenTickNs = now
        if (listenOffered || listenedMs < CACHE_AFTER_LISTENED_MS) return
        listenOffered = true
        val (videoId, source) = playerOrigins[player] ?: return
        if (source.isLive || LiveStreamRegistry.isLive(videoId)) return
        // The audio stream itself: a video source carries it as its slave. A local path is a file
        // that is already a download or a cached copy.
        val audioUrl = (if (source.isVideo) source.audioSlaveUrl else source.url) ?: return
        if (!audioUrl.startsWith("http")) return
        listenCache.offer(videoId, audioUrl)
    }

    /**
     * Drops the cached copy of [videoId] when [player] was playing exactly that file. Returns true if
     * it did, and the caller then reloads the track, which now streams.
     */
    private fun healCorruptCache(
        player: MpvPlayer,
        videoId: String,
        reason: String,
    ): Boolean {
        val (originId, source) = playerOrigins[player] ?: return false
        val cached = listenCache.cachedFile(videoId) ?: return false
        if (originId != videoId || source.url != cached.absolutePath) return false
        if (!listenCache.drop(videoId)) return false
        Logger.i("Offline", "heal $videoId cache dropped ($reason)")
        return true
    }

    /**
     * mpv failed twice on a track it plays from the downloads folder: the file is broken (a download
     * is written whole or not at all, so it cannot be a half, but what the server sent may have been
     * garbage). Drops it and queues the download again, so the reload that follows streams the track
     * and the next play is from a good file. Once per track per session: a file that breaks again, or
     * an error that was never the file's, ends in the usual retries and the skip.
     */
    private fun healCorruptDownload(videoId: String): Boolean {
        if (videoId in healedVideoIds || downloadedFile(videoId) == null) return false
        healedVideoIds += videoId
        Logger.i("Offline", "heal $videoId (mpv failed twice on the downloaded file)")
        return downloadHandler.redownloadCorrupt(videoId)
    }

    private suspend fun extractPlayableUrl(mediaItem: GenericMediaItem): PlayableSource? {
        val started = System.nanoTime()
        return resolvePlayableUrl(mediaItem).also {
            Logger.i("Offline", "resolve ${mediaItem.mediaId} extract ${(System.nanoTime() - started) / 1_000_000} ok=${it != null}")
        }
    }

    private suspend fun resolvePlayableUrl(mediaItem: GenericMediaItem): PlayableSource? {
        Logger.d(TAG, "Extracting playable URL for ${mediaItem.mediaId}")
        val shouldFindVideo =
            mediaItem.isVideo() &&
                dataStoreManager.watchVideoInsteadOfPlayingAudio.first() == DataStoreManager.TRUE
        val videoId = mediaItem.mediaId

        // Check downloads first
        downloadedFile(videoId)?.let { audioFile ->
            // mpv accepts absolute file paths directly
            Logger.i("Offline", "source $videoId download")
            return PlayableSource(isVideo = false, url = audioFile.absolutePath)
        }

        // A live broadcast is one HLS playlist carrying audio and video together: there is nothing
        // to merge, and nothing about it is in the format table (see StreamRepositoryImpl).
        if (LiveStreamRegistry.isLive(videoId)) return liveStreamSource(videoId, asVideo = shouldFindVideo)

        // Then the listening cache, which holds audio only: a track meant to play as video goes to the
        // network first and falls back to the cached audio (see below).
        if (!shouldFindVideo) cachedSource(videoId)?.let { return it }

        // Try new format API (returns both audio and video URLs)
        streamRepository.getNewFormat(videoId).lastOrNull()?.let { format ->
            val audioUrl = format.audioUrl
            val videoUrl = format.videoUrl

            if (shouldFindVideo && !videoUrl.isNullOrEmpty()) {
                val is403Video = streamRepository.is403Url(videoUrl).firstOrNull() != false
                if (!is403Video) {
                    // Return video URL with audio as a second EDL stream for merging
                    val audioSlave =
                        if (!audioUrl.isNullOrEmpty()) {
                            val is403Audio = streamRepository.is403Url(audioUrl).firstOrNull() != false
                            if (!is403Audio) audioUrl else null
                        } else {
                            null
                        }

                    Logger.d("Stream", "Video from format (with audio stream: ${audioSlave != null})")
                    Logger.i("Offline", "source $videoId saved-url")
                    return PlayableSource(
                        isVideo = true,
                        url = videoUrl,
                        audioSlaveUrl = audioSlave,
                    )
                }
            } else if (!shouldFindVideo && !audioUrl.isNullOrEmpty()) {
                val checkStarted = System.nanoTime()
                val is403Url = streamRepository.is403Url(audioUrl).firstOrNull() != false
                Logger.i("Offline", "resolve $videoId saved-is403 ${(System.nanoTime() - checkStarted) / 1_000_000} rejected=$is403Url")
                if (!is403Url) {
                    Logger.d("Stream", "Audio from format")
                    Logger.i("Offline", "source $videoId saved-url")
                    return PlayableSource(isVideo = false, url = audioUrl)
                }
            }
        }

        // Fallback to stream extraction
        if (shouldFindVideo) {
            val videoUrl =
                streamRepository
                    .getStream(
                        dataStoreManager,
                        videoId,
                        isDownloading = false,
                        isVideo = true,
                        // NOT muxed — deliberately different from VlcPlayerAdapter.
                        //
                        // VLC cannot mux two streams into one source, so it has to ask for an HLS
                        // manifest (muxed=true), which returns the SAME url as both audioUrl and
                        // videoUrl. mpv can mux, via `edl://` + `!new_stream` — the very mechanism
                        // mpv's own ytdl_hook uses for YouTube's separate audio/video formats.
                        //
                        // Staying on the default (false) therefore gives us: real DASH streams
                        // instead of one manifest opened twice, free choice of itag rather than
                        // whatever the HLS variant offers, and the signed-in session preserved
                        // (StreamRepositoryImpl passes `noLogIn = muxed` straight to the player
                        // request, so muxed=true silently drops the login for that call).
                    ).lastOrNull()
            if (videoUrl != null) {
                // getStream is where a live broadcast is first recognised, and its playlist
                // already carries the audio — there is no separate audio stream to merge in.
                if (LiveStreamRegistry.isLive(videoId)) return PlayableSource(isVideo = true, url = videoUrl, isLive = true)
                // The stream above is video-ONLY. Dropping `muxed = true` traded the HLS
                // manifest (audio and video in one URL) for a real DASH stream, which carries
                // no audio track at all — so the audio URL has to be fetched separately and
                // merged back in, exactly as the format path above does. Without this the
                // player plays a silent video, with nothing in the logs to say why.
                val audioUrl =
                    streamRepository
                        .getStream(
                            dataStoreManager,
                            videoId,
                            isDownloading = false,
                            isVideo = false,
                        ).lastOrNull()
                Logger.d(TAG, "Stream Video $videoUrl (with audio stream: ${audioUrl != null})")
                Logger.i("Offline", "source $videoId fresh-url")
                return PlayableSource(
                    isVideo = true,
                    url = videoUrl,
                    audioSlaveUrl = audioUrl,
                )
            }
        } else {
            val audioUrl =
                streamRepository
                    .getStream(
                        dataStoreManager,
                        videoId,
                        isDownloading = false,
                        isVideo = false,
                    ).lastOrNull()
            if (audioUrl != null) {
                Logger.d(TAG, "Stream Audio $audioUrl")
                Logger.i("Offline", "source $videoId fresh-url")
                // getStream above is also where a live broadcast is first recognised.
                return PlayableSource(isVideo = false, url = audioUrl, isLive = LiveStreamRegistry.isLive(videoId))
            }
        }

        if (shouldFindVideo) cachedSource(videoId)?.let { return it }

        Logger.i("Offline", "source $videoId none")
        return null
    }

    /** The cached copy of [videoId], marked as just played. */
    private fun cachedSource(videoId: String): PlayableSource? =
        listenCache.cachedFile(videoId)?.let { file ->
            listenCache.touch(file)
            Logger.i("Offline", "source $videoId cache")
            PlayableSource(isVideo = false, url = file.absolutePath)
        }

    /** A live broadcast's HLS playlist, resolved afresh on every play — it expires within hours. */
    private suspend fun liveStreamSource(
        videoId: String,
        asVideo: Boolean,
    ): PlayableSource? =
        streamRepository
            .getStream(
                dataStoreManager,
                videoId,
                isDownloading = false,
                isVideo = asVideo,
            ).lastOrNull()
            ?.let { liveHlsUrl ->
                Logger.d(TAG, "Live stream $videoId: $liveHlsUrl")
                PlayableSource(isVideo = asVideo, url = liveHlsUrl, isLive = true)
            }
}

private const val MPV_AUTO_DEVICE = "auto"

/**
 * The outputs [driver] can route to, read off mpv's `audio-device-list`, with [activeDevice] marked.
 *
 * mpv lists every driver's devices in one array (`coreaudio/…` and `avfoundation/…` both, on a Mac),
 * and naming another driver's device switches the handle to that driver — on macOS, back to the
 * coreaudio one the whole player is pinned away from. Only [driver]'s own entries are offered, and
 * nothing at all while the driver is unknown, rather than guessing. `auto` comes first as the system
 * default, with an empty name for the UI to label in the user's language.
 */
internal fun parseAudioOutputs(
    json: String?,
    driver: String?,
    activeDevice: String?,
): List<AudioOutput> {
    if (json == null || driver == null) return emptyList()
    val entries = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
    val active = activeDevice ?: MPV_AUTO_DEVICE
    val devices =
        entries.mapNotNull { entry ->
            val fields = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = fields["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!name.startsWith("$driver/")) return@mapNotNull null
            AudioOutput(
                id = name,
                name = fields["description"]?.jsonPrimitive?.contentOrNull ?: name.substringAfter('/'),
                // mpv says nothing about what a device is, so the type is left open.
                kind = AudioOutputKind.OTHER,
                isActive = name == active,
            )
        }
    val systemDefault =
        AudioOutput(
            id = MPV_AUTO_DEVICE,
            name = "",
            kind = AudioOutputKind.DEVICE_SPEAKER,
            isActive = active == MPV_AUTO_DEVICE || devices.none { it.isActive },
        )
    return listOf(systemDefault) + devices
}
