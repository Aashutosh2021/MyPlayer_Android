package com.myplayer.app.ui.opentune

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.myplayer.app.cast.CastSpeakerDevice
import com.myplayer.app.cast.LocalCastManager
import com.myplayer.app.data.local.entity.SongEntity
import com.myplayer.app.data.online.model.OnlineSong
import com.myplayer.app.data.recommendation.model.RecommendationSong
import com.myplayer.app.data.recognize.RecognizerUiState
import com.myplayer.app.data.recognize.RecognizedSong
import com.myplayer.app.data.recognize.ShazamRecognizer
import com.myplayer.app.data.sponsorblock.SponsorBlockManager
import com.myplayer.app.playback.dsp.AutoEqHeadphoneEntry
import com.myplayer.app.playback.dsp.AutoEqManager
import com.myplayer.app.ui.common.AlbumArtImage
import com.myplayer.app.ui.theme.bouncyPopClick
import java.util.Calendar
import kotlin.math.abs
import kotlinx.coroutines.launch

// ═════════════════════════════════════════════════════════════════════════════
// 1. OPENTUNE CLEAN SETTINGS UI COMPONENTS (Ported from SettingsComponents.kt)
// ═════════════════════════════════════════════════════════════════════════════

object OpenTuneUiPrefs {
    private const val PREFS_NAME = "opentune_merged_ui_prefs"

    var useOpenTuneMiniPlayer by mutableStateOf(true)
    var showSpeedDialOnHome by mutableStateOf(true)
    var showDaylistOnHome by mutableStateOf(true)
    var showQuickPicksOnHome by mutableStateOf(true)
    var showMixesOnHome by mutableStateOf(true)
    var showFestivalBanner by mutableStateOf(true)
    var showStreakPill by mutableStateOf(true)
    var pureBlackOled by mutableStateOf(false)
    var activeAutoEqModel by mutableStateOf("None")
    var sponsorBlockEnabled by mutableStateOf(true)
    var sponsorBlockCategories by mutableStateOf(SponsorBlockManager.DEFAULT_CATEGORIES)

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        useOpenTuneMiniPlayer = sp.getBoolean("use_opentune_miniplayer", true)
        showSpeedDialOnHome = sp.getBoolean("show_speed_dial", true)
        showDaylistOnHome = sp.getBoolean("show_daylist", true)
        showQuickPicksOnHome = sp.getBoolean("show_quick_picks", true)
        showMixesOnHome = sp.getBoolean("show_mixes", true)
        showFestivalBanner = sp.getBoolean("show_festival_banner", true)
        showStreakPill = sp.getBoolean("show_streak_pill", true)
        pureBlackOled = sp.getBoolean("pure_black_oled", false)
        activeAutoEqModel = sp.getString("active_autoeq_model", "None") ?: "None"
        sponsorBlockEnabled = sp.getBoolean("sponsorblock_enabled", true)
        val savedCats = sp.getStringSet("sponsorblock_cats", null)
        if (savedCats != null) {
            sponsorBlockCategories = savedCats.toSet()
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("use_opentune_miniplayer", useOpenTuneMiniPlayer)
            .putBoolean("show_speed_dial", showSpeedDialOnHome)
            .putBoolean("show_daylist", showDaylistOnHome)
            .putBoolean("show_quick_picks", showQuickPicksOnHome)
            .putBoolean("show_mixes", showMixesOnHome)
            .putBoolean("show_festival_banner", showFestivalBanner)
            .putBoolean("show_streak_pill", showStreakPill)
            .putBoolean("pure_black_oled", pureBlackOled)
            .putString("active_autoeq_model", activeAutoEqModel)
            .putBoolean("sponsorblock_enabled", sponsorBlockEnabled)
            .putStringSet("sponsorblock_cats", sponsorBlockCategories)
            .apply()
    }
}

/** Small gray uppercase label above a group, matching OpenTune's GroupLabel. */
@Composable
fun OpenTuneGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
}

/** A rounded 28.dp card holding rows, with hairline dividers between them. */
@Composable
fun OpenTuneGroupCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = border,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
fun OpenTuneRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
        thickness = 0.75.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    )
}

@Composable
fun OpenTuneBetaTag(modifier: Modifier = Modifier, text: String = "Beta") {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun OpenTuneAppSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val c = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.onSurface,
            checkedThumbColor = c.background,
            checkedBorderColor = c.onSurface,
            uncheckedTrackColor = c.surfaceContainerHighest,
            uncheckedThumbColor = c.onSurfaceVariant.copy(alpha = 0.65f),
            uncheckedBorderColor = c.surfaceContainerHighest
        )
    )
}

@Composable
fun OpenTuneSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    badge: String? = null
) {
    val alpha = if (enabled) 1f else 0.45f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(18.dp))
            } else if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge != null) {
                        OpenTuneBetaTag(Modifier.padding(start = 8.dp), text = badge)
                    }
                }
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.padding(start = 12.dp)) { trailing() }
            }
        }
        if (below != null) {
            Column(
                Modifier.padding(start = if (icon != null || leading != null) 54.dp else 0.dp, top = 12.dp),
                content = below
            )
        }
    }
}

