package com.tiktoktv.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.ui.ProfileTab
import com.tiktoktv.app.ui.UiState
import com.tiktoktv.app.ui.components.FocusPillButton
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.util.formatCompactCount
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun ProfileScreen(
    uiState: UiState,
    onSelectTab: (ProfileTab) -> Unit,
    onPlayVideo: (List<VideoItem>, Int) -> Unit,
    onRetryProfile: () -> Unit,
    onLoadMoreProfile: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onCycleQuality: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit
) {
    val profileSidebarFocus = remember { FocusRequester() }
    val likedSidebarFocus = remember { FocusRequester() }
    val historySidebarFocus = remember { FocusRequester() }
    val settingsSidebarFocus = remember { FocusRequester() }

    LaunchedEffect(uiState.profileTab) {
        when (uiState.profileTab) {
            ProfileTab.PROFILE -> runCatching { profileSidebarFocus.requestFocus() }
            ProfileTab.LIKED_VIDEOS -> runCatching { likedSidebarFocus.requestFocus() }
            ProfileTab.HISTORY -> runCatching { historySidebarFocus.requestFocus() }
            ProfileTab.SETTINGS -> runCatching { settingsSidebarFocus.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TikTokColors.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else false
            }
    ) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(TikTokColors.SurfaceDark)
                .padding(horizontal = 20.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SidebarNavItem(
                    label = if (uiState.isOwnProfile) "Profile" else "Creator Videos",
                    selected = uiState.profileTab == ProfileTab.PROFILE,
                    focusRequester = profileSidebarFocus,
                    onSelect = { onSelectTab(ProfileTab.PROFILE) }
                )
                SidebarNavItem(
                    label = "Liked videos",
                    selected = uiState.profileTab == ProfileTab.LIKED_VIDEOS,
                    focusRequester = likedSidebarFocus,
                    onSelect = { onSelectTab(ProfileTab.LIKED_VIDEOS) }
                )
                if (uiState.isOwnProfile) {
                    SidebarNavItem(
                        label = "Watch history",
                        selected = uiState.profileTab == ProfileTab.HISTORY,
                        focusRequester = historySidebarFocus,
                        onSelect = { onSelectTab(ProfileTab.HISTORY) }
                    )
                }
            }
            SidebarNavItem(
                label = "Settings",
                selected = uiState.profileTab == ProfileTab.SETTINGS,
                focusRequester = settingsSidebarFocus,
                onSelect = { onSelectTab(ProfileTab.SETTINGS) }
            )
        }

        VerticalDivider(
            color = TikTokColors.Divider,
            thickness = 1.dp,
            modifier = Modifier.fillMaxHeight()
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 42.dp, vertical = 32.dp)
        ) {
            when (uiState.profileTab) {
                ProfileTab.SETTINGS -> {
                    SettingsContent(
                        settings = uiState.settings,
                        cacheSizeText = uiState.cacheSizeText,
                        onToggleAutoScroll = onToggleAutoScroll,
                        onToggleAutoplay = onToggleAutoplay,
                        onCycleQuality = onCycleQuality,
                        onClearCache = onClearCache
                    )
                }
                ProfileTab.LIKED_VIDEOS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Liked videos",
                            color = TikTokColors.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${uiState.likedVideos.size} saved locally",
                            color = TikTokColors.TextSecondary,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        VideoFiveColumnGrid(
                            videos = uiState.likedVideos,
                            emptyText = "Videos you like while watching will appear here.",
                            onVideoClick = { idx -> onPlayVideo(uiState.likedVideos, idx) }
                        )
                    }
                }
                ProfileTab.HISTORY -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Watch history",
                            color = TikTokColors.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${uiState.watchHistory.size} recently watched videos",
                            color = TikTokColors.TextSecondary,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        VideoFiveColumnGrid(
                            videos = uiState.watchHistory,
                            emptyText = "Your recently watched videos will appear here.",
                            onVideoClick = { idx -> onPlayVideo(uiState.watchHistory, idx) }
                        )
                    }
                }
                ProfileTab.PROFILE -> {
                    if (uiState.isProfileLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TikTokColors.Pink)
                        }
                    } else {
                        val author = uiState.profileAuthor
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (author != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(88.dp)
                                            .clip(CircleShape)
                                            .background(TikTokColors.SurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (author.avatarUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = author.avatarUrl,
                                                contentDescription = author.displayName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.Person,
                                                contentDescription = null,
                                                tint = TikTokColors.TextMuted,
                                                modifier = Modifier.size(40.dp)
                                            )
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = author.displayName,
                                            color = TikTokColors.White,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "@${author.username}",
                                            color = TikTokColors.TextSecondary,
                                            fontSize = 15.sp
                                        )
                                        if (author.bio.isNotBlank()) {
                                            Text(
                                                text = author.bio,
                                                color = TikTokColors.TextSecondary,
                                                fontSize = 14.sp,
                                                maxLines = 2
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                            ProfileStatText(count = author.followingCount, label = "Following")
                                            ProfileStatText(count = author.followersCount, label = "Followers")
                                            ProfileStatText(count = author.likesCount, label = "Likes")
                                            if (author.videoCount > 0L) {
                                                ProfileStatText(count = author.videoCount, label = "Videos")
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(22.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val totalVideosCount = author?.videoCount ?: 0L
                                Text(
                                    text = when {
                                        uiState.isOwnProfile -> "Bookmarked Videos (${uiState.profileVideos.size})"
                                        (uiState.isProfileLoadingMore || uiState.profileHasMore) &&
                                            totalVideosCount > uiState.profileVideos.size ->
                                            "Videos (${uiState.profileVideos.size} / $totalVideosCount)"
                                        else -> "Videos (${uiState.profileVideos.size})"
                                    },
                                    color = TikTokColors.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (uiState.isProfileLoadingMore) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "Loading all videos...",
                                            color = TikTokColors.TextSecondary,
                                            fontSize = 14.sp
                                        )
                                        CircularProgressIndicator(
                                            color = TikTokColors.Pink,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                } else if (uiState.profileHasMore) {
                                    FocusPillButton(
                                        label = "Load More Videos",
                                        onClick = onLoadMoreProfile
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (uiState.profileError != null && uiState.profileVideos.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = uiState.profileError,
                                        color = TikTokColors.TextSecondary,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(18.dp))
                                    FocusPillButton(
                                        label = "Retry",
                                        onClick = onRetryProfile
                                    )
                                }
                            } else if (uiState.profileVideos.isEmpty() && !uiState.isOwnProfile) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "No public videos found for this creator yet.",
                                        color = TikTokColors.TextSecondary,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(18.dp))
                                    FocusPillButton(
                                        label = "Retry",
                                        onClick = onRetryProfile
                                    )
                                }
                            } else {
                                VideoFiveColumnGrid(
                                    videos = uiState.profileVideos,
                                    emptyText = if (uiState.isOwnProfile) {
                                        "Bookmarked videos will appear here."
                                    } else {
                                        "No public videos found for this creator yet."
                                    },
                                    onVideoClick = { idx -> onPlayVideo(uiState.profileVideos, idx) },
                                    hasMore = !uiState.isOwnProfile && uiState.profileHasMore,
                                    isLoadingMore = uiState.isProfileLoadingMore,
                                    onLoadMore = if (!uiState.isOwnProfile) onLoadMoreProfile else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNavItem(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f, label = "sidebarScale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isFocused -> TikTokColors.White
                    selected -> Color(0xFF26262C)
                    else -> Color.Transparent
                }
            )
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onSelect()
            }
            .focusable()
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = when {
                isFocused -> TikTokColors.Black
                selected -> TikTokColors.White
                else -> TikTokColors.TextMuted
            },
            fontSize = 17.sp,
            fontWeight = if (isFocused || selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ProfileStatText(count: Long, label: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = TikTokColors.White, fontWeight = FontWeight.Bold)) {
                append(formatCompactCount(count))
            }
            append(" ")
            withStyle(SpanStyle(color = TikTokColors.TextSecondary, fontWeight = FontWeight.Normal)) {
                append(label)
            }
        },
        fontSize = 15.sp
    )
}

