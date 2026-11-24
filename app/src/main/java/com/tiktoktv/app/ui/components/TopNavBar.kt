package com.tiktoktv.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiktoktv.app.data.model.Category
import com.tiktoktv.app.ui.theme.TikTokColors

@Composable
fun TopNavBar(
    activeCategory: Category,
    onCategoryPillClick: () -> Unit,
    onHomeClick: () -> Unit,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onLoginClick: () -> Unit,
    onUpToCategories: () -> Unit,
    onDismissDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val homeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        homeFocus.requestFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent)
                )
            )
            .padding(horizontal = 36.dp, vertical = 22.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            onUpToCategories()
                            true
                        }
                        Key.DirectionDown, Key.Back, Key.Escape -> {
                            onDismissDown()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TikTokNoteIcon(size = 34.dp)

            var catFocused by remember { mutableStateOf(false) }
            val catScale by animateFloatAsState(if (catFocused) 1.08f else 1f, label = "catPillScale")
            Box(
                modifier = Modifier
                    .scale(catScale)
                    .clip(RoundedCornerShape(50))
                    .background(TikTokColors.Pink)
                    .border(
                        width = if (catFocused) 3.dp else 0.dp,
                        color = if (catFocused) TikTokColors.White else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    )
                    .onFocusChanged { catFocused = it.isFocused }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && isOkKey(event.key)) {
                            onCategoryPillClick()
                            true
                        } else false
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = activeCategory.label,
                    color = TikTokColors.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Press UP for Categories",
                color = TikTokColors.TextSecondary,
                fontSize = 13.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TopNavIconButton(
                icon = Icons.Filled.Home,
                contentDescription = "Home",
                focusRequester = homeFocus,
                onClick = onHomeClick
            )
            TopNavIconButton(
                icon = Icons.Filled.Search,
                contentDescription = "Search",
                onClick = onSearchClick
            )
            TopNavIconButton(
                icon = Icons.Filled.Person,
                contentDescription = "Profile",
                onClick = onProfileClick
            )

            Spacer(modifier = Modifier.width(4.dp))

            var loginFocused by remember { mutableStateOf(false) }
            val loginScale by animateFloatAsState(if (loginFocused) 1.08f else 1f, label = "loginScale")
            Box(
                modifier = Modifier
                    .scale(loginScale)
                    .clip(RoundedCornerShape(50))
                    .background(TikTokColors.Cyan)
                    .border(
                        width = if (loginFocused) 3.dp else 0.dp,
                        color = if (loginFocused) TikTokColors.White else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    )
                    .onFocusChanged { loginFocused = it.isFocused }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && isOkKey(event.key)) {
                            onLoginClick()
                            true
                        } else false
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Log in to TikTok",
                    color = TikTokColors.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun TopNavIconButton(
    icon: ImageVector,
    contentDescription: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.15f else 1f, label = "topIconScale")

    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (isFocused) TikTokColors.White
                else Color.White.copy(alpha = 0.14f)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) TikTokColors.Pink else Color.Transparent,
                shape = CircleShape
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && isOkKey(event.key)) {
                    onClick()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) TikTokColors.Black else TikTokColors.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun isOkKey(key: Key): Boolean {
    return key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
}
