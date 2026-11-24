package com.tiktoktv.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiktoktv.app.ui.theme.TikTokColors

@Composable
fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        retryFocus.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TikTokColors.Black)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = "Network Error",
                tint = TikTokColors.Pink,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Unable to Load Content",
                color = TikTokColors.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = message,
                color = TikTokColors.TextSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(26.dp))
            FocusPillButton(
                label = "Retry",
                focusRequester = retryFocus,
                onClick = onRetry
            )
        }
    }
}

@Composable
fun FocusPillButton(
    label: String,
    focusRequester: FocusRequester? = null,
    backgroundColor: Color = TikTokColors.Pink,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "pillBtnScale")

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(if (isFocused) TikTokColors.White else backgroundColor)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) TikTokColors.Pink else Color.Transparent,
                shape = RoundedCornerShape(50)
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isFocused) TikTokColors.Black else TikTokColors.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
