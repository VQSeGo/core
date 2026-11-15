package com.maxrave.data.repository

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.YouTubeLocale
import kotlinx.coroutines.flow.first

/** The client's region and language from the stored settings: two-letter language, English when unset. */
internal fun youTubeLocale(
    location: String,
    language: String,
) = YouTubeLocale(
    location,
    if (language.length >= 2) language.substring(0, 2) else "en",
)

/**
 * Puts the stored region and language on the client right before a request that depends on them.
 *
 * CommonRepositoryImpl keeps the client in step with the settings, but from its own subscription:
 * a screen that reloads on the same change (Home on a new region) used to fetch before that
 * subscription ran, got the previous region's page and cached it under the new region's key.
 */
internal suspend fun YouTube.applyStoredLocale(dataStoreManager: DataStoreManager) {
    locale = youTubeLocale(dataStoreManager.location.first(), dataStoreManager.language.first())
}
