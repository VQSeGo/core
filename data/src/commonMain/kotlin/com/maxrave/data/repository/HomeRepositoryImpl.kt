package com.maxrave.data.repository

import com.maxrave.data.mapping.toHomeContent
import com.maxrave.data.parser.parseChart
import com.maxrave.data.parser.parseGenreObject
import com.maxrave.data.parser.parseMixedContent
import com.maxrave.data.parser.parseMoodsMomentObject
import com.maxrave.data.parser.parseNewRelease
import com.maxrave.domain.data.model.home.BrowsePage
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.MoodItem
import com.maxrave.domain.data.model.mood.MoodSection
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.utils.NetworkGate
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * One resolved category cover plus when it was fetched.
 *
 * Kept with a timestamp because the cover is whatever playlist YouTube happened to rank first in
 * that category — it drifts, so a cached copy should expire rather than stick forever.
 */
@Serializable
private data class MoodArtwork(
    val url: String,
    val cachedAt: Long,
) {
    @OptIn(ExperimentalTime::class)
    fun isStale(): Boolean = Clock.System.now().toEpochMilliseconds() - cachedAt > MOOD_ARTWORK_TTL_MILLIS
}

private const val MOOD_ARTWORK_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

@OptIn(ExperimentalTime::class)
internal class HomeRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val youTube: YouTube,
) : HomeRepository {
    /**
     * Same posture as the Room converters: a cache written by an older build must not crash the
     * app after the model gains a field, so unknown keys are dropped rather than rejected.
     */
    private val moodJson =
        Json {
            ignoreUnknownKeys = true
        }

    override val networkRestored: Flow<Unit> get() = NetworkGate.restored

    override fun getHomeData(
        params: String?,
        viewString: String,
        songString: String,
        useCache: Boolean,
    ): Flow<Resource<Triple<String?, List<HomeItem>, String?>>> =
        flow {
            // Only the default feed is cached: a mood chip is picked by hand and can wait for the network.
            // The key is computed on a refresh too, so the fresh feed is still saved.
            val cacheKey =
                if (params == null) {
                    HomeFeedCache.key(
                        language = dataStoreManager.language.first(),
                        location = dataStoreManager.location.first(),
                        cookie = dataStoreManager.cookie.first(),
                        pageId = dataStoreManager.pageId.first(),
                        authUser = dataStoreManager.authUser.first(),
                    )
                } else {
                    null
                }
            // Last launch's feed first, so Home paints without waiting for (or having) a network.
            // Its continuation token is dropped: it goes stale, and paging waits for the fresh feed.
            val cached =
                cacheKey
                    ?.takeIf { useCache }
                    ?.let { HomeFeedCache.read(it) }
                    ?.let { response -> runCatching { parseHomeResponse(response, viewString, songString) }.getOrNull() }
                    ?.takeIf { it.second.isNotEmpty() }
                    ?.copy(first = null)
            if (cached != null) {
                emit(Resource.Success(cached))
            }
            youTube.applyStoredLocale(dataStoreManager)
            val result = youTube.customQuery(browseId = "FEmusic_home", params = params)
            // mapCatching only around the plain parser: a parse that throws becomes an error, not silence.
            result
                .mapCatching { response -> parseHomeResponse(response, viewString, songString) }
                .onSuccess { home ->
                    saveAccountFromHome(result.getOrThrow())
                    Logger.d("Repository", "List size: ${home.second.size}")
                    emit(Resource.Success(home))
                    if (cacheKey != null && home.second.isNotEmpty()) {
                        HomeFeedCache.write(cacheKey, result.getOrThrow())
                    }
                }.onFailure { error ->
                    // The cached feed rides along as data: a caller that already shows it keeps it, and one
                    // that only takes the last value (Android Auto) still gets a feed.
                    if (cached != null) {
                        Logger.i("Offline", "home network failed, cached feed stays: ${error::class.simpleName}")
                    }
                    emit(Resource.Error(error.message.toString(), cached))
                }
        }.flowOn(Dispatchers.IO)

    /**
     * The one parser for the Home browse response, shared by the network and the cache paths:
     * continuation token, shelves and page background. Anything that filters shelves belongs here.
     */
    private fun parseHomeResponse(
        result: BrowseResponse,
        viewString: String,
        songString: String,
    ): Triple<String?, List<HomeItem>, String?> {
        val sectionList =
            result.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
        val continueParam =
            sectionList
                ?.continuations
                ?.firstOrNull()
                ?.nextContinuationData
                ?.continuation
        val list = parseMixedContent(sectionList?.contents, viewString, songString)
        val background = result.background?.musicThumbnailRenderer?.getThumbnailUrl()
        return Triple(continueParam, list, background)
    }

    /** The signed-in account's name and avatar ride on the first shelf's header; network responses only. */
    private suspend fun saveAccountFromHome(result: BrowseResponse) {
        val header =
            result.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicCarouselShelfRenderer
                ?.header
                ?.musicCarouselShelfBasicHeaderRenderer
                ?: return
        val accountName = header.strapline?.runs?.firstOrNull()?.text ?: return
        val accountThumbUrl =
            header.thumbnail
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.firstOrNull()
                ?.url
                ?.replace("s88", "s352") ?: ""
        if (accountName != "" && accountThumbUrl != "") {
            dataStoreManager.putString("AccountName", accountName)
            dataStoreManager.putString("AccountThumbUrl", accountThumbUrl)
        }
    }

    override fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> = flow {
        youTube.applyStoredLocale(dataStoreManager)
        youTube
            .customQuery(browseId = "", continuation = continueParam)
            .onSuccess { response ->
                val newContinueParam =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.get(
                            0,
                        )?.nextContinuationData
                        ?.continuation
                Logger.d("Repository", "continueParam: $continueParam")
                val dataContinue =
                    response.continuationContents?.sectionListContinuation?.contents
                val list =
                    parseMixedContent(
                        dataContinue,
                        viewString,
                        songString,
                    )
                emit(Resource.Success(newContinueParam to list))
            }.onFailure {
                emit(Resource.Error<Pair<String?, List<HomeItem>>>(it.message.toString()))
            }
    }.flowOn(Dispatchers.IO)

    override fun getNewRelease(
        newReleaseString: String,
        musicVideoString: String,
    ): Flow<Resource<List<HomeItem>>> =
        flow {
            youTube.applyStoredLocale(dataStoreManager)
            youTube
                .newRelease()
                .onSuccess { result ->
                    emit(Resource.Success<List<HomeItem>>(parseNewRelease(result, newReleaseString, musicVideoString)))
                }.onFailure { error ->
                    emit(Resource.Error<List<HomeItem>>(error.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getChartData(countryCode: String): Flow<Resource<Chart>> =
        flow {
            youTube.applyStoredLocale(dataStoreManager)
            runCatching {
                youTube
                    .customQuery("FEmusic_charts", country = countryCode)
                    .onSuccess { result ->
                        val data =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                        val chart = parseChart(data)
                        if (chart != null) {
                            emit(Resource.Success<Chart>(chart))
                        } else {
                            emit(Resource.Error<Chart>("Error"))
                        }
                    }.onFailure { error ->
                        emit(Resource.Error<Chart>(error.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getMoodAndMomentsData(): Flow<Resource<Mood>> =
        flow {
            // Serve the cached copy first so the grid paints instantly; the network result is
            // emitted right after and overwrites it. The category list changes about as often as
            // YouTube ships a new mood, so a stale frame costs nothing while a spinner does.
            val cached =
                dataStoreManager.moodAndGenresCache
                    .first()
                    ?.let { runCatching { moodJson.decodeFromString<Mood>(it) }.getOrNull() }
            if (cached != null) {
                emit(Resource.Success<Mood>(cached))
            }
            youTube.applyStoredLocale(dataStoreManager)
            runCatching {
                youTube
                    .moodAndGenres()
                    .onSuccess { result ->
                        // Every section is kept, in the order YouTube sent it, under its own
                        // title. Indexing result[0]/result[1] used to break the moment a
                        // signed-in account got an extra "For you" section in front.
                        val sections =
                            result.map { section ->
                                MoodSection(
                                    title = section.title,
                                    items =
                                        section.items.map { item ->
                                            MoodItem(
                                                title = item.title,
                                                params = item.endpoint.params ?: "",
                                                stripeColor = item.stripeColor,
                                            )
                                        },
                                )
                            }
                        val mood = Mood(sections)
                        emit(Resource.Success<Mood>(mood))
                        dataStoreManager.setMoodAndGenresCache(moodJson.encodeToString(mood))
                    }.onFailure { e ->
                        // Already showing the cached copy — surfacing an error over it would
                        // replace working content with an error state.
                        if (cached == null) {
                            emit(Resource.Error<Mood>(e.message.toString()))
                        }
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getMoodCategoryArtwork(params: String): Flow<String?> =
        flow {
            val cache = readMoodArtworkCache()
            val hit = cache[params]
            if (hit != null && !hit.isStale()) {
                emit(hit.url)
                return@flow
            }
            val resolved =
                youTube
                    .customQuery(
                        browseId = "FEmusic_moods_and_genres_category",
                        params = params,
                    ).getOrNull()
                    ?.let { parseMoodsMomentObject(it) }
                    ?.items
                    ?.firstNotNullOfOrNull { section ->
                        section.contents
                            .firstNotNullOfOrNull { it.thumbnails?.lastOrNull()?.url }
                    }
            emit(resolved)
            if (resolved != null) {
                dataStoreManager.setMoodArtworkCache(
                    moodJson.encodeToString(
                        cache + (params to MoodArtwork(resolved, Clock.System.now().toEpochMilliseconds())),
                    ),
                )
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun readMoodArtworkCache(): Map<String, MoodArtwork> =
        dataStoreManager.moodArtworkCache
            .first()
            ?.let { runCatching { moodJson.decodeFromString<Map<String, MoodArtwork>>(it) }.getOrNull() }
            .orEmpty()

    override fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>> =
        flow {
            runCatching {
                youTube
                    .customQuery(
                        browseId = "FEmusic_moods_and_genres_category",
                        params = params,
                    ).onSuccess { result ->
                        val data = parseMoodsMomentObject(result)
                        if (data != null) {
                            emit(Resource.Success<MoodsMomentObject>(data))
                        } else {
                            emit(Resource.Error<MoodsMomentObject>("Error"))
                        }
                    }.onFailure { e ->
                        emit(Resource.Error<MoodsMomentObject>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getGenreData(params: String): Flow<Resource<GenreObject>> =
        flow {
            runCatching {
                youTube
                    .customQuery(
                        browseId = "FEmusic_moods_and_genres_category",
                        params = params,
                    ).onSuccess { result ->
                        val data = parseGenreObject(result)
                        if (data != null) {
                            emit(Resource.Success<GenreObject>(data))
                        } else {
                            emit(Resource.Error<GenreObject>("Error"))
                        }
                    }.onFailure { e ->
                        emit(Resource.Error<GenreObject>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getBrowsePage(
        browseId: String,
        params: String?,
        songString: String,
    ): Flow<Resource<BrowsePage>> =
        flow {
            youTube
                .browse(browseId = browseId, params = params, songString = songString)
                .onSuccess { result ->
                    emit(
                        Resource.Success(
                            BrowsePage(
                                title = result.title,
                                contents =
                                    result.items
                                        .flatMap { it.items }
                                        .distinctBy { it.id }
                                        .map { it.toHomeContent() },
                                moods =
                                    result.items.flatMap { it.moods }.map { item ->
                                        MoodItem(
                                            title = item.title,
                                            params = item.endpoint.params ?: "",
                                            stripeColor = item.stripeColor,
                                        )
                                    },
                            ),
                        ),
                    )
                }.onFailure { e ->
                    emit(Resource.Error<BrowsePage>(e.message.toString()))
                }
        }.flowOn(Dispatchers.IO)
}