package com.tiktoktv.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object TikTokColors {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Pink = Color(0xFFFE2C55)
    val Cyan = Color(0xFF25F4EE)
    val SurfaceDark = Color(0xFF121212)
    val SurfaceElevated = Color(0xFF1F1F23)
    val Divider = Color(0x33FFFFFF)
    val TextSecondary = Color(0xFFB5B5BA)
    val TextMuted = Color(0xFF7A7A80)
    val VerifiedBlue = Color(0xFF20D5EC)
}

private val TikTokDarkColorScheme = darkColorScheme(
    primary = TikTokColors.Pink,
    onPrimary = TikTokColors.White,
    secondary = TikTokColors.Cyan,
    onSecondary = TikTokColors.Black,
    background = TikTokColors.Black,
    onBackground = TikTokColors.White,
    surface = TikTokColors.SurfaceDark,
    onSurface = TikTokColors.White,
    surfaceVariant = TikTokColors.SurfaceElevated,
    onSurfaceVariant = TikTokColors.TextSecondary,
    outline = TikTokColors.Divider
)

@Composable
fun TikTokTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TikTokDarkColorScheme,
        content = content
    )
}
