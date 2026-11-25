package com.tiktoktv.app.data.repository

import com.tiktoktv.app.data.model.Author
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.data.model.Comment
import com.tiktoktv.app.data.model.FeedPage
import com.tiktoktv.app.data.model.HashtagsPage
import com.tiktoktv.app.data.model.SearchSuggestion
import com.tiktoktv.app.data.model.UnifiedSearchResult
import com.tiktoktv.app.data.model.UserProfileResult
import com.tiktoktv.app.data.model.UsersPage

interface VideoRepository {

    suspend fun getForYouFeed(
        cursor: String? = null,
        forceRefresh: Boolean = false,
        excludeIds: Set<String> = emptySet()
    ): Result<FeedPage>

    suspend fun getCategoryFeed(
        category: Category,
        cursor: String? = null,
        forceRefresh: Boolean = false,
        excludeIds: Set<String> = emptySet()
    ): Result<FeedPage>

    suspend fun searchVideos(
        query: String,
        cursor: String? = null
    ): Result<FeedPage>

    suspend fun searchUnified(
        query: String,
        cursor: String? = null
    ): Result<UnifiedSearchResult>

    suspend fun searchUsersPage(
        query: String,
        cursor: String? = null
    ): Result<UsersPage>

    suspend fun searchHashtagsPage(
        query: String,
        cursor: String? = null
    ): Result<HashtagsPage>

    suspend fun getSearchSuggestions(
        query: String
    ): List<SearchSuggestion>

    fun seedAuthorHint(author: Author) {}

    suspend fun getUserProfile(
        username: String,
        cursor: String? = null,
        forceRefresh: Boolean = false
    ): Result<UserProfileResult>

    suspend fun getComments(
        videoId: String,
        cursor: String? = null
    ): Result<List<Comment>>

    suspend fun clearFeedCache()

    suspend fun clearCache(): Long

    suspend fun getCacheSizeBytes(): Long
}