@Composable
fun OpenTuneToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    badge: String? = null
) {
    OpenTuneSettingRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        badge = badge,
        onClick = { onChange(!checked) },
        trailing = { OpenTuneAppSwitch(checked = checked, onChange = onChange, enabled = enabled) }
    )
}

@Composable
fun OpenTuneNavRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    dot: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    badge: String? = null
) {
    val alpha = if (enabled) 1f else 0.45f
    OpenTuneSettingRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        leading = leading,
        badge = badge,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34D399))
                    )
                }
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
        }
    )
}

/** Segmented pill selector matching OpenTune's PillSegmented. */
@Composable
fun <T> OpenTunePillSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val segment = (maxWidth - 10.dp) / options.size
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceContainerHighest.copy(alpha = 0.7f))
                .padding(4.dp)
        ) {
            val index = options.indexOf(selected).coerceAtLeast(0)
            val x by animateDpAsState(
                targetValue = segment * index,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 450f),
                label = "pillOffset"
            )
            Box(
                Modifier
                    .offset { IntOffset(x.roundToPx(), 0) }
                    .width(segment)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.onSurface)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                options.forEachIndexed { i, option ->
                    val color by animateColorAsState(
                        targetValue = if (i == index) c.background else c.onSurfaceVariant,
                        label = "pillText_$i"
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = i == index,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) }
                            )
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label(option),
                            color = color,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (i == index) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 2. OPENTUNE HOME SCREEN SECTIONS (Speed Dial, Daylist, Quick Picks, Mixes)
// ═════════════════════════════════════════════════════════════════════════════

data class OpenTuneSpeedDialItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val isArtist: Boolean = false,
    val onPlay: () -> Unit
)

