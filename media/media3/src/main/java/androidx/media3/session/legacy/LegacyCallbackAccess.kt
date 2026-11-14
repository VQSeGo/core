package androidx.media3.session.legacy

import android.media.session.MediaSession

/**
 * The platform callback Media3's legacy session registered with the framework session. Lives in this
 * package because the field is package-private; see `EmptyPackageMediaButtonGuard`.
 */
internal fun MediaSessionCompat.Callback.platformCallback(): MediaSession.Callback = callbackFwk
