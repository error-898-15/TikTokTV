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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiktoktv.app.data.config.ScraperConfig
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.ui.UiState
import com.tiktoktv.app.ui.components.ErrorScreen
import com.tiktoktv.app.ui.components.FocusPillButton
import com.tiktoktv.app.ui.theme.TikTokColors

@Composable
fun SearchScreen(
    uiState: UiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onPlayVideo: (List<VideoItem>, Int) -> Unit,
    onBack: () -> Unit
) {
    val firstChipFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstChipFocus.requestFocus() }
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
            .padding(horizontal = 42.dp, vertical = 32.dp)
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
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter)
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
                placeholder = { Text("Search TikTok videos or #hashtags", color = TikTokColors.TextMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = TikTokColors.TextSecondary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(uiState.searchQuery) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TikTokColors.White,
                    unfocusedTextColor = TikTokColors.White,
                    focusedBorderColor = TikTokColors.Pink,
                    unfocusedBorderColor = TikTokColors.Divider,
                    focusedContainerColor = TikTokColors.SurfaceDark,
                    unfocusedContainerColor = TikTokColors.SurfaceDark
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )

            FocusPillButton(
                label = "Search",
                onClick = { onSubmitSearch(uiState.searchQuery) }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(ScraperConfig.TRENDING_SEARCH_CHIPS) { index, chip ->
                var isFocused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "chipScale")

                Box(
                    modifier = Modifier
                        .scale(scale)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isFocused) TikTokColors.White else TikTokColors.SurfaceElevated
                        )
                        .border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) TikTokColors.Pink else TikTokColors.Divider,
                            shape = RoundedCornerShape(50)
                        )
                        .then(if (index == 0) Modifier.focusRequester(firstChipFocus) else Modifier)
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                onSubmitSearch(chip)
                                true
                            } else false
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
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

        Spacer(modifier = Modifier.height(22.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isSearching -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TikTokColors.Pink)
                    }
                }
                uiState.searchError != null && uiState.searchResults.isEmpty() -> {
                    ErrorScreen(
                        message = uiState.searchError,
                        onRetry = { onSubmitSearch(uiState.searchQuery) }
                    )
                }
                else -> {
                    VideoFiveColumnGrid(
                        videos = uiState.searchResults,
                        emptyText = "Select a trending hashtag above or enter a keyword to search.",
                        onVideoClick = { idx -> onPlayVideo(uiState.searchResults, idx) }
                    )
                }
            }
        }
    }
}
