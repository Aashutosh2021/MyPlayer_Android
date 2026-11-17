package com.myplayer.app.ui.screens.home

import java.util.Calendar
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myplayer.app.R
import com.myplayer.app.data.local.entity.SongEntity
import com.myplayer.app.data.recommendation.model.RecommendationSong
import com.myplayer.app.ui.common.AlbumArtImage
import com.myplayer.app.ui.opentune.OpenTuneDaylistHeroCard
import com.myplayer.app.ui.opentune.OpenTuneMadeForYouMixesShelf
import com.myplayer.app.ui.opentune.OpenTuneQuickPicksSection
import com.myplayer.app.ui.opentune.OpenTuneRecentsBottomSheet
import com.myplayer.app.ui.opentune.OpenTuneRecognizeDialog
import com.myplayer.app.ui.opentune.OpenTuneSpeedDialFullDialog
import com.myplayer.app.ui.opentune.OpenTuneSpeedDialItem
import com.myplayer.app.ui.opentune.OpenTuneSpeedDialSection
import com.myplayer.app.ui.opentune.OpenTuneUiPrefs
import com.myplayer.app.ui.theme.CardBorderOlive
import com.myplayer.app.ui.theme.NeonLimePrimary
import com.myplayer.app.ui.theme.SurfaceContainerHigh
import com.myplayer.app.ui.theme.ThemeState
import com.myplayer.app.ui.theme.bouncyPopClick
import com.myplayer.app.ui.theme.carouselSlidePop
import com.myplayer.app.ui.theme.slidePopIn

