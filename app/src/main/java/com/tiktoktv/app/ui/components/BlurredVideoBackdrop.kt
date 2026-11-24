package com.tiktoktv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tiktoktv.app.util.BlurTransformation

/**
 * Full-screen blurred, enlarged copy of the active video thumbnail behind the centered 9:16 player.
 * Uses Coil stack-blur on all API levels (21+) without spinning up a competing hardware video decoder.
 */
@Composable
fun BlurredVideoBackdrop(
    coverUrl: String,
    backdropPlayer: ExoPlayer? = null,
    backdropFailed: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val blurTransform = remember { BlurTransformation(radius = 25, sampling = 5f) }

    Box(modifier = modifier.fillMaxSize()) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .transformations(blurTransform)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D12))
            )
        }

        // Subtle dark vignette for foreground text contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.58f),
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.58f)
                        )
                    )
                )
        )
    }
}
