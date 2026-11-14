package com.maxrave.kotlinytmusicscraper.extractor

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.ServiceList
import dev.maxrave.pipepipe.extractor.services.youtube.YoutubeApiDecoder
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import org.schabi.newpipe.extractor.NewPipe as BraveNewPipe
import org.schabi.newpipe.extractor.ServiceList as BraveServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager as BravePlayerCode
import org.schabi.newpipe.extractor.stream.StreamInfo as BraveStreamInfo

private const val TAG = "Extractor"
private const val LOCAL_TIER = "local"
private const val REMOTE_TIER = "pipepipe.dev"

/** Any public video: BravePipe needs one to find the current player code. */
private const val WARM_UP_VIDEO_ID = "dQw4w9WgXcQ"

actual class Extractor {
    private var newPipeDownloader = NewPipeDownloaderImpl(proxy = null)
    private var braveNewPipeDownloader = BraveNewPipeDownloaderImpl(proxy = null)
    private val faradayDecoder = FaradayJsDecoder()

    actual fun init() {
        NewPipe.init(newPipeDownloader)
        BraveNewPipe.init(braveNewPipeDownloader)
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
    }

    /**
     * BravePipe keeps what it learns on its first extraction (the player code, the client version and
     * the like) in static fields with no lock, so tracks resolved together while it is cold each fetch
     * and parse all of it again. Seen 2026-11-14 on the phone: three resolutions right after a launch
     * spent 9-20 s in BravePipe and finished within a second of each other, against 1.5-2 s each once
     * it was warm. Until one extraction went through, one caller at a time.
     */
    private val braveColdLock = Any()

    @Volatile
    private var braveWarm = false

    actual fun warmUp() {
        if (braveWarm) return
        synchronized(braveColdLock) {
            if (braveWarm) return
            val started = System.nanoTime()
            val code = runCatching { BravePlayerCode.getSignatureTimestamp(WARM_UP_VIDEO_ID) }.isSuccess
            val codeDone = System.nanoTime()
            // A whole extraction, not just the player code: the code alone left a later extraction
            // still cold for 14 s.
            val ok =
                runCatching {
                    BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$WARM_UP_VIDEO_ID")
                }.onFailure { Logger.w(TAG, "BravePipe warm-up failed: ${it.message}") }.isSuccess
            // Once, whatever came of it: a failed warm-up (no network at launch, this one video
            // refused) must not make every later extraction pay for another one.
            braveWarm = true
            val done = System.nanoTime()
            Logger.i(
                "Offline",
                "resolve warm-up brave ${(done - started) / 1_000_000} ok=$ok " +
                    "(player code ${(codeDone - started) / 1_000_000} ok=$code, extraction ${(done - codeDone) / 1_000_000})",
            )
        }
    }

    actual fun logIn(cookie: String?) {
        ServiceList.YouTube.tokens = cookie ?: ""
    }

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        // Three attempts, kept separate on purpose. PipePipe's own local-then-server fallback lives
        // inside a single StreamInfo call and only fires when the local decoder THROWS — but the
        // failure that actually happens is a stale player table, where the signature is well-formed
        // and merely rejected by the CDN. Nothing throws, so the server tier is never asked, and a
        // 403 used to skip straight to BravePipe. Driving the two as separate attempts is what
        // stops that jump.

        // Tier 0 — BravePipe up front while the PipePipe tiers are known to fail. Its URL is
        // HEAD-checked here, since a rejection must still reach the PipePipe tiers below.
        val braveFirst = BravePipeFirst.active
        if (braveFirst) {
            val early = timedStage(videoId, "brave-first") { braveStreams(videoId) }
            if (early.isNotEmpty() && !early.refusedByHead()) return early
            // Only this track falls back: one refused URL (seen on a single video itag while the
            // rest of BravePipe's answers kept passing) is no reason to pay for the PipePipe tiers
            // on every track after it. The window still runs out on its own.
            Logger.i("Offline", "resolve $videoId brave-first rejected, back to PipePipe")
        }

        // Tier 1 — ciphers solved locally.
        // Re-registered every time: PipePipe drops the local decoder for good the first time it
        // throws, and the getter is package-private so its state cannot be read from here. Setting
        // it again turns "disabled forever" into "skipped for one track".
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
        timedStage(videoId, "pipepipe-local") { pipePipeStreams(videoId, LOCAL_TIER) }?.let {
            BravePipeFirst.pipePipeAnswered()
            return it
        }

        // Tier 2 — same extractor with no local decoder at all, so every challenge is solved at
        // api.pipepipe.dev. This is the tier a 403 from tier 1 must reach.
        //
        // Clearing it is what `setLocalDecoder(null)` does: both call sites read the field and
        // null-check it before use — `decodeBatch` and `getPlayerMetadata` each branch straight to
        // the API path when it is absent (verified in the bytecode of the pinned f8982ca9e7 jar).
        // PipePipe's own `disableLocalDecoder` does exactly this but is private, which is why it
        // cannot simply be called from here.
        YoutubeApiDecoder.setLocalDecoder(null)
        timedStage(videoId, "pipepipe-remote") { pipePipeStreams(videoId, REMOTE_TIER) }?.let {
            BravePipeFirst.pipePipeAnswered()
            return it
        }

        // Tier 3 — a different extractor entirely.
        // Checked here like every other tier, so YouTube.newPipePlayer does not HEAD it again.
        val last = timedStage(videoId, "brave") { braveStreams(videoId) }
        if (last.isEmpty() || last.refusedByHead()) return emptyList()
        // Only when BravePipe was not just refused up front.
        if (!braveFirst) BravePipeFirst.pipePipeFailed()
        return last
    }

    /** One `Offline` timing line per extraction tier, for the stream-resolution measurements. */
    private inline fun <T> timedStage(
        videoId: String,
        stage: String,
        block: () -> T,
    ): T {
        val started = System.nanoTime()
        val result = block()
        val ok = result != null && (result as? List<*>)?.isNotEmpty() != false
        Logger.i("Offline", "resolve $videoId $stage ${(System.nanoTime() - started) / 1_000_000} ok=$ok")
        return result
    }

    actual fun liveHlsUrl(videoId: String): String? {
        // The same three tiers as newPipePlayer, in the same order and for the same reasons.
        YoutubeApiDecoder.setLocalDecoder(faradayDecoder)
        pipePipeLiveHlsUrl(videoId, LOCAL_TIER)?.let { return it }
        YoutubeApiDecoder.setLocalDecoder(null)
        pipePipeLiveHlsUrl(videoId, REMOTE_TIER)?.let { return it }
        return braveLiveHlsUrl(videoId)
    }

    private fun pipePipeLiveHlsUrl(
        videoId: String,
        tier: String,
    ): String? =
        runCatching {
            StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId").hlsUrl
        }.onFailure {
            Logger.w(TAG, "PipePipe[$tier] live extraction failed for $videoId: ${it.message}")
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                ExtractSource.record(videoId, "PipePipe · $tier · live")
                Logger.d(TAG, "live HLS from PipePipe[$tier] for $videoId")
            }

    private fun braveLiveHlsUrl(videoId: String): String? =
        runCatching {
            BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId").hlsUrl
        }.onFailure {
            Logger.w(TAG, "BravePipe live extraction failed for $videoId: ${it.message}")
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                ExtractSource.record(videoId, "BravePipe · live")
                Logger.d(TAG, "live HLS from BravePipe for $videoId")
            }

    /**
     * One PipePipe extraction with whatever decoder is currently registered. Returns null when the
     * attempt produced nothing usable, so the caller moves on to the next tier.
     */
    private fun pipePipeStreams(
        videoId: String,
        tier: String,
    ): List<Pair<Int, String>>? {
        try {
            val streamInfo =
                StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val pipeResult =
                streamsList.mapNotNull {
                    (it.itagItem?.id ?: return@mapNotNull null) to it.content
                }
            if (!pipeResult.hasRequiredItags()) {
                Logger.d(TAG, "PipePipe[$tier] missing required itags for $videoId (got=${pipeResult.map { it.first }})")
                return null
            }
            val headStarted = System.nanoTime()
            val headOk = pipeResult.headCheckRandomStream()
            Logger.i("Offline", "resolve $videoId head-$tier ${(System.nanoTime() - headStarted) / 1_000_000} ok=$headOk")
            if (!headOk) {
                Logger.d(TAG, "PipePipe[$tier] stream URL HEAD check failed (non 2xx) for $videoId")
                // A rejected URL is the one symptom of a stale player table: the signature is
                // well-formed and still wrong, so nothing threw on the way here. Only the on-device
                // table can go stale, so only that tier is worth invalidating.
                if (tier == LOCAL_TIER) faradayDecoder.invalidate()
                return null
            }
            val label = if (tier == LOCAL_TIER) faradayDecoder.lastOutcomeLabel else REMOTE_TIER
            ExtractSource.record(videoId, "PipePipe · $label")
            Logger.d(TAG, "extract source=PipePipe[$tier] itags=${pipeResult.map { it.first }} for $videoId")
            return pipeResult
        } catch (e: Throwable) {
            Logger.w(TAG, "PipePipe[$tier] extractor failed for $videoId: ${e.message}")
            return null
        }
    }

    private fun braveStreams(videoId: String): List<Pair<Int, String>> =
        runCatching {
            warmUp()
            val streamInfo =
                BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList
                .mapNotNull {
                    (it.itagItem?.id ?: return@mapNotNull null) to it.content
                }.also {
                    ExtractSource.record(videoId, "BravePipe")
                    Logger.d(TAG, "extract source=BravePipe itags=${it.map { pair -> pair.first }} for $videoId")
                }
        }.onFailure {
            Logger.w(TAG, "BravePipe extractor failed for $videoId: ${it.message}")
        }.getOrElse { emptyList() }

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress {
        val command =
            listOf(
                "-i",
                ("$filePath.mp4"),
                "-i",
                ("$filePath.webm"),
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-shortest",
                "$filePath-SimpMusic.mp4",
            ).joinToString(" ")

        if (FileSystem.SYSTEM.exists("$filePath-SimpMusic.mp4".toPath())) {
            FileSystem.SYSTEM.delete("$filePath-SimpMusic.mp4".toPath())
        }

        val session =
            FFmpegKit.execute(
                command,
            )
        if (ReturnCode.isSuccess(session.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.VIDEO_DONE)
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed(session.failStackTrace ?: "Command cancelled"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${session.state}, ${session.returnCode}, ${session.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed(session.failStackTrace ?: "FFmpeg command failed"))
        }
    }

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress {
        val command =
            listOf(
                "-i",
                ("$filePath.webm"),
                "-q:a 0",
                "$filePath.mp3",
            ).joinToString(" ")

        try {
            if (FileSystem.SYSTEM.exists("$filePath.mp3".toPath())) {
                FileSystem.SYSTEM.delete("$filePath.mp3".toPath())
            }
            if (FileSystem.SYSTEM.exists("$filePath-simpmusic.mp3".toPath())) {
                FileSystem.SYSTEM.delete("$filePath-simpmusic.mp3".toPath())
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val session =
            FFmpegKit.execute(
                command,
            )
        if (ReturnCode.isSuccess(session.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${session.state}, ${session.returnCode}, ${session.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        }

        val commandInject =
            listOf(
                "-i",
                "$filePath.mp3",
                "-i $filePath.jpg",
                "-map 0:a",
                "-map 1:v",
                "-c copy",
                "-id3v2_version 3",
                "-metadata",
                "title=\"${track.title}\"",
                "-metadata",
                "artist=\"${track.artists.joinToString(", ") { it.name }}\"",
                "-metadata",
                "album=\"${track.album?.name ?: track.title}\"",
                "-disposition:v:0 attached_pic",
                "$filePath-simpmusic.mp3",
            ).joinToString(" ")
        val sessionInject =
            FFmpegKit.execute(
                commandInject,
            )
        if (ReturnCode.isSuccess(sessionInject.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${sessionInject.state}, ${sessionInject.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.mp3".toPath())
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.AUDIO_DONE)
        } else if (ReturnCode.isCancel(sessionInject.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${sessionInject.state}, ${sessionInject.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath-simpmusic.mp3".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${sessionInject.state}, ${sessionInject.returnCode}, ${sessionInject.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath-simpmusic.mp3".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        }
    }
}