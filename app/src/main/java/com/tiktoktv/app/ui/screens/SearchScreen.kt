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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.model.Author
import com.tiktoktv.app.data.model.HashtagResult
import com.tiktoktv.app.data.model.LiveStreamItem
import com.tiktoktv.app.data.model.SearchSuggestion
import com.tiktoktv.app.data.model.SearchTab
import com.tiktoktv.app.data.model.SuggestionType
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.ui.UiState
import com.tiktoktv.app.ui.components.ErrorScreen
import com.tiktoktv.app.ui.components.FocusPillButton
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.util.formatCompactCount

@Composable
fun SearchScreen(
    uiState: UiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    onOpenUserProfile: (Author) -> Unit,
    onPlayVideo: (List<VideoItem>, Int) -> Unit,
    onLoadMoreResults: () -> Unit = {},
    onLoadMoreUsers: () -> Unit = {},
    onLoadMoreHashtags: () -> Unit = {},
    onBack: () -> Unit
) {
    val searchFieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { searchFieldFocus.requestFocus() }
    }

    Column(
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
            .padding(horizontal = 42.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            var backFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (backFocused) TikTokColors.White else TikTokColors.SurfaceElevated)
                    .onFocusChanged { backFocused = it.isFocused }
                    .focusable()
                    .clickable { onBack() }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            onBack()
                            true
                        } else false
                    }
                    .padding(14.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (backFocused) TikTokColors.Black else TikTokColors.White
                )
            }

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Search videos, @creators, live or #hashtags",
                        color = TikTokColors.TextMuted
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = TikTokColors.TextSecondary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onSubmitSearch(uiState.searchQuery) },
                    onDone = { onSubmitSearch(uiState.searchQuery) }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TikTokColors.White,
                    unfocusedTextColor = TikTokColors.White,
                    focusedBorderColor = TikTokColors.Pink,
                    unfocusedBorderColor = TikTokColors.Divider,
                    focusedContainerColor = TikTokColors.SurfaceDark,
                    unfocusedContainerColor = TikTokColors.SurfaceDark
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFieldFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            onSubmitSearch(uiState.searchQuery)
                            true
                        } else false
                    }
            )

            FocusPillButton(
                label = "Search",
                onClick = { onSubmitSearch(uiState.searchQuery) }
            )
        }

        if (uiState.showSearchSuggestions && uiState.searchSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(uiState.searchSuggestions, key = { "${it.type}_${it.text}" }) { suggestion ->
                    SuggestionChipCard(
                        suggestion = suggestion,
                        onClick = { onSubmitSearch(suggestion.text) }
                    )
                }
            }
        }

        if (uiState.searchResults.isEmpty() && uiState.searchUsers.isEmpty() && uiState.searchHashtags.isEmpty() && uiState.searchLiveStreams.isEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(ScraperConfig.TRENDING_SEARCH_CHIPS) { _, chip ->
                    var isFocused by remember { mutableStateOf(false) }
                    val scale by animateFloatAsState(if (isFocused) 1.06f else 1f, label = "chipScale")
                    Box(
                        modifier = Modifier
                            .scale(scale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isFocused) TikTokColors.White else TikTokColors.SurfaceElevated)
                            .border(
                                width = 1.dp,
                                color = if (isFocused) TikTokColors.Pink else TikTokColors.Divider,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable()
                            .clickable { onSubmitSearch(chip) }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = chip,
                            color = if (isFocused) TikTokColors.Black else TikTokColors.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchTab.entries.forEach { tab ->
                    val count = when (tab) {
                        SearchTab.TOP -> uiState.searchResults.size + uiState.searchUsers.size
                        SearchTab.USERS -> uiState.searchUsers.size
                        SearchTab.VIDEOS -> uiState.searchResults.size
                        SearchTab.LIVE -> uiState.searchLiveStreams.size
                        SearchTab.HASHTAGS -> uiState.searchHashtags.size
                    }
                    val suffix = when {
                        count <= 0 -> ""
                        (tab == SearchTab.TOP || tab == SearchTab.VIDEOS) && uiState.searchHasMore -> " ($count+)"
                        tab == SearchTab.USERS && uiState.searchUsersHasMore -> " ($count+)"
                        tab == SearchTab.HASHTAGS && uiState.searchHashtagsHasMore -> " ($count+)"
                        else -> " ($count)"
                    }
                    SearchTabButton(
                        label = "${tab.label}$suffix",
                        selected = uiState.activeSearchTab == tab,
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isSearching -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TikTokColors.Pink)
                    }
                }
                uiState.searchError != null &&
                    uiState.searchResults.isEmpty() &&
                    uiState.searchUsers.isEmpty() &&
                    uiState.searchHashtags.isEmpty() -> {
                    ErrorScreen(
                        message = uiState.searchError,
                        onRetry = { onSubmitSearch(uiState.searchQuery) }
                    )
                }
                else -> {
                    when (uiState.activeSearchTab) {
                        SearchTab.TOP -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (uiState.searchUsers.isNotEmpty()) {
                                    Text(
                                        text = "Matching Creators",
                                        color = TikTokColors.TextSecondary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(uiState.searchUsers.take(8), key = { it.username }) { user ->
                                            UserResultCard(
                                                author = user,
                                                onClick = { onOpenUserProfile(user) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(18.dp))
                                }
                                VideoFiveColumnGrid(
                                    videos = uiState.searchResults,
                                    emptyText = if (uiState.searchQuery.isBlank()) {
                                        "Type a keyword above for live suggestions and exact results."
                                    } else {
                                        "No matching videos found for \"${uiState.searchQuery}\"."
                                    },
                                    onVideoClick = { idx -> onPlayVideo(uiState.searchResults, idx) },
                                    modifier = Modifier.weight(1f),
                                    hasMore = uiState.searchHasMore,
                                    isLoadingMore = uiState.isSearchLoadingMore,
                                    onLoadMore = onLoadMoreResults
                                )
                            }
                        }
                        SearchTab.USERS -> {
                            if (uiState.searchUsers.isEmpty()) {
                                EmptyTabState("No matching users found.")
                            } else {
                                val usersGridState = rememberLazyGridState()
                                if (uiState.searchUsersHasMore) {
                                    LaunchedEffect(usersGridState, uiState.searchUsers.size, uiState.searchUsersHasMore, uiState.isSearchUsersLoadingMore) {
                                        snapshotFlow {
                                            val layoutInfo = usersGridState.layoutInfo
                                            val total = layoutInfo.totalItemsCount
                                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                            total > 0 && (lastVisible >= (total - 6).coerceAtLeast(0) || !usersGridState.canScrollForward)
                                        }
                                            .distinctUntilChanged()
                                            .filter { it && uiState.searchUsersHasMore && !uiState.isSearchUsersLoadingMore }
                                            .collect { onLoadMoreUsers() }
                                    }
                                }
                                LazyVerticalGrid(
                                    state = usersGridState,
                                    columns = GridCells.Fixed(3),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(uiState.searchUsers, key = { it.username }) { user ->
                                        UserResultCard(
                                            author = user,
                                            onClick = { onOpenUserProfile(user) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (uiState.isSearchUsersLoadingMore) {
                                        item(span = { GridItemSpan(3) }, key = "__users_loading__") {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    color = TikTokColors.Pink,
                                                    strokeWidth = 2.5.dp,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        SearchTab.VIDEOS -> {
                            VideoFiveColumnGrid(
                                videos = uiState.searchResults,
                                emptyText = "No videos found for this query.",
                                onVideoClick = { idx -> onPlayVideo(uiState.searchResults, idx) },
                                hasMore = uiState.searchHasMore,
                                isLoadingMore = uiState.isSearchLoadingMore,
                                onLoadMore = onLoadMoreResults
                            )
                        }
                        SearchTab.LIVE -> {
                            if (uiState.searchLiveStreams.isEmpty()) {
                                EmptyTabState("No active LIVE streams matched right now.")
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(uiState.searchLiveStreams, key = { it.id }) { live ->
                                        LiveStreamCard(
                                            live = live,
                                            onClick = { onOpenUserProfile(live.host) }
                                        )
                                    }
                                }
                            }
                        }
                        SearchTab.HASHTAGS -> {
                            if (uiState.searchHashtags.isEmpty()) {
                                EmptyTabState("No hashtags found.")
                            } else {
                                val tagsGridState = rememberLazyGridState()
                                if (uiState.searchHashtagsHasMore) {
                                    LaunchedEffect(tagsGridState, uiState.searchHashtags.size, uiState.searchHashtagsHasMore, uiState.isSearchHashtagsLoadingMore) {
                                        snapshotFlow {
                                            val layoutInfo = tagsGridState.layoutInfo
                                            val total = layoutInfo.totalItemsCount
                                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                            total > 0 && (lastVisible >= (total - 8).coerceAtLeast(0) || !tagsGridState.canScrollForward)
                                        }
                                            .distinctUntilChanged()
                                            .filter { it && uiState.searchHashtagsHasMore && !uiState.isSearchHashtagsLoadingMore }
                                            .collect { onLoadMoreHashtags() }
                                    }
                                }
                                LazyVerticalGrid(
                                    state = tagsGridState,
                                    columns = GridCells.Fixed(4),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(uiState.searchHashtags, key = { it.tag }) { tag ->
                                        HashtagResultCard(
                                            hashtag = tag,
                                            onClick = { onSubmitSearch(tag.tag) }
                                        )
                                    }
                                    if (uiState.isSearchHashtagsLoadingMore) {
                                        item(span = { GridItemSpan(4) }, key = "__hashtags_loading__") {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    color = TikTokColors.Pink,
                                                    strokeWidth = 2.5.dp,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChipCard(
    suggestion: SearchSuggestion,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val icon = when (suggestion.type) {
        SuggestionType.HISTORY -> Icons.Filled.History
        SuggestionType.CREATOR -> Icons.Filled.Person
        SuggestionType.HASHTAG -> Icons.Filled.Tag
        SuggestionType.TRENDING -> Icons.Filled.TrendingUp
        SuggestionType.KEYWORD -> Icons.Filled.Search
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) TikTokColors.White else TikTokColors.SurfaceDark)
            .border(
                width = 1.dp,
                color = if (isFocused) TikTokColors.Pink else TikTokColors.Divider,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isFocused) TikTokColors.Black else TikTokColors.Pink,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = suggestion.text,
            color = if (isFocused) TikTokColors.Black else TikTokColors.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun SearchTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isFocused -> TikTokColors.White
                    selected -> TikTokColors.Pink
                    else -> TikTokColors.SurfaceElevated
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (isFocused) TikTokColors.Black else TikTokColors.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun UserResultCard(
    author: Author,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .width(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TikTokColors.SurfaceElevated else TikTokColors.SurfaceDark)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TikTokColors.White else TikTokColors.Divider,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
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
                Icon(Icons.Filled.Person, contentDescription = null, tint = TikTokColors.TextMuted)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = author.displayName,
                    color = TikTokColors.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (author.verified) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Verified",
                        tint = TikTokColors.VerifiedBlue,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Text(
                text = "@${author.username}",
                color = TikTokColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (author.followersCount > 0L) {
                Text(
                    text = "${formatCompactCount(author.followersCount)} followers",
                    color = TikTokColors.TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun LiveStreamCard(
    live: LiveStreamItem,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(12.dp))
            .background(TikTokColors.SurfaceDark)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TikTokColors.Pink else TikTokColors.Divider,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
    ) {
        if (live.coverUrl.isNotBlank()) {
            AsyncImage(
                model = live.coverUrl,
                contentDescription = live.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
        )
        Text(
            text = "LIVE · ${formatCompactCount(live.viewerCount)} watching",
            color = TikTokColors.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TikTokColors.Pink)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                text = live.title,
                color = TikTokColors.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${live.host.username}",
                color = TikTokColors.TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun HashtagResultCard(
    hashtag: HashtagResult,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TikTokColors.White else TikTokColors.SurfaceDark)
            .border(
                width = 1.dp,
                color = if (isFocused) TikTokColors.Pink else TikTokColors.Divider,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(
            text = hashtag.tag,
            color = if (isFocused) TikTokColors.Black else TikTokColors.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${hashtag.videoCount} videos · ${hashtag.viewCountText}",
            color = if (isFocused) TikTokColors.SurfaceElevated else TikTokColors.TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EmptyTabState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = TikTokColors.TextSecondary, fontSize = 16.sp)
    }
}
