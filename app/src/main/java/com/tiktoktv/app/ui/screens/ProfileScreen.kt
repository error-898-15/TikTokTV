package com.tiktoktv.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.tiktoktv.app.ui.components.ErrorScreen
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.util.formatCompactCount

@Composable
fun ProfileScreen(
    uiState: UiState,
    onSelectTab: (ProfileTab) -> Unit,
    onPlayVideo: (List<VideoItem>, Int) -> Unit,
    onRetryProfile: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onCycleQuality: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit
) {
    val profileSidebarFocus = remember { FocusRequester() }
    val likedSidebarFocus = remember { FocusRequester() }
    val settingsSidebarFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val target = when (uiState.profileTab) {
            ProfileTab.PROFILE -> profileSidebarFocus
            ProfileTab.LIKED_VIDEOS -> likedSidebarFocus
            ProfileTab.SETTINGS -> settingsSidebarFocus
        }
        runCatching { target.requestFocus() }
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
                .width(230.dp)
                .fillMaxHeight()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SidebarNavItem(
                    label = "Profile",
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
                .padding(horizontal = 42.dp, vertical = 36.dp)
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${uiState.likedVideos.size} saved locally",
                            color = TikTokColors.TextSecondary,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(22.dp))
                        VideoFiveColumnGrid(
                            videos = uiState.likedVideos,
                            emptyText = "Videos you like while watching will appear here.",
                            onVideoClick = { idx -> onPlayVideo(uiState.likedVideos, idx) }
                        )
                    }
                }
                ProfileTab.PROFILE -> {
                    when {
                        uiState.isProfileLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = TikTokColors.Pink)
                            }
                        }
                        uiState.profileError != null && uiState.profileVideos.isEmpty() -> {
                            ErrorScreen(
                                message = uiState.profileError,
                                onRetry = onRetryProfile
                            )
                        }
                        else -> {
                            val author = uiState.profileAuthor
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (author != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(132.dp)
                                                .clip(CircleShape)
                                                .background(TikTokColors.SurfaceElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (author.avatarUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = author.avatarUrl,
                                                    contentDescription = author.username,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Filled.Person,
                                                    contentDescription = null,
                                                    tint = TikTokColors.TextSecondary,
                                                    modifier = Modifier.size(58.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(28.dp))

                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = author.username,
                                                color = TikTokColors.White,
                                                fontSize = 30.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = author.displayName,
                                                color = TikTokColors.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (author.bio.isNotBlank()) {
                                                Text(
                                                    text = author.bio,
                                                    color = TikTokColors.TextSecondary,
                                                    fontSize = 14.sp,
                                                    maxLines = 2
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                                ProfileStatText(
                                                    count = author.followingCount,
                                                    label = "Following"
                                                )
                                                ProfileStatText(
                                                    count = author.followersCount,
                                                    label = "Followers"
                                                )
                                                ProfileStatText(
                                                    count = author.likesCount,
                                                    label = "Likes"
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(28.dp))

                                Text(
                                    text = if (uiState.isOwnProfile) "Bookmarked Videos" else "Videos",
                                    color = TikTokColors.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                VideoFiveColumnGrid(
                                    videos = uiState.profileVideos,
                                    emptyText = if (uiState.isOwnProfile) {
                                        "Bookmarked videos will appear here."
                                    } else {
                                        "No public videos found for this creator."
                                    },
                                    onVideoClick = { idx -> onPlayVideo(uiState.profileVideos, idx) }
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
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onSelect()
                    true
                } else false
            }
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
    modifier: Modifier = Modifier
) {
    if (videos.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                color = TikTokColors.TextSecondary,
                fontSize = 16.sp
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(videos, key = { idx, item -> "${item.id}_$idx" }) { index, video ->
            VideoGridThumbnailCard(
                video = video,
                onClick = { onVideoClick(index) }
            )
        }
    }
}

@Composable
private fun VideoGridThumbnailCard(
    video: VideoItem,
    onClick: () -> Unit
) {
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
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else false
            }
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
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
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
