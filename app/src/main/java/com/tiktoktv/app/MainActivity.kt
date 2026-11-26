package com.tiktoktv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.ui.AppScreen
import com.tiktoktv.app.ui.FeedOverlayMode
import com.tiktoktv.app.ui.FeedSourceMode
import com.tiktoktv.app.ui.MainViewModel
import com.tiktoktv.app.ui.screens.CategoryScreen
import com.tiktoktv.app.ui.screens.HomeFeedScreen
import com.tiktoktv.app.ui.screens.ProfileScreen
import com.tiktoktv.app.ui.screens.SearchScreen
import com.tiktoktv.app.ui.screens.SettingsScreen
import com.tiktoktv.app.ui.screens.SplashScreen
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.ui.theme.TikTokTvTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TikTokTvTheme {
                val uiState by viewModel.uiState.collectAsState()

                BackHandler(
                    enabled = uiState.currentScreen != AppScreen.HOME_FEED ||
                        uiState.overlayMode != FeedOverlayMode.NONE
                ) {
                    when {
                        uiState.currentScreen == AppScreen.CATEGORY_PICKER ->
                            viewModel.closeCategoryScreenWithoutChange()
                        uiState.currentScreen != AppScreen.HOME_FEED &&
                            uiState.currentScreen != AppScreen.SPLASH ->
                            viewModel.navigateBackToFeed()
                        uiState.overlayMode == FeedOverlayMode.COMMENTS ||
                            uiState.overlayMode == FeedOverlayMode.MORE_MENU ->
                            viewModel.setOverlayMode(FeedOverlayMode.BOTTOM_BAR)
                        uiState.overlayMode == FeedOverlayMode.LOGIN_MODAL ->
                            viewModel.setOverlayMode(FeedOverlayMode.TOP_BAR)
                        uiState.overlayMode != FeedOverlayMode.NONE ->
                            viewModel.setOverlayMode(FeedOverlayMode.NONE)
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(TikTokColors.Black)) {
                    when (uiState.currentScreen) {
                        AppScreen.SPLASH -> SplashScreen(onFinished = { viewModel.finishSplash() })
                        AppScreen.HOME_FEED -> HomeFeedScreen(
                            uiState = uiState,
                            playerManager = viewModel.playerManager,
                            onNextVideo = { viewModel.nextVideo() },
                            onPreviousVideo = { viewModel.previousVideo() },
                            onTogglePlayPause = { viewModel.togglePlayPause() },
                            onSetOverlayMode = { mode -> viewModel.setOverlayMode(mode) },
                            onOpenCategories = { viewModel.openCategoryScreen() },
                            onRefreshForYou = { viewModel.selectCategory(ScraperConfig.DEFAULT_CATEGORIES.first()) },
                            onOpenSearch = { viewModel.openSearch() },
                            onOpenOwnProfile = { viewModel.openOwnProfile() },
                            onOpenAuthorProfile = {
                                uiState.currentVideo?.author?.let { viewModel.openAuthorProfile(it) }
                            },
                            onToggleLike = { viewModel.toggleLikeCurrentVideo() },
                            onOpenComments = { viewModel.openComments() },
                            onToggleCommentLike = { id -> viewModel.toggleCommentLike(id) },
                            onToggleSave = { viewModel.toggleSaveCurrentVideo() },
                            onCopyLink = { viewModel.copyCurrentVideoLink() },
                            onNotInterested = { viewModel.markNotInterestedCurrentVideo() },
                            onOpenSettings = { viewModel.openSettingsScreen() },
                            onRetryFeed = { viewModel.retryFeed() }
                        )
                        AppScreen.CATEGORY_PICKER -> CategoryScreen(
                            categories = uiState.categories,
                            activeCategory = uiState.activeCategory,
                            fallbackBackdropUrl = uiState.currentVideo?.coverUrl.orEmpty(),
                            onSelectCategory = { cat -> viewModel.selectCategory(cat) },
                            onDismiss = { viewModel.closeCategoryScreenWithoutChange() }
                        )
                        AppScreen.PROFILE -> ProfileScreen(
                            uiState = uiState,
                            onSelectTab = { tab -> viewModel.selectProfileTab(tab) },
                            onPlayVideo = { list, idx -> viewModel.playVideoFromGrid(list, idx, FeedSourceMode.PROFILE) },
                            onRetryProfile = { viewModel.retryProfile() },
                            onLoadMoreProfile = { viewModel.loadMoreProfileVideos() },
                            onToggleAutoScroll = { viewModel.toggleAutoScroll() },
                            onToggleAutoplay = { viewModel.toggleAutoplay() },
                            onCycleQuality = { viewModel.cycleVideoQuality() },
                            onSelectRegion = { region -> viewModel.selectContentRegion(region) },
                            onClearCache = { viewModel.clearAllCache() },
                            onBack = { viewModel.navigateBackToFeed() },
                            onRefreshThumbnail = { videoId -> viewModel.refreshVideoThumbnail(videoId) }
                        )
                        AppScreen.SEARCH -> SearchScreen(
                            uiState = uiState,
                            onQueryChange = { q -> viewModel.updateSearchQuery(q) },
                            onSubmitSearch = { q -> viewModel.submitSearch(q) },
                            onSelectTab = { tab -> viewModel.selectSearchTab(tab) },
                            onOpenUserProfile = { author -> viewModel.openAuthorProfile(author) },
                            onPlayVideo = { list, idx -> viewModel.playVideoFromGrid(list, idx, FeedSourceMode.SEARCH) },
                            onLoadMoreResults = { viewModel.loadMoreSearchResults() },
                            onLoadMoreUsers = { viewModel.loadMoreSearchUsers() },
                            onLoadMoreHashtags = { viewModel.loadMoreSearchHashtags() },
                            onBack = { viewModel.navigateBackToFeed() }
                        )
                        AppScreen.SETTINGS -> SettingsScreen(
                            settings = uiState.settings,
                            cacheSizeText = uiState.cacheSizeText,
                            onToggleAutoScroll = { viewModel.toggleAutoScroll() },
                            onToggleAutoplay = { viewModel.toggleAutoplay() },
                            onCycleQuality = { viewModel.cycleVideoQuality() },
                            onSelectRegion = { region -> viewModel.selectContentRegion(region) },
                            onClearCache = { viewModel.clearAllCache() },
                            onBack = { viewModel.navigateBackToFeed() }
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.toastMessage != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xEE22222A))
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = uiState.toastMessage.orEmpty(),
                                color = TikTokColors.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.playerManager.pause()
    }

    override fun onResume() {
        super.onResume()
        val state = viewModel.uiState.value
        if (state.currentScreen == AppScreen.HOME_FEED && state.settings.autoplay) {
            viewModel.playerManager.resume()
        }
    }
}
