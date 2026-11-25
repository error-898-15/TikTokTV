package com.tiktoktv.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import com.tiktoktv.app.TikTokTvApp
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.model.AppSettings
import com.tiktoktv.app.data.model.Author
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.data.model.Comment
import com.tiktoktv.app.data.model.HashtagResult
import com.tiktoktv.app.data.model.LiveStreamItem
import com.tiktoktv.app.data.model.SearchSuggestion
import com.tiktoktv.app.data.model.SearchTab
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.data.model.VideoQuality
import com.tiktoktv.app.util.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppScreen {
    SPLASH,
    HOME_FEED,
    CATEGORY_PICKER,
    PROFILE,
    SEARCH,
    SETTINGS
}

enum class FeedOverlayMode {
    NONE,
    TOP_BAR,
    BOTTOM_BAR,
    COMMENTS,
    MORE_MENU,
    LOGIN_MODAL
}

enum class ProfileTab {
    PROFILE,
    LIKED_VIDEOS,
    HISTORY,
    SETTINGS
}

data class UiState(
    val currentScreen: AppScreen = AppScreen.SPLASH,
    val overlayMode: FeedOverlayMode = FeedOverlayMode.NONE,
    val categories: List<Category> = ScraperConfig.DEFAULT_CATEGORIES,
    val activeCategory: Category = ScraperConfig.DEFAULT_CATEGORIES.first(),
    val feedVideos: List<VideoItem> = emptyList(),
    val currentIndex: Int = 0,
    val isFeedLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val feedError: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = true,
    // Comments
    val comments: List<Comment> = emptyList(),
    val isCommentsLoading: Boolean = false,
    val commentsError: String? = null,
    // Profile & History
    val profileTab: ProfileTab = ProfileTab.PROFILE,
    val profileAuthor: Author? = null,
    val profileVideos: List<VideoItem> = emptyList(),
    val isProfileLoading: Boolean = false,
    val isProfileLoadingMore: Boolean = false,
    val profileNextCursor: String? = null,
    val profileHasMore: Boolean = false,
    val profileError: String? = null,
    val isOwnProfile: Boolean = true,
    val likedVideos: List<VideoItem> = emptyList(),
    val savedVideos: List<VideoItem> = emptyList(),
    val watchHistory: List<VideoItem> = emptyList(),
    // Search & Live Suggestions + Tabs
    val searchQuery: String = "",
    val activeSearchTab: SearchTab = SearchTab.TOP,
    val searchSuggestions: List<SearchSuggestion> = emptyList(),
    val showSearchSuggestions: Boolean = false,
    val searchResults: List<VideoItem> = emptyList(),
    val searchUsers: List<Author> = emptyList(),
    val searchLiveStreams: List<LiveStreamItem> = emptyList(),
    val searchHashtags: List<HashtagResult> = emptyList(),
    val relatedQueries: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    // Settings & Cache
    val settings: AppSettings = AppSettings(),
    val cacheSizeText: String = "0 MB",
    val toastMessage: String? = null,
    val playPauseIndicatorVisible: Boolean = false,
    val playPauseIndicatorPlaying: Boolean = true
) {
    val currentVideo: VideoItem?
        get() = feedVideos.getOrNull(currentIndex)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TikTokTvApp
    private val repo = app.videoRepository
    private val prefs = app.prefsRepository
    val playerManager = app.playerManager
    private val prefetcher = app.videoPrefetcher

    private var suggestionJob: Job? = null
    private var feedLoadJob: Job? = null
    private var profileLoadJob: Job? = null

    private val _uiState = MutableStateFlow(
        UiState(
            settings = prefs.getSettings(),
            likedVideos = prefs.getLikedVideos(),
            savedVideos = prefs.getSavedVideos(),
            watchHistory = prefs.getWatchHistory()
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        val settings = prefs.getSettings()
        playerManager.setAutoScroll(settings.autoScroll)
        playerManager.setQuality(settings.videoQuality)
        playerManager.onVideoEndedCallback = {
            if (_uiState.value.currentScreen == AppScreen.HOME_FEED &&
                _uiState.value.overlayMode == FeedOverlayMode.NONE
            ) {
                nextVideo()
            }
        }
        loadCategoryFeed(ScraperConfig.DEFAULT_CATEGORIES.first(), forceRefresh = true)
        refreshCacheSize()
        refreshSuggestions("")
    }

    fun finishSplash() {
        _uiState.update { it.copy(currentScreen = AppScreen.HOME_FEED) }
        playCurrentVideoIfOnFeed()
    }

    fun setOverlayMode(mode: FeedOverlayMode) {
        _uiState.update { it.copy(overlayMode = mode) }
    }

    fun openCategoryScreen() {
        playerManager.pause()
        _uiState.update {
            it.copy(currentScreen = AppScreen.CATEGORY_PICKER, overlayMode = FeedOverlayMode.NONE)
        }
    }

    fun closeCategoryScreenWithoutChange() {
        _uiState.update {
            it.copy(currentScreen = AppScreen.HOME_FEED, overlayMode = FeedOverlayMode.NONE)
        }
        playCurrentVideoIfOnFeed()
    }

    fun selectCategory(category: Category) {
        playerManager.pause()
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.HOME_FEED,
                overlayMode = FeedOverlayMode.NONE,
                activeCategory = category,
                feedVideos = emptyList(),
                currentIndex = 0,
                nextCursor = null,
                hasMore = true,
                isFeedLoading = true,
                isLoadingMore = false,
                feedError = null
            )
        }
        loadCategoryFeed(category, forceRefresh = true)
    }

    fun loadCategoryFeed(category: Category, forceRefresh: Boolean = false) {
        feedLoadJob?.cancel()
        feedLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFeedLoading = true,
                    isLoadingMore = false,
                    feedError = null,
                    activeCategory = category
                )
            }
            val seenIds = prefs.getSeenVideoIds()
            val result = repo.getCategoryFeed(
                category = category,
                cursor = null,
                forceRefresh = forceRefresh,
                excludeIds = seenIds
            )
            if (_uiState.value.activeCategory.id != category.id) return@launch

            result.onSuccess { page ->
                val hydrated = hydrateLocalFlags(page.videos)
                hydrated.groupBy { it.author.username }.forEach { (uname, list) ->
                    prefs.recordAuthorVideos(uname, list)
                }
                _uiState.update { state ->
                    state.copy(
                        isFeedLoading = false,
                        feedVideos = hydrated,
                        currentIndex = 0,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        feedError = if (hydrated.isEmpty()) "No videos found for ${category.label}." else null
                    )
                }
                if (hydrated.isNotEmpty() && _uiState.value.currentScreen == AppScreen.HOME_FEED) {
                    playAndPrefetch(0)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isFeedLoading = false,
                        feedError = err.message ?: "Failed to load feed. Check your network connection."
                    )
                }
            }
        }
    }

    fun retryFeed() {
        loadCategoryFeed(_uiState.value.activeCategory, forceRefresh = true)
    }

    fun nextVideo() {
        val state = _uiState.value
        if (state.feedVideos.isEmpty()) return
        val next = state.currentIndex + 1
        if (next < state.feedVideos.size) {
            _uiState.update { it.copy(currentIndex = next) }
            playAndPrefetch(next)
            if (next >= state.feedVideos.size - 3 && state.hasMore && !state.isLoadingMore) {
                loadMoreFeed()
            }
        } else if (state.hasMore && !state.isLoadingMore) {
            loadMoreFeed(advanceAfterLoad = true)
        } else {
            showToast("You've reached the end of this feed")
        }
    }

    fun previousVideo() {
        val state = _uiState.value
        if (state.feedVideos.isEmpty()) return
        val prev = (state.currentIndex - 1).coerceAtLeast(0)
        if (prev != state.currentIndex) {
            _uiState.update { it.copy(currentIndex = prev) }
            playAndPrefetch(prev)
        }
    }

    private fun loadMoreFeed(advanceAfterLoad: Boolean = false) {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return
        val expectedCategoryId = state.activeCategory.id
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = repo.getCategoryFeed(
                category = state.activeCategory,
                cursor = state.nextCursor,
                forceRefresh = false,
                excludeIds = prefs.getSeenVideoIds()
            )
            if (_uiState.value.activeCategory.id != expectedCategoryId) return@launch

            result.onSuccess { page ->
                val existingIds = _uiState.value.feedVideos.map { it.id }.toSet()
                val newOnes = hydrateLocalFlags(page.videos).filterNot { existingIds.contains(it.id) }
                newOnes.groupBy { it.author.username }.forEach { (uname, list) ->
                    prefs.recordAuthorVideos(uname, list)
                }
                _uiState.update { current ->
                    val combined = current.feedVideos + newOnes
                    val targetIdx = if (advanceAfterLoad && newOnes.isNotEmpty()) {
                        (current.currentIndex + 1).coerceAtMost(combined.lastIndex)
                    } else {
                        current.currentIndex
                    }
                    current.copy(
                        feedVideos = combined,
                        currentIndex = targetIdx,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore && (newOnes.isNotEmpty() || page.nextCursor != null),
                        isLoadingMore = false
                    )
                }
                if (advanceAfterLoad && newOnes.isNotEmpty()) {
                    playAndPrefetch(_uiState.value.currentIndex)
                } else {
                    prefetchNextTwo(_uiState.value.currentIndex)
                }
            }.onFailure {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun togglePlayPause() {
        val nowPlaying = playerManager.togglePlayPause()
        viewModelScope.launch {
            _uiState.update {
                it.copy(playPauseIndicatorVisible = true, playPauseIndicatorPlaying = nowPlaying)
            }
            delay(900L)
            _uiState.update { it.copy(playPauseIndicatorVisible = false) }
        }
    }

    fun toggleLikeCurrentVideo() {
        val video = _uiState.value.currentVideo ?: return
        val nowLiked = prefs.toggleLike(video)
        _uiState.update { state ->
            val updatedList = state.feedVideos.map { item ->
                if (item.id == video.id) {
                    val delta = if (nowLiked) 1L else -1L
                    item.copy(
                        isLiked = nowLiked,
                        stats = item.stats.copy(likesCount = (item.stats.likesCount + delta).coerceAtLeast(0L))
                    )
                } else item
            }
            state.copy(feedVideos = updatedList, likedVideos = prefs.getLikedVideos())
        }
    }

    fun toggleSaveCurrentVideo() {
        val video = _uiState.value.currentVideo ?: return
        val nowSaved = prefs.toggleSave(video)
        _uiState.update { state ->
            val updatedList = state.feedVideos.map { item ->
                if (item.id == video.id) item.copy(isSaved = nowSaved) else item
            }
            state.copy(feedVideos = updatedList, savedVideos = prefs.getSavedVideos())
        }
        showToast(if (nowSaved) "Saved to bookmarks" else "Removed from saved")
    }

    fun copyCurrentVideoLink() {
        val video = _uiState.value.currentVideo ?: return
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TikTok Link", video.shareUrl))
        showToast("Link copied to clipboard")
    }

    fun markNotInterestedCurrentVideo() {
        val state = _uiState.value
        val video = state.currentVideo ?: return
        prefs.markNotInterested(video.id)
        val remaining = state.feedVideos.filterNot { it.id == video.id }
        val newIndex = state.currentIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
        _uiState.update {
            it.copy(feedVideos = remaining, currentIndex = newIndex, overlayMode = FeedOverlayMode.NONE)
        }
        showToast("We'll show fewer videos like this")
        if (remaining.isNotEmpty()) playAndPrefetch(newIndex) else retryFeed()
    }

    fun openComments() {
        val video = _uiState.value.currentVideo ?: return
        _uiState.update {
            it.copy(
                overlayMode = FeedOverlayMode.COMMENTS,
                isCommentsLoading = true,
                commentsError = null,
                comments = emptyList()
            )
        }
        viewModelScope.launch {
            val result = repo.getComments(video.id)
            result.onSuccess { list ->
                val likedIds = prefs.getLikedCommentIds()
                val hydrated = list.map { c ->
                    c.copy(
                        isLiked = likedIds.contains(c.id),
                        likesCount = if (likedIds.contains(c.id)) c.likesCount + 1 else c.likesCount
                    )
                }
                _uiState.update {
                    it.copy(
                        isCommentsLoading = false,
                        comments = hydrated,
                        commentsError = if (hydrated.isEmpty()) "No comments yet." else null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCommentsLoading = false,
                        commentsError = err.message ?: "Unable to load comments."
                    )
                }
            }
        }
    }

    fun toggleCommentLike(commentId: String) {
        val nowLiked = prefs.toggleCommentLike(commentId)
        _uiState.update { state ->
            state.copy(
                comments = state.comments.map { comment ->
                    if (comment.id == commentId) {
                        val delta = if (nowLiked) 1L else -1L
                        comment.copy(
                            isLiked = nowLiked,
                            likesCount = (comment.likesCount + delta).coerceAtLeast(0L)
                        )
                    } else comment
                }
            )
        }
    }

    fun openAuthorProfile(author: Author) {
        playerManager.pause()
        profileLoadJob?.cancel()
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.PROFILE,
                overlayMode = FeedOverlayMode.NONE,
                profileTab = ProfileTab.PROFILE,
                isOwnProfile = false,
                profileAuthor = author,
                profileVideos = emptyList(),
                profileNextCursor = null,
                profileHasMore = false,
                isProfileLoading = true,
                isProfileLoadingMore = false,
                profileError = null
            )
        }
        loadProfileData(author.username, forceRefresh = false)
    }

    fun openOwnProfile(initialTab: ProfileTab = ProfileTab.PROFILE) {
        playerManager.pause()
        profileLoadJob?.cancel()
        val saved = prefs.getSavedVideos()
        val liked = prefs.getLikedVideos()
        val history = prefs.getWatchHistory()
        val guestAuthor = Author(
            id = "guest_tv",
            username = "tv_viewer",
            displayName = "TV Viewer",
            avatarUrl = liked.firstOrNull()?.author?.avatarUrl.orEmpty(),
            bio = "Watching trending TikToks on Android TV",
            likesCount = liked.size.toLong()
        )
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.PROFILE,
                overlayMode = FeedOverlayMode.NONE,
                profileTab = initialTab,
                isOwnProfile = true,
                profileAuthor = guestAuthor,
                profileVideos = when (initialTab) {
                    ProfileTab.LIKED_VIDEOS -> liked
                    ProfileTab.HISTORY -> history
                    else -> saved
                },
                profileHasMore = false,
                isProfileLoading = false,
                isProfileLoadingMore = false,
                profileError = null,
                likedVideos = liked,
                savedVideos = saved,
                watchHistory = history
            )
        }
    }

    fun selectProfileTab(tab: ProfileTab) {
        _uiState.update { state ->
            val saved = prefs.getSavedVideos()
            val liked = prefs.getLikedVideos()
            val history = prefs.getWatchHistory()
            val updatedVideos = when {
                state.isOwnProfile && tab == ProfileTab.PROFILE -> saved
                state.isOwnProfile && tab == ProfileTab.LIKED_VIDEOS -> liked
                state.isOwnProfile && tab == ProfileTab.HISTORY -> history
                else -> state.profileVideos
            }
            state.copy(
                profileTab = tab,
                profileVideos = updatedVideos,
                likedVideos = liked,
                savedVideos = saved,
                watchHistory = history
            )
        }
    }

    fun retryProfile() {
        val username = _uiState.value.profileAuthor?.username ?: return
        loadProfileData(username, forceRefresh = true)
    }

    fun loadMoreProfileVideos() {
        val state = _uiState.value
        val username = state.profileAuthor?.username ?: return
        val cursor = state.profileNextCursor ?: return
        if (state.isProfileLoadingMore || !state.profileHasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isProfileLoadingMore = true) }
            val result = repo.getUserProfile(username, cursor = cursor, forceRefresh = false)
            result.onSuccess { res ->
                val cleanTarget = username.trim().removePrefix("@")
                val existingIds = _uiState.value.profileVideos.map { it.id }.toSet()
                val newOnes = hydrateLocalFlags(res.videos)
                    .filter { it.author.username.equals(cleanTarget, ignoreCase = true) || it.author.username.equals(res.author.username, ignoreCase = true) }
                    .filterNot { existingIds.contains(it.id) }
                prefs.recordAuthorVideos(res.author.username, newOnes)
                _uiState.update { current ->
                    current.copy(
                        isProfileLoadingMore = false,
                        profileVideos = current.profileVideos + newOnes,
                        profileNextCursor = res.nextCursor,
                        profileHasMore = res.hasMore && !res.nextCursor.isNullOrBlank()
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isProfileLoadingMore = false) }
            }
        }
    }

    private fun loadProfileData(username: String, forceRefresh: Boolean = false) {
        profileLoadJob?.cancel()
        profileLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isProfileLoading = true, profileError = null) }
            val result = repo.getUserProfile(username, forceRefresh = forceRefresh)
            val cleanTarget = username.trim().removePrefix("@")

            result.onSuccess { res ->
                val resolvedHandle = res.author.username.trim().removePrefix("@")
                val archivedVideos = (
                    prefs.getAuthorVideoHistory(cleanTarget) +
                        prefs.getAuthorVideoHistory(resolvedHandle)
                    )
                val knownLocalVideos = (
                    _uiState.value.feedVideos +
                        _uiState.value.searchResults +
                        _uiState.value.likedVideos +
                        _uiState.value.savedVideos +
                        _uiState.value.watchHistory +
                        archivedVideos
                    ).filter {
                        it.author.username.equals(cleanTarget, ignoreCase = true) ||
                            it.author.username.equals(resolvedHandle, ignoreCase = true)
                    }.distinctBy { it.id }

                val mergedVideos = (res.videos + knownLocalVideos).distinctBy { it.id }
                prefs.recordAuthorVideos(resolvedHandle.ifBlank { cleanTarget }, mergedVideos)
                val existingAuthor = _uiState.value.profileAuthor
                val mergedAuthor = res.author.copy(
                    avatarUrl = res.author.avatarUrl.ifBlank { existingAuthor?.avatarUrl.orEmpty() },
                    displayName = res.author.displayName.ifBlank { existingAuthor?.displayName ?: cleanTarget },
                    followersCount = maxOf(res.author.followersCount, existingAuthor?.followersCount ?: 0L),
                    followingCount = maxOf(res.author.followingCount, existingAuthor?.followingCount ?: 0L),
                    likesCount = maxOf(res.author.likesCount, existingAuthor?.likesCount ?: 0L),
                    videoCount = maxOf(res.author.videoCount, existingAuthor?.videoCount ?: 0L)
                )
                _uiState.update {
                    it.copy(
                        isProfileLoading = false,
                        profileAuthor = mergedAuthor,
                        profileVideos = hydrateLocalFlags(mergedVideos),
                        profileNextCursor = res.nextCursor,
                        profileHasMore = res.hasMore && !res.nextCursor.isNullOrBlank(),
                        profileError = null
                    )
                }
            }.onFailure { err ->
                val archivedVideos = prefs.getAuthorVideoHistory(cleanTarget)
                val knownLocalVideos = (
                    _uiState.value.feedVideos +
                        _uiState.value.searchResults +
                        _uiState.value.likedVideos +
                        _uiState.value.savedVideos +
                        _uiState.value.watchHistory +
                        archivedVideos
                    ).filter { it.author.username.equals(cleanTarget, ignoreCase = true) }
                    .distinctBy { it.id }
                val fallbackHydrated = hydrateLocalFlags(knownLocalVideos)
                _uiState.update {
                    it.copy(
                        isProfileLoading = false,
                        profileVideos = fallbackHydrated,
                        profileError = if (fallbackHydrated.isEmpty()) {
                            err.message ?: "Failed to load profile."
                        } else null
                    )
                }
            }
        }
    }

    fun playVideoFromGrid(videos: List<VideoItem>, selectedIndex: Int) {
        if (videos.isEmpty()) return
        val safeIdx = selectedIndex.coerceIn(0, videos.lastIndex)
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.HOME_FEED,
                overlayMode = FeedOverlayMode.NONE,
                feedVideos = hydrateLocalFlags(videos),
                currentIndex = safeIdx,
                feedError = null,
                hasMore = false
            )
        }
        playAndPrefetch(safeIdx)
    }

    fun openSearch() {
        playerManager.pause()
        refreshSuggestions(_uiState.value.searchQuery)
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.SEARCH,
                overlayMode = FeedOverlayMode.NONE,
                showSearchSuggestions = true
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, showSearchSuggestions = true) }
        refreshSuggestions(query)
    }

    fun selectSearchTab(tab: SearchTab) {
        _uiState.update { it.copy(activeSearchTab = tab) }
    }

    private fun refreshSuggestions(query: String) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            if (query.isNotBlank()) delay(220L)
            val list = repo.getSearchSuggestions(query)
            _uiState.update { it.copy(searchSuggestions = list) }
        }
    }

    fun submitSearch(query: String = _uiState.value.searchQuery) {
        val clean = query.trim()
        if (clean.isBlank()) return
        prefs.addSearchHistory(clean)
        _uiState.update {
            it.copy(
                searchQuery = clean,
                isSearching = true,
                showSearchSuggestions = false,
                searchError = null
            )
        }
        viewModelScope.launch {
            val result = repo.searchUnified(clean)
            result.onSuccess { unified ->
                val list = hydrateLocalFlags(unified.videos)
                list.groupBy { it.author.username }.forEach { (uname, vids) ->
                    prefs.recordAuthorVideos(uname, vids)
                }
                val isEmptyAll = list.isEmpty() && unified.users.isEmpty() && unified.liveStreams.isEmpty()
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = list,
                        searchUsers = unified.users,
                        searchLiveStreams = unified.liveStreams,
                        searchHashtags = unified.hashtags,
                        relatedQueries = unified.relatedQueries,
                        searchError = if (isEmptyAll) "No results matched \"$clean\"" else null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchError = err.message ?: "Search failed. Please try again."
                    )
                }
            }
        }
    }

    fun openSettingsScreen() {
        playerManager.pause()
        refreshCacheSize()
        _uiState.update {
            it.copy(currentScreen = AppScreen.SETTINGS, overlayMode = FeedOverlayMode.NONE)
        }
    }

    fun navigateBackToFeed() {
        _uiState.update {
            it.copy(currentScreen = AppScreen.HOME_FEED, overlayMode = FeedOverlayMode.NONE)
        }
        playCurrentVideoIfOnFeed()
    }

    fun toggleAutoScroll() {
        val next = !_uiState.value.settings.autoScroll
        val updated = _uiState.value.settings.copy(autoScroll = next)
        prefs.saveSettings(updated)
        playerManager.setAutoScroll(next)
        _uiState.update { it.copy(settings = updated) }
    }

    fun toggleAutoplay() {
        val next = !_uiState.value.settings.autoplay
        val updated = _uiState.value.settings.copy(autoplay = next)
        prefs.saveSettings(updated)
        _uiState.update { it.copy(settings = updated) }
    }

    fun cycleVideoQuality() {
        val values = VideoQuality.entries
        val currentIdx = values.indexOf(_uiState.value.settings.videoQuality)
        val nextQuality = values[(currentIdx + 1) % values.size]
        val updated = _uiState.value.settings.copy(videoQuality = nextQuality)
        prefs.saveSettings(updated)
        playerManager.setQuality(nextQuality)
        _uiState.update { it.copy(settings = updated) }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            val repoFreed = repo.clearCache()
            val videoFreed = prefetcher.getCacheSizeBytes()
            prefetcher.clearCache()
            runCatching {
                val loader = Coil.imageLoader(app)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            refreshCacheSize()
            showToast("Cleared ${formatBytes(repoFreed + videoFreed)} of cache")
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch {
            val total = repo.getCacheSizeBytes() + prefetcher.getCacheSizeBytes()
            _uiState.update { it.copy(cacheSizeText = formatBytes(total)) }
        }
    }

    private fun playCurrentVideoIfOnFeed() {
        if (_uiState.value.currentScreen == AppScreen.HOME_FEED && _uiState.value.feedVideos.isNotEmpty()) {
            playAndPrefetch(_uiState.value.currentIndex)
        }
    }

    private fun playAndPrefetch(index: Int) {
        val videos = _uiState.value.feedVideos
        val video = videos.getOrNull(index) ?: return
        prefs.markVideosSeen(listOf(video.id))
        prefs.recordWatchHistory(video)
        val quality = _uiState.value.settings.videoQuality
        playerManager.playVideo(video.resolveCandidateUrls(quality), autoplay = _uiState.value.settings.autoplay)
        prefetchNextTwo(index)
    }

    private fun prefetchNextTwo(index: Int) {
        val videos = _uiState.value.feedVideos
        val quality = _uiState.value.settings.videoQuality
        val nextUrls = listOfNotNull(
            videos.getOrNull(index + 1)?.resolveUrlForQuality(quality),
            videos.getOrNull(index + 2)?.resolveUrlForQuality(quality)
        )
        prefetcher.prefetchUrls(nextUrls)
    }

    private fun hydrateLocalFlags(list: List<VideoItem>): List<VideoItem> {
        val liked = prefs.getLikedVideoIds()
        val saved = prefs.getSavedVideoIds()
        val hidden = prefs.getNotInterestedIds()
        return list.filterNot { hidden.contains(it.id) }.map { item ->
            item.copy(isLiked = liked.contains(item.id), isSaved = saved.contains(item.id))
        }
    }

    fun showToast(message: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = message) }
            delay(2400L)
            _uiState.update { current ->
                if (current.toastMessage == message) current.copy(toastMessage = null) else current
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.pause()
    }
}
