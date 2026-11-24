package com.tiktoktv.app.data.repository

import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.data.model.Comment
import com.tiktoktv.app.data.model.FeedPage
import com.tiktoktv.app.data.model.UserProfileResult

interface VideoRepository {

    suspend fun getForYouFeed(
        cursor: String? = null,
        forceRefresh: Boolean = false
    ): Result<FeedPage>

    suspend fun getCategoryFeed(
        category: Category,
        cursor: String? = null,
        forceRefresh: Boolean = false
    ): Result<FeedPage>

    suspend fun searchVideos(
        query: String,
        cursor: String? = null
    ): Result<FeedPage>

    suspend fun getUserProfile(
        username: String,
        cursor: String? = null,
        forceRefresh: Boolean = false
    ): Result<UserProfileResult>

    suspend fun getComments(
        videoId: String,
        cursor: String? = null
    ): Result<List<Comment>>

    suspend fun clearCache(): Long

    suspend fun getCacheSizeBytes(): Long
}