@Composable
fun OpenTuneSpeedDialSection(
    items: List<OpenTuneSpeedDialItem>,
    userName: String,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shown = items.take(18)
    if (shown.isEmpty()) return
    val perPage = 9
    val pageCount = (shown.size + perPage - 1) / perPage
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAll)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val initial = userName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = (userName.ifBlank { "YOUR ROTATION" }).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = "Speed dial",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Show all",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }

        val side = 20.dp
        val gap = 8.dp
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val tileSize = (maxWidth - side * 2 - gap * 2) / 3
            val visibleCount = (shown.size - pagerState.currentPage * perPage).coerceIn(1, perPage)
            val rows = (visibleCount + 2) / 3
            val pageHeight by animateDpAsState(
                targetValue = tileSize * rows + gap * (rows - 1),
                label = "speedDialHeight"
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pageHeight),
                contentPadding = PaddingValues(horizontal = side),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.Top
            ) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    val pageItems = (shown.size - page * perPage).coerceIn(0, perPage)
                    for (row in 0 until (pageItems + 2) / 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (col in 0 until 3) {
                                val item = shown.getOrNull(page * perPage + row * 3 + col)
                                if (item != null) {
                                    SpeedDialSquareTile(
                                        item = item,
                                        tileSize = tileSize,
                                        modifier = Modifier.size(tileSize)
                                    )
                                } else {
                                    Spacer(Modifier.size(tileSize))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pageCount) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (active) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 0.9f else 0.28f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedDialSquareTile(
    item: OpenTuneSpeedDialItem,
    tileSize: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyPopClick(onClick = item.onPlay)
    ) {
        AlbumArtImage(
            uri = item.thumbnailUrl,
            title = item.title,
            artist = item.subtitle,
            size = tileSize,
            shape = RoundedCornerShape(14.dp),
            iconSize = 32.dp,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.38f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.84f)
                    )
                )
        )
        Text(
            text = item.title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun OpenTuneSpeedDialFullDialog(
    items: List<OpenTuneSpeedDialItem>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Speed dial",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(items, key = { i, item -> "${item.id}_$i" }) { _, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bouncyPopClick {
                                    item.onPlay()
                                    onDismiss()
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                AlbumArtImage(
                                    uri = item.thumbnailUrl,
                                    title = item.title,
                                    artist = item.subtitle,
                                    size = 112.dp,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** OpenTune Daylist Hero Banner — dynamic time-of-day mix card with 2x2 collage cover. */
@Composable
fun OpenTuneDaylistHeroCard(
    songs: List<RecommendationSong>,
    onPlayMix: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val (daylistTitle, daylistSub, gradientColors) = remember(hour) {
        when (hour) {
            in 5..11 -> Triple(
                "Morning Acoustic & Energy daylist",
                "Fresh vocals, acoustic warmth & upbeat rhythms for your morning",
                listOf(Color(0xFF1F3B4D), Color(0xFF12232E))
            )
            in 12..16 -> Triple(
                "Afternoon Flow & Chill daylist",
                "Smooth melodies, indie grooves & focus beats for the afternoon",
                listOf(Color(0xFF3E2753), Color(0xFF1E132A))
            )
            in 17..21 -> Triple(
                "Evening Sunset & Drive daylist",
                "Golden-hour hits, soulful vocals & atmospheric synth mixes",
                listOf(Color(0xFF4A2538), Color(0xFF211119))
            )
            else -> Triple(
                "Late Night Lo-Fi & After Hours daylist",
                "Midnight ambient, deep basslines & slow nocturnal vibes",
                listOf(Color(0xFF1B2845), Color(0xFF0D1322))
            )
        }
    }

    val covers = songs.take(4)
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(gradientColors))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(26.dp))
            .bouncyPopClick(onClick = onPlayMix)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 2x2 Cover Collage
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AlbumArtImage(
                            uri = covers.getOrNull(0)?.thumbnailUrl,
                            title = covers.getOrNull(0)?.title,
                            artist = covers.getOrNull(0)?.artist,
                            size = 42.dp,
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AlbumArtImage(
                            uri = covers.getOrNull(1)?.thumbnailUrl ?: covers.getOrNull(0)?.thumbnailUrl,
                            title = covers.getOrNull(1)?.title,
                            artist = covers.getOrNull(1)?.artist,
                            size = 42.dp,
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AlbumArtImage(
                            uri = covers.getOrNull(2)?.thumbnailUrl ?: covers.getOrNull(0)?.thumbnailUrl,
                            title = covers.getOrNull(2)?.title,
                            artist = covers.getOrNull(2)?.artist,
                            size = 42.dp,
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        AlbumArtImage(
                            uri = covers.getOrNull(3)?.thumbnailUrl ?: covers.getOrNull(1)?.thumbnailUrl,
                            title = covers.getOrNull(3)?.title,
                            artist = covers.getOrNull(3)?.artist,
                            size = 42.dp,
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFD2F83A),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "DAYLIST • FOR RIGHT NOW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD2F83A),
                        letterSpacing = 0.8.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = daylistTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = daylistSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play Daylist",
                    tint = Color(0xFF17171C),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** OpenTune 4-Row Horizontal Pager Quick Picks Section with Recents History button. */
@Composable
fun OpenTuneQuickPicksSection(
    songs: List<RecommendationSong>,
    currentPlayingId: String?,
    onPlaySongAt: (Int) -> Unit,
    onOpenRecents: () -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shown = songs.take(20)
    if (shown.isEmpty()) return
    val pageCount = (shown.size + 3) / 4
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "START RADIO FROM A SONG",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Quick picks",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = onOpenRecents,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = "Recently played",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
                FilledTonalButton(
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play all", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp * 4),
            contentPadding = PaddingValues(start = 20.dp, end = 44.dp),
            pageSpacing = 12.dp,
            verticalAlignment = Alignment.Top
        ) { page ->
            Column(Modifier.fillMaxWidth()) {
                for (i in page * 4 until minOf(page * 4 + 4, shown.size)) {
                    val song = shown[i]
                    val isPlaying = currentPlayingId != null && currentPlayingId == song.videoId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isPlaying) MaterialTheme.colorScheme.surfaceContainerHigh
                                else Color.Transparent
                            )
                            .clickable { onPlaySongAt(i) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtImage(
                            uri = song.thumbnailUrl,
                            title = song.title,
                            artist = song.artist,
                            size = 48.dp,
                            shape = RoundedCornerShape(10.dp),
                            iconSize = 22.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isPlaying) {
                            Icon(
                                imageVector = Icons.Filled.Equalizer,
                                contentDescription = "Playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp).padding(end = 4.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** OpenTune "Made For You Today" Mixes Shelf with 2x2 collage covers. */
data class OpenTuneMixData(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val accentColor: Color,
    val songs: List<RecommendationSong>
)

@Composable
fun OpenTuneMadeForYouMixesShelf(
    recommendations: List<RecommendationSong>,
    hotlistSongs: List<RecommendationSong>,
    onPlayMix: (List<RecommendationSong>) -> Unit,
    modifier: Modifier = Modifier
) {
    val mixes = remember(recommendations, hotlistSongs) {
        val pool = (recommendations + hotlistSongs).distinctBy { it.videoId }
        if (pool.size < 4) emptyList()
        else buildList {
            val chunk1 = pool.take(12)
            if (chunk1.isNotEmpty()) {
                add(
                    OpenTuneMixData(
                        id = "daily_mix_1",
                        title = "Daily Mix 1",
                        subtitle = chunk1.map { it.artist }.distinct().take(3).joinToString(", "),
                        badge = "MIX 01",
                        accentColor = Color(0xFF7C3AED),
                        songs = chunk1
                    )
                )
            }
            val chunk2 = pool.drop(6).take(12).ifEmpty { pool.shuffled().take(10) }
            if (chunk2.isNotEmpty()) {
                add(
                    OpenTuneMixData(
                        id = "daily_mix_2",
                        title = "Daily Mix 2",
                        subtitle = chunk2.map { it.artist }.distinct().take(3).joinToString(", "),
                        badge = "MIX 02",
                        accentColor = Color(0xFF10B981),
                        songs = chunk2
                    )
                )
            }
            val chunk3 = hotlistSongs.take(12).ifEmpty { pool.takeLast(10) }
            if (chunk3.isNotEmpty()) {
                add(
                    OpenTuneMixData(
                        id = "discover_mix",
                        title = "Discover Weekly",
                        subtitle = "Fresh tracks matched to your 128-D taste profile",
                        badge = "DISCOVER",
                        accentColor = Color(0xFFF59E0B),
                        songs = chunk3
                    )
                )
            }
            val chunk4 = pool.reversed().take(12)
            if (chunk4.isNotEmpty()) {
                add(
                    OpenTuneMixData(
                        id = "vibe_radar",
                        title = "Release & Vibe Radar",
                        subtitle = chunk4.map { it.artist }.distinct().take(3).joinToString(", "),
                        badge = "RADAR",
                        accentColor = Color(0xFFEC4899),
                        songs = chunk4
                    )
                )
            }
        }
    }

    if (mixes.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(
                text = "FROM YOUR LISTENING & TASTE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Made for you today",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(mixes, key = { it.id }) { mix ->
                val covers = mix.songs.take(4)
                Column(
                    modifier = Modifier
                        .width(164.dp)
                        .bouncyPopClick { onPlayMix(mix.songs) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(164.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                AlbumArtImage(
                                    uri = covers.getOrNull(0)?.thumbnailUrl,
                                    title = covers.getOrNull(0)?.title,
                                    artist = covers.getOrNull(0)?.artist,
                                    size = 82.dp,
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                AlbumArtImage(
                                    uri = covers.getOrNull(1)?.thumbnailUrl ?: covers.getOrNull(0)?.thumbnailUrl,
                                    title = covers.getOrNull(1)?.title,
                                    artist = covers.getOrNull(1)?.artist,
                                    size = 82.dp,
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                AlbumArtImage(
                                    uri = covers.getOrNull(2)?.thumbnailUrl ?: covers.getOrNull(0)?.thumbnailUrl,
                                    title = covers.getOrNull(2)?.title,
                                    artist = covers.getOrNull(2)?.artist,
                                    size = 82.dp,
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                AlbumArtImage(
                                    uri = covers.getOrNull(3)?.thumbnailUrl ?: covers.getOrNull(1)?.thumbnailUrl,
                                    title = covers.getOrNull(3)?.title,
                                    artist = covers.getOrNull(3)?.artist,
                                    size = 82.dp,
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.45f to Color.Transparent,
                                        1.0f to Color.Black.copy(alpha = 0.78f)
                                    )
                                )
                        )

                        // Top-left Mix Badge
                        Text(
                            text = mix.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(mix.accentColor.copy(alpha = 0.9f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )

                        // Bottom-right Play Pill
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play ${mix.title}",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = mix.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mix.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** OpenTune Recents Modal Bottom Sheet with List/Grid Toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenTuneRecentsBottomSheet(
    recentSongs: List<SongEntity>,
    onPlaySongAt: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var showAsGrid by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Recents",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showAsGrid = !showAsGrid }) {
                    Icon(
                        imageVector = if (showAsGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = "Toggle layout"
                    )
                }
            }

            if (recentSongs.isEmpty()) {
                Text(
                    text = "Your recently played & downloaded songs will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else if (showAsGrid) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(recentSongs, key = { i, s -> "${s.id}_$i" }) { index, song ->
                        Column(
                            modifier = Modifier
                                .width(132.dp)
                                .clickable {
                                    onPlaySongAt(index)
                                    onDismiss()
                                }
                        ) {
                            AlbumArtImage(
                                uri = song.albumArt,
                                title = song.title,
                                artist = song.artist,
                                size = 132.dp,
                                shape = RoundedCornerShape(16.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(recentSongs, key = { i, s -> "${s.id}_$i" }) { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onPlaySongAt(index)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtImage(
                                uri = song.albumArt,
                                title = song.title,
                                artist = song.artist,
                                size = 46.dp,
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 4. OPENTUNE SHAZAM RECOGNIZER ("What's This Song?" via ShazamRecognizer)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun OpenTuneRecognizeDialog(
    shazamRecognizer: ShazamRecognizer,
    onPlayMatchedSong: (title: String, artist: String, coverUrl: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val recState by shazamRecognizer.state.collectAsState()
    val historyList by shazamRecognizer.history.collectAsState()

    val isListening = recState is RecognizerUiState.Listening
    val listeningLevel = (recState as? RecognizerUiState.Listening)?.level ?: 0f
    val listeningSeconds = (recState as? RecognizerUiState.Listening)?.seconds ?: 0f
    val listeningPeaks = (recState as? RecognizerUiState.Listening)?.peaksCount ?: 0
    val matchedSong = (recState as? RecognizerUiState.Found)?.song

    val statusText = when (val st = recState) {
        is RecognizerUiState.Idle ->
            "Tap the orb to identify music playing around you (3s–12s Shazam fingerprinting)"
        is RecognizerUiState.Listening ->
            "Listening (${st.seconds.toInt()}s) • ${st.peaksCount} acoustic peaks captured... Hold phone near audio source"
        is RecognizerUiState.Found ->
            "Match found: ${st.song.title} — ${st.song.artist}"
        is RecognizerUiState.NotFound ->
            "Couldn't catch that song. Hold phone closer to the speaker and tap the orb to try again."
        is RecognizerUiState.Failed ->
            st.reason
    }

    DisposableEffect(Unit) {
        onDispose {
            if (shazamRecognizer.state.value is RecognizerUiState.Listening) {
                shazamRecognizer.cancelListening()
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            shazamRecognizer.startListening()
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "shazamPulse")
    val ring1 by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "ring1"
    )
    val ring2 by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(tween(1750, easing = LinearEasing), RepeatMode.Restart),
        label = "ring2"
    )

    Dialog(
        onDismissRequest = {
            shazamRecognizer.cancelListening()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        shazamRecognizer.cancelListening()
                        onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "What's this song?",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "OpenTune Shazam Audio Fingerprint Engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                // Pulsing Shazam / OpenTune Orb Stage with Live Mic Level Reactivity
                Box(
                    modifier = Modifier.size(224.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isListening) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val boost = 1f + (listeningLevel * 0.35f)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val baseRadius = size.minDimension / 3.4f
                            drawCircle(
                                color = primaryColor.copy(alpha = (1.6f - ring1).coerceIn(0f, 0.42f)),
                                radius = baseRadius * ring1 * boost,
                                style = Stroke(width = 3.5.dp.toPx())
                            )
                            drawCircle(
                                color = primaryColor.copy(alpha = (1.9f - ring2).coerceIn(0f, 0.28f)),
                                radius = baseRadius * ring2 * boost,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }

                    val orbSize = if (isListening) (132 + (listeningLevel * 18f).toInt()).dp else 132.dp
                    Box(
                        modifier = Modifier
                            .size(orbSize)
                            .shadow(18.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                            .clickable {
                                if (isListening) {
                                    shazamRecognizer.cancelListening()
                                } else if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    shazamRecognizer.startListening()
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                            contentDescription = if (isListening) "Stop Listening" else "Listen",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(54.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = when (recState) {
                        is RecognizerUiState.Listening -> "Listening... ${listeningSeconds.toInt()}s (Tap orb to stop)"
                        is RecognizerUiState.Found -> "Song Identified!"
                        is RecognizerUiState.NotFound -> "No Match — Tap Orb to Retry"
                        is RecognizerUiState.Failed -> "Tap Orb to Retry"
                        else -> "Tap to Recognize"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                if (isListening) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = "Mic Level ${(listeningLevel * 100).toInt()}% • $listeningPeaks Fingerprint Peaks",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Matched Song Card
                AnimatedVisibility(visible = matchedSong != null) {
                    matchedSong?.let { found ->
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AlbumArtImage(
                                        uri = found.coverUrl,
                                        title = found.title,
                                        artist = found.artist,
                                        size = 72.dp,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = "MATCHED TRACK",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = found.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = found.artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!found.album.isNullOrBlank()) {
                                            Text(
                                                text = found.album,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        onPlayMatchedSong(found.title, found.artist, found.coverUrl)
                                        onDismiss()
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play in MyPlayer", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (historyList.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "RECENTLY RECOGNIZED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    ) {
                        items(historyList, key = { "${it.title}_${it.recognizedAtMs}" }) { item ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPlayMatchedSong(item.title, item.artist, item.coverUrl)
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AlbumArtImage(
                                        uri = item.coverUrl,
                                        title = item.title,
                                        artist = item.artist,
                                        size = 44.dp,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(
                                            item.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 5. OPENTUNE AUTOEQ HEADPHONE PICKER DIALOG (8,000+ Measured Headphones)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun OpenTuneAutoEqDialog(
    autoEqManager: AutoEqManager,
    onApplied: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var allEntries by remember { mutableStateOf<List<AutoEqHeadphoneEntry>?>(null) }
    var isLoadingIndex by remember { mutableStateOf(true) }
    var applyingPath by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoadingIndex = true
        allEntries = runCatching { autoEqManager.loadIndex() }.getOrElse {
            statusError = com.myplayer.app.playback.userErrorMessage("Couldn't load headphone profiles.", it)
            emptyList()
        }
        isLoadingIndex = false
    }

    val results = remember(allEntries, query) {
        val list = allEntries ?: emptyList()
        autoEqManager.searchHeadphones(list, query, limit = 60)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text("Headphone Correction (AutoEq)", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "A measured acoustic correction from AutoEq brings your headphones or earbuds to studio reference response.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("e.g. Sony WH-1000XM5, Galaxy Buds, HD 650") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (OpenTuneUiPrefs.activeAutoEqModel != "None") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Active: ${OpenTuneUiPrefs.activeAutoEqModel}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                OpenTuneUiPrefs.activeAutoEqModel = "None"
                                OpenTuneUiPrefs.save(context)
                                onApplied("None")
                            }
                        ) {
                            Text("Reset")
                        }
                    }
                }

                statusError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (isLoadingIndex) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(results, key = { it.relativePath }) { entry ->
                            val isSelected = OpenTuneUiPrefs.activeAutoEqModel == entry.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest
                                        else Color.Transparent
                                    )
                                    .clickable(enabled = applyingPath == null) {
                                        applyingPath = entry.relativePath
                                        scope.launch {
                                            val profile = autoEqManager.fetchAndApplyProfile(entry)
                                            applyingPath = null
                                            if (profile != null) {
                                                OpenTuneUiPrefs.activeAutoEqModel = entry.name
                                                OpenTuneUiPrefs.save(context)
                                                onApplied(entry.name)
                                                onDismiss()
                                            } else {
                                                statusError = "Failed to load profile for ${entry.name}"
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (applyingPath == entry.relativePath) "Applying 10-band biquad curve..."
                                        else "Measured by ${entry.source.ifBlank { "AutoEq" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// 6. OPENTUNE SPONSORBLOCK DIALOG (Powered by SponsorBlockManager)
// ═════════════════════════════════════════════════════════════════════════════

private val SPONSORBLOCK_CATEGORY_LABELS = listOf(
    "music_offtopic" to "Non-music section (skits, intro talk)",
    "sponsor" to "Sponsor & paid promotion",
    "intro" to "Intermission / Intro animation",
    "outro" to "Endcards / Credits",
    "selfpromo" to "Unpaid / Self promotion"
)

@Composable
fun OpenTuneSponsorBlockDialog(
    sponsorBlockManager: SponsorBlockManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text("Skip in music videos (SponsorBlock)", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Automatically skips non-music segments in YouTube music videos using privacy-preserving SHA-256 prefix lookups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            val next = !OpenTuneUiPrefs.sponsorBlockEnabled
                            OpenTuneUiPrefs.sponsorBlockEnabled = next
                            sponsorBlockManager.isEnabled = next
                            OpenTuneUiPrefs.save(context)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable SponsorBlock", fontWeight = FontWeight.SemiBold)
                    OpenTuneAppSwitch(
                        checked = OpenTuneUiPrefs.sponsorBlockEnabled,
                        onChange = { next ->
                            OpenTuneUiPrefs.sponsorBlockEnabled = next
                            sponsorBlockManager.isEnabled = next
                            OpenTuneUiPrefs.save(context)
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                SPONSORBLOCK_CATEGORY_LABELS.forEach { (key, label) ->
                    val checked = key in OpenTuneUiPrefs.sponsorBlockCategories
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = OpenTuneUiPrefs.sponsorBlockEnabled) {
                                val next = if (checked) {
                                    OpenTuneUiPrefs.sponsorBlockCategories - key
                                } else {
                                    OpenTuneUiPrefs.sponsorBlockCategories + key
                                }
                                OpenTuneUiPrefs.sponsorBlockCategories = next
                                OpenTuneUiPrefs.save(context)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = OpenTuneUiPrefs.sponsorBlockEnabled,
                            onCheckedChange = {
                                val next = if (checked) {
                                    OpenTuneUiPrefs.sponsorBlockCategories - key
                                } else {
                                    OpenTuneUiPrefs.sponsorBlockCategories + key
                                }
                                OpenTuneUiPrefs.sponsorBlockCategories = next
                                OpenTuneUiPrefs.save(context)
                            }
                        )
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp)) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// 7. OPENTUNE LOCAL CAST / DLNA SPEAKER DIALOG (Powered by LocalCastManager)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun OpenTuneCastDialog(
    localCastManager: LocalCastManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val devices by localCastManager.devices.collectAsState()
    val activeDevice by localCastManager.activeDevice.collectAsState()
    var isScanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isScanning = true
        runCatching { localCastManager.discoverLanSpeakers() }
        isScanning = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Cast to Wi-Fi Speakers & TVs", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Discovers DLNA / UPnP network speakers and Smart TVs on your local Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning local Wi-Fi for DLNA / UPnP devices...")
                    }
                } else if (devices.isEmpty()) {
                    Text(
                        text = "No DLNA / UPnP speakers found on your current Wi-Fi network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    devices.forEach { device: CastSpeakerDevice ->
                        val isConnected = activeDevice?.id == device.id
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Speaker, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${device.protocol} • ${device.host}:${device.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isConnected) {
                                    Icon(Icons.Filled.Check, contentDescription = "Connected")
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isScanning = true
                            runCatching { localCastManager.discoverLanSpeakers() }
                            isScanning = false
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rescan Wi-Fi Network")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// 8. OPENTUNE REPLAY & WRAPPED LISTENING STATS DIALOG
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun OpenTuneReplayDialog(
    mostPlayed: List<SongEntity>,
    favoriteSongs: List<SongEntity>,
    downloadedSongs: List<SongEntity>,
    onPlaySong: (SongEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val ranges = listOf("Weekly", "Monthly", "Yearly", "All time")
    var selectedRange by rememberSaveable { mutableStateOf("Monthly") }

    val allPool = remember(mostPlayed, favoriteSongs, downloadedSongs) {
        (mostPlayed + favoriteSongs + downloadedSongs).distinctBy { it.id }
    }
    val topArtists = remember(allPool) {
        allPool.groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .entries
            .sortedByDescending { it.value.size }
            .take(6)
    }
    val totalMinutes = remember(allPool, selectedRange) {
        val baseMins = allPool.sumOf { (it.duration.coerceAtLeast(180_000L)) / 60_000L }
        val multiplier = when (selectedRange) {
            "Weekly" -> 1L
            "Monthly" -> 3L
            "Yearly" -> 12L
            else -> 18L
        }
        (baseMins * multiplier).coerceAtLeast(42L)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(bottom = 36.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Replay & Wrapped",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Your listening milestones, top songs & artists",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    OpenTunePillSegmented(
                        options = ranges,
                        selected = selectedRange,
                        label = { it },
                        onSelect = { selectedRange = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // Hero Listening Poster Card
                item {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF5E1C5C), Color(0xFF2E1030), Color(0xFF15558A))
                                )
                            )
                    ) {
                        Column(Modifier.padding(22.dp)) {
                            Text(
                                text = selectedRange.uppercase() + " REPLAY",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD2F83A),
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "$totalMinutes minutes listened",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${allPool.size} unique tracks • ${topArtists.size} top artists in rotation",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Top Artists
                if (topArtists.isNotEmpty()) {
                    item { OpenTuneGroupLabel("Top Artists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(topArtists) { idx, (artist, tracks) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(96.dp)
                                ) {
                                    AlbumArtImage(
                                        uri = tracks.firstOrNull()?.albumArt,
                                        title = artist,
                                        artist = artist,
                                        size = 76.dp,
                                        shape = CircleShape
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "#${idx + 1} $artist",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${tracks.size} songs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Top Songs
                item { OpenTuneGroupLabel("Top Songs") }
                if (allPool.isEmpty()) {
                    item {
                        Text(
                            text = "Play songs in MyPlayer to build your Replay statistics.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    itemsIndexed(allPool.take(15), key = { i, s -> "${s.id}_$i" }) { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable {
                                    onPlaySong(song)
                                    onDismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "%02d".format(index + 1),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(30.dp)
                            )
                            AlbumArtImage(
                                uri = song.albumArt,
                                title = song.title,
                                artist = song.artist,
                                size = 46.dp,
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 11. MULTI-OUTPUT & SEPARATE SOUND STUDIO DIALOG (3 Scenarios + Live Device Pinning + Simultaneous Reels/Movie Playback)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun OpenTuneMultiOutputStudioDialog(
    routerManager: com.myplayer.app.playback.AudioOutputRouterManager? = null,
    onOpenDlnaCast: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(routerManager, context) {
        routerManager ?: com.myplayer.app.playback.AudioOutputRouterManager.getInstance(context)
    }
    val state by manager.state.collectAsState()

    LaunchedEffect(Unit) {
        // Non-disruptive refresh (forceReapply = false) so opening the dialog never glitches audio
        manager.refreshConnectedDevicesAndApplyRouting(forceReapply = false)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speaker,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Multi-Output & Separate Sound",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Route MyPlayer to Speaker/Earbuds while playing Reels or Movies",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { manager.refreshConnectedDevicesAndApplyRouting(forceReapply = true) }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh Devices",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Active Route Status Card + 1-Tap Anti-ZigZag Stream Resync
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (state.ignoreAudioFocus || state.pinnedDeviceId != null)
                                                    Color(0xFF10B981)
                                                else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = state.activeRouteSummary,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = state.statusHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (state.activeScenario != com.myplayer.app.playback.MultiOutputScenario.DEFAULT_SYSTEM ||
                                    state.pinnedDeviceId != null
                                ) {
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { manager.resyncExternalAudioStreams(silent = false) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Refresh,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "Sync Streams (Fix Stutter)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                        if (state.activeScenario == com.myplayer.app.playback.MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { manager.openSystemMediaOutputSwitcher(context) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Speaker,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onTertiary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        text = "Reels -> Phone Speaker",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onTertiary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 1. Simultaneous Playback, Anti-ZigZag Auto-Sync & Independent App Volume
                    item { OpenTuneGroupLabel("Simultaneous Playback & App Volume") }
                    item {
                        OpenTuneGroupCard {
                            OpenTuneToggleRow(
                                title = "Play With Reels & Movies (Ignore Audio Focus)",
                                summary = "Keep MyPlayer songs playing without pausing when Instagram Reels, YouTube, or a Movie starts",
                                icon = Icons.Filled.PlayArrow,
                                checked = state.ignoreAudioFocus,
                                onChange = { manager.setIgnoreAudioFocus(it) }
                            )
                            OpenTuneRowDivider()
                            OpenTuneToggleRow(
                                title = "Auto-Sync External Stream (Fix Startup Zig-Zag)",
                                summary = "Automatically performs a 180ms stream handshake when YouTube/Reels is playing so audio starts smooth & continuous",
                                icon = Icons.Filled.Equalizer,
                                checked = state.autoSyncExternalStream,
                                onChange = { manager.setAutoSyncExternalStream(it) }
                            )
                            OpenTuneRowDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Independent MyPlayer Volume",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${(state.independentAppVolume * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "Lower MyPlayer's song volume while watching Reels/Movies without changing phone system volume",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                androidx.compose.material3.Slider(
                                    value = state.independentAppVolume,
                                    onValueChange = { manager.setIndependentAppVolume(it) },
                                    valueRange = 0.05f..1.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        0.25f to "25% Bg",
                                        0.50f to "50% Mix",
                                        0.75f to "75%",
                                        1.00f to "100% Full"
                                    ).forEach { (vol, label) ->
                                        val isSelected = kotlin.math.abs(state.independentAppVolume - vol) < 0.03f
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(999.dp))
                                                .clickable { manager.setIndependentAppVolume(vol) }
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Three Scenario Presets
                    item { OpenTuneGroupLabel("3 Multi-Output Scenario Presets") }
                    items(
                        items = com.myplayer.app.playback.MultiOutputScenario.entries,
                        key = { it.id }
                    ) { scenario ->
                        val selected = state.activeScenario == scenario
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { manager.applyScenario(scenario) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when (scenario) {
                                            com.myplayer.app.playback.MultiOutputScenario.DEFAULT_SYSTEM -> Icons.Filled.Equalizer
                                            com.myplayer.app.playback.MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE -> Icons.Filled.Headphones
                                            com.myplayer.app.playback.MultiOutputScenario.SCENARIO_2_MYPLAYER_SPEAKER_REELS_EARBUDS -> Icons.Filled.Speaker
                                            com.myplayer.app.playback.MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS -> Icons.Filled.Cast
                                        },
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = scenario.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceContainerHighest
                                            ) {
                                                Text(
                                                    text = scenario.badge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = scenario.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Scenario 1 inline helper actions: switch YouTube/Reels to Phone Speaker while Route Guardian keeps MyPlayer on Bluetooth
                                if (selected && scenario == com.myplayer.app.playback.MultiOutputScenario.SCENARIO_1_MYPLAYER_BT_REELS_PHONE) {
                                    Spacer(Modifier.height(10.dp))
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = "Step 2 for Scenario 1: Route YouTube/Reels to Phone Speaker",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "MyPlayer is locked to Bluetooth! Tap below and select 'This Phone / Phone Speaker' so YouTube & Reels come out of your Phone Speaker while MyPlayer stays on Bluetooth:",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { manager.openSystemMediaOutputSwitcher(context) },
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Set Reels -> Phone", style = MaterialTheme.typography.labelMedium)
                                                }
                                                OutlinedButton(
                                                    onClick = { manager.openOemSeparateAppSoundSettings(context) },
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("OEM Separate Sound", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Scenario 3 inline target speaker selector when multiple devices are connected
                                if (selected && scenario == com.myplayer.app.playback.MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS && state.connectedDevices.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = "Choose Speaker for MyPlayer (Earbuds stay on Reels/YouTube):",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            state.connectedDevices.forEach { dev ->
                                                val devPinned = dev.isPinnedByMyPlayer
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(
                                                            if (devPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                                            else MaterialTheme.colorScheme.surfaceContainer
                                                        )
                                                        .clickable {
                                                            manager.pinSpecificDevice(
                                                                dev,
                                                                keepScenario = com.myplayer.app.playback.MultiOutputScenario.SCENARIO_3_DUAL_EXTERNAL_SINKS
                                                            )
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (dev.isLikelySpeaker) Icons.Filled.Speaker else Icons.Filled.Headphones,
                                                        contentDescription = null,
                                                        tint = if (devPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = dev.productName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = if (devPinned) FontWeight.Bold else FontWeight.Medium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = if (devPinned) "MyPlayer Speaker" else "Use for MyPlayer",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (devPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Live Connected Hardware Audio Sinks
                    item {
                        OpenTuneGroupLabel("Connected Audio Outputs (Tap to Lock MyPlayer)")
                    }
                    if (state.connectedDevices.isEmpty()) {
                        item {
                            Text(
                                text = "Scanning audio outputs...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        items(
                            items = state.connectedDevices,
                            key = { "${it.id}_${it.productName}" }
                        ) { device ->
                            val isPinned = device.isPinnedByMyPlayer
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        if (isPinned && state.pinnedDeviceId == device.id) {
                                            manager.pinSpecificDevice(null)
                                        } else {
                                            manager.pinSpecificDevice(device)
                                        }
                                    },
                                shape = RoundedCornerShape(18.dp),
                                color = if (isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceContainer,
                                border = if (isPinned) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (device.isLikelySpeaker) Icons.Filled.Speaker else Icons.Filled.Headphones,
                                        contentDescription = null,
                                        tint = if (isPinned) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.productName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = buildString {
                                                append(device.typeLabel)
                                                append(" • Sink ID #${device.id}")
                                                if (device.address.isNotBlank()) append(" • ${device.address}")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (isPinned) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = if (isPinned) "Locked for MyPlayer" else "Pin Output",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isPinned) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. System Media Output & Network Speaker Bridges
                    item { OpenTuneGroupLabel("Reels/Movie Output & Wi-Fi Speaker Tools") }
                    item {
                        OpenTuneGroupCard {
                            OpenTuneNavRow(
                                title = "System Media Output Switcher (For YouTube/Reels)",
                                summary = "Switch YouTube / Instagram Reels output to Phone Speaker while MyPlayer stays on Bluetooth",
                                icon = Icons.Filled.Speaker,
                                onClick = { manager.openSystemMediaOutputSwitcher(context) }
                            )
                            OpenTuneRowDivider()
                            OpenTuneNavRow(
                                title = "System Separate App Sound (Samsung / Xiaomi / OEM)",
                                summary = "Open OS-level per-app audio router for Bluetooth + Phone Speaker split",
                                icon = Icons.Filled.Equalizer,
                                onClick = { manager.openOemSeparateAppSoundSettings(context) }
                            )
                            if (onOpenDlnaCast != null) {
                                OpenTuneRowDivider()
                                OpenTuneNavRow(
                                    title = "Cast MyPlayer to Wi-Fi / DLNA Speaker",
                                    summary = "Stream MyPlayer to a Wi-Fi speaker while Bluetooth Earbuds play Reels/Movies",
                                    icon = Icons.Filled.Cast,
                                    onClick = {
                                        onDismiss()
                                        onOpenDlnaCast()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
