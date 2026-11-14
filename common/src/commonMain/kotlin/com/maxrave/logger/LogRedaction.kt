package com.maxrave.logger

/**
 * Masks account credentials in log text: the YouTube cookie and its SAPISIDHASH authorization, the
 * Spotify `sp_dc` cookie and its bearer and client tokens. Always on, whatever the "Debug logging"
 * setting and the build type: the log is something a person attaches to a bug report.
 *
 * Two layers. The network loggers drop secret headers by name ([SECRET_HEADERS]: the curl logger, Ktor
 * `Logging`, OkHttp `HttpLoggingInterceptor`); [Logger] runs every message through [redact], which
 * catches a credential that reached a log line any other way. Non-secret ids stay readable on purpose:
 * `X-Goog-Visitor-Id`, `X-Goog-AuthUser` (an account index), `X-Goog-PageId` (a channel id).
 */
object LogRedaction {
    const val MASK = "***"

    /** Header names (case-insensitive) whose values are credentials. */
    val SECRET_HEADERS: Set<String> =
        setOf("Cookie", "Set-Cookie", "Authorization", "Proxy-Authorization", "Client-Token")

    private val TRIGGERS = listOf("cookie", "authoriz", "sapisid", "sp_dc", "bearer", "token", "sid=", "secure-", "googlevideo")

    private val HEADER_VALUE =
        Regex("""\b(cookie|set-cookie|authorization|proxy-authorization|client-token)("?\s*:\s*"?)(?!\s*\*\*\*)[^'"\r\n]+""", RegexOption.IGNORE_CASE)
    private val COOKIE_PAIR =
        Regex("""\b(SAPISID|APISID|SSID|HSID|SID|SIDCC|LOGIN_INFO|__Secure-[\w-]+|sp_dc|sp_key)=(?!\*\*\*)[^;&\s'"]+""")
    private val AUTH_SCHEME =
        Regex("""\b(SAPISID(?:1P|3P)?HASH|Bearer)\s+(?!\s*\*\*\*)[\w.~+/=-]+""", RegexOption.IGNORE_CASE)
    private val JSON_TOKEN =
        Regex(
            """"(accessToken|access_token|refreshToken|refresh_token|clientToken|client_token|token|sp_dc|cookie)"\s*:\s*"[^"]*"""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * A stream link of `googlevideo.com` carries the signature of the link (`sig`, `lsig`, `signature`),
     * the throttling and proof-of-origin tokens (`n`, `pot`), a key and the address the link was cut for
     * (`ip`, `bui`, `spc`, `ei`). None of it is an account credential and the link dies within hours, but a
     * log line with it is a working link for that address. What helps to read a log stays: the host, `expire`,
     * `itag`, `mime`, `clen`, `dur`.
     */
    private val STREAM_LINK_PARAM =
        Regex("""([?&](?:sig|lsig|signature|n|pot|key|ip|ipbits|bui|spc|ei))=(?!\*\*\*)[^&\s'"]*""")

    /** [text] with every credential it carries replaced by [MASK]; the same instance when there is none. */
    fun redact(text: String): String {
        if (TRIGGERS.none { text.contains(it, ignoreCase = true) }) return text
        return text
            .let { if (it.contains("googlevideo", ignoreCase = true)) it.replace(STREAM_LINK_PARAM) { m -> "${m.groupValues[1]}=$MASK" } else it }
            .replace(HEADER_VALUE) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            .replace(COOKIE_PAIR) { "${it.groupValues[1]}=$MASK" }
            .replace(AUTH_SCHEME) { "${it.groupValues[1]} $MASK" }
            .replace(JSON_TOKEN) { "\"${it.groupValues[1]}\":\"$MASK\"" }
    }

    /**
     * [t] as it should be logged: the same throwable when its stack trace holds nothing to mask, else a
     * [RedactedThrowable] carrying the masked trace. A request exception's message is the whole link
     * (`Client request(HEAD https://....googlevideo.com/videoplayback?...&sig=...)`), and a writer prints
     * the throwable on its own, past [redact].
     */
    fun redactThrowable(t: Throwable): Throwable {
        val trace = t.stackTraceToString()
        val masked = redact(trace)
        return if (masked == trace) t else RedactedThrowable(masked)
    }
}

/** A throwable that stands for one whose trace was masked: its message is the masked trace. */
class RedactedThrowable internal constructor(
    maskedTrace: String,
) : Throwable(maskedTrace)
