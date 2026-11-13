package com.maxrave.domain.repository

import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.data.model.library.LibraryOverview
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow

interface CommonRepository {
    fun init(cookiePath: String, dataStoreManager: DataStoreManager)

    // Database
    fun closeDatabase()

    fun getDatabasePath(): String?

    suspend fun databaseDaoCheckpoint()

    // Recently data
    fun getAllRecentData(): Flow<List<RecentlyType>>

    /** Counts and newest artwork for the Your library cards; re-emits whenever any of them changes. */
    fun getLibraryOverview(): Flow<LibraryOverview>

    suspend fun writeTextToFile(text: String, filePath: String): Boolean

    suspend fun getCookiesFromInternalDatabase(url: String, packageName: String): CookieItem
}