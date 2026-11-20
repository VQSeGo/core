package com.maxrave.domain.mediaservice.player

/**
 * The current track is a clip downloaded as audio only, played while "watch video" is on: what
 * plays is the audio file, so the player shows only the cover. The user can still watch the clip
 * from the network for this one track ([MediaPlayerInterface.showDownloadedClip]); the video is
 * never downloaded.
 *
 * [shown] is true while the clip streams from the network instead of the file.
 */
data class DownloadedClip(
    val mediaId: String,
    val shown: Boolean,
)

object DownloadedClips {
    /**
     * What the button on the cover offers for the current track, or null when it has nothing to offer:
     * a song, "watch video" off, or a clip that is not downloaded (or whose video is downloaded too)
     * and therefore already plays as a video.
     *
     * [streamedClipId] is the one track the user asked to stream; it counts only for that track.
     */
    fun stateOf(
        mediaId: String,
        watchVideoEnabled: Boolean,
        itemIsVideo: Boolean,
        audioOnlyDownload: Boolean,
        streamedClipId: String?,
    ): DownloadedClip? {
        if (!watchVideoEnabled || !itemIsVideo || !audioOnlyDownload) return null
        return DownloadedClip(mediaId, shown = streamedClipId == mediaId)
    }

    /**
     * Whether [mediaId] is to be played from the network as a video and not from its download: only
     * while the user asked for its clip, and only while it still counts as a clip with "watch video" on.
     */
    fun streams(
        mediaId: String,
        watchVideoEnabled: Boolean,
        itemIsVideo: Boolean,
        streamedClipId: String?,
    ): Boolean = watchVideoEnabled && itemIsVideo && streamedClipId != null && streamedClipId == mediaId

    /**
     * Whether a track plays as a video, which keeps it out of a crossfade and out of the music end
     * measurement: only what really plays with a picture. The queue item only says it is a clip, and
     * only matters while "watch video" is on. A downloaded clip is an audio file (the video is never
     * downloaded), so it counts as a song, like any, unless the user asked to stream its video
     * ([streamedClipId]). [handleHasVideo] is the answer of a player that already holds the track (the
     * playing one, a precached one), null when there is none or it has not told yet: it outranks the rest.
     */
    fun playsAsVideo(
        mediaId: String,
        watchVideoEnabled: Boolean,
        itemIsVideo: Boolean,
        audioOnlyDownload: Boolean,
        streamedClipId: String?,
        handleHasVideo: Boolean? = null,
    ): Boolean {
        if (!watchVideoEnabled || !itemIsVideo) return false
        return handleHasVideo ?: (!audioOnlyDownload || streamedClipId == mediaId)
    }

    /** The request carried over to [currentMediaId]: kept for the same track, dropped once another one plays. */
    fun carriedOver(
        streamedClipId: String?,
        currentMediaId: String?,
    ): String? = streamedClipId?.takeIf { it == currentMediaId }
}
