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
import com.tiktoktv.app.data.model.ContentRegion
import com.tiktoktv.app.data.model.HashtagResult
import com.tiktoktv.app.data.model.LiveStreamItem
import com.tiktoktv.app.data.model.SearchSuggestion
import com.tiktoktv.app.data.model.SearchTab
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.data.model.VideoQuality
import com.tiktoktv.app.util.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

enum class FeedSourceMode {
    CATEGORY,
    SEARCH,
    PROFILE,
    LOCAL
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
    val isSearchLoadingMore: Boolean = false,
    val searchNextCursor: String? = null,
    val searchHasMore: Boolean = false,
    val searchUsersNextCursor: String? = null,
    val searchUsersHasMore: Boolean = false,
    val isSearchUsersLoadingMore: Boolean = false,
    val searchHashtagsNextCursor: String? = null,
    val searchHashtagsHasMore: Boolean = false,
    val isSearchHashtagsLoadingMore: Boolean = false,
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

    private var feedSourceMode: FeedSourceMode = FeedSourceMode.CATEGORY
    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var feedLoadJob: Job? = null
    private var profileLoadJob: Job? = null
    private var profileAutoLoadJob: Job? = null
    private var streamRecoveryJob: Job? = null
    private var firstFrameWatchdogJob: Job? = null
    private val profilePageMutex = Mutex()
    private val thumbnailRefreshSemaphore = Semaphore(4)
    private val refreshedStreamIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val refreshedThumbnailIds = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var profileSessionToken: Long = 0L

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
        playerManager.onAllCandidatesFailedCallback = { _ ->
            attemptStreamRecoveryForCurrentVideo()
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
        profileAutoLoadJob?.cancel()
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
        feedSourceMode = FeedSourceMode.CATEGORY
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

        when (feedSourceMode) {
            FeedSourceMode.LOCAL -> return
            FeedSourceMode.SEARCH -> {
                val query = state.searchQuery.trim()
                val startCursor = state.nextCursor ?: state.searchNextCursor
                if (query.isBlank() || startCursor.isNullOrBlank()) return
                viewModelScope.launch {
                    _uiState.update { it.copy(isLoadingMore = true) }
                    val existingIds = _uiState.value.feedVideos.map { it.id }.toHashSet()
                    val collected = mutableListOf<VideoItem>()
                    var cursorNow: String? = startCursor
                    var hasNextNow = true
                    var attempts = 0
                    var failed = false

                    while (attempts < 4 && collected.size < 18 && hasNextNow && !cursorNow.isNullOrBlank()) {
                        attempts++
                        val res = repo.searchUnified(query, cursor = cursorNow)
                        if (feedSourceMode != FeedSourceMode.SEARCH || _uiState.value.searchQuery.trim() != query) {
                            _uiState.update { it.copy(isLoadingMore = false) }
                            return@launch
                        }
                        res.onSuccess { page ->
                            val fresh = hydrateLocalFlags(page.videos).filter {
                                existingIds.add(it.id)
                            }
                            collected.addAll(fresh)
                            val nextC = page.nextCursor
                            hasNextNow = page.hasMore && !nextC.isNullOrBlank() && nextC != cursorNow
                            cursorNow = if (hasNextNow) nextC else null
                        }.onFailure {
                            failed = true
                            hasNextNow = false
                        }
                        if (failed) break
                    }

                    collected.groupBy { it.author.username }.forEach { (uname, list) ->
                        prefs.recordAuthorVideos(uname, list)
                    }

                    _uiState.update { current ->
                        val combinedFeed = (current.feedVideos + collected).distinctBy { it.id }
                        val combinedSearch = (current.searchResults + collected).distinctBy { it.id }
                        val targetIdx = if (advanceAfterLoad && collected.isNotEmpty()) {
                            (current.currentIndex + 1).coerceAtMost(combinedFeed.lastIndex)
                        } else {
                            current.currentIndex
                        }
                        current.copy(
                            feedVideos = combinedFeed,
                            searchResults = combinedSearch,
                            currentIndex = targetIdx,
                            nextCursor = cursorNow,
                            hasMore = hasNextNow,
                            searchNextCursor = cursorNow,
                            searchHasMore = hasNextNow,
                            isLoadingMore = false
                        )
                    }
                    if (advanceAfterLoad && collected.isNotEmpty()) {
                        playAndPrefetch(_uiState.value.currentIndex)
                    } else {
                        prefetchNextTwo(_uiState.value.currentIndex)
                    }
                }
                return
            }
            FeedSourceMode.PROFILE -> {
                val username = state.profileAuthor?.username ?: return
                viewModelScope.launch {
                    val sessionToken = profileSessionToken
                    _uiState.update { it.copy(isLoadingMore = true) }
                    val step = loadMoreProfilePageInternal(username)
                    if (sessionToken != profileSessionToken || feedSourceMode != FeedSourceMode.PROFILE) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                        return@launch
                    }
                    val latest = _uiState.value
                    _uiState.update { current ->
                        val combinedFeed = (current.feedVideos + latest.profileVideos).distinctBy { it.id }
                        val newAdded = combinedFeed.size > current.feedVideos.size
                        val targetIdx = if (advanceAfterLoad && newAdded) {
                            (current.currentIndex + 1).coerceAtMost(combinedFeed.lastIndex)
                        } else {
                            current.currentIndex
                        }
                        current.copy(
                            feedVideos = combinedFeed,
                            currentIndex = targetIdx,
                            nextCursor = latest.profileNextCursor,
                            hasMore = step.hasMore && !latest.profileNextCursor.isNullOrBlank(),
                            isLoadingMore = false
                        )
                    }
                    if (advanceAfterLoad && _uiState.value.currentIndex < _uiState.value.feedVideos.size) {
                        playAndPrefetch(_uiState.value.currentIndex)
                    } else {
                        prefetchNextTwo(_uiState.value.currentIndex)
                    }
                }
                return
            }
            FeedSourceMode.CATEGORY -> {
                val expectedCategoryId = state.activeCategory.id
                viewModelScope.launch {
                    _uiState.update { it.copy(isLoadingMore = true) }
                    val result = repo.getCategoryFeed(
                        category = state.activeCategory,
                        cursor = state.nextCursor,
                        forceRefresh = false,
                        excludeIds = prefs.getSeenVideoIds()
                    )
                    if (feedSourceMode != FeedSourceMode.CATEGORY || _uiState.value.activeCategory.id != expectedCategoryId) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                        return@launch
                    }

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
        profileAutoLoadJob?.cancel()
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
        repo.seedAuthorHint(author)
        loadProfileData(author.username, forceRefresh = false)
    }

    fun openOwnProfile(initialTab: ProfileTab = ProfileTab.PROFILE) {
        playerManager.pause()
        profileSessionToken++
        profileLoadJob?.cancel()
        profileAutoLoadJob?.cancel()
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
        val author = _uiState.value.profileAuthor ?: return
        repo.seedAuthorHint(author)
        loadProfileData(author.username, forceRefresh = true)
    }

    fun loadMoreProfileVideos() {
        val state = _uiState.value
        val username = state.profileAuthor?.username ?: return
        if (state.isProfileLoadingMore || !state.profileHasMore || state.profileNextCursor.isNullOrBlank()) return
        viewModelScope.launch {
            loadMoreProfilePageInternal(username)
        }
    }

    private fun startProfileAutoLoader(targetUsername: String) {
        profileAutoLoadJob?.cancel()
        val cleanTarget = targetUsername.trim().removePrefix("@")
        if (cleanTarget.isBlank()) return
        val sessionToken = profileSessionToken

        profileAutoLoadJob = viewModelScope.launch {
            var zeroNewStreak = 0
            var consecutiveErrors = 0
            var steps = 0
            try {
                while (isActive && steps < 500 && sessionToken == profileSessionToken) {
                    val state = _uiState.value
                    val watchingSameProfileFeed = state.currentScreen == AppScreen.HOME_FEED &&
                        feedSourceMode == FeedSourceMode.PROFILE
                    if ((state.currentScreen != AppScreen.PROFILE && !watchingSameProfileFeed) ||
                        state.isOwnProfile ||
                        !state.profileHasMore ||
                        state.profileNextCursor.isNullOrBlank()
                    ) {
                        break
                    }
                    val activeUser = state.profileAuthor?.username?.trim()?.removePrefix("@").orEmpty()
                    if (!activeUser.equals(cleanTarget, ignoreCase = true)) {
                        break
                    }

                    val step = loadMoreProfilePageInternal(activeUser)
                    steps++
                    if (!step.advanced) {
                        if (!step.hasMore) break
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) {
                            break
                        }
                        delay(450L * consecutiveErrors)
                        continue
                    }
                    consecutiveErrors = 0
                    if (!step.hasMore) {
                        break
                    }
                    if (step.newCount == 0) {
                        zeroNewStreak++
                        if (zeroNewStreak >= 15) {
                            _uiState.update {
                                it.copy(
                                    isProfileLoadingMore = false,
                                    profileHasMore = false,
                                    profileNextCursor = null
                                )
                            }
                            break
                        }
                    } else {
                        zeroNewStreak = 0
                    }
                    delay(30L)
                }
            } finally {
                if (sessionToken == profileSessionToken) {
                    _uiState.update { it.copy(isProfileLoadingMore = false) }
                }
            }
        }
    }

