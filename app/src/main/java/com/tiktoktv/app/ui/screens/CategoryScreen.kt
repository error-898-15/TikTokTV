package com.tiktoktv.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.util.BlurTransformation

@Composable
fun CategoryScreen(
    categories: List<Category>,
    activeCategory: Category,
    fallbackBackdropUrl: String,
    onSelectCategory: (Category) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val initialIndex = categories.indexOfFirst { it.id == activeCategory.id }.coerceAtLeast(0)
    var focusedIndex by remember { mutableIntStateOf(initialIndex) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val focusRequesters = remember(categories.size) { List(categories.size) { FocusRequester() } }
    val blurTransform = remember { BlurTransformation(radius = 25, sampling = 5f) }

    val focusedCategory = categories.getOrNull(focusedIndex) ?: activeCategory
    val backdropUrl = focusedCategory.previewImageUrl.ifBlank { fallbackBackdropUrl }

    LaunchedEffect(Unit) {
        runCatching { focusRequesters.getOrNull(initialIndex)?.requestFocus() }
    }

    LaunchedEffect(focusedIndex) {
        listState.animateScrollToItem((focusedIndex - 1).coerceAtLeast(0))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TikTokColors.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape || event.key == Key.DirectionDown)
                ) {
                    onDismiss()
                    true
                } else false
            }
    ) {
        if (backdropUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .transformations(blurTransform)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (backdropUrl.isNotBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = focusedCategory.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .height(420.dp)
                    .aspectRatio(9f / 16f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        Text(
            text = focusedCategory.label,
            color = TikTokColors.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 42.dp, top = 34.dp)
        )

        LazyRow(
            state = listState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            contentPadding = PaddingValues(horizontal = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                CategoryPortraitCard(
                    category = category,
                    fallbackCover = fallbackBackdropUrl,
                    focusRequester = focusRequesters[index],
                    onFocused = { focusedIndex = index },
                    onClick = { onSelectCategory(category) }
                )
            }
        }
    }
}

@Composable
private fun CategoryPortraitCard(
    category: Category,
    fallbackCover: String,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.09f else 1f, label = "catCardScale")
    val imageUrl = category.previewImageUrl.ifBlank { fallbackCover }

    Column(
        modifier = Modifier
            .width(146.dp)
            .scale(scale)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused()
            }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 14f)
                .clip(RoundedCornerShape(12.dp))
                .background(categoryPlaceholderBrush(category.id))
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) TikTokColors.White else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl.isNotBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = category.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "#${category.hashtag}",
                    color = TikTokColors.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = category.label,
            color = if (isFocused) TikTokColors.White else TikTokColors.TextSecondary,
            fontSize = if (isFocused) 17.sp else 15.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

private fun categoryPlaceholderBrush(id: String): Brush {
    val pair = when (id) {
        "comedy" -> Color(0xFF8E24AA) to Color(0xFF4A148C)
        "learning" -> Color(0xFFE91E63) to Color(0xFF880E4F)
        "sports" -> Color(0xFF1E88E5) to Color(0xFF0D47A1)
        "travel" -> Color(0xFF00ACC1) to Color(0xFF006064)
        "animals" -> Color(0xFF43A047) to Color(0xFF1B5E20)
        "art" -> Color(0xFF7B1FA2) to Color(0xFF311B92)
        "food" -> Color(0xFFFB8C00) to Color(0xFFE65100)
        "games" -> Color(0xFF3949AB) to Color(0xFF1A237E)
        else -> Color(0xFF2B2B35) to Color(0xFF14141A)
    }
    return Brush.verticalGradient(listOf(pair.first, pair.second))
}
