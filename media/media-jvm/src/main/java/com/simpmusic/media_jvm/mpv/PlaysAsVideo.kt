package com.simpmusic.media_jvm.mpv

/**
 * Whether a track is played as a video, which keeps it out of a crossfade and of the music end
 * ([MpvPlayerAdapter]'s polling loop).
 *
 * The queue item only says it is a clip, and only matters while "watch video" is on. What plays is
 * settled by the source: a downloaded track is an audio file whatever the item is, so with nothing to
 * show there is no first frame to protect and it fades like any song. [handleHasVideo] is the answer
 * of a handle that already exists (the playing one, a precached one); null when there is none yet.
 */
internal fun playsAsVideo(
    watchVideoEnabled: Boolean,
    itemIsVideo: Boolean,
    handleHasVideo: Boolean?,
    downloaded: Boolean,
): Boolean {
    if (!watchVideoEnabled || !itemIsVideo) return false
    return handleHasVideo ?: !downloaded
}