@Composable
fun VideoFiveColumnGrid(
    videos: List<VideoItem>,
    emptyText: String,
    onVideoClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null
) {
    if (videos.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = emptyText, color = TikTokColors.TextSecondary, fontSize = 16.sp)
        }
        return
    }
    val gridState = rememberLazyGridState()
    if (onLoadMore != null && hasMore) {
        LaunchedEffect(gridState, videos.size, hasMore, isLoadingMore) {
            snapshotFlow {
                val layoutInfo = gridState.layoutInfo
                val total = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                total > 0 && lastVisible >= total - 5
            }
                .distinctUntilChanged()
                .filter { it && hasMore && !isLoadingMore }
                .collect {
                    onLoadMore()
                }
        }
    }

    val uniqueVideos = remember(videos) { videos.distinctBy { it.id } }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(5),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(uniqueVideos, key = { _, item -> item.id }) { index, video ->
            VideoGridThumbnailCard(video = video, onClick = { onVideoClick(videos.indexOfFirst { it.id == video.id }.coerceAtLeast(0)) })
        }
    }
}

@Composable
private fun VideoGridThumbnailCard(video: VideoItem, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.07f else 1f, label = "gridCardScale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 14f)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(TikTokColors.SurfaceElevated)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) TikTokColors.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
    ) {
        if (video.coverUrl.isNotBlank()) {
            AsyncImage(
                model = video.coverUrl,
                contentDescription = video.caption,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = "Views",
                tint = TikTokColors.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = formatCompactCount(video.stats.viewsCount),
                color = TikTokColors.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
