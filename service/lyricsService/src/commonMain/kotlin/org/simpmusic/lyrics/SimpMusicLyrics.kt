package org.simpmusic.lyrics

import com.maxrave.ktorext.curl.CurlLogger
import com.maxrave.ktorext.encoding.brotli
import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.am.AMTokenManager

class SimpMusicLyrics {
    private var httpClient = createClient()
    var proxy: ProxyConfig? = null
        set(value) {
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    private val baseUrl = "https://api-lyrics.simpmusic.org/v1/"

    private val amTokenManager = AMTokenManager()

    private fun createClient() =
        HttpClient(getEngine()) {
            expectSuccess = false
            followRedirects = true
            install(HttpCache)
            install(CurlLogger) {
                logger = { Logger.d("SimpMusicLyrics", it) }
            }
            install(HttpSend) {
                maxSendCount = 100
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }
            defaultRequest {
                url("https://api-lyrics.simpmusic.org/v1")
            }
            if (proxy != null) {
                engine {
                    proxy = this@SimpMusicLyrics.proxy
                }
            }
        }

    private fun HttpRequestBuilder.buildDefaultHeaders() {
        headers {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "SimpMusicLyrics/1.0")
            header(HttpHeaders.ContentType, "application/json")
        }
    }

    private fun HttpRequestBuilder.buildAMHeaders(token: String) {
        headers {
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header(
                HttpHeaders.UserAgent,
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0",
            )
            header(HttpHeaders.Accept, "application/json")
        }
    }

    suspend fun findLyricsByVideoId(videoId: String) =
        httpClient.get(baseUrl + videoId) {
            buildDefaultHeaders()
        }

    suspend fun findTranslatedLyrics(
        videoId: String,
        language: String,
    ) = httpClient.get(baseUrl + "translated/$videoId/$language") {
        buildDefaultHeaders()
    }

    suspend fun searchLrclibLyrics(
        q_track: String,
        q_artist: String,
    ) = httpClient.get("https://lrclib.net/api/search") {
        buildDefaultHeaders()
        parameter("q", "$q_artist $q_track")
    }

    suspend fun searchBetterLyrics(
        q_track: String,
        q_artist: String,
        durationSeconds: Int?,
    ) = httpClient.get("https://lyrics-api.boidu.dev/getLyrics") {
        buildDefaultHeaders()
        parameter("s", q_track)
        parameter("a", q_artist)
        durationSeconds?.let {
            parameter("d", it)
        }
    }

    suspend fun searchAMArtist(
        name: String,
        limit: Int,
    ): HttpResponse {
        val response = requestAMSearch(name, limit, amTokenManager.getToken(httpClient))
        // Expired bearer -> refresh the token once and retry on a fresh request.
        if (response.status.value == 401) {
            amTokenManager.clearToken()
            return requestAMSearch(name, limit, amTokenManager.getToken(httpClient))
        }
        return response
    }

    private suspend fun requestAMSearch(
        name: String,
        limit: Int,
        token: String,
    ) = httpClient.get("https://amp-api-edge.music.apple.com/v1/catalog/us/search") {
        parameter("term", name)
        parameter("types", "artists")
        parameter("fields[artists]", "url,name,artwork")
        parameter("art[url]", "f")
        parameter("format[resources]", "map")
        parameter("extend", "artistUrl")
        parameter("l", "en-US")
        parameter("limit", limit)
        parameter("platform", "web")
        buildAMHeaders(token)
    }

    suspend fun getAMArtist(id: String): HttpResponse {
        val response = requestAMArtist(id, amTokenManager.getToken(httpClient))
        // Expired bearer -> refresh the token once and retry on a fresh request.
        if (response.status.value == 401) {
            amTokenManager.clearToken()
            return requestAMArtist(id, amTokenManager.getToken(httpClient))
        }
        return response
    }

    private suspend fun requestAMArtist(
        id: String,
        token: String,
    ) = httpClient.get("https://amp-api.music.apple.com/v1/catalog/us/artists/$id") {
        parameter("art[url]", "c,f")
        // editorialVideo is what brings the animated artist artwork back. Without it named here
        // the field is simply absent from the response — the request still succeeds.
        parameter("extend", "editorialArtwork,editorialVideo,hero,keyColor")
        parameter("format[resources]", "map")
        parameter("l", "en-US")
        parameter("platform", "web")
        buildAMHeaders(token)
    }

    /**
     * Fetch an animated-artwork playlist off Apple's media CDN. Deliberately without the AM headers:
     * this host takes no bearer, serves no DRM and needs no origin — sending them only invites the
     * edge to treat the request as something it is not.
     */
    suspend fun fetchAMHlsPlaylist(url: String): HttpResponse = httpClient.get(url)
}
