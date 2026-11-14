package com.simpmusic.media_jvm.download

import com.maxrave.logger.Logger
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "PartialFileStore"

/**
 * A folder of `<videoId>.webm` files the player takes by exact name and trusts to be whole. A transfer
 * writes under [PARTIAL_DIR] and nowhere else, and the finished file appears in [dir] only by a rename
 * out of it, which no reader can see half done. Used by the downloads and by the listening cache.
 */
internal class PartialFileStore(
    val dir: File,
) {
    val partialDir: File get() = File(dir, PARTIAL_DIR)

    /** The finished file of [videoId], whether or not it exists. */
    fun file(videoId: String) = File(dir, "$videoId.webm")

    /** The finished file of [videoId], or null when there is none (or it is empty). */
    fun existing(videoId: String): File? = file(videoId).takeIf { it.isFile && it.length() > 0 }

    /** Where a transfer of [videoId] writes, without the extension (the download code adds `.webm`). */
    fun partialBase(videoId: String) = File(partialDir, videoId).path

    /** For a launch: nothing runs yet, so whatever lies under [PARTIAL_DIR] was left by a transfer the last run died in. */
    fun dropAllPartials() {
        partialDir.deleteRecursively()
    }

    /** Everything a transfer of [videoId] may have left: the file, the video half and the chunks folder. */
    fun deletePartial(videoId: String) {
        listOf("$videoId.webm", "$videoId.mp4", "temp_chunks_$videoId.webm").forEach { File(partialDir, it).deleteRecursively() }
    }

    /** Whole file or nothing: moves the finished transfer of [videoId] into [dir]. */
    fun publish(videoId: String): Boolean {
        val source = File(partialDir, "$videoId.webm")
        if (!source.isFile || source.length() == 0L) return false
        val target = file(videoId)
        return try {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: IOException) {
            Logger.e(TAG, "Could not move the finished file of $videoId into ${dir.name}: ${e.message}")
            false
        }
    }

    companion object {
        const val PARTIAL_DIR = ".partial"
    }
}