    private data class ProfilePageStepResult(
        val advanced: Boolean,
        val newCount: Int,
        val hasMore: Boolean
    )

    private suspend fun loadMoreProfilePageInternal(username: String): ProfilePageStepResult {
        return profilePageMutex.withLock {
            val sessionToken = profileSessionToken
            val state = _uiState.value
            if (state.isOwnProfile || !state.profileHasMore) {
                return@withLock ProfilePageStepResult(false, 0, false)
            }
            val cursor = state.profileNextCursor ?: run {
                _uiState.update { it.copy(isProfileLoadingMore = false, profileHasMore = false) }
                return@withLock ProfilePageStepResult(false, 0, false)
            }

            _uiState.update { it.copy(isProfileLoadingMore = true) }
            var result = repo.getUserProfile(username, cursor = cursor, forceRefresh = false)
            if (result.isFailure) {
                delay(350L)
                result = repo.getUserProfile(username, cursor = cursor, forceRefresh = true)
            }
            if (sessionToken != profileSessionToken) {
                _uiState.update { it.copy(isProfileLoadingMore = false) }
                return@withLock ProfilePageStepResult(false, 0, false)
            }

            var stepResult = ProfilePageStepResult(false, 0, false)
            result.onSuccess { res ->
                val cleanTarget = username.trim().removePrefix("@")
                val activeAuthorNow = _uiState.value.profileAuthor?.username?.trim()?.removePrefix("@").orEmpty()
                if (!activeAuthorNow.equals(cleanTarget, ignoreCase = true)) {
                    _uiState.update { it.copy(isProfileLoadingMore = false) }
                    stepResult = ProfilePageStepResult(false, 0, false)
                    return@onSuccess
                }

                val existingIds = _uiState.value.profileVideos.map { it.id }.toSet()
                val activeSecUid = _uiState.value.profileAuthor?.secUid.orEmpty()
                val newOnes = hydrateLocalFlags(res.videos)
                    .filter {
                        it.author.username.equals(cleanTarget, ignoreCase = true) ||
                            it.author.username.equals(res.author.username, ignoreCase = true) ||
                            it.author.username.equals(activeAuthorNow, ignoreCase = true) ||
                            it.author.username == "creator" ||
                            it.author.username.isBlank() ||
                            (activeSecUid.isNotBlank() && it.author.secUid == activeSecUid)
                    }
                    .filterNot { existingIds.contains(it.id) }
                    .distinctBy { it.id }

                if (newOnes.isNotEmpty()) {
                    prefs.recordAuthorVideos(res.author.username.ifBlank { cleanTarget }, newOnes)
                }

                val nextCursorValid = res.hasMore && !res.nextCursor.isNullOrBlank() && res.nextCursor != cursor
                _uiState.update { current ->
                    val curAuthor = current.profileAuthor
                    val updatedAuthor = if (curAuthor != null) {
                        curAuthor.copy(
                            videoCount = maxOf(curAuthor.videoCount, res.author.videoCount),
                            followersCount = maxOf(curAuthor.followersCount, res.author.followersCount),
                            likesCount = maxOf(curAuthor.likesCount, res.author.likesCount),
                            avatarUrl = curAuthor.avatarUrl.ifBlank { res.author.avatarUrl }
                        )
                    } else res.author
                    val updatedProfileVideos = (current.profileVideos + newOnes).distinctBy { it.id }
                    val updatedFeedVideos = if (feedSourceMode == FeedSourceMode.PROFILE &&
                        current.currentScreen == AppScreen.HOME_FEED
                    ) {
                        (current.feedVideos + newOnes).distinctBy { it.id }
                    } else current.feedVideos
                    current.copy(
                        isProfileLoadingMore = false,
                        profileAuthor = updatedAuthor,
                        profileVideos = updatedProfileVideos,
                        feedVideos = updatedFeedVideos,
                        nextCursor = if (feedSourceMode == FeedSourceMode.PROFILE && current.currentScreen == AppScreen.HOME_FEED) {
                            if (nextCursorValid) res.nextCursor else null
                        } else current.nextCursor,
                        hasMore = if (feedSourceMode == FeedSourceMode.PROFILE && current.currentScreen == AppScreen.HOME_FEED) {
                            nextCursorValid
                        } else current.hasMore,
                        profileNextCursor = if (nextCursorValid) res.nextCursor else null,
                        profileHasMore = nextCursorValid
                    )
                }
                stepResult = ProfilePageStepResult(
                    advanced = true,
                    newCount = newOnes.size,
                    hasMore = nextCursorValid
                )
            }.onFailure { err ->
                var keepMore = false
                _uiState.update { current ->
                    keepMore = current.profileVideos.isNotEmpty() && !current.profileNextCursor.isNullOrBlank()
                    current.copy(
                        isProfileLoadingMore = false,
                        profileHasMore = keepMore,
                        profileNextCursor = if (keepMore) current.profileNextCursor else null,
                        profileError = if (current.profileVideos.isEmpty()) {
                            err.message ?: "Failed to load profile."
                        } else current.profileError
                    )
                }
                stepResult = ProfilePageStepResult(false, 0, keepMore)
            }
            stepResult
        }
    }

