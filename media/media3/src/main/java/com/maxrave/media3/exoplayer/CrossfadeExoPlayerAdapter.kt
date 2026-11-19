package com.maxrave.media3.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.AudioOutput
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
import com.maxrave.domain.mediaservice.player.CrossfadeExclusions
import com.maxrave.domain.mediaservice.player.DownloadedClip
import com.maxrave.domain.mediaservice.player.DownloadedClips
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.mediaservice.player.MusicEnd
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.waveform.WaveformReader
import com.maxrave.media3.audio.ConvolutionReverbAudioProcessor
import com.maxrave.media3.audio.EchoAudioProcessor
import com.maxrave.media3.audio.EightDAudioProcessor
import com.maxrave.media3.audio.EqualizerAudioProcessor
import com.maxrave.media3.audio.EqualizerCurve
import com.maxrave.media3.audio.SleepFadeAudioProcessor
import com.maxrave.media3.audio.TrailingSilenceAudioProcessor
import com.maxrave.media3.extension.dropStale
import com.maxrave.media3.extension.isCorruptMedia
import com.maxrave.media3.extension.isFullyCached
import com.maxrave.media3.service.mediasourcefactory.isLiveStreamDetected
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "CrossfadeExoPlayerAdapter"

/** The longest precaching waits for the current track, see awaitCurrentTrackUnderway. */
private const val PRECACHE_WAIT_MS = 20_000L
private const val PRECACHE_POLL_MS = 250L

/**
 * Failed loads in a row after which a precached player is dropped. ExoPlayer retries a failed load by
 * itself and surfaces the error only when playback needs the data, which a paused precache never does:
 * a link that no longer resolves (the network gone mid-load) was asked again every 5 s for as long as
 * the player sat in the map (2 minutes in the log of 2026-11-16, until the track was promoted).
 */
private const val PRECACHE_MAX_LOAD_ERRORS = 3

