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

    private val TRIGGERS = listOf("cookie", "authoriz", "sapisid", "sp_dc", "bearer", "token", "sid=", "secure-")

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

    /** [text] with every credential it carries replaced by [MASK]; the same instance when there is none. */
    fun redact(text: String): String {
        if (TRIGGERS.none { text.contains(it, ignoreCase = true) }) return text
        return text
            .replace(HEADER_VALUE) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            .replace(COOKIE_PAIR) { "${it.groupValues[1]}=$MASK" }
            .replace(AUTH_SCHEME) { "${it.groupValues[1]} $MASK" }
            .replace(JSON_TOKEN) { "\"${it.groupValues[1]}\":\"$MASK\"" }
    }
}
