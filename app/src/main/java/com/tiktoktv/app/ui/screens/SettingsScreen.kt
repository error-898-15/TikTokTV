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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tiktoktv.app.data.model.AppSettings
import com.tiktoktv.app.data.model.ContentRegion
import com.tiktoktv.app.ui.theme.TikTokColors

@Composable
fun SettingsScreen(
    settings: AppSettings,
    cacheSizeText: String,
    onToggleAutoScroll: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onCycleQuality: () -> Unit,
    onSelectRegion: (ContentRegion) -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    Box(
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
            .padding(horizontal = 80.dp, vertical = 48.dp)
    ) {
        SettingsContent(
            settings = settings,
            cacheSizeText = cacheSizeText,
            onToggleAutoScroll = onToggleAutoScroll,
            onToggleAutoplay = onToggleAutoplay,
            onCycleQuality = onCycleQuality,
            onSelectRegion = onSelectRegion,
            onClearCache = onClearCache,
            firstFocusRequester = firstFocus
        )
    }
}

@Composable
fun SettingsContent(
    settings: AppSettings,
    cacheSizeText: String,
    onToggleAutoScroll: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onCycleQuality: () -> Unit,
    onSelectRegion: (ContentRegion) -> Unit,
    onClearCache: () -> Unit,
    firstFocusRequester: FocusRequester? = null
) {
    var showRegionPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Settings",
            color = TikTokColors.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Playback, Region & Storage",
            color = TikTokColors.TextSecondary,
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingValueRow(
            title = "Content region",
            subtitle = "Choose country/region for For You & trending feed (press OK to change)",
            valueText = settings.contentRegion.shortLabel,
            accentColor = TikTokColors.Cyan,
            focusRequester = firstFocusRequester,
            onClick = { showRegionPicker = true }
        )

        SettingToggleRow(
            title = "Auto-scroll",
            subtitle = "Automatically advance to the next video when playback ends",
            checked = settings.autoScroll,
            onClick = onToggleAutoScroll
        )

        SettingToggleRow(
            title = "Autoplay",
            subtitle = "Start playing videos immediately when opened",
            checked = settings.autoplay,
            onClick = onToggleAutoplay
        )

        SettingValueRow(
            title = "Video quality",
            subtitle = "Preferred stream resolution (press OK to change)",
            valueText = settings.videoQuality.label,
            onClick = onCycleQuality
        )

        SettingValueRow(
            title = "Clear cache",
            subtitle = "Remove cached videos, thumbnails, and scraped feed data",
            valueText = cacheSizeText,
            accentColor = TikTokColors.Pink,
            onClick = onClearCache
        )
    }

    if (showRegionPicker) {
        RegionPickerDialog(
            selectedRegion = settings.contentRegion,
            onSelect = { region ->
                showRegionPicker = false
                onSelectRegion(region)
            },
            onDismiss = { showRegionPicker = false }
        )
    }
}

@Composable
private fun RegionPickerDialog(
    selectedRegion: ContentRegion,
    onSelect: (ContentRegion) -> Unit,
    onDismiss: () -> Unit
) {
    val regions = ContentRegion.entries
    val selectedIndex = regions.indexOf(selectedRegion).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val itemFocusRequesters = remember(regions.size) { List(regions.size) { FocusRequester() } }

    LaunchedEffect(Unit) {
        runCatching { itemFocusRequesters.getOrNull(selectedIndex)?.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(TikTokColors.SurfaceDark)
                .border(1.5.dp, TikTokColors.Divider, RoundedCornerShape(18.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.Escape)
                    ) {
                        onDismiss()
                        true
                    } else false
                }
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select Content Region",
                    color = TikTokColors.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "For You feed will prioritize videos from your selected region",
                    color = TikTokColors.TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(regions, key = { _, item -> item.name }) { idx, region ->
                        var isFocused by remember { mutableStateOf(false) }
                        val isSelected = region == selectedRegion
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        isFocused -> TikTokColors.White
                                        isSelected -> Color(0xFF282834)
                                        else -> TikTokColors.SurfaceElevated
                                    }
                                )
                                .border(
                                    width = if (isFocused) 2.dp else 1.dp,
                                    color = when {
                                        isFocused -> TikTokColors.Pink
                                        isSelected -> TikTokColors.Cyan
                                        else -> TikTokColors.Divider
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .focusRequester(itemFocusRequesters[idx])
                                .onFocusChanged { isFocused = it.isFocused }
                                .focusable()
                                .clickable { onSelect(region) }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                                    ) {
                                        onSelect(region)
                                        true
                                    } else false
                                }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = region.label,
                                    color = if (isFocused) TikTokColors.Black else TikTokColors.White,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = if (region == ContentRegion.BANGLADESH_MIX) {
                                        "Mostly Bangladeshi videos + global mix"
                                    } else if (region == ContentRegion.GLOBAL_MIX) {
                                        "Trending videos from around the world"
                                    } else {
                                        "Region code: ${region.code}"
                                    },
                                    color = if (isFocused) TikTokColors.SurfaceElevated else TikTokColors.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = if (isFocused) TikTokColors.Black else TikTokColors.Cyan,
                                    modifier = Modifier.size(20.dp)
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
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f, label = "settingScale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) Color(0xFF262630) else TikTokColors.SurfaceDark)
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) TikTokColors.White else TikTokColors.Divider,
                shape = RoundedCornerShape(14.dp)
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
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TikTokColors.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TikTokColors.TextSecondary,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TikTokColors.White,
                checkedTrackColor = TikTokColors.Pink,
                uncheckedThumbColor = TikTokColors.TextSecondary,
                uncheckedTrackColor = TikTokColors.SurfaceElevated
            )
        )
    }
}

@Composable
private fun SettingValueRow(
    title: String,
    subtitle: String,
    valueText: String,
    accentColor: Color = TikTokColors.Cyan,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f, label = "settingValScale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) Color(0xFF262630) else TikTokColors.SurfaceDark)
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) TikTokColors.White else TikTokColors.Divider,
                shape = RoundedCornerShape(14.dp)
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
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TikTokColors.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TikTokColors.TextSecondary,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accentColor.copy(alpha = 0.2f))
                .border(1.dp, accentColor, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = valueText,
                color = TikTokColors.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