    private fun loadProfileData(username: String, forceRefresh: Boolean = false) {
        val sessionToken = ++profileSessionToken
        profileLoadJob?.cancel()
        profileAutoLoadJob?.cancel()
        profileLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isProfileLoading = true, isProfileLoadingMore = false, profileError = null) }
            var result = repo.getUserProfile(username, forceRefresh = forceRefresh)
            val cleanTarget = username.trim().removePrefix("@")

            val firstProf = result.getOrNull()
            val needsForcedRetry = !forceRefresh && (
                firstProf == null ||
                    firstProf.videos.isEmpty()
                )
            if (needsForcedRetry) {
                val retryResult = repo.getUserProfile(username, forceRefresh = true)
                val retryProf = retryResult.getOrNull()
                if (retryResult.isSuccess && retryProf != null && (
                        retryProf.videos.size > (firstProf?.videos?.size ?: 0) ||
                            (retryProf.hasMore && firstProf?.hasMore != true)
                        )
                ) {
                    result = retryResult
                }
            }
            if (sessionToken != profileSessionToken) return@launch

            result.onSuccess { res ->
                val resolvedHandle = res.author.username.trim().removePrefix("@")
                val archivedVideos = (
                    prefs.getAuthorVideoHistory(cleanTarget) +
                        prefs.getAuthorVideoHistory(resolvedHandle)
                    )
                val freshSessionVideos = (
                    _uiState.value.feedVideos +
                        _uiState.value.searchResults
                    ).filter {
                        it.author.username.equals(cleanTarget, ignoreCase = true) ||
                            it.author.username.equals(resolvedHandle, ignoreCase = true)
                    }
                val archivedFreshVideos = (
                    _uiState.value.likedVideos +
                        _uiState.value.savedVideos +
                        _uiState.value.watchHistory +
                        archivedVideos
                    ).filter {
                        (it.author.username.equals(cleanTarget, ignoreCase = true) ||
                            it.author.username.equals(resolvedHandle, ignoreCase = true)) &&
                            !isCdnUrlExpired(it.coverUrl) &&
                            !isCdnUrlExpired(it.videoUrl)
                    }
                val knownLocalVideos = (freshSessionVideos + archivedFreshVideos).distinctBy { it.id }

                val mergedVideos = if (res.videos.isNotEmpty()) {
                    (res.videos + knownLocalVideos).distinctBy { it.id }
                } else {
                    (knownLocalVideos + archivedVideos.filter {
                        it.author.username.equals(cleanTarget, ignoreCase = true) ||
                            it.author.username.equals(resolvedHandle, ignoreCase = true)
                    }).distinctBy { it.id }
                }
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
                val hasMorePages = res.hasMore && !res.nextCursor.isNullOrBlank()
                _uiState.update {
                    it.copy(
                        isProfileLoading = false,
                        isProfileLoadingMore = hasMorePages && mergedVideos.isEmpty(),
                        profileAuthor = mergedAuthor,
                        profileVideos = hydrateLocalFlags(mergedVideos),
                        profileNextCursor = if (hasMorePages) res.nextCursor else null,
                        profileHasMore = hasMorePages,
                        profileError = null
                    )
                }
                if (hasMorePages && !_uiState.value.isOwnProfile) {
                    startProfileAutoLoader(mergedAuthor.username.ifBlank { cleanTarget })
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
                        isProfileLoadingMore = false,
                        profileHasMore = false,
                        profileNextCursor = null,
                        profileVideos = fallbackHydrated,
                        profileError = if (fallbackHydrated.isEmpty()) {
                            err.message ?: "Failed to load profile."
                        } else null
                    )
                }
            }
        }
    }

    fun playVideoFromGrid(
        videos: List<VideoItem>,
        selectedIndex: Int,
        source: FeedSourceMode = FeedSourceMode.LOCAL
    ) {
        if (videos.isEmpty()) return
        val safeIdx = selectedIndex.coerceIn(0, videos.lastIndex)
        feedSourceMode = source
        _uiState.update { state ->
            val (cursorForPlayer, hasMoreForPlayer) = when (source) {
                FeedSourceMode.SEARCH -> state.searchNextCursor to (state.searchHasMore && !state.searchNextCursor.isNullOrBlank())
                FeedSourceMode.PROFILE -> if (!state.isOwnProfile) {
                    state.profileNextCursor to (state.profileHasMore && !state.profileNextCursor.isNullOrBlank())
                } else null to false
                else -> null to false
            }
            state.copy(
                currentScreen = AppScreen.HOME_FEED,
                overlayMode = FeedOverlayMode.NONE,
                feedVideos = hydrateLocalFlags(videos),
                currentIndex = safeIdx,
                feedError = null,
                nextCursor = cursorForPlayer,
                hasMore = hasMoreForPlayer,
                isLoadingMore = false
            )
        }
        playAndPrefetch(safeIdx)
    }

    fun openSearch() {
        playerManager.pause()
        profileAutoLoadJob?.cancel()
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
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = clean,
                isSearching = true,
                isSearchLoadingMore = false,
                isSearchUsersLoadingMore = false,
                isSearchHashtagsLoadingMore = false,
                searchNextCursor = null,
                searchHasMore = false,
                searchUsersNextCursor = null,
                searchUsersHasMore = false,
                searchHashtagsNextCursor = null,
                searchHashtagsHasMore = false,
                showSearchSuggestions = false,
                searchError = null
            )
        }
        searchJob = viewModelScope.launch {
            val result = repo.searchUnified(clean)
            if (_uiState.value.searchQuery != clean) return@launch
            result.onSuccess { unified ->
                val combinedVideos = LinkedHashMap<String, VideoItem>()
                hydrateLocalFlags(unified.videos).forEach { combinedVideos[it.id] = it }
                var nextCursorNow = unified.nextCursor
                var hasMoreNow = unified.hasMore && !nextCursorNow.isNullOrBlank()
                var autoAttempts = 0
                while (combinedVideos.size < 15 && hasMoreNow && !nextCursorNow.isNullOrBlank() && autoAttempts < 2) {
                    autoAttempts++
                    val moreRes = repo.searchUnified(clean, cursor = nextCursorNow)
                    if (_uiState.value.searchQuery != clean) return@launch
                    val morePage = moreRes.getOrNull() ?: break
                    hydrateLocalFlags(morePage.videos).forEach { combinedVideos.putIfAbsent(it.id, it) }
                    val nextC = morePage.nextCursor
                    hasMoreNow = morePage.hasMore && !nextC.isNullOrBlank() && nextC != nextCursorNow
                    nextCursorNow = if (hasMoreNow) nextC else null
                }

                val list = combinedVideos.values.toList()
                list.groupBy { it.author.username }.forEach { (uname, vids) ->
                    prefs.recordAuthorVideos(uname, vids)
                }
                val isEmptyAll = list.isEmpty() &&
                    unified.users.isEmpty() &&
                    unified.liveStreams.isEmpty() &&
                    unified.hashtags.isEmpty()
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = list,
                        searchUsers = unified.users,
                        searchLiveStreams = unified.liveStreams,
                        searchHashtags = unified.hashtags,
                        relatedQueries = unified.relatedQueries,
                        searchNextCursor = nextCursorNow,
                        searchHasMore = hasMoreNow,
                        searchUsersNextCursor = unified.usersNextCursor,
                        searchUsersHasMore = unified.usersHasMore && !unified.usersNextCursor.isNullOrBlank(),
                        searchHashtagsNextCursor = unified.hashtagsNextCursor,
                        searchHashtagsHasMore = unified.hashtagsHasMore && !unified.hashtagsNextCursor.isNullOrBlank(),
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

    fun loadMoreSearchResults() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        val startCursor = state.searchNextCursor
        if (query.isBlank() || startCursor.isNullOrBlank() || !state.searchHasMore || state.isSearchLoadingMore || state.isSearching) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchLoadingMore = true) }
            val existingIds = _uiState.value.searchResults.map { it.id }.toHashSet()
            val aggregated = mutableListOf<VideoItem>()
            var cursorNow: String? = startCursor
            var hasNextNow = true
            var attempts = 0
            var failed = false

            while (attempts < 4 && aggregated.size < 18 && hasNextNow && !cursorNow.isNullOrBlank()) {
                attempts++
                val result = repo.searchUnified(query, cursor = cursorNow)
                if (_uiState.value.searchQuery.trim() != query) {
                    _uiState.update { it.copy(isSearchLoadingMore = false) }
                    return@launch
                }
                result.onSuccess { page ->
                    val fresh = hydrateLocalFlags(page.videos).filter { existingIds.add(it.id) }
                    aggregated.addAll(fresh)
                    val nextC = page.nextCursor
                    hasNextNow = page.hasMore && !nextC.isNullOrBlank() && nextC != cursorNow
                    cursorNow = if (hasNextNow) nextC else null
                }.onFailure {
                    failed = true
                    hasNextNow = false
                }
                if (failed) break
            }

            aggregated.groupBy { it.author.username }.forEach { (uname, vids) ->
                prefs.recordAuthorVideos(uname, vids)
            }
            _uiState.update { current ->
                current.copy(
                    searchResults = (current.searchResults + aggregated).distinctBy { it.id },
                    searchNextCursor = cursorNow,
                    searchHasMore = hasNextNow,
                    isSearchLoadingMore = false
                )
            }
        }
    }

    fun loadMoreSearchUsers() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        val cursor = state.searchUsersNextCursor
        if (query.isBlank() || cursor.isNullOrBlank() || !state.searchUsersHasMore || state.isSearchUsersLoadingMore) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchUsersLoadingMore = true) }
            val result = repo.searchUsersPage(query, cursor = cursor)
            if (_uiState.value.searchQuery.trim() != query) return@launch
            result.onSuccess { page ->
                val existing = _uiState.value.searchUsers.map { it.username.lowercase() }.toHashSet()
                val incoming = page.users.filterNot { existing.contains(it.username.lowercase()) }
                val hasNext = page.hasMore && !page.nextCursor.isNullOrBlank()
                _uiState.update { current ->
                    current.copy(
                        searchUsers = current.searchUsers + incoming,
                        searchUsersNextCursor = if (hasNext) page.nextCursor else null,
                        searchUsersHasMore = hasNext,
                        isSearchUsersLoadingMore = false
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isSearchUsersLoadingMore = false) }
            }
        }
    }

    fun loadMoreSearchHashtags() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        val cursor = state.searchHashtagsNextCursor
        if (query.isBlank() || cursor.isNullOrBlank() || !state.searchHashtagsHasMore || state.isSearchHashtagsLoadingMore) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchHashtagsLoadingMore = true) }
            val result = repo.searchHashtagsPage(query, cursor = cursor)
            if (_uiState.value.searchQuery.trim() != query) return@launch
            result.onSuccess { page ->
                val existing = _uiState.value.searchHashtags.map { it.tag.lowercase() }.toHashSet()
                val incoming = page.hashtags.filterNot { existing.contains(it.tag.lowercase()) }
                val hasNext = page.hasMore && !page.nextCursor.isNullOrBlank()
                _uiState.update { current ->
                    current.copy(
                        searchHashtags = current.searchHashtags + incoming,
                        searchHashtagsNextCursor = if (hasNext) page.nextCursor else null,
                        searchHashtagsHasMore = hasNext,
                        isSearchHashtagsLoadingMore = false
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isSearchHashtagsLoadingMore = false) }
            }
        }
    }

    fun openSettingsScreen() {
        playerManager.pause()
        profileAutoLoadJob?.cancel()
        refreshCacheSize()
        _uiState.update {
            it.copy(currentScreen = AppScreen.SETTINGS, overlayMode = FeedOverlayMode.NONE)
        }
    }

    fun navigateBackToFeed() {
        profileAutoLoadJob?.cancel()
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

    fun selectContentRegion(region: ContentRegion) {
        val prev = _uiState.value.settings.contentRegion
        if (prev == region) return
        val updated = _uiState.value.settings.copy(contentRegion = region)
        prefs.saveSettings(updated)
        _uiState.update { it.copy(settings = updated) }
        showToast("Region set to ${region.shortLabel}")
        viewModelScope.launch {
            repo.clearFeedCache()
            val currentCat = _uiState.value.activeCategory
            if (currentCat.id == "for_you") {
                loadCategoryFeed(currentCat, forceRefresh = true)
            }
        }
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
        firstFrameWatchdogJob?.cancel()
        streamRecoveryJob?.cancel()
        val videos = _uiState.value.feedVideos
        val video = videos.getOrNull(index) ?: return
        prefs.markVideosSeen(listOf(video.id))
        prefs.recordWatchHistory(video)
        val quality = _uiState.value.settings.videoQuality
        val autoplay = _uiState.value.settings.autoplay
        if (video.isPhotoPost) {
            playerManager.playPhotoAudio(video.audioUrl, autoplay = autoplay)
        } else {
            val urls = video.resolveCandidateUrls(quality)
            val allExpired = urls.isNotEmpty() && urls.all { isCdnUrlExpired(it) }
            if (urls.isEmpty() || allExpired) {
                playerManager.playVideo(urls, autoplay = autoplay, forceReload = true)
                attemptStreamRecoveryForCurrentVideo()
            } else {
                playerManager.playVideo(urls, autoplay = autoplay)
                if (autoplay) {
                    startFirstFrameWatchdog(video.id)
                }
            }
            prefetchNextTwo(index)
        }
    }

    private fun startFirstFrameWatchdog(videoId: String) {
        firstFrameWatchdogJob?.cancel()
        firstFrameWatchdogJob = viewModelScope.launch {
            delay(6_500L)
            val state = _uiState.value
            if (state.currentScreen == AppScreen.HOME_FEED &&
                state.currentVideo?.id == videoId &&
                !playerManager.hasRenderedFirstFrame.value &&
                playerManager.playbackError.value == null
            ) {
                attemptStreamRecoveryForCurrentVideo()
            }
        }
    }

    private fun attemptStreamRecoveryForCurrentVideo(): Boolean {
        val video = _uiState.value.currentVideo ?: return false
        if (!refreshedStreamIds.add(video.id)) return false
        streamRecoveryJob?.cancel()
        streamRecoveryJob = viewModelScope.launch {
            val fresh = repo.refreshVideoDetails(video)
            val current = _uiState.value.currentVideo
            if (fresh != null && current?.id == video.id) {
                updateVideoEverywhere(fresh)
                val quality = _uiState.value.settings.videoQuality
                val urls = fresh.resolveCandidateUrls(quality)
                if (urls.isNotEmpty()) {
                    playerManager.playVideo(urls, autoplay = true, forceReload = true)
                } else {
                    playerManager.reportPlaybackError("Video stream unavailable — press RIGHT for next")
                }
            } else if (current?.id == video.id) {
                playerManager.reportPlaybackError("Video stream unavailable — press RIGHT for next")
            }
        }
        return true
    }

    fun refreshVideoThumbnail(videoId: String) {
        if (videoId.isBlank() || !refreshedThumbnailIds.add(videoId)) return
        val state = _uiState.value
        val target = state.profileVideos.firstOrNull { it.id == videoId }
            ?: state.feedVideos.firstOrNull { it.id == videoId }
            ?: state.searchResults.firstOrNull { it.id == videoId }
            ?: return
        viewModelScope.launch {
            thumbnailRefreshSemaphore.withPermit {
                val fresh = repo.refreshVideoDetails(target) ?: return@withPermit
                updateVideoEverywhere(fresh)
            }
        }
    }

    private fun updateVideoEverywhere(fresh: VideoItem) {
        val liked = prefs.getLikedVideoIds().contains(fresh.id)
        val saved = prefs.getSavedVideoIds().contains(fresh.id)
        val hydratedFresh = fresh.copy(isLiked = liked, isSaved = saved)
        _uiState.update { state ->
            state.copy(
                profileVideos = state.profileVideos.map { if (it.id == fresh.id) hydratedFresh else it },
                feedVideos = state.feedVideos.map { if (it.id == fresh.id) hydratedFresh else it },
                searchResults = state.searchResults.map { if (it.id == fresh.id) hydratedFresh else it }
            )
        }
    }

    private fun isCdnUrlExpired(url: String): Boolean {
        if (url.isBlank()) return false
        val idx = url.indexOf("x-expires=", ignoreCase = true)
        if (idx < 0) return false
        val start = idx + "x-expires=".length
        val end = url.indexOf('&', start).let { if (it < 0) url.length else it }
        val expiresSec = url.substring(start, end).toLongOrNull() ?: return false
        if (expiresSec <= 0L) return false
        return (System.currentTimeMillis() / 1000L) >= (expiresSec - 90L)
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
        profileAutoLoadJob?.cancel()
        streamRecoveryJob?.cancel()
        firstFrameWatchdogJob?.cancel()
        playerManager.pause()
    }
}