/**
 * ExoPlayer implementation of [MediaPlayerInterface] with crossfade support.
 *
 * Architecture mirrors [com.simpmusic.media_jvm.GstreamerPlayerAdapter]:
 * - Internal playlist management (not ExoPlayer's playlist)
 * - Multi-player instance model: each track gets its own ExoPlayer
 * - Precaching system for smooth transitions
 * - Crossfade with listener swap pattern
 * - [DelegatingForwardingPlayer] for MediaSession integration
 *
 * Key difference from GstreamerPlayerAdapter:
 * - Uses [MergingMediaSourceFactory] + ResolvingDataSource for URL resolution
 *   (instead of manually extracting URLs via StreamRepository)
 * - Each ExoPlayer gets a single [MediaItem] and auto-resolves the stream URL
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
internal class CrossfadeExoPlayerAdapter(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val mediaSourceFactory: MergingMediaSourceFactory,
    private val audioAttributes: AudioAttributes,
    private val streamRepository: StreamRepository,
    private val waveformReader: WaveformReader,
    private val downloadCache: SimpleCache,
    private val playerCache: SimpleCache,
    private val downloadHandler: DownloadHandler,
) : MediaPlayerInterface {
    // ========== Internal State Enum (same as GstreamerPlayerAdapter) ==========

    private enum class InternalState {
        IDLE, // No media loaded
        PREPARING, // Loading media
        READY, // Ready to play/paused
        PLAYING, // Currently playing
        PAUSED,
        ENDED, // Playback ended
        ERROR, // Error state
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    // ========== Crossfade Settings (loaded from DataStore) ==========

    init {
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
            // The first value can arrive before the fields refreshDownloadedClip reads exist; nothing
            // plays yet then anyway.
            var first = true
            dataStoreManager.watchVideoInsteadOfPlayingAudio.collect { enabled ->
                watchVideoEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Watch video enabled: $watchVideoEnabled")
                if (!first) refreshDownloadedClip()
                first = false
            }
        }
        coroutineScope.launch {
            dataStoreManager.crossfadeSkipAlbum.collect { enabled ->
                skipCrossfadeInAlbum = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Skip crossfade inside album: $skipCrossfadeInAlbum")
            }
        }
        // The per-track marks from the ⋯ menu, kept here so the position loop never reads the store.
        coroutineScope.launch {
            dataStoreManager.crossfadeExcludedStarts.collect { crossfadeExcludedStarts = it }
        }
        coroutineScope.launch {
            dataStoreManager.crossfadeExcludedEnds.collect { crossfadeExcludedEnds = it }
        }
    }

    // ========== State Management ==========

    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: ExoPlayer? = null

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var internalVolume = 1.0f

    /**
     * Sleep-timer fade attenuation. Applied on a separate volume line — every player's
     * [SleepFadeAudioProcessor] reads this value straight out of here, so writing it once covers
     * both players of a crossfade and nothing on the `volume` line has to be touched.
     */
    @Volatile
    private var internalSleepFadeFactor = 1.0f

    /**
     * The equalizer curve in force. Read the same way as [internalSleepFadeFactor]: every player's
     * [EqualizerAudioProcessor] samples it on every buffer, so one write covers both players of a
     * crossfade and every precached one without any of them having to be found first.
     */
    @Volatile
    private var internalEqualizerCurve: EqualizerCurve = EqualizerCurve.FLAT

    /**
     * The delay and reverb settings in force. Read exactly like [internalEqualizerCurve]: every
     * player's [EchoAudioProcessor] and [ConvolutionReverbAudioProcessor] sample it on every
     * buffer, so one write covers both players of a crossfade and every precached one without any
     * of them having to be found first.
     */
    @Volatile
    private var internalAudioEffects: AudioEffects = AudioEffects.NONE

    private val eightDOrbit = EightDOrbit()
    override val eightDAvailable: StateFlow<Boolean> = MutableStateFlow(true)

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalSkipSilence = false

    /**
     * Measured music ends by media id ([WaveformReader.musicEnd]); -1 when it could not be told. Read
     * only while skip silence and crossfade are both on.
     */
    private val musicEnds = ConcurrentHashMap<String, Long>()

    /** Media ids whose music end has been asked for in this session, so each is measured once. */
    private val musicEndRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Position tracking - updated periodically, not on every query
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    private var positionUpdateJob: Job? = null

    // Active Player.Listener (equivalent to BusListeners in GstreamerPlayerAdapter)
    // Only ONE listener instance, attached to ONE ExoPlayer at a time.
    // Swapped between players during crossfade.
    private var activePlayerListener: Player.Listener? = null

    // ========== Audio Focus (manual, session-scoped) — #2155 ==========
    // The multi-player swap model means audio focus must NOT be tied to any single
    // ExoPlayer: releasing the outgoing player would abandon focus, and the incoming
    // player (built with handleAudioFocus=false) never re-requests it — the root cause
    // of "music stops between tracks" (see androidx/media#2100). Instead we hold one
    // app-level AudioFocusRequest at the adapter level so focus survives every swap.

    private val duckVolumeFactor = 0.2f

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @Volatile
    private var hasAudioFocus = false

    /** True when focus was lost transiently so playback should auto-resume on regain. */
    @Volatile
    private var resumeOnFocusGain = false

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Don't fight the crossfade ramp; while crossfading it owns the volume.
                    if (!isCrossfading) currentPlayer?.volume = internalVolume
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        play()
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss (another app took over): pause and stop tracking focus.
                    resumeOnFocusGain = false
                    hasAudioFocus = false
                    pause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Temporary loss (e.g. an incoming call): pause and remember to resume.
                    resumeOnFocusGain = internalState == InternalState.PLAYING
                    pause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Lower the volume instead of pausing (e.g. a navigation prompt).
                    // Skip during crossfade — the ramp owns volume and would override this.
                    if (!isCrossfading) currentPlayer?.volume = internalVolume * duckVolumeFactor
                }
            }
        }

    private val audioFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes
                    .Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
    }

    /** Request app-level audio focus once; idempotent while focus is held. */
    private fun requestAudioFocusInternal(): Boolean {
        val am = audioManager ?: return true
        if (hasAudioFocus) return true
        val granted = am.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        Logger.d(TAG, "requestAudioFocus -> granted=$granted")
        return granted
    }

    private fun abandonAudioFocusInternal() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return
        am.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        resumeOnFocusGain = false
        Logger.d(TAG, "abandonAudioFocus")
    }

    // ========== Precaching System ==========

    private data class PrecachedPlayer(
        val player: ExoPlayer,
        val mediaItem: GenericMediaItem,
        /** Retries or evicts this player while it waits; taken off when it is promoted (see [precacheRetryListener]). */
        val retryListener: Player.Listener? = null,
    )

    // VideoId -> PrecachedPlayer
    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()

    // ========== Audio Output ==========

    /**
     * Where the sound leaves by — see [MediaPlayerInterface.audioOutputs]. The picked device goes to
     * every player this adapter runs, and [createExoPlayerInstance] gives it to each new one, so the
     * next track, a crossfade partner or a precached handle cannot slip back to the old output.
     * Declared ahead of the first player built below, which reads it.
     */
    private val outputRouter = AudioOutputRouter(context) { device -> applyPreferredAudioDevice(device) }

    override val audioOutputs: StateFlow<List<AudioOutput>>
        get() = outputRouter.outputs

    override fun selectAudioOutput(id: String?) = outputRouter.select(id)

    override suspend fun loadWaveform(mediaId: String): FloatArray? = waveformReader.read(mediaId)

    // The same check the stream resolver makes before it goes to the network, keyed the same way.
    override fun isLocallyPlayable(mediaId: String): Boolean =
        downloadCache.isFullyCached(mediaId, 0L) || playerCache.isFullyCached(mediaId, 0L)

    override fun refreshAudioOutputs() = outputRouter.refresh()

    private fun applyPreferredAudioDevice(device: AudioDeviceInfo?) {
        coroutineScope.launch {
            currentPlayer?.setPreferredAudioDevice(device)
            secondaryPlayer?.setPreferredAudioDevice(device)
            precachedPlayers.values.forEach { it.player.setPreferredAudioDevice(device) }
        }
    }
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // ========== Crossfade System ==========

    @Volatile
    private var crossfadeEnabled = false

    /** See MediaPlayerInterface.crossfadeSuppressed — set while in a Listen Together room. */
    override var crossfadeSuppressed: Boolean = false

    @Volatile
    private var crossfadeDurationMs = 5000

    // Whether video content plays as video (watch-video setting) — the same condition
    // MergingMediaSourceFactory uses to build a merged audio+video source.
    @Volatile
    private var watchVideoEnabled = false

    /** User setting: leave transitions inside an album alone. */
    @Volatile
    private var skipCrossfadeInAlbum = false

    /** Set by the handler when an album is loaded; empty for every other kind of queue. */
    @Volatile
    private var internalAlbumTrackIds: Set<String> = emptySet()

    /** Tracks marked "no crossfade at the start" / "at the end" (see CrossfadeExclusions). */
    @Volatile
    private var crossfadeExcludedStarts: Set<String> = emptySet()

    @Volatile
    private var crossfadeExcludedEnds: Set<String> = emptySet()

    /** The junction last logged as excluded, so the position loop logs it once, not every tick. */
    private var loggedExcludedJunction: String? = null

    @Volatile
    private var secondaryPlayer: ExoPlayer? = null

    @Volatile
    private var crossfadeJob: Job? = null

    @Volatile
    private var isCrossfading = false

    /** Index we're crossfading from; used when cancelling to revert localCurrentMediaItemIndex. */
    @Volatile
    private var crossfadeFromIndex = -1

    // ========== Retry on Source Error ==========
    // Track retry attempts per media item to avoid infinite retry loops
    private var retryCount = 0

    // Tracks whose broken copy on disk was already dropped this session (see healCorruptCopy).
    private val healedVideoIds = mutableSetOf<String>()
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    /**
     * Update crossfade state and notify listeners when it changes.
     */
    private fun setCrossfading(value: Boolean) {
        if (isCrossfading != value) {
            isCrossfading = value
            listeners.forEach { it.onCrossfadeStateChanged(value) }
        }
    }

    // ========== Playlist Management ==========

    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // ========== Downloaded clip shown from the network ==========
    // The request itself lives in MergingMediaSourceFactory.streamedClipId, where the source is built.

    private val _downloadedClip = MutableStateFlow<DownloadedClip?>(null)
    override val downloadedClip: StateFlow<DownloadedClip?> = _downloadedClip.asStateFlow()

    private val _downloadedClipFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val downloadedClipFailures: SharedFlow<Unit> = _downloadedClipFailures

    /**
     * Re-reads [downloadedClip] for the current track, and drops the streamed clip once another track
     * is current, so the request never outlives its track.
     */
    private fun refreshDownloadedClip() {
        val item = playlist.getOrNull(localCurrentMediaItemIndex)
        mediaSourceFactory.streamedClipId = DownloadedClips.carriedOver(mediaSourceFactory.streamedClipId, item?.mediaId)
        _downloadedClip.value =
            item?.let {
                DownloadedClips.stateOf(
                    mediaId = it.mediaId,
                    watchVideoEnabled = watchVideoEnabled,
                    itemIsVideo = it.isVideo(),
                    audioOnlyDownload = mediaSourceFactory.isAudioOnlyDownload(it.mediaId),
                    streamedClipId = mediaSourceFactory.streamedClipId,
                )
            }
    }

    override fun showDownloadedClip(show: Boolean) {
        coroutineScope.launch {
            val clip = _downloadedClip.value
            val videoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
            if (clip == null || clip.mediaId != videoId || clip.shown == show) return@launch
            // A crossfade is about to make another track current: nothing to switch.
            if (isCrossfading) return@launch
            if (show) {
                // Asked before the switch: the playing file is not given up for a video that cannot
                // be had, and the resolver below finds the answer saved.
                _downloadedClip.value = clip.copy(shown = true)
                val videoUrl =
                    try {
                        withTimeoutOrNull(DOWNLOADED_CLIP_RESOLVE_TIMEOUT_MS) {
                            streamRepository
                                .getStream(dataStoreManager, videoId, isDownloading = false, isVideo = true)
                                .firstOrNull { it != null }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Video of the downloaded clip $videoId failed: ${e.message}")
                        null
                    }
                if (videoUrl == null || playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId != videoId) {
                    if (videoUrl == null) {
                        Logger.w(TAG, "No video for the downloaded clip $videoId, staying on the file")
                        _downloadedClipFailures.tryEmit(Unit)
                    }
                    refreshDownloadedClip()
                    return@launch
                }
            }
            Logger.i(TAG, "downloaded clip $videoId: ${if (show) "streaming its video" else "back to the file"}")
            switchDownloadedClip(videoId, show)
        }
    }

    /** Reloads the current track with or without its streamed video, at the position it has reached. */
    private fun switchDownloadedClip(
        videoId: String,
        show: Boolean,
    ) {
        mediaSourceFactory.streamedClipId = if (show) videoId else null
        retryVideoId = null
        retryCount = 0
        precachedPlayers.remove(videoId)?.player?.release()
        val positionMs = currentPlayer?.currentPosition?.takeIf { it > 0 } ?: cachedPosition
        loadAndPlayTrackInternal(
            localCurrentMediaItemIndex,
            positionMs.coerceAtLeast(0L),
            shouldPlay = internalPlayWhenReady,
            // Still the same track: announcing it would log a second listen.
            announceTransition = false,
        )
    }

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

    // ========== ForwardingPlayer for MediaSession ==========

    // Create an initial idle ExoPlayer for MediaSession to hold
    private val initialPlayer = createExoPlayerInstance()

    /**
     * Stable [Player] reference for MediaSession.
     * Delegates all calls to the currently active [ExoPlayer] instance.
     * Updated via [DelegatingForwardingPlayer.swapDelegate] when the active player changes.
     */
    val forwardingPlayer: DelegatingForwardingPlayer = DelegatingForwardingPlayer(initialPlayer)

    init {
        currentPlayer = initialPlayer

        // Wire up playlist navigation so ForwardingPlayer (and thus MediaSession)
        // can see the full playlist state instead of the single-item ExoPlayer state.
        // Only navigation commands are overridden — NOT getMediaItemCount/getCurrentMediaItemIndex
        // which must stay consistent with ExoPlayer's internal Timeline to avoid crashes.
        forwardingPlayer.playlistNavigationProvider =
            object : DelegatingForwardingPlayer.PlaylistNavigationProvider {
                override fun hasNextMediaItem(): Boolean = this@CrossfadeExoPlayerAdapter.hasNextMediaItem()

                override fun hasPreviousMediaItem(): Boolean = this@CrossfadeExoPlayerAdapter.hasPreviousMediaItem()

                override fun seekToNext(): Unit = this@CrossfadeExoPlayerAdapter.seekToNext()

                override fun seekToPrevious(): Unit = this@CrossfadeExoPlayerAdapter.seekToPrevious()

                override fun seekToPreviousMediaItem(): Unit = this@CrossfadeExoPlayerAdapter.seekToPreviousMediaItem()

                override fun play(): Unit = this@CrossfadeExoPlayerAdapter.play()

                override fun pause(): Unit = this@CrossfadeExoPlayerAdapter.pause()

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    this@CrossfadeExoPlayerAdapter.playWhenReady = playWhenReady
                }
            }
    }

    // ========== ExoPlayer Instance Factory ==========

    /**
     * Create a new ExoPlayer instance with its own audio processor chain.
     *
     * Audio focus is NOT handled per-player: it is managed once at the adapter level
     * (see the Audio Focus section) so it survives every player swap (#2155).
     */
    private fun createExoPlayerInstance(): ExoPlayer {
        // First in the chain: silence is judged on the decoded audio, before the equalizer or an effect
        // can lift it. The built-in skipper below handles silence at the start and inside the track.
        val trailingSilence = TrailingSilenceAudioProcessor { internalSkipSilence }
        val sleepFade = SleepFadeAudioProcessor { internalSleepFadeFactor }
        val equalizer = EqualizerAudioProcessor { internalEqualizerCurve }
        val echo = EchoAudioProcessor { internalAudioEffects }
        val reverb = ConvolutionReverbAudioProcessor { internalAudioEffects }
        val eightD = EightDAudioProcessor(eightDOrbit)

        val perPlayerRenderers =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            object : DefaultAudioSink.DefaultAudioProcessorChain(
                                // Equalizer first, matching the desktop graph, where the ten
                                // `equalizer` entries sit ahead of everything else in mpv's `af`
                                // list — it decides which stage a boost can clip in, and the
                                // preamp that makes room for the boost belongs with it. Then the
                                // two effects. The sleep fade is the master attenuation; only the 8D pan,
                                // a gain matrix it commutes with, comes after it (see below).
                                arrayOf(trailingSilence, equalizer, echo, reverb, sleepFade),
                                SilenceSkippingAudioProcessor(
                                    2_000_000,
                                    (20_000 / 2_000_000).toFloat(),
                                    2_000_000,
                                    0,
                                    256,
                                ),
                                SonicAudioProcessor(),
                            ) {
                                // Keep the existing chain/timing methods, but rotate the final
                                // PCM after Sonic. One turn then lasts the selected wall-clock
                                // duration even when playback speed or DJ tempo changes.
                                // DefaultAudioSink builds its pipeline from getAudioProcessors()
                                // (media3 1.11.1 DefaultAudioSink.java:759).
                                private val processorsWithEightD = super.getAudioProcessors() + eightD

                                override fun getAudioProcessors(): Array<AudioProcessor> = processorsWithEightD

                                // The sink moves the playback position forward by what the skippers cut.
                                // The tail the trailing-silence processor drops has to count too, or the
                                // track only ends once the clock has run through the silence it removed.
                                override fun getSkippedOutputFrameCount(): Long =
                                    super.getSkippedOutputFrameCount() + trailingSilence.skippedFrames
                            },
                        ).build()
            }

        val player =
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(audioAttributes, false)
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * 4,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 4,
                            0,
                            0,
                        ).build(),
                ).setWakeMode(C.WAKE_MODE_NETWORK)
                .setHandleAudioBecomingNoisy(true)
                .setSeekForwardIncrementMs(5000)
                .setSeekBackIncrementMs(5000)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(perPlayerRenderers)
                .build()

        // The output the user picked, if any: a player built for the next track, a crossfade or a
        // precache starts where the others are playing rather than back on the system's choice.
        outputRouter.preferredDevice?.let { player.setPreferredAudioDevice(it) }

        // Every way a player starts (play(), a precache promoted, a crossfade's incoming player)
        // goes through playWhenReady, so 8D learns it here without being threaded through them all.
        player.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    if (playWhenReady) eightD.markStarted()
                }
            },
        )

        return player
    }

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        requestAudioFocusInternal()
                        // At the end of the queue `play()` only sets playWhenReady, which does
                        // nothing while the player sits in STATE_ENDED — the press would look
                        // ignored. Rewind first so the last track replays.
                        if (internalState == InternalState.ENDED) {
                            Logger.d(TAG, "Play: replaying from the start after end of queue")
                            player.seekTo(0L)
                        }
                        player.play()
                        transitionToState(InternalState.PLAYING)
                        internalPlayWhenReady = true
                    } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                }

                InternalState.PREPARING -> {
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
        Logger.d(TAG, "pause() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        coroutineScope.launch {
            try {
                forwardingPlayer.suppressPlaybackEnded = false
                // Cancel any ongoing crossfade by committing the incoming track (A+1) as current.
                // Direction 1: pausing during a crossfade stays on A+1 (the track the UI already
                // shows) and freezes it in place via the when(internalState) block below — it does
                // NOT jump back to A.
                if (isCrossfading) {
                    Logger.d(TAG, "Pause: committing incoming (A+1) and pausing in place")
                    commitIncomingAsCurrentInternal()
                }

                when (internalState) {
                    InternalState.PLAYING, InternalState.READY -> {
                        currentPlayer?.let { player ->
                            player.pause()
                            transitionToState(InternalState.PAUSED)
                            internalPlayWhenReady = false
                        }
                    }

                    InternalState.PREPARING -> {
                        internalPlayWhenReady = false
                    }

                    else -> {
                        Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                    }
                }
            } finally {
                // Playback has stopped, so a sleep-timer attenuation has served its purpose.
                // Cleared here rather than by the caller, which cannot tell when this actually
                // happened — pause() only queues this coroutine.
                //
                // In `finally` because the block above can throw or be cancelled (committing a
                // crossfade joins a job that may be cancelled). Anything that skips this leaves the
                // factor near zero, and since the processor multiplies every sample by it, that is
                // permanent silence with a full volume slider — no code path recovers from it.
                internalSleepFadeFactor = 1f
            }
        }
    }

    override fun stop() {
        coroutineScope.launch {
            forwardingPlayer.suppressPlaybackEnded = false
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.stop()
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
                abandonAudioFocusInternal()
                notifyEqualizerIntent(false)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        // A live broadcast is followed at its live edge: its seek bar shows LIVE, not a length.
        if (isCurrentTrackLive()) return
        // Reflected immediately so the progress bar does not snap back while the seek is queued.
        cachedPosition = positionMs
        coroutineScope.launch {
            // Seeking mid-crossfade: commit the incoming track (A+1) as current first, the same way
            // pause() does. The progress bar the user just dragged belongs to A+1 — that is the
            // track the UI shows and the one position updates are read from during a crossfade.
            // Without this the seek lands on the *outgoing* track while the crossfade carries on,
            // so the old song keeps playing underneath and the seek appears to do nothing.
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: committing incoming (A+1) before seeking")
                commitIncomingAsCurrentInternal()
            }
            currentPlayer?.let { player ->
                try {
                    player.seekTo(positionMs)
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

            // Cancel any ongoing crossfade
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: Cancelling crossfade")
                crossfadeJob?.cancel()
                crossfadeJob = null
                secondaryPlayer?.release()
                secondaryPlayer = null
                setCrossfading(false)
            }

            // Cancel any ongoing load
            currentLoadJob?.cancel()

            // Load the new track
            localCurrentMediaItemIndex = mediaItemIndex
            unpinCurrent()
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val end = duration.takeIf { it > 0 } ?: cachedDuration
        val newPosition = (currentPosition + 5000).coerceAtMost(end)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        coroutineScope.launch {
            // During crossfade A→A+1, "next" commits A+1 as current (Direction 1: the UI already
            // shows A+1) and then advances to A+2. Outside crossfade it advances normally.
            val wasCrossfading = isCrossfading
            if (wasCrossfading) {
                Logger.d(TAG, "seekToNext: committing incoming (A+1), then advancing to A+2")
                commitIncomingAsCurrentInternal()
            }
            if (hasNextMediaItem()) {
                seekTo(getNextMediaItemIndex(), 0)
            } else if (wasCrossfading) {
                // A+1 was the last track — stay on it (already promoted), just refresh metadata.
                forwardingPlayer.notifyMediaItemChanged()
            }
        }
    }

    override fun seekToPrevious() {
        coroutineScope.launch {
            // During a crossfade, commit the incoming track (A+1) as current FIRST, so the
            // 3-second rule below evaluates against A+1's position/index (Direction 1).
            if (isCrossfading) {
                Logger.d(TAG, "seekToPrevious: committing incoming (A+1) first")
                commitIncomingAsCurrentInternal()
            }

            // Standard music player behavior:
            // - Position > 3s  → seek to start of current track
            // - Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            val position = currentPosition
            // A live broadcast has no start to go back to, so Previous always means the previous track.
            if (position > positionThresholdMs && !isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms > ${positionThresholdMs}ms — seeking to start")
                seekTo(0)
            } else if (hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms <= ${positionThresholdMs}ms — going to previous track")
                val prevIndex = getPreviousMediaItemIndex()
                seekTo(prevIndex, 0)
            } else if (!isCurrentTrackLive()) {
                Logger.d(TAG, "seekToPrevious: No previous item, seeking to start")
                seekTo(0)
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            // Mirror seekToPrevious(): commit the incoming track (A+1) as current first.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPreviousMediaItem: committing incoming (A+1) first")
                commitIncomingAsCurrentInternal()
            }

            // Always advance to the previous track regardless of `cachedPosition` —
            // skips the 3-second "seek to start" rule used by seekToPrevious().
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
            // Cancel ongoing operations
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

            // Adjust current index if needed
            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }
            pointAtFirstItemIfUnset()

            // Update shuffle order if enabled
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
            // Bounds checked INSIDE the launch, on the same queue as removeAt: a removal queued
            // earlier (a radio trimming its history) can shrink the playlist between an outside check
            // and this body — issue #2156's crash shape, fixed the same way in MpvPlayerAdapter.
            if (index !in playlist.indices) return@launch
            // Where the track sat in the shuffled order, read before the order loses it.
            val shuffledPos = if (internalShuffleModeEnabled) shuffleIndices.getOrNull(index) ?: -1 else -1
            val track = playlist.removeAt(index)
            // Only this track leaves the shuffled order, before anything below reads the order.
            // Rebuilding it here (createShuffleOrder) reshuffled the whole queue every time one track
            // was removed with shuffle on.
            if (internalShuffleModeEnabled) removeFromShuffleOrder(index)
            pinnedNextItems.remove(track)

            // Remove from precache
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
     * timeline notification, instead of repeating both per removed track (the cost shape of #2504).
     *
     * Indices count the UNSHUFFLED playlist, like [removeMediaItem] and [currentMediaItemIndex].
     * Only a range strictly BELOW the current track is accepted; anything else is refused rather
     * than guessed at, since getting it wrong stops playback. [onRemoved] hears back exactly once
     * either way, from inside this block — see [MediaPlayerInterface.removeMediaItems] for why.
     */
    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
        onRemoved: (removedIds: List<String>) -> Unit,
    ) {
        coroutineScope.launch {
            // Everything is re-checked INSIDE the launch, on the same queue as the mutation: another
            // queued op (clearMediaItems / setMediaItem) can change the playlist between a
            // caller-thread check and this body, which is issue #2156's crash shape.
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
            // Everything removed sits behind the current track and precache is keyed by mediaId,
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
            // Same reason as removeMediaItem (issue #2156).
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return@launch
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            // Update current index
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
            // Same reason as removeMediaItem (issue #2156).
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
        // During a crossfade the timeline is the incoming track's, as currentPosition already is: the
        // seek bar used to show the new track's position against the old track's length.
        get() = (if (isCrossfading) secondaryPlayer ?: currentPlayer else currentPlayer)?.duration ?: cachedDuration

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
        get() {
            // Media3 Player.STATE_* values match PlayerConstants 1:1.
            return when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }
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
            listeners.forEach { it.onShuffleModeEnabledChanged(value, mediaItemList) }
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            Logger.d(TAG, "Shuffle mode ${if (value) "enabled" else "disabled"}")
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            listeners.forEach { it.onRepeatModeChanged(value) }
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
            val params = PlaybackParameters(value.speed, value.pitch)
            currentPlayer?.playbackParameters = params
            // Also apply to secondary player during crossfade
            secondaryPlayer?.playbackParameters = params
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = currentPlayer?.audioSessionId ?: 0

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.d(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            currentPlayer?.volume = internalVolume
            listeners.forEach { it.onVolumeChanged(internalVolume) }
        }

    override var albumTrackIds: Set<String>
        get() = internalAlbumTrackIds
        set(value) {
            internalAlbumTrackIds = value
        }

    override var sleepFadeFactor: Float
        get() = internalSleepFadeFactor
        set(value) {
            // Nothing to push: each player's SleepFadeAudioProcessor samples this on every buffer.
            // That is the whole point of keeping it off the `volume` line, which the crossfade ramp
            // owns and rewrites fifty times per transition.
            internalSleepFadeFactor = value.coerceIn(0f, 1f)
        }

    /**
     * Hand the equalizer a new curve.
     *
     * Nothing is pushed anywhere, for the same reason the sleep fade pushes nothing: the value is
     * sampled per buffer. A fresh [EqualizerCurve] is allocated on every call precisely so each
     * processor can decide whether to rebuild its coefficients with one reference comparison,
     * rather than diffing eleven floats on every buffer for the whole of playback.
     */
    override fun setEqualizer(
        bandsDb: List<Float>,
        preampDb: Float,
    ) {
        internalEqualizerCurve = EqualizerCurve(bandsDb, preampDb)
    }

    /**
     * Hand the delay and the reverb a new setting.
     *
     * Nothing is pushed anywhere, for the reason [setEqualizer] pushes nothing: the value is
     * sampled per buffer. A fresh [AudioEffects] is allocated on every call precisely so each
     * processor can decide whether to re-derive its taps — or rebuild its partitioned impulse
     * response — with one reference comparison, rather than diffing the whole setting on every
     * buffer for the whole of playback.
     */
    override fun setAudioEffects(effects: AudioEffects) {
        internalAudioEffects = effects
    }

    override fun setEightD(effect: EightDEffect?) {
        eightDOrbit.setEffect(effect)
    }

    override var skipSilenceEnabled: Boolean
        get() = internalSkipSilence
        set(value) {
            internalSkipSilence = value
            currentPlayer?.skipSilenceEnabled = value
            // Also apply to secondary player during crossfade
            secondaryPlayer?.skipSilenceEnabled = value
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
        eightDOrbit.setPlaying(false)
        // Cancel all ongoing jobs
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()

        // Cancel crossfade
        crossfadeJob?.cancel()
        secondaryPlayer?.release()
        secondaryPlayer = null
        isCrossfading = false

        abandonAudioFocusInternal()
        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        outputRouter.release()
        listeners.clear()
    }

    // ========== Internal: State Transition ==========

    /**
     * State transition helper - mirrors GstreamerPlayerAdapter.transitionToState()
     */
    private fun propagatePlayerError(error: PlaybackException) {
        val genericError =
            PlayerError(
                errorCode =
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_TIMEOUT -> PlayerConstants.ERROR_CODE_TIMEOUT
                        else -> error.errorCode
                    },
                errorCodeName = error.errorCodeName,
                message = error.message,
            )
        Logger.e(TAG, "Playback error: ${error.message}")
        transitionToState(InternalState.ERROR, genericError)
    }

    private fun transitionToState(
        newState: InternalState,
        error: PlayerError? = null,
    ) {
        if (internalState == newState) {
            Logger.d(TAG, "State transition ignored: already in $newState")
            return
        }

        val oldState = internalState
        internalState = newState
        eightDOrbit.setPlaying(newState == InternalState.PLAYING)

        Logger.d(TAG, "State: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Update cached duration from player
        currentPlayer?.let {
            val dur = it.duration
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners
        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.PREPARING -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
            }

            InternalState.READY -> {
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
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
     * A track read from disk that its container parser cannot read is a broken file there, not a
     * network hiccup, and reloading it reads the same bytes again: a download spliced from two
     * stream variants failed this way on every Play. So the copy is dropped (a download is fetched
     * again, see DownloadHandler.redownloadCorrupt) and the track reloads where it failed, now
     * from the network. Once per track per session, so a file that breaks again, or a parser
     * error that has nothing to do with the file, ends in the usual retries and the skip.
     *
     * Handled here, before onPlayerError reaches the handler: the reload is not a failure for
     * UnplayableTrackSkipper to count, and it must not move on to the next track first.
     */
    private fun healCorruptCopy(
        videoId: String,
        error: PlaybackException,
    ): Boolean {
        if (!error.isCorruptMedia() || videoId in healedVideoIds) return false
        val keys = listOf(videoId, "${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$videoId")
        val downloaded = keys.filter { downloadCache.isFullyCached(it, 0L) }
        val cached = keys.filter { playerCache.isFullyCached(it, 0L) }
        if (downloaded.isEmpty() && cached.isEmpty()) return false
        healedVideoIds += videoId
        Logger.i("Offline", "heal $videoId download=$downloaded cache=$cached (${error.errorCodeName})")
        val index = localCurrentMediaItemIndex
        val resumePositionMs = cachedPosition.coerceAtLeast(0L)
        coroutineScope.launch {
            try {
                precachedPlayers.remove(videoId)?.player?.release()
                withContext(Dispatchers.IO) {
                    cached.forEach { playerCache.dropStale(it) }
                    downloaded.forEach { key ->
                        if (!downloadHandler.redownloadCorrupt(key)) downloadCache.dropStale(key)
                    }
                }
                loadAndPlayTrackInternal(index, resumePositionMs, shouldPlay = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Heal failed: ${e.message}", e)
                propagatePlayerError(error)
            }
        }
        return true
    }

    // ========== Internal: Load and Play Track ==========

    /**
     * Load and play track - mirrors GstreamerPlayerAdapter.loadAndPlayTrackInternal()
     *
     * Key difference: instead of extracting URL + creating PlayBin,
     * we create an ExoPlayer, set a MediaItem, and let MediaSourceFactory resolve the URL.
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


        // Cancel previous load
        currentLoadJob?.cancel()

        currentLoadJob =
            coroutineScope.launch {
                try {
                    transitionToState(InternalState.PREPARING)

                    // Notify media item transition
                    if (announceTransition) {
                        listeners.forEach {
                            it.onMediaItemTransition(
                                mediaItem,
                                PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }
                    refreshDownloadedClip()

                    // Use precached player if available and healthy. One that already failed is
                    // dropped and the track is built afresh: this also covers a live broadcast, whose
                    // progressive source threw LiveStreamDetectedException while nobody was listening,
                    // so the fresh player now gets an HLS source.
                    val cachedPlayerEntry = takeHealthyPrecachedPlayer(videoId)
                    val player: ExoPlayer
                    if (cachedPlayerEntry?.player != null) {
                        Logger.d(TAG, "Using precached player for $videoId")
                        player = cachedPlayerEntry.player
                    } else {
                        Logger.d(TAG, "Creating new player for $videoId")
                        player = createExoPlayerInstance()
                        player.setMediaItem(mediaItem.toMedia3MediaItem())
                        player.prepare()
                    }

                    // === CAREFUL ORDER for ForwardingPlayer integration ===

                    // 1. Remove our active listener from old player
                    cleanupPlayerListenerInternal()
                    stopPositionUpdates()
                    crossfadeJob?.cancel()
                    crossfadeJob = null
                    setCrossfading(false)

                    // 2. Save old player reference
                    val oldPlayer = currentPlayer

                    // 3. Set new player as current
                    currentPlayer = player

                    // 4. Setup our listener on new player
                    setupPlayerListenerInternal(player)

                    // 4b. Apply settings and set the play state BEFORE swapping the
                    // MediaSession delegate. The session must never observe this player
                    // with playWhenReady=false while a track should be playing: media3
                    // treats that as the user disengaging, arms its user-engaged
                    // foreground-service timeout and demotes the service 10 minutes
                    // later in the middle of playback (#2233).
                    player.volume = internalVolume
                    player.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                    player.skipSilenceEnabled = internalSkipSilence

                    // Seek if needed
                    if (startPositionMs > 0) {
                        player.seekTo(startPositionMs)
                        cachedPosition = startPositionMs
                    }

                    // Auto-play if requested
                    if (shouldPlay) {
                        requestAudioFocusInternal()
                        player.play()
                        transitionToState(InternalState.PLAYING)
                    } else {
                        player.pause()
                        transitionToState(InternalState.READY)
                    }

                    // 5. Swap ForwardingPlayer delegate (moves MediaSession's listeners from old to new)
                    forwardingPlayer.swapDelegate(player)

                    // 5b. Notify MediaSession about the new media item and play state.
                    // The MediaItem was set and play() was called before the swap, so
                    // MediaSession's listener missed those events; this explicit notify
                    // makes it re-read the player (metadata and playWhenReady=true).
                    forwardingPlayer.notifyMediaItemChanged()

                    // 6. NOW release old player (it has no listeners anymore)
                    if (oldPlayer != null && oldPlayer !== player) {
                        try {
                            oldPlayer.stop()
                            oldPlayer.release()
                        } catch (e: Exception) {
                            Logger.w(TAG, "Error releasing old player: ${e.message}")
                        }
                    }

                    // Audio focus is held at the adapter level (see Audio Focus section),
                    // not per-player, so it survives this swap (#2155).

                    forwardingPlayer.suppressPlaybackEnded = false

                    // Start position updates
                    startPositionUpdates()

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Load track error: ${e.message}", e)
                    forwardingPlayer.suppressPlaybackEnded = false
                    transitionToState(InternalState.ERROR)
                }
            }
    }

    // ========== Internal: Player Listener Management ==========
    // Equivalent to setupPlayerListenersInternal / cleanupBusListenersInternal

    /**
     * Setup Player.Listener on the given player.
     * First removes any existing listener from the old player (like cleanupBusListenersInternal),
     * then creates and attaches a new listener to the given player.
     *
     * This is the KEY crossfade mechanism:
     * When crossfade starts, the listener is moved from old player to new player.
     * The old player's STATE_ENDED event is then ignored (no listener to handle it).
     */
    private fun setupPlayerListenerInternal(player: ExoPlayer) {
        // Clean up old listener first
        cleanupPlayerListenerInternal()

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Logger.d(TAG, "End of stream reached")
                            // Repeat One replays this track, so its end is not the end of playback.
                            if (hasNextMediaItem() || internalRepeatMode == PlayerConstants.REPEAT_MODE_ONE) {
                                forwardingPlayer.suppressPlaybackEnded = true
                                transitionToState(InternalState.PREPARING)
                            } else {
                                transitionToState(InternalState.ENDED)
                            }
                            handleTrackEndInternal()
                        }

                        Player.STATE_READY -> {
                            // Always clear loading state when ExoPlayer is ready
                            // (handles both initial load AND mid-playback rebuffer)
                            if (cachedIsLoading && player == currentPlayer) {
                                cachedIsLoading = false
                                listeners.forEach { it.onIsLoadingChanged(false) }
                            }
                            // Duration should be available now
                            val dur = player.duration
                            if (dur > 0) cachedDuration = dur
                            // Reset retry counter on successful playback
                            retryCount = 0
                            retryVideoId = null
                        }

                        Player.STATE_BUFFERING -> {
                            // Playback is stalled waiting for data — report buffering
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    if (isPlaying) {
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            notifyEqualizerIntent(true)
                        }
                    } else {
                        if (internalState == InternalState.PLAYING) {
                            if (!player.playWhenReady) {
                                transitionToState(InternalState.PAUSED)
                                notifyEqualizerIntent(false)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlayerError from non-current player")
                        return
                    }

                    // Retry for source errors (expired/invalid stream URL)
                    // ERROR_CODE_PARSING_CONTAINER_MALFORMED (3001) = server returned non-media response (e.g. HTML error page)
                    // ERROR_CODE_IO_BAD_HTTP_STATUS (2004) = HTTP 403/410 from expired URL
                    // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001) = connection refused
                    // ERROR_CODE_IO_FILE_NOT_FOUND (2005) = the resolver served a cache hit as a bare
                    //   media id and the cached spans were evicted (or cleared) mid-read, so
                    //   DefaultDataSource fell through to FileDataSource on a scheme-less URI. Media3
                    //   lists FileNotFoundException as non-retriable, so this layer is the only chance
                    //   to recover; reloading re-runs the resolver, which now sees an incomplete cache
                    //   and resolves a real URL.
                    val isRetryableSourceError = error.isRetryableSourceError()

                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                    // A downloaded clip watched from the network: back to the file at the same place.
                    // Never the heal below, the downloaded audio is not what failed.
                    if (currentVideoId != null && currentVideoId == mediaSourceFactory.streamedClipId) {
                        Logger.w(TAG, "Video of the downloaded clip $currentVideoId failed (${error.errorCodeName}), back to the file")
                        _downloadedClipFailures.tryEmit(Unit)
                        coroutineScope.launch { switchDownloadedClip(currentVideoId, show = false) }
                        return
                    }
                    // The progressive resolver found this track to be a live broadcast. Loading it
                    // again builds an HLS source instead — see LiveStreamDetectedException. Not a
                    // retry, and not counted as one: it is a different source, not the same one again.
                    if (error.isLiveStreamDetected() && currentVideoId != null) {
                        Logger.w(TAG, "$currentVideoId is a live stream; loading it again as HLS")
                        coroutineScope.launch {
                            precachedPlayers.remove(currentVideoId)?.player?.release()
                            loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0L, shouldPlay = true)
                        }
                        return
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                        // Without this the crash report is a bare FileDataSourceException with
                        // nothing tying it back to a cache decision made three layers up.
                        Logger.w(
                            TAG,
                            "Cache disappeared mid-read for $currentVideoId — the resolver had served it " +
                                "as a fully cached bare media id. Retrying to resolve a real URL.",
                        )
                    }
                    if (currentVideoId != null && healCorruptCopy(currentVideoId, error)) return
                    if (isRetryableSourceError && currentVideoId != null) {
                        // Reset retry count if this is a different track
                        if (retryVideoId != currentVideoId) {
                            retryVideoId = currentVideoId
                            retryCount = 0
                        }
                        if (retryCount < maxRetryCount) {
                            retryCount++
                            Logger.w(TAG, "Retryable source error (attempt $retryCount/$maxRetryCount) for $currentVideoId: ${error.errorCodeName}")
                            // Snapshot the current position so the retry resumes where playback
                            // failed instead of restarting the track from the beginning.
                            val resumePositionMs = cachedPosition.coerceAtLeast(0L)
                            coroutineScope.launch {
                                try {
                                    // Invalidate cached format so ResolvingDataSource fetches a fresh URL
                                    streamRepository.invalidateFormat(currentVideoId)
                                    streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                    // Evict from precache (it may hold a stale player)
                                    precachedPlayers.remove(currentVideoId)?.player?.release()
                                    // Reload the track at the saved position
                                    loadAndPlayTrackInternal(localCurrentMediaItemIndex, resumePositionMs, shouldPlay = true)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                                    propagatePlayerError(error)
                                }
                            }
                            return
                        }
                        Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                    }

                    propagatePlayerError(error)
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    // ExoPlayer reports isLoading=true during normal background buffer refill,
                    // not just when playback is stalled. Only propagate loading=true when
                    // playback is actually stalled (STATE_BUFFERING) AND the player intends
                    // to play. Ignore buffering events while paused to avoid showing
                    // a loading spinner when the user has explicitly paused.
                    val isPlaybackStalled = isLoading && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
                    val isCurrentPlayer = player == currentPlayer
                    Logger.d(TAG, "onIsLoadingChanged: isLoading=$isLoading, isPlaybackStalled=$isPlaybackStalled, isCurrentPlayer=$isCurrentPlayer")
                    if (cachedIsLoading != isPlaybackStalled && isCurrentPlayer) {
                        cachedIsLoading = isPlaybackStalled
                        // A stall stops the audio but not the PLAYING state; the orbit must stop
                        // with the audio, or the next player joins ahead of what was heard.
                        eightDOrbit.setPlaying(internalState == InternalState.PLAYING && !isPlaybackStalled)
                        listeners.forEach { it.onIsLoadingChanged(isPlaybackStalled) }
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val genericTracks = tracks.toGenericTracks()
                    listeners.forEach { it.onTracksChanged(genericTracks) }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (player != currentPlayer) return
                    // Fires for both in-app seeks and MediaSession/notification seeks — both land on the
                    // raw ExoPlayer. seekTo() does no manual notification, so this is the single source.
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        listeners.forEach { it.onSeeked(newPosition.positionMs) }
                    }
                }

                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val shouldBePlaying =
                        !(player.playbackState == Player.STATE_ENDED || !player.playWhenReady)
                    if (events.containsAny(
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                        )
                    ) {
                        if (shouldBePlaying) {
                            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(true) }
                        } else {
                            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(false) }
                        }
                    }
                }
            }

        player.addListener(listener)
        activePlayerListener = listener
    }

    /**
     * Clean up active player listener from whichever player has it.
     * During crossfade the listener is on secondaryPlayer, not currentPlayer.
     */
    private fun cleanupPlayerListenerInternal() {
        activePlayerListener?.let { listener ->
            currentPlayer?.removeListener(listener)
            secondaryPlayer?.removeListener(listener)
        }
        activePlayerListener = null
    }

    // ========== Internal: Player Cleanup ==========

    private fun cleanupPlayerInternal(player: ExoPlayer) {
        try {
            player.stop()
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupPlayerListenerInternal()

        // Cancel any ongoing crossfade
        crossfadeJob?.cancel()
        crossfadeJob = null
        setCrossfading(false)

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
    }

    /**
     * Abort an in-progress crossfade by committing the INCOMING track (A+1) as the new
     * current player — the mid-fade counterpart of [finalizeCrossfade]. Invoked when the
     * user interacts during a crossfade (next/prev/pause). Direction: "crossfade means we
     * have moved to A+1", so we keep A+1 and drop A.
     *
     * Mirrors [finalizeCrossfade]'s player swap: release the outgoing player (A), promote
     * the secondary player (A+1) to current. The active listener and the ForwardingPlayer
     * delegate are intentionally NOT touched — both already point at A+1 (set in
     * [triggerCrossfadeTransition]); touching them would lose the listener / detach
     * MediaSession. [localCurrentMediaItemIndex] already equals A+1, so it is kept.
     *
     * Does NOT change [internalState], restart position updates, or seek — the caller
     * decides what to do next (pause in place, advance to A+2, go to previous, ...).
     */
    private fun commitIncomingAsCurrentInternal() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        stopPositionUpdates()

        // Release the outgoing player (A). Do NOT remove listeners — the active listener
        // is on the incoming player (A+1), which we are keeping (same as finalizeCrossfade).
        currentPlayer?.let { cleanupPlayerInternal(it) }

        // Promote incoming (A+1) to current.
        currentPlayer = secondaryPlayer
        secondaryPlayer = null

        // The incoming player was fading in: reduced volume (and the outgoing one carried
        // any tempo/pitch match). Restore normal playback on A+1.
        currentPlayer?.volume = internalVolume
        currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        currentPlayer?.skipSilenceEnabled = internalSkipSilence

        setCrossfading(false)
        crossfadeFromIndex = -1

        // The position loop was stopped above and only finalizeCrossfade or a track load starts it
        // again; play() does not. Left stopped, a seek or a pause in the middle of a fade froze the
        // position (and the music-end check and the next crossfade with it) for the rest of the
        // track while A+1 played on. It reads currentPlayer, which is A+1 now.
        startPositionUpdates()
        triggerPrecachingInternal()
        Logger.i(TAG, "Crossfade interrupted: track $localCurrentMediaItemIndex kept, position updates resumed")
    }

    // ========== Internal: Track End ==========

    /**
     * Crossfade is skipped when the NEXT track will play as a video (video content with
     * the watch-video setting on — the same condition MergingMediaSourceFactory uses to
     * build a merged audio+video source). The merged source resolves two stream URLs and
     * is error-prone to prepare mid-fade, and a video should start from its first frame
     * instead of fading in under the outgoing song — so the transition takes the normal
     * (non-crossfade) path.
     */
    private fun isNextTrackVideo(): Boolean = watchVideoEnabled && playlist.getOrNull(getNextMediaItemIndex())?.isVideo() == true

    /** Same skip rule for the CURRENT track: a video should play out to its last frame instead of fading out under the incoming song. */
    private fun isCurrentTrackVideo(): Boolean = watchVideoEnabled && currentMediaItem?.isVideo() == true

    /** A live broadcast never reaches an end to fade out of, and its "remaining time" is only how far behind the live edge it is. */
    private fun isCurrentTrackLive(): Boolean = currentMediaItem?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /** Nor can a live broadcast fade in: its HLS source is not one a crossfade can prepare ahead of time. */
    private fun isNextTrackLive(): Boolean = playlist.getOrNull(getNextMediaItemIndex())?.mediaId?.let(LiveStreamRegistry::isLive) == true

    /**
     * Crossfade needs a track long enough that both sides of the blend are still worth hearing. At
     * the default 5s fade a 20s track would spend half its length fading in or out, and a longer
     * fade setting swallows it whole — so the bar scales with the fade rather than being fixed.
     *
     * Only the current track is measured: the next one has not been prepared yet, so its duration
     * is unknown until it becomes current.
     */
    private fun isCurrentTrackTooShortForCrossfade(): Boolean {
        val duration = currentPlayer?.duration ?: return false
        if (duration <= 0L) return false
        return duration < maxOf(MIN_CROSSFADE_TRACK_MS, crossfadeDurationMs * 3L)
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
     * True when the user marked this track's end, or the next track's start, to play without a
     * crossfade. The junction then goes as with crossfade off, like [isWithinAlbum].
     */
    private fun isCrossfadeExcludedByUser(): Boolean {
        val ends = crossfadeExcludedEnds
        val starts = crossfadeExcludedStarts
        if (ends.isEmpty() && starts.isEmpty()) return false
        val current = currentMediaItem?.mediaId
        val next = playlist.getOrNull(getNextMediaItemIndex())?.mediaId
        val excluded = CrossfadeExclusions.blocksJunction(current, next, ends, starts)
        val junction = "$current -> $next"
        if (!excluded) {
            if (loggedExcludedJunction == junction) loggedExcludedJunction = null
        } else if (loggedExcludedJunction != junction) {
            loggedExcludedJunction = junction
            val why = if (current != null && current in ends) "end of $current" else "start of $next"
            Logger.i(TAG, "No crossfade $junction: the $why is marked to play without one")
        }
        return excluded
    }

    /**
     * Handle track end - mirrors GstreamerPlayerAdapter.handleTrackEndInternal()
     */
    private fun handleTrackEndInternal() {
        // Check if crossfade should be used
        val shouldCrossfade =
            crossfadeEnabled &&
                // The track has already ended: with skip silence on, its closing silence was cut, so
                // the music finished before the position the fade is timed from. A crossfade now
                // would only fade the next track in from nothing for seconds after the old one
                // stopped; the next track starts at once instead. The fade that runs ahead of the
                // end, off the position loop, is untouched.
                !internalSkipSilence &&
                !crossfadeSuppressed &&
                // Repeat One loops this track through the branch below instead of fading into the next.
                internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                hasNextMediaItem() &&
                !isCrossfading &&
                !isCurrentTrackVideo() &&
                !isNextTrackVideo() &&
                !isCurrentTrackLive() &&
                !isNextTrackLive() &&
                !isCurrentTrackTooShortForCrossfade() &&
                !isWithinAlbum() &&
                !isCrossfadeExcludedByUser()

        if (shouldCrossfade) {
            val nextIndex = getNextMediaItemIndex()
            triggerCrossfadeTransition(nextIndex)
        } else {
            // Original behavior
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
                    } else {
                        notifyEqualizerIntent(false)
                    }
                }
            }
        }
    }

    // ========== Internal: Crossfade ==========

    /**
     * Trigger crossfade to next track.
     * Mirrors GstreamerPlayerAdapter.triggerCrossfadeTransition()
     *
     * Key mechanism: [setupPlayerListenerInternal] moves the active listener
     * from the current player to the next player. The old player's STATE_ENDED
     * event is then ignored (no listener to fire).
     */
    private fun triggerCrossfadeTransition(nextIndex: Int) {
        if (nextIndex !in playlist.indices || isCrossfading) return

        // Pinned now, not inside the launch: the target is already chosen, and an append that runs
        // before the fade makes it current must not take its slot.
        if (internalShuffleModeEnabled) pinnedNextItems.add(playlist[nextIndex])

        coroutineScope.launch {
            try {
                setCrossfading(true)
                val nextMediaItem = playlist[nextIndex]
                val nextVideoId = nextMediaItem.mediaId

                Logger.d(TAG, "Starting crossfade to track $nextIndex")

                // Get or create secondary player
                val cachedPlayerEntry = takeHealthyPrecachedPlayer(nextVideoId)
                val nextPlayer: ExoPlayer
                if (cachedPlayerEntry?.player != null) {
                    nextPlayer = cachedPlayerEntry.player
                } else {
                    nextPlayer = createExoPlayerInstance()
                    nextPlayer.setMediaItem(nextMediaItem.toMedia3MediaItem(), 0L)
                    nextPlayer.prepare()
                }

                // Setup secondary player
                secondaryPlayer = nextPlayer
                // *** KEY: Move our custom listener from current to next player ***
                setupPlayerListenerInternal(nextPlayer)
                // Playback parameters applied below
                nextPlayer.skipSilenceEnabled = internalSkipSilence
                nextPlayer.volume = 0f

                // === CRITICAL ORDER for MediaSession notification ===
                // 1. Swap ForwardingPlayer BEFORE play()
                //    This moves MediaSession's Player.Listener to nextPlayer.
                //    If we play() first, MediaSession misses onIsPlayingChanged and
                //    onMediaItemTransition events (they fire before its listener is attached).
                forwardingPlayer.swapDelegate(nextPlayer)

                // 2. Now play - MediaSession's listener is attached and receives state change events
                requestAudioFocusInternal()
                nextPlayer.play()

                forwardingPlayer.suppressPlaybackEnded = false

                // 3. Force MediaSession to update notification metadata
                //    Even though play() triggers onIsPlayingChanged (which causes MediaSession
                //    to re-query player state), the onMediaItemTransition event was missed
                //    (MediaItem was set during precache, before the swap).
                //    This explicitly notifies MediaSession about the new track metadata.
                forwardingPlayer.notifyMediaItemChanged()

                val resolvedConfigDurationMs = crossfadeDurationMs

                nextPlayer.playbackParameters =
                    PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)

                // Update now playing IMMEDIATELY (store from-index for cancel scenarios)
                crossfadeFromIndex = localCurrentMediaItemIndex
                localCurrentMediaItemIndex = nextIndex
                unpinCurrent()
                refreshDownloadedClip()

                // Notify our custom listeners IMMEDIATELY (UI updates to new track)
                listeners.forEach {
                    it.onMediaItemTransition(
                        nextMediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    )
                }

                Logger.d(TAG, "Now playing updated to track $nextIndex during crossfade")

                // Calculate effective crossfade duration based on ACTUAL remaining time.
                // If the secondary player wasn't precached, URL resolution + buffering may
                // have consumed part of the crossfade window. Use the lesser of configured
                // duration and actual remaining time so the animation ends when the old track does.
                // Remaining up to where the outgoing music ends: the music end with skip silence on.
                var fadeEndLog = ""
                val actualTimeRemaining =
                    currentPlayer?.let { player ->
                        val dur = player.duration
                        val pos = player.currentPosition
                        if (dur > 0 && pos >= 0) {
                            val fadeEnd = fadeEndMs(player, dur)
                            if (fadeEnd < dur) fadeEndLog = ", timed to the music end at ${fadeEnd}ms of ${dur}ms (position ${pos}ms)"
                            // Divide by playback speed: at 2x speed, remaining wall-clock time is halved
                            MusicEnd.remainingMs(pos, fadeEnd, internalPlaybackSpeed)
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

                // Perform crossfade animation with effective duration
                performCrossfade(nextIndex, nextPlayer, effectiveCrossfadeDurationMs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Crossfade error: ${e.message}", e)
                setCrossfading(false)
                // Fallback to normal transition
                seekTo(nextIndex, 0)
            }
        }
    }

    /**
     * Perform the actual crossfade animation.
     * Mirrors GstreamerPlayerAdapter.performCrossfade()
     *
     * @param effectiveDurationMs The actual crossfade duration to use. May be shorter than
     *   the configured [crossfadeDurationMs] if URL resolution / buffering consumed
     *   part of the crossfade window.
     */
    private suspend fun performCrossfade(
        nextIndex: Int,
        nextPlayer: ExoPlayer,
        effectiveDurationMs: Int,
    ) {
        val steps = 50 // 50 steps for smooth transition
        val delayPerStep = (effectiveDurationMs / steps).coerceAtLeast(20) // min 20ms per step
        val targetVolume = internalVolume
        Logger.d(
            TAG,
            "Crossfade animation: ${effectiveDurationMs}ms, $steps steps, ${delayPerStep}ms/step",
        )

        crossfadeJob?.cancel()
        crossfadeJob =
            coroutineScope.launch {
                try {
                    for (step in 0..steps) {
                        if (!isActive) break

                        val progress = step.toFloat() / steps

                        // Equal-power crossfade (cos/sin curves) instead of linear.
                        // Human loudness perception is logarithmic — linear volume fade makes
                        // the outgoing track "die" perceptually around the midpoint while
                        // incoming hasn't filled in yet, leaving an audible gap.
                        // cos²(θ) + sin²(θ) = 1 → total acoustic power stays constant across
                        // the blend, so the listener perceives a smooth handoff.
                        val fadeAngle = (progress * PI / 2).toFloat()

                        // Fade out current player (old track): cos curve, slow at start, fast at end
                        val fadeOutVolume = targetVolume * cos(fadeAngle)
                        currentPlayer?.volume = fadeOutVolume

                        // Fade in next player (new track): sin curve, fast at start, slow at end
                        val fadeInVolume = targetVolume * sin(fadeAngle)
                        nextPlayer.volume = fadeInVolume

                        delay(delayPerStep.toLong())
                    }

                    // Transition complete
                    finalizeCrossfade(nextIndex, nextPlayer)
                } catch (e: CancellationException) {
                    Logger.d(TAG, "Crossfade cancelled")
                    // The cancel can come from commitIncomingAsCurrentInternal (a seek or pause in the
                    // middle of the fade), which has already made the incoming player the current one.
                    // This handler runs after it, so releasing nextPlayer here would release the player
                    // that is now playing and leave the adapter holding a dead ExoPlayer: Play does
                    // nothing from then on.
                    if (nextPlayer !== currentPlayer) {
                        // Restore outgoing player's speed/pitch to natural
                        currentPlayer?.playbackParameters =
                            PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                        // Cleanup player
                        nextPlayer.release()
                    }
                    // A newer crossfade may already own the secondary slot and the flag.
                    if (secondaryPlayer === nextPlayer || secondaryPlayer == null) {
                        secondaryPlayer = null
                        setCrossfading(false)
                    }
                }
            }
    }

    companion object {
        /** Floor for the shortest track worth crossfading, in ms. */
        private const val MIN_CROSSFADE_TRACK_MS = 20_000L

        /** How long "show clip" waits for the video's URL before it stays on the file. */
        private const val DOWNLOADED_CLIP_RESOLVE_TIMEOUT_MS = 15_000L
    }


    /**
     * Finalize crossfade: swap players and cleanup.
     * Mirrors GstreamerPlayerAdapter.finalizeCrossfade()
     */
    private fun finalizeCrossfade(
        nextIndex: Int,
        nextPlayer: ExoPlayer,
    ) {
        Logger.d(TAG, "Crossfade complete, swapping players")

        // Cleanup old current player WITHOUT touching listeners
        // (listeners are already setup for nextPlayer via setupPlayerListenerInternal)
        stopPositionUpdates()

        // Cleanup the old current player manually
        currentPlayer?.let { oldPlayer ->
            try {
                oldPlayer.stop()
                oldPlayer.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error cleaning up old player: ${e.message}")
            }
        }

        // Promote secondary to current
        currentPlayer = nextPlayer
        secondaryPlayer = null
        // localCurrentMediaItemIndex already updated in triggerCrossfadeTransition()

        // Audio focus is held at the adapter level (see Audio Focus section),
        // not per-player, so it survives this crossfade swap (#2155).

        // Ensure correct volume and playback parameters
        currentPlayer?.volume = internalVolume
        currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        currentPlayer?.skipSilenceEnabled = internalSkipSilence

        // Reset state
        setCrossfading(false)
        crossfadeFromIndex = -1
        transitionToState(InternalState.PLAYING)

        // Ensure MediaSession notification has correct metadata after crossfade completes.
        // setAudioAttributes() above may cause a brief audio session reset, so re-notify
        // MediaSession to guarantee the notification shows the correct track info.
        forwardingPlayer.notifyMediaItemChanged()

        // Start position tracking
        startPositionUpdates()

        // Trigger next precache
        triggerPrecachingInternal()
    }

    // ========== Internal: Position Updates ==========

    /**
     * Start position updates (periodic background task).
     * Also handles crossfade detection when position approaches end.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != null) {
                    try {
                        currentPlayer?.let { player ->
                            if (internalState == InternalState.PLAYING ||
                                internalState == InternalState.READY ||
                                internalState == InternalState.PAUSED
                            ) {
                                // During crossfade, show the incoming track's timeline
                                val timelinePlayer = if (isCrossfading) secondaryPlayer ?: player else player
                                val pos = timelinePlayer.currentPosition
                                val dur = timelinePlayer.duration
                                val buf = timelinePlayer.bufferedPosition

                                if (pos >= 0) cachedPosition = pos
                                if (dur > 0) cachedDuration = dur
                                if (buf >= 0) cachedBufferedPosition = buf

                                if (!isCrossfading) measureMusicEndIfDue(player, pos, dur)

                                // Check if should trigger crossfade.
                                // Add a preparation buffer (3s) so that if the next track
                                // is NOT precached, URL resolution + buffering time doesn't
                                // eat into the audible crossfade window.
                                if (crossfadeEnabled &&
                                    !crossfadeSuppressed &&
                                    internalRepeatMode != PlayerConstants.REPEAT_MODE_ONE &&
                                    !isCrossfading &&
                                    player.isPlaying &&
                                    dur > 0 &&
                                    pos > 0 &&
                                    !isCurrentTrackVideo() &&
                                    !isNextTrackVideo() &&
                                    !isCurrentTrackLive() &&
                                    !isNextTrackLive() &&
                                    !isCurrentTrackTooShortForCrossfade() &&
                                    !isWithinAlbum() &&
                                    !isCrossfadeExcludedByUser()
                                ) {
                                    // Up to the music end when skip silence is on and it is known, so
                                    // the old track fades out over its last music, not its silence.
                                    // Account for playback speed: at higher speed, media time
                                    // is consumed faster, so wall-clock remaining is shorter
                                    val timeRemaining = MusicEnd.remainingMs(pos, fadeEndMs(player, dur), internalPlaybackSpeed)
                                    val nextVideoId = playlist.getOrNull(getNextMediaItemIndex())?.mediaId
                                    val isPrecached = nextVideoId != null && precachedPlayers.containsKey(nextVideoId)
                                    // If next track is precached, trigger at exactly crossfadeDurationMs.
                                    // If NOT precached, trigger 3s earlier to allow preparation time.
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
                    } catch (e: Exception) {
                        // Ignore query errors - don't log to avoid spam
                    }

                    delay(50) // Update every 50ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // ========== Internal: Music end (skip silence + crossfade) ==========

    /** The measured music end of [mediaId], or null when it is not known. */
    private fun musicEndOf(mediaId: String): Long? = musicEnds[mediaId]?.takeIf { it > 0L }

    /**
     * Where a crossfade out of [player] should finish: its music end while skip silence is on and that
     * is known, the file's end otherwise (see [MusicEnd.fadeEndMs]). With crossfade off nothing reads
     * it: the trailing-silence processor ends the track where its music ends by itself.
     */
    private fun fadeEndMs(
        player: ExoPlayer,
        durationMs: Long,
    ): Long {
        if (!internalSkipSilence) return durationMs
        val mediaId = player.currentMediaItem?.mediaId ?: return durationMs
        return MusicEnd.fadeEndMs(durationMs, musicEndOf(mediaId))
    }

    /**
     * Measures where the current track's music ends once it nears its end ([MusicEnd.shouldMeasure]),
     * when a crossfade with skip silence on is going to need it. The processor drops the closing
     * silence only as it is reached, too late to time a fade from, so it is found ahead of time.
     */
    private fun measureMusicEndIfDue(
        player: ExoPlayer,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!crossfadeEnabled || !internalSkipSilence || isCurrentTrackLive() || isCurrentTrackVideo()) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId in musicEndRequested || !MusicEnd.shouldMeasure(durationMs, positionMs)) return
        musicEndRequested += mediaId
        coroutineScope.launch {
            val end =
                try {
                    waveformReader.musicEnd(mediaId, durationMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Music end of $mediaId not measured: ${e.message}")
                    null
                }
            musicEnds[mediaId] = end ?: -1L
        }
    }

    // ========== Internal: Precaching ==========

    /**
     * Trigger precaching - mirrors GstreamerPlayerAdapter.triggerPrecachingInternal()
     *
     * Creates ExoPlayer instances for upcoming tracks. Each player gets a MediaItem
     * and calls prepare(), which triggers URL resolution and buffering via MediaSourceFactory.
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

                        try {
                            val player = createExoPlayerInstance()
                            player.setMediaItem(mediaItem.toMedia3MediaItem())
                            val retryListener = precacheRetryListener(mediaItem.mediaId, player)
                            player.addListener(retryListener)
                            player.addAnalyticsListener(precacheLoadErrorListener(mediaItem.mediaId, player))
                            player.prepare()
                            precachedPlayers[mediaItem.mediaId] = PrecachedPlayer(player, mediaItem, retryListener)
                            Logger.d(TAG, "Precached player for ${mediaItem.mediaId}")
                        } catch (e: Exception) {
                            Logger.e(TAG, "Precaching error for ${mediaItem.mediaId}: ${e.message}")
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

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    /**
     * Precaching waits until the track meant to be heard is playing from a loaded source (or has
     * failed), or [PRECACHE_WAIT_MS] at most. Every precache resolves a stream of its own, and those
     * shared the network and the extractor with the track that was still being resolved: measured
     * 2026-11-16 on the phone, a link opened at launch resolved alongside two precaches of the queue
     * restored a moment before (thrown away 2.5 s later, still resolving 10 s on) and two of its own
     * radio started a second after it. A paused queue, the one restored at launch above all, is not
     * precached until it plays or the wait runs out.
     */
    private suspend fun awaitCurrentTrackUnderway() {
        withTimeoutOrNull(PRECACHE_WAIT_MS) {
            while (true) {
                val player = currentPlayer ?: break
                val state = player.playbackState
                if (player.playerError != null || state == Player.STATE_ENDED) break
                if (player.playWhenReady && state == Player.STATE_READY) break
                delay(PRECACHE_POLL_MS)
            }
        }
    }

    /**
     * Remove and return the precached player for [videoId], or null if there is none
     * or its preparation already failed. A failed player sits in STATE_IDLE with
     * [ExoPlayer.getPlayerError] set; swapping it in would only surface that stale
     * error and stop playback, so it is released here instead (#2239).
     */
    private fun takeHealthyPrecachedPlayer(videoId: String): PrecachedPlayer? {
        val cached = precachedPlayers.remove(videoId) ?: return null
        cached.retryListener?.let { cached.player.removeListener(it) }
        val error = cached.player.playerError
        if (error != null) {
            Logger.w(TAG, "Discarding failed precached player for $videoId: ${error.errorCodeName}")
            cleanupPlayerInternal(cached.player)
            return null
        }
        return cached
    }

    /**
     * Watches a precached player while it waits for its turn. A source error a fresh stream URL can
     * fix is retried the way the playing player is retried in [setupPlayerListenerInternal]: drop the
     * stored format so the resolver fetches a fresh URL, then prepare the same player again (#2239).
     * Any other error, or one left after [maxRetryCount] tries, evicts the player from the map so it
     * is never swapped in failed and the track can be precached again once connectivity returns.
     */
    private fun precacheRetryListener(
        videoId: String,
        player: ExoPlayer,
    ): Player.Listener =
        object : Player.Listener {
            private var attempts = 0

            override fun onPlayerError(error: PlaybackException) {
                if (precachedPlayers[videoId]?.player !== player) return
                if (!error.isRetryableSourceError() || attempts >= maxRetryCount) {
                    precachedPlayers.remove(videoId)
                    Logger.w(TAG, "Evicting failed precached player for $videoId: ${error.errorCodeName}")
                    coroutineScope.launch { cleanupPlayerInternal(player) }
                    return
                }
                attempts++
                Logger.w(TAG, "Precache source error (attempt $attempts/$maxRetryCount) for $videoId: ${error.errorCodeName}")
                coroutineScope.launch {
                    try {
                        streamRepository.invalidateFormat(videoId)
                        streamRepository.invalidateFormat("${MERGING_DATA_TYPE.VIDEO}$videoId")
                        // Promoted or released while the format was being dropped: nothing left to retry.
                        if (precachedPlayers[videoId]?.player === player) player.prepare()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.e(TAG, "Precache retry failed for $videoId: ${e.message}")
                    }
                }
            }
        }

    /**
     * Counts the failed loads a precached player retries inside ExoPlayer, where no player error is
     * raised (see [PRECACHE_MAX_LOAD_ERRORS]): after that many without a finished load in between the
     * player is evicted, and the track is precached again at the next trigger. Harmless once the player
     * is promoted or released: it is no longer in the map then.
     */
    private fun precacheLoadErrorListener(
        videoId: String,
        player: ExoPlayer,
    ): AnalyticsListener =
        object : AnalyticsListener {
            private var failedLoads = 0

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                failedLoads = 0
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                if (wasCanceled || precachedPlayers[videoId]?.player !== player) return
                if (++failedLoads < PRECACHE_MAX_LOAD_ERRORS) return
                precachedPlayers.remove(videoId)
                Logger.w(TAG, "Evicting precached player for $videoId: $failedLoads loads failed (${error.javaClass.simpleName})")
                coroutineScope.launch { cleanupPlayerInternal(player) }
            }
        }

    /** Source errors a fresh stream URL can fix; the codes are explained in [setupPlayerListenerInternal]. */
    private fun PlaybackException.isRetryableSourceError(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

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

    // ========== Internal: Notifications ==========

    private fun notifyEqualizerIntent(shouldOpen: Boolean) {
        listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(shouldOpen) }
    }

    // ========== Internal: Shuffle Management ==========
    // Mirrors GstreamerPlayerAdapter shuffle management exactly

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
        Logger.d(TAG, "Cleared shuffle order")
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) {
            return
        }

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

        Logger.d(
            TAG,
            "Inserted index $insertedOriginalIndex into shuffle at position $insertPos (after shuffle pos $afterShufflePos)",
        )
    }

    /**
     * Gives the track just appended to [playlist] a random place after the current one, leaving
     * every other track where it was. Rebuilding the order here (createShuffleOrder) reshuffled the
     * whole queue on every Add to queue and on every track an endless queue appended, and put tracks
     * already played back in front of the listener.
     *
     * The slots right after the current track that hold [pinnedNextItems] are skipped: a Play next
     * track, and the track a crossfade has picked. The fade chooses its next index first and only
     * makes it current after loading it, so a track inserted ahead of it meanwhile would end up
     * behind Now Playing. Every other slot stays open, so a queue built by appends is still a fair
     * shuffle, its second track included.
     *
     * The full rebuild stays as the fallback for an order that no longer covers the playlist. Appends
     * can run on another thread than the one editing the order, and rebuilding is what used to repair
     * such an order on the next append.
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
        listeners.forEach { it.onTimelineChanged(list, reason) }
    }
}