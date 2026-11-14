package androidx.media3.session

import android.content.Intent
import android.media.Rating
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.legacy.platformCallback
import com.maxrave.logger.Logger
import android.media.session.MediaSession as PlatformSession

private const val TAG = "MediaButton"

/**
 * Keeps a media button sent without a package name from crashing the app.
 *
 * Media3's legacy session (1.11.1, unchanged on `main`) wraps every platform callback in
 * `setCurrentControllerInfo`, which builds a `RemoteUserInfo` from the caller and throws
 * `IllegalArgumentException: packageName should be nonempty` when the platform reports an empty
 * package — which is what `cmd media_session dispatch` does. The throw happens on the main thread
 * before the key reaches any code of ours, so the process dies and the queue is not saved.
 *
 * The platform callback is wrapped once, after the session is built (Media3 registers it only in the
 * legacy stub's constructor): everything is passed through unchanged, except a media button whose
 * caller has no package name, which is applied to the player directly the way Media3 would. Lives in
 * this package because the path to the platform session is package-private.
 */
@OptIn(UnstableApi::class)
internal fun MediaSession.guardMediaButtonsWithoutPackage() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return // the throwing path is API 28+
    val stub = impl.mediaSessionLegacyStub
    val platformSession = stub.sessionCompat.mediaSession as? PlatformSession ?: return
    platformSession.setCallback(
        EmptyPackageGuardCallback(platformSession, stub.platformCallback(), player),
        Handler(player.applicationLooper),
    )
    Logger.d(TAG, "guard installed")
}

@OptIn(UnstableApi::class)
private class EmptyPackageGuardCallback(
    private val session: PlatformSession,
    private val delegate: PlatformSession.Callback,
    private val player: Player,
) : PlatformSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        val callerPackage = runCatching { session.currentControllerInfo.packageName }.getOrNull()
        if (!callerPackage.isNullOrEmpty()) return delegate.onMediaButtonEvent(mediaButtonIntent)
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        Logger.i(TAG, "key ${event.keyCode} without a package name, applied directly")
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> Util.handlePlayButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Util.handlePauseButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                Util.handlePlayPauseButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_NEXT -> player.seekToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.seekToPrevious()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekForward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBack()
            KeyEvent.KEYCODE_MEDIA_STOP -> player.stop()
            else -> return false
        }
        return true
    }

    override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) = delegate.onCommand(command, args, cb)

    override fun onPrepare() = delegate.onPrepare()

    override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) = delegate.onPrepareFromMediaId(mediaId, extras)

    override fun onPrepareFromSearch(query: String, extras: Bundle?) = delegate.onPrepareFromSearch(query, extras)

    override fun onPrepareFromUri(uri: Uri, extras: Bundle?) = delegate.onPrepareFromUri(uri, extras)

    override fun onPlay() = delegate.onPlay()

    override fun onPlayFromSearch(query: String, extras: Bundle?) = delegate.onPlayFromSearch(query, extras)

    override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = delegate.onPlayFromMediaId(mediaId, extras)

    override fun onPlayFromUri(uri: Uri, extras: Bundle?) = delegate.onPlayFromUri(uri, extras)

    override fun onSkipToQueueItem(id: Long) = delegate.onSkipToQueueItem(id)

    override fun onPause() = delegate.onPause()

    override fun onSkipToNext() = delegate.onSkipToNext()

    override fun onSkipToPrevious() = delegate.onSkipToPrevious()

    override fun onFastForward() = delegate.onFastForward()

    override fun onRewind() = delegate.onRewind()

    override fun onStop() = delegate.onStop()

    override fun onSeekTo(pos: Long) = delegate.onSeekTo(pos)

    override fun onSetRating(rating: Rating) = delegate.onSetRating(rating)

    override fun onSetPlaybackSpeed(speed: Float) = delegate.onSetPlaybackSpeed(speed)

    override fun onCustomAction(action: String, extras: Bundle?) = delegate.onCustomAction(action, extras)
}
