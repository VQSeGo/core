package com.maxrave.logger

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Message
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

// This facade is named like Kermit's own Logger, which this file alone imports: call sites use
// com.maxrave.logger.Logger and never Kermit directly.
object Logger {
    private val logger = Logger

    /**
     * The "Debug logging" setting. Off: Debug lines are dropped before their message is built, Kermit's
     * floor rises to Info, and the network loggers (curl commands, Ktor Logging, OkHttp headers) check
     * this flag on every request and stay silent. Info, Warn and Error (the `Startup` and `Offline`
     * marks, `Auth`) are written either way. Secrets are masked in both modes ([LogRedaction]).
     *
     * On until the stored value is read at startup (`CommonRepositoryImpl.init`, inside Koin's start).
     */
    /** DataStore key of the setting (`getString`/`putString`); "FALSE" turns it off, anything else is on. */
    const val DEBUG_LOGGING_KEY = "debug_logging"

    @Volatile
    var debugLogging: Boolean = true
        private set

    fun setDebugLogging(enabled: Boolean) {
        debugLogging = enabled
        logger.setMinSeverity(if (enabled) Severity.Verbose else Severity.Info)
    }

    // Tags suppressed at all log levels. Add a tag here to silence its logs globally.
    private val mutedTags =
        setOf(
            "DiscordWebSocket",
        )

    private fun isMuted(tag: String): Boolean = tag in mutedTags

    const val APP_LOG = "app"
    const val ERROR_LOG = "error"
    private const val APP_LOG_FILES = 3
    private const val ERROR_LOG_FILES = 2

    /** The folder [enableFileLogging] writes to; null while the log only reaches Logcat/console. */
    var logDirectory: String? = null
        private set

    /**
     * Mirrors the log onto disk under [directory]: Info and above into [APP_LOG], Error alone into
     * [ERROR_LOG]. Debug stays on Logcat/console only. Called once at startup by each app.
     *
     * Kermit's RollingFileLogWriter writes on a thread of its own, so a caller never waits on the
     * disk, and rolls each file over by size, so the folder never grows past about 4 MB.
     */
    fun enableFileLogging(directory: String) {
        if (logDirectory != null) return
        val path = Path(directory)
        // The writer opens its file but never creates the folder, and a missing one only drops lines.
        runCatching { SystemFileSystem.createDirectories(path) }
        logDirectory = directory
        logger.addLogWriter(
            LevelFileWriter(path, APP_LOG, rollOnSize = 1_000_000, maxLogFiles = APP_LOG_FILES, minSeverity = Severity.Info),
            LevelFileWriter(path, ERROR_LOG, rollOnSize = 512_000, maxLogFiles = ERROR_LOG_FILES, minSeverity = Severity.Error),
        )
    }

    /**
     * The files behind [name] ([APP_LOG] or [ERROR_LOG]), oldest first, whether they exist yet or
     * not; empty before [enableFileLogging]. RollingFileLogWriter's own naming: `<name>.log` is the
     * live file, `<name>-N.log` the older ones.
     */
    fun logFiles(name: String): List<String> {
        val directory = logDirectory ?: return emptyList()
        val count = if (name == ERROR_LOG) ERROR_LOG_FILES else APP_LOG_FILES
        return (count - 1 downTo 0).map { index ->
            Path(directory, if (index == 0) "$name.log" else "$name-$index.log").toString()
        }
    }

    fun d(
        tag: String,
        message: String,
    ) {
        if (!debugLogging || isMuted(tag)) return
        logger.d(tag = tag, message = { LogRedaction.redact(message) })
    }

    /** Debug line built only when it is written: for dumps of whole responses and objects. */
    fun d(
        tag: String,
        message: () -> String,
    ) {
        if (!debugLogging || isMuted(tag)) return
        logger.d(tag = tag, message = { LogRedaction.redact(message()) })
    }

    fun i(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.i(tag = tag, message = { LogRedaction.redact(message) })
    }

    fun w(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.w(tag = tag, message = { LogRedaction.redact(message) })
    }

    fun e(
        tag: String,
        message: String,
        e: Throwable? = null,
    ) {
        if (isMuted(tag)) return
        // A cancelled coroutine is how work ends when the track changes or a screen closes, not a failure.
        if (e is CancellationException) {
            d(tag, "$message (cancelled)")
            return
        }
        logger.e(throwable = e?.let { LogRedaction.redactThrowable(it) }, tag = tag, message = { LogRedaction.redact(message) })
    }

    /** [value] as text cut to [max] characters, for logging objects that can run to kilobytes. */
    fun clip(
        value: Any?,
        max: Int = CLIP_DEFAULT,
    ): String {
        val text = value.toString()
        return if (text.length <= max) text else text.take(max) + "… (+${text.length - max} chars)"
    }

    private const val CLIP_DEFAULT = 500
}

/** A rolling log file that takes [minSeverity] and above only. */
private class LevelFileWriter(
    directory: Path,
    name: String,
    rollOnSize: Long,
    maxLogFiles: Int,
    private val minSeverity: Severity,
) : RollingFileLogWriter(
    config =
        RollingFileLogWriterConfig(
            logFileName = name,
            logFilePath = directory,
            rollOnSize = rollOnSize,
            maxLogFiles = maxLogFiles,
        ),
    messageStringFormatter = LogLineFormatter,
) {
    override fun isLoggable(
        tag: String,
        severity: Severity,
    ): Boolean = severity >= minSeverity
}

/**
 * One entry per line, logcat-style: `I/Tag: message`. The writer puts the timestamp in front and a
 * throwable's stack trace on the lines after, which is the shape the App log screen parses.
 */
private object LogLineFormatter : MessageStringFormatter {
    override fun formatMessage(
        severity: Severity?,
        tag: Tag?,
        message: Message,
    ): String = "${severity?.name?.first() ?: '-'}/${tag?.tag.orEmpty()}: ${com.maxrave.logger.Logger.clip(message.message, MAX_FILE_MESSAGE)}"

    // A line in the file stays under 2000 characters with the timestamp and tag in front; Logcat keeps
    // the whole message (it cuts at ~4 KB itself).
    private const val MAX_FILE_MESSAGE = 1800
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}