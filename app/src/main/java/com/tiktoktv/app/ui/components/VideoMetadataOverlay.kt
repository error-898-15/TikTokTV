package com.tiktoktv.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiktoktv.app.data.model.VideoItem
import com.tiktoktv.app.ui.theme.TikTokColors
import com.tiktoktv.app.util.formatCompactCount

@Composable
fun VideoMetadataOverlay(
    video: VideoItem,
    modifier: Modifier = Modifier
) {
    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.85f),
        offset = Offset(1f, 2f),
        blurRadius = 6f
    )

    Column(
        modifier = modifier.widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "@${video.author.username}",
                style = TextStyle(
                    color = TikTokColors.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = textShadow
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (video.author.verified) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Verified",
                    tint = TikTokColors.VerifiedBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (video.caption.isNotBlank()) {
            Text(
                text = video.caption,
                style = TextStyle(
                    color = TikTokColors.White,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    shadow = textShadow
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (video.hashtags.isNotEmpty()) {
            Text(
                text = video.hashtags.joinToString(" "),
                style = TextStyle(
                    color = TikTokColors.White.copy(alpha = 0.95f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = textShadow
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = "Music",
                tint = TikTokColors.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = video.music.displayText,
                style = TextStyle(
                    color = TikTokColors.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    shadow = textShadow
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Likes",
                tint = if (video.isLiked) TikTokColors.Pink else TikTokColors.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatCompactCount(video.stats.likesCount),
                style = TextStyle(
                    color = TikTokColors.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = textShadow
                )
            )
        }
    }
}