@Composable
fun HomeScreen(
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    bottomPadding: Dp = 100.dp,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        OpenTuneUiPrefs.load(context)
    }

    val recentSongs by viewModel.recentSongs.collectAsStateWithLifecycle()
    val mostPlayed by viewModel.mostPlayed.collectAsStateWithLifecycle()
    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val hotlistSongs by viewModel.hotlistSongs.collectAsStateWithLifecycle()
    val isLoadingRecommendations by viewModel.isLoadingRecommendations.collectAsStateWithLifecycle()

    val popularPool = remember(mostPlayed, recentSongs, allSongs) {
        (mostPlayed.ifEmpty { recentSongs.ifEmpty { allSongs } }).take(8)
    }

    val showTasteOnboarding by viewModel.showTasteOnboarding.collectAsStateWithLifecycle()
    val selectedLanguages by viewModel.selectedLanguages.collectAsStateWithLifecycle()
    val selectedGenres by viewModel.selectedGenres.collectAsStateWithLifecycle()
    val selectedArtists by viewModel.selectedArtists.collectAsStateWithLifecycle()

    var showEditNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var showSpeedDialAll by remember { mutableStateOf(false) }
    var showRecentsSheet by remember { mutableStateOf(false) }
    var showRecognizeDialog by remember { mutableStateOf(false) }

    if (showTasteOnboarding) {
        TasteOnboardingDialog(
            initialLanguages = selectedLanguages,
            initialGenres = selectedGenres,
            initialArtists = selectedArtists,
            onDismiss = { viewModel.dismissTasteSelector() },
            onSave = { langs, genres, artists ->
                viewModel.saveUserTastePreferences(langs, genres, artists)
            }
        )
    }

    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    BackHandler(enabled = selectedCategory != "All") {
        viewModel.selectCategory("All")
    }
    val displayedDownloadedSongs by viewModel.displayedDownloadedSongs.collectAsStateWithLifecycle()
    val categories = remember {
        listOf(
            "All",
            "Energize",
            "Relax",
            "Workout",
            "Focus",
            "Party",
            "Romance",
            "Sad",
            "Sleep",
            "Deep Focus",
            "Acoustic Chill",
            "Late Night",
            "Discoveries",
            "Hip Hop",
            "Chill",
            "Pop"
        )
    }

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Late night"
        }
    }

    // Build unified OpenTune Speed Dial items from local favorites, mostPlayed, recents & AI recommendations
    val speedDialItems = remember(mostPlayed, favoriteSongs, recentSongs, recommendations) {
        val localCombined = (mostPlayed + favoriteSongs + recentSongs).distinctBy { it.id }
        val localDial = localCombined.mapIndexed { idx, song ->
            OpenTuneSpeedDialItem(
                id = "local_${song.id}",
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.albumArt,
                onPlay = {
                    viewModel.playSong(localCombined, idx)
                    onNavigateToNowPlaying()
                }
            )
        }
        val onlineDial = recommendations.mapIndexed { idx, reco ->
            OpenTuneSpeedDialItem(
                id = "reco_${reco.videoId}",
                title = reco.title,
                subtitle = reco.artist,
                thumbnailUrl = reco.thumbnailUrl,
                onPlay = {
                    viewModel.playRecommendedSongs(recommendations, idx)
                    onNavigateToNowPlaying()
                }
            )
        }
        (localDial + onlineDial).distinctBy { "${it.title.lowercase()}_${it.subtitle.lowercase()}" }.take(27)
    }

    if (showSpeedDialAll) {
        OpenTuneSpeedDialFullDialog(
            items = speedDialItems,
            onDismiss = { showSpeedDialAll = false }
        )
    }

    if (showRecentsSheet) {
        val recentsPool = remember(recentSongs, mostPlayed, allSongs) {
            (recentSongs + mostPlayed + allSongs).distinctBy { it.id }
        }
        OpenTuneRecentsBottomSheet(
            recentSongs = recentsPool,
            onPlaySongAt = { idx ->
                viewModel.playSong(recentsPool, idx)
                onNavigateToNowPlaying()
            },
            onDismiss = { showRecentsSheet = false }
        )
    }

    if (com.myplayer.app.BuildConfig.DEBUG && showRecognizeDialog) {
        OpenTuneRecognizeDialog(
            shazamRecognizer = viewModel.shazamRecognizer,
            onPlayMatchedSong = { title, artist, coverUrl ->
                viewModel.playRecognizedSong(title, artist, coverUrl) {
                    onNavigateToNowPlaying()
                }
            },
            onDismiss = { showRecognizeDialog = false }
        )
    }

    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Set Display Name", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter your name to personalize your player experience:")
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Your Name", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nameInput.isNotBlank()) {
                            viewModel.setUserName(nameInput.trim())
                        }
                        showEditNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditNameDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val jumpBackInSongs = remember(popularPool, recentSongs, allSongs) {
        (recentSongs.ifEmpty { popularPool.ifEmpty { allSongs } }).take(8)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = bottomPadding + 28.dp)
    ) {
        // ── OpenTune + MyPlayer Merged Greeting & Top Action Bar ─────────────
        item(key = "home_top_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            nameInput = userName
                            showEditNameDialog = true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.inside_logo),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp)
                    )
                    Column {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = userName.ifBlank { "MyPlayer" },
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Right Action Glass Buttons: Shazam Recognize | Theme | Settings
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Shazam Recognize ("What's this song?") - debug only
                    if (com.myplayer.app.BuildConfig.DEBUG) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape)
                                .clickable { showRecognizeDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = "Recognize song nearby",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    
                    // 3. Day / Night Theme Switcher
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape)
                            .clickable { ThemeState.toggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (ThemeState.isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "Switch Theme",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }                    
                }
            }
        }

        // ── OpenTune Streak & Quick Stats Pill Row ───────────────────────────
        if (OpenTuneUiPrefs.showStreakPill) {
            item(key = "home_streak_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocalFireDepartment,
                                    contentDescription = null,
                                    tint = Color(0xFFF97316),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Daily Listening Streak Active",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Category & Mood Filter Pills ─────────────────────────────────────
        item(key = "home_category_pills") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                item {
                    AssistChip(
                        onClick = { viewModel.openTasteSelector() },
                        label = { Text("Music Taste", fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = "Customize Music Taste",
                                tint = NeonLimePrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, CardBorderOlive),
                        shape = RoundedCornerShape(24.dp)
                    )
                }

                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    val pillBg by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        label = "pillBg_$category"
                    )
                    val textTint by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "textTint_$category"
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(pillBg)
                            .then(
                                if (!isSelected) Modifier.border(
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    RoundedCornerShape(24.dp)
                                ) else Modifier
                            )
                            .clickable { viewModel.selectCategory(category) }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textTint
                        )
                    }
                }
            }
        }

        if (selectedCategory == "All") {
            // ── 1. OpenTune Speed Dial (3x3 Paged Square Grid) ───────────────
            if (OpenTuneUiPrefs.showSpeedDialOnHome && speedDialItems.isNotEmpty()) {
                item(key = "opentune_speed_dial") {
                    OpenTuneSpeedDialSection(
                        items = speedDialItems,
                        userName = userName,
                        onOpenAll = { showSpeedDialAll = true }
                    )
                }
            }

            // ── 2. OpenTune Daylist Hero Banner ──────────────────────────────
            if (OpenTuneUiPrefs.showDaylistOnHome && recommendations.isNotEmpty()) {
                item(key = "opentune_daylist_hero") {
                    OpenTuneDaylistHeroCard(
                        songs = recommendations,
                        onPlayMix = {
                            viewModel.playRecommendedSongs(recommendations, 0)
                            onNavigateToNowPlaying()
                        }
                    )
                }
            }

            // ── 3. OpenTune Quick Picks (4-Row Horizontal Pager) ─────────────
            if (OpenTuneUiPrefs.showQuickPicksOnHome && recommendations.isNotEmpty()) {
                item(key = "opentune_quick_picks") {
                    OpenTuneQuickPicksSection(
                        songs = recommendations,
                        currentPlayingId = currentSong?.videoId ?: currentSong?.id,
                        onPlaySongAt = { index ->
                            viewModel.playRecommendedSongs(recommendations, index)
                            onNavigateToNowPlaying()
                        },
                        onOpenRecents = { showRecentsSheet = true },
                        onPlayAll = {
                            viewModel.playRecommendedSongs(recommendations, 0)
                            onNavigateToNowPlaying()
                        }
                    )
                }
            }

            // ── 4. OpenTune "Made For You Today" Mixes Shelf ─────────────────
            if (OpenTuneUiPrefs.showMixesOnHome && (recommendations.isNotEmpty() || hotlistSongs.isNotEmpty())) {
                item(key = "opentune_made_for_you_mixes") {
                    OpenTuneMadeForYouMixesShelf(
                        recommendations = recommendations,
                        hotlistSongs = hotlistSongs,
                        onPlayMix = { mixSongs ->
                            viewModel.playRecommendedSongs(mixSongs, 0)
                            onNavigateToNowPlaying()
                        }
                    )
                }
            }

            // ── 5. MyPlayer Preserved: Jump Back In (Horizontal Scroll) ──────
            if (jumpBackInSongs.isNotEmpty()) {
                item(key = "jump_back_in_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Jump Back In",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                item(key = "jump_back_in_row") {
                    val jumpBackInListState = rememberLazyListState()
                    LazyRow(
                        state = jumpBackInListState,
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        itemsIndexed(jumpBackInSongs, key = { _, song -> song.id }) { index, song ->
                            JumpBackInCard(
                                song = song,
                                isPlaying = currentSong?.id == song.id,
                                modifier = Modifier.carouselSlidePop(jumpBackInListState, index),
                                onClick = {
                                    viewModel.playSong(jumpBackInSongs, index)
                                    onNavigateToNowPlaying()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ── 6. MyPlayer Preserved: Your Quick Mixes (2x2 Bento Tiles) ────
            // ── 7. MyPlayer Preserved: Recommended For You Track List ────────
            item(key = "recommended_for_you_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recommended For You",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = {
                            if (recommendations.isNotEmpty()) {
                                viewModel.playRecommendedSongs(recommendations, 0)
                                onNavigateToNowPlaying()
                            } else if (allSongs.isNotEmpty()) {
                                viewModel.playSong(allSongs, 0)
                                onNavigateToNowPlaying()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Play all", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (recommendations.isNotEmpty()) {
                itemsIndexed(recommendations.take(12), key = { index, reco -> "${reco.videoId}_$index" }) { index, reco ->
                    val isPlaying = currentSong?.videoId == reco.videoId || currentSong?.id == reco.videoId
                    StitchTrackRow(
                        title = reco.title,
                        artist = reco.artist,
                        album = reco.album,
                        artUri = reco.thumbnailUrl,
                        durationMs = reco.durationMs,
                        isPlaying = isPlaying,
                        modifier = Modifier.padding(horizontal = 12.dp).slidePopIn(index),
                        onClick = {
                            viewModel.playRecommendedSongs(recommendations, index)
                            onNavigateToNowPlaying()
                        }
                    )
                }
            } else if (allSongs.isNotEmpty()) {
                itemsIndexed(allSongs.take(12), key = { _, s -> s.id }) { index, s ->
                    val isPlaying = currentSong?.id == s.id
                    StitchTrackRow(
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        artUri = s.albumArt,
                        durationMs = s.duration,
                        isPlaying = isPlaying,
                        modifier = Modifier.padding(horizontal = 12.dp).slidePopIn(index),
                        onClick = {
                            viewModel.playSong(allSongs, index)
                            onNavigateToNowPlaying()
                        }
                    )
                }
            }
        } else {
            // ── Category Specific Mode (Energize, Relax, Workout, Deep Focus, etc.) ─
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { viewModel.selectCategory("All") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to All",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = selectedCategory,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (recommendations.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.playRecommendedSongs(recommendations, 0)
                                onNavigateToNowPlaying()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Play Mix",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            if (displayedDownloadedSongs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloaded",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${displayedDownloadedSongs.size} songs",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                item {
                    val downloadedState = rememberLazyListState()
                    LazyRow(
                        state = downloadedState,
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 10.dp)
                    ) {
                        itemsIndexed(displayedDownloadedSongs, key = { _, song -> song.id }) { index, song ->
                            PopularSongCard(
                                song = song,
                                isPlaying = currentSong?.id == song.id,
                                modifier = Modifier.carouselSlidePop(downloadedState, index),
                                onClick = {
                                    viewModel.playSong(displayedDownloadedSongs, index)
                                    onNavigateToNowPlaying()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "No downloaded $selectedCategory songs",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Stream from 30+ $selectedCategory recommendations below",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (recommendations.isEmpty() && isLoadingRecommendations) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Loading $selectedCategory tracks...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$selectedCategory Recommendations",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (recommendations.isNotEmpty()) {
                        Text(
                            text = "${recommendations.size} tracks",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                val catRecoState = rememberLazyListState()
                LazyRow(
                    state = catRecoState,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (recommendations.isNotEmpty()) {
                        itemsIndexed(recommendations.take(10), key = { index, reco -> "${reco.videoId}_$index" }) { index, reco ->
                            RecommendedSongCard(
                                song = reco,
                                isPlaying = currentSong?.videoId == reco.videoId || currentSong?.id == reco.videoId,
                                modifier = Modifier.carouselSlidePop(catRecoState, index),
                                onClick = {
                                    viewModel.playRecommendedSongs(recommendations, index)
                                    onNavigateToNowPlaying()
                                }
                            )
                        }
                    } else {
                        items(4) {
                            RecommendationPlaceholderCard()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (recommendations.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "All $selectedCategory Tracks (${recommendations.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                itemsIndexed(recommendations, key = { index, s -> "${s.videoId}_$index" }) { index, reco ->
                    DarkRecommendationListItem(
                        index = index,
                        song = reco,
                        isPlaying = currentSong?.videoId == reco.videoId || currentSong?.id == reco.videoId,
                        onClick = {
                            viewModel.playRecommendedSongs(recommendations, index)
                            onNavigateToNowPlaying()
                        }
                    )
                }
                item {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (allSongs.isEmpty() && recentSongs.isEmpty() && recommendations.isEmpty()) {
            item { DarkEmptyState() }
        }
    }
}

@Composable
private fun RecommendationPlaceholderCard() {
    Box(
        modifier = Modifier
            .width(156.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
            .padding(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
    }
}

@Composable
private fun RecommendedSongCard(
    song: RecommendationSong,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(
                    1.dp,
                    if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
                ),
                RoundedCornerShape(20.dp)
            )
            .bouncyPopClick(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtImage(
                    uri = song.thumbnailUrl,
                    title = song.title,
                    artist = song.artist,
                    size = 132.dp,
                    shape = RoundedCornerShape(14.dp),
                    iconSize = 44.dp
                )

                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedEqualizerBars(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
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
    }
}

@Composable
private fun DarkRecommendationListItem(
    index: Int = 0,
    song: RecommendationSong,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val durSec = (song.durationMs / 1000).coerceAtLeast(0L)
    val durText = if (durSec > 0) " • %d:%02d".format(durSec / 60, durSec % 60) else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .slidePopIn(index)
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .bouncyPopClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(34.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isPlaying) {
                Icon(
                    Icons.Filled.Equalizer,
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = "%02d".format(index + 1),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${song.artist}$durText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            Icons.Filled.MoreHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PopularSongCard(
    song: SongEntity,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(
                    1.dp,
                    if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
                ),
                RoundedCornerShape(20.dp)
            )
            .bouncyPopClick(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtImage(
                    uri = song.albumArt,
                    title = song.title,
                    artist = song.artist,
                    size = 132.dp,
                    shape = RoundedCornerShape(14.dp),
                    iconSize = 44.dp
                )

                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedEqualizerBars(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
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
    }
}

@Composable
private fun DarkEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("No music yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "Search online to stream or download songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun formatDurationHome(durationMs: Long): String {
    val s = durationMs / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun AnimatedEqualizerBars(
    modifier: Modifier = Modifier.size(18.dp),
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "eqBars")
    val bar1 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "b1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.85f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse), label = "b2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.4f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "b3"
    )

    Canvas(modifier = modifier) {
        val barWidth = size.width / 5f
        val maxH = size.height
        val heights = floatArrayOf(bar1, bar2, bar3)
        for (i in 0..2) {
            val h = (heights[i] * maxH).coerceAtLeast(barWidth)
            val x = i * (barWidth * 1.8f) + barWidth / 2f
            drawLine(
                color = color,
                start = Offset(x, maxH - (maxH - h) / 2f),
                end = Offset(x, (maxH - h) / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun JumpBackInCard(
    song: SongEntity,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .width(144.dp)
            .bouncyPopClick(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(144.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = Color(0x20171C26))
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            AlbumArtImage(
                uri = song.albumArt,
                title = song.title,
                artist = song.artist,
                size = 144.dp,
                shape = RoundedCornerShape(16.dp),
                iconSize = 52.dp
            )

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(6.dp, CircleShape, spotColor = Color(0x33000000))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399))
                )
                Text(
                    text = "Recent",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = song.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun StitchTrackRow(
    title: String,
    artist: String,
    album: String?,
    artUri: String?,
    durationMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .bouncyPopClick(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            AlbumArtImage(
                uri = artUri,
                title = title,
                artist = artist,
                size = 48.dp,
                shape = RoundedCornerShape(10.dp),
                iconSize = 22.dp
            )

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedEqualizerBars(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPlaying) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$artist${if (!album.isNullOrBlank()) " • $album" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (durationMs > 0) {
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            Text(
                text = String.format("%d:%02d", minutes, seconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }

        IconButton(
            onClick = { /* more options */ },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
