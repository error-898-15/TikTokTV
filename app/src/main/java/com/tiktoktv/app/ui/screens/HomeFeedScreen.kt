package com.tiktoktv.app.ui.screens

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tiktoktv.app.player.VideoPlayerManager
import com.tiktoktv.app.ui.FeedOverlayMode
import com.tiktoktv.app.ui.UiState
import com.tiktoktv.app.ui.components.BlurredVideoBackdrop
import com.tiktoktv.app.ui.components.BottomActionBar
import com.tiktoktv.app.ui.components.CommentsPanel
import com.tiktoktv.app.ui.components.ErrorScreen
import com.tiktoktv.app.ui.components.MoreMenuDialog
import com.tiktoktv.app.ui.components.TopNavBar
import com.tiktoktv.app.ui.components.VideoMetadataOverlay
import com.tiktoktv.app.ui.theme.TikTokColors

@OptIn(UnstableApi::class)
@Composable
fun HomeFeedScreen(
    uiState: UiState,
    playerManager: VideoPlayerManager,
    onNextVideo: () -> Unit,
    onPreviousVideo: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSetOverlayMode: (FeedOverlayMode) -> Unit,
    onOpenCategories: () -> Unit,
    onRefreshForYou: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenOwnProfile: () -> Unit,
    onOpenAuthorProfile: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenComments: () -> Unit,
    onToggleCommentLike: (String) -> Unit,
    onToggleSave: () -> Unit,
    onCopyLink: () -> Unit,
    onNotInterested: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryFeed: () -> Unit
) {
    val isBuffering by playerManager.isBuffering.collectAsState()
    val backdropFailed by playerManager.backdropFailed.collectAsState()

    val playerFocusRequester = remember { FocusRequester() }
    val commentsButtonFocus = remember { FocusRequester() }

    LaunchedEffect(uiState.overlayMode, uiState.feedVideos.isNotEmpty()) {
        if (uiState.overlayMode == FeedOverlayMode.NONE && uiState.feedVideos.isNotEmpty()) {
            runCatching { playerFocusRequester.requestFocus() }
        }
    }

    when {
        uiState.isFeedLoading && uiState.feedVideos.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TikTokColors.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TikTokColors.Pink)
            }
        }
        uiState.feedError != null && uiState.feedVideos.isEmpty() -> {
            ErrorScreen(
                message = uiState.feedError,
                onRetry = onRetryFeed
            )
        }
        else -> {
            val video = uiState.currentVideo ?: return

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TikTokColors.Black)
            ) {
                BlurredVideoBackdrop(
                    coverUrl = video.coverUrl,
                    backdropPlayer = playerManager.backdropPlayer,
                    backdropFailed = backdropFailed
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(playerFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (uiState.overlayMode != FeedOverlayMode.NONE) {
                                return@onPreviewKeyEvent false
                            }
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                            when (event.key) {
                                Key.DirectionLeft -> {
                                    onPreviousVideo()
                                    true
                                }
                                Key.DirectionRight -> {
                                    onNextVideo()
                                    true
                                }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> {
                                    onTogglePlayPause()
                                    true
                                }
                                Key.DirectionDown -> {
                                    onSetOverlayMode(FeedOverlayMode.BOTTOM_BAR)
                                    true
                                }
                                Key.DirectionUp -> {
                                    onSetOverlayMode(FeedOverlayMode.TOP_BAR)
                                    true
                                }
                                else -> false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(9f / 16f)
                            .background(TikTokColors.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    player = playerManager.mainPlayer
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                }
                            },
                            update = { view ->
                                if (view.player !== playerManager.mainPlayer) {
                                    view.player = playerManager.mainPlayer
                                }
                            }
                        )

                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = TikTokColors.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(42.dp)
                            )
                        }

                        AnimatedVisibility(
                            visible = uiState.playPauseIndicatorVisible,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.58f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (uiState.playPauseIndicatorPlaying) {
                                        Icons.Filled.PlayArrow
                                    } else {
                                        Icons.Filled.Pause
                                    },
                                    contentDescription = null,
                                    tint = TikTokColors.White,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }
                    }
                }

                VideoMetadataOverlay(
                    video = video,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 36.dp, bottom = 36.dp)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 32.dp, bottom = 36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.feedVideos.size}",
                        color = TikTokColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                AnimatedVisibility(
                    visible = uiState.overlayMode == FeedOverlayMode.TOP_BAR,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    TopNavBar(
                        activeCategory = uiState.activeCategory,
                        onCategoryPillClick = onOpenCategories,
                        onHomeClick = onRefreshForYou,
                        onSearchClick = onOpenSearch,
                        onProfileClick = onOpenOwnProfile,
                        onLoginClick = { onSetOverlayMode(FeedOverlayMode.LOGIN_MODAL) },
                        onUpToCategories = onOpenCategories,
                        onDismissDown = { onSetOverlayMode(FeedOverlayMode.NONE) }
                    )
                }

                AnimatedVisibility(
                    visible = uiState.overlayMode == FeedOverlayMode.BOTTOM_BAR,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    BottomActionBar(
                        video = video,
                        initialFocusRequester = commentsButtonFocus,
                        onAvatarClick = onOpenAuthorProfile,
                        onLikeClick = onToggleLike,
                        onCommentsClick = onOpenComments,
                        onMoreClick = { onSetOverlayMode(FeedOverlayMode.MORE_MENU) },
                        onDismiss = { onSetOverlayMode(FeedOverlayMode.NONE) }
                    )
                }

                AnimatedVisibility(
                    visible = uiState.overlayMode == FeedOverlayMode.COMMENTS,
                    enter = fadeIn() + slideInHorizontally { it },
                    exit = fadeOut() + slideOutHorizontally { it },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    CommentsPanel(
                        totalCount = video.stats.commentsCount,
                        comments = uiState.comments,
                        isLoading = uiState.isCommentsLoading,
                        error = uiState.commentsError,
                        onToggleLikeComment = onToggleCommentLike,
                        onRetry = onOpenComments,
                        onClose = { onSetOverlayMode(FeedOverlayMode.BOTTOM_BAR) }
                    )
                }

                if (uiState.overlayMode == FeedOverlayMode.MORE_MENU) {
                    MoreMenuDialog(
                        isSaved = video.isSaved,
                        onSaveClick = onToggleSave,
                        onCopyLinkClick = onCopyLink,
                        onNotInterestedClick = onNotInterested,
                        onSettingsClick = onOpenSettings,
                        onDismiss = { onSetOverlayMode(FeedOverlayMode.BOTTOM_BAR) }
                    )
                }

                if (uiState.overlayMode == FeedOverlayMode.LOGIN_MODAL) {
                    LoginModal(
                        onDismiss = { onSetOverlayMode(FeedOverlayMode.TOP_BAR) }
                    )
                }
            }
        }
    }
}
