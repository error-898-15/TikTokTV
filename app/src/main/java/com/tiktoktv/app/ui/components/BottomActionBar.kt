package com.tiktoktv.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.util.formatCompactCount

@Composable
fun BottomActionBar(
    video: VideoItem,
    initialFocusRequester: FocusRequester,
    onAvatarClick: () -> Unit,
    onLikeClick: () -> Unit,
    onCommentsClick: () -> Unit,
    onMoreClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarFocus = remember { FocusRequester() }
    val likeFocus = remember { FocusRequester() }
    val commentsFocus = initialFocusRequester
    val moreFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        likeFocus.requestFocus()
    }

    var burstActive by remember { mutableStateOf(false) }
    LaunchedEffect(video.isLiked) {
        if (video.isLiked) {
            burstActive = true
            kotlinx.coroutines.delay(240L)
            burstActive = false
        }
    }
    val heartBurstScale by animateFloatAsState(
        targetValue = if (burstActive) 1.38f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "heartBurst"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .padding(top = 28.dp, bottom = 26.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.Back || event.key == Key.Escape)
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(38.dp),
            verticalAlignment = Alignment.Top
        ) {
            ActionBarCircleItem(
                label = "@${video.author.username}",
                focusRequester = avatarFocus,
                onClick = onAvatarClick
            ) { isFocused ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TikTokColors.SurfaceElevated)
                        .border(
                            width = if (isFocused) 3.dp else 1.5.dp,
                            color = if (isFocused) TikTokColors.White else TikTokColors.White.copy(alpha = 0.6f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (video.author.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = video.author.avatarUrl,
                            contentDescription = video.author.username,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Profile",
                            tint = TikTokColors.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            ActionBarCircleItem(
                label = formatCompactCount(video.stats.likesCount),
                focusRequester = likeFocus,
                onClick = onLikeClick
            ) { isFocused ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .scale(heartBurstScale)
                        .clip(CircleShape)
                        .background(
                            if (isFocused) TikTokColors.White.copy(alpha = 0.22f)
                            else Color.Black.copy(alpha = 0.45f)
                        )
                        .border(
                            width = if (isFocused) 3.dp else 0.dp,
                            color = if (isFocused) TikTokColors.White else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (video.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (video.isLiked) TikTokColors.Pink else TikTokColors.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            ActionBarCircleItem(
                label = "Comments",
                focusRequester = commentsFocus,
                onClick = onCommentsClick
            ) { isFocused ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isFocused) TikTokColors.White
                            else Color.Black.copy(alpha = 0.45f)
                        )
                        .border(
                            width = if (isFocused) 3.dp else 0.dp,
                            color = if (isFocused) TikTokColors.White else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Comments",
                        tint = if (isFocused) TikTokColors.Black else TikTokColors.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            ActionBarCircleItem(
                label = "More",
                focusRequester = moreFocus,
                onClick = onMoreClick
            ) { isFocused ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isFocused) TikTokColors.White
                            else Color.Black.copy(alpha = 0.45f)
                        )
                        .border(
                            width = if (isFocused) 3.dp else 0.dp,
                            color = if (isFocused) TikTokColors.White else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = "More",
                        tint = if (isFocused) TikTokColors.Black else TikTokColors.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBarCircleItem(
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    iconContent: @Composable (isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.14f else 1f,
        label = "actionScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
    ) {
        iconContent(isFocused)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = if (isFocused) TikTokColors.White else TikTokColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
