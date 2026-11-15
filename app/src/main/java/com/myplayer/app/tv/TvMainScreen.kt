package com.myplayer.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.myplayer.app.data.local.entity.SongEntity
import com.myplayer.app.data.recommendation.model.RecommendationSong
import com.myplayer.app.ui.common.AlbumArtImage
import com.myplayer.app.ui.screens.home.HomeViewModel
import com.myplayer.app.ui.screens.main.MainViewModel
import com.myplayer.app.ui.screens.nowplaying.LyricsUiState
import com.myplayer.app.ui.screens.nowplaying.LyricsViewModel
import com.myplayer.app.ui.screens.search.OnlineSearchViewModel

enum class TvDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    HOTLIST("Hotlist", Icons.Rounded.Whatshot),
    SEARCH("Search", Icons.Rounded.Search),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    NOW_PLAYING("Now Playing", Icons.Rounded.Album),
    PHONE_REMOTE("Phone Remote", Icons.Rounded.QrCode2)
}

/**
 * OpenTune TvSurface ported to MyPlayer TV:
 * Every card, pill, and button scales up (1.06x) with a glowing white focus border when
 * navigated via Android TV D-Pad Remote or hovered with a pointer/mouse!
 */
@Composable
fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    focusedScale: Float = 1.06f,
    containerColor: Color = Color(0xFF181826),
    focusedContainerColor: Color = Color(0xFF2B2244),
    showFocusRing: Boolean = true,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isFocused || isHovered

    val scale by animateFloatAsState(
        targetValue = if (highlighted) focusedScale else 1f,
        animationSpec = tween(140),
        label = "tvScale"
    )
    val bg by animateColorAsState(
        targetValue = if (highlighted) focusedContainerColor else containerColor,
        animationSpec = tween(140),
        label = "tvBg"
    )

    Box(
        modifier = modifier
            .zIndex(if (highlighted) 2f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(bg)
            .then(
                if (highlighted && showFocusRing) {
                    Modifier.border(2.5.dp, Color.White, shape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                }
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        content = { content(highlighted) }
    )
}

@Composable
fun TvMainScreen(
    initialVoiceQuery: String? = null,
    onConsumeVoiceQuery: () -> Unit = {},
    mainViewModel: MainViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    searchViewModel: OnlineSearchViewModel = hiltViewModel(),
    lyricsViewModel: LyricsViewModel = hiltViewModel()
) {
    var activeTab by remember { mutableStateOf(TvDestination.HOME) }
    val scope = rememberCoroutineScope()

    val currentSong by mainViewModel.currentSong.collectAsStateWithLifecycle()
    val currentOnlineSong by mainViewModel.currentOnlineSong.collectAsStateWithLifecycle()
    val isPlaying by mainViewModel.isPlaying.collectAsStateWithLifecycle()
    val positionMs by mainViewModel.currentPosition.collectAsStateWithLifecycle()
    val durationMs by mainViewModel.currentDuration.collectAsStateWithLifecycle()
    val audioQuality by mainViewModel.currentAudioQuality.collectAsStateWithLifecycle()
    val isFavorite by mainViewModel.isFavorite.collectAsStateWithLifecycle()

    val displayTitle = currentSong?.title ?: currentOnlineSong?.title ?: ""
    val displayArtist = currentSong?.artist ?: currentOnlineSong?.artist ?: ""
    val displayArt = currentSong?.albumArt ?: currentOnlineSong?.thumbnailUrl

    // Start Embedded Phone-to-TV LAN Remote Server automatically
    val remoteServer = remember {
        TvRemoteServer(
            onCommand = { action, value ->
                when (action) {
                    "playpause" -> mainViewModel.playPause()
                    "next" -> mainViewModel.skipToNext()
                    "prev" -> mainViewModel.skipToPrevious()
                    "fav" -> mainViewModel.toggleFavorite()
                    "seek_fwd" -> mainViewModel.seekTo((positionMs + 10_000L).coerceAtMost(durationMs.coerceAtLeast(10_000L)))
                    "seek_back" -> mainViewModel.seekTo((positionMs - 10_000L).coerceAtLeast(0L))
                    "search_play" -> {
                        if (!value.isNullOrBlank()) {
                            searchViewModel.onSearch(value)
                            activeTab = TvDestination.SEARCH
                        }
                    }
                }
            },
            currentTrackProvider = { displayTitle to displayArtist }
        )
    }
    var remoteLanUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        remoteLanUrl = remoteServer.start(scope)
        onDispose { remoteServer.stop() }
    }

    // Handle Android TV Voice Search ("Play ... on MyPlayer")
    LaunchedEffect(initialVoiceQuery) {
        if (!initialVoiceQuery.isNullOrBlank()) {
            searchViewModel.onSearch(initialVoiceQuery)
            activeTab = TvDestination.SEARCH
            onConsumeVoiceQuery()
        }
    }

    BackHandler(enabled = activeTab != TvDestination.HOME) {
        activeTab = TvDestination.HOME
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1F1135), Color(0xFF0B0B12)),
                    radius = 1600f
                )
            )
            .padding(horizontal = 36.dp, vertical = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP 10-FOOT TV NAVIGATION BAR + MINI NOW-PLAYING PILL ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // MyPlayer TV Brand Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF6D28D9), Color(0xFFBE185D))))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("MYPLAYER TV", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }

                    TvDestination.values().forEach { tab ->
                        val selected = activeTab == tab
                        TvFocusableSurface(
                            onClick = { activeTab = tab },
                            shape = RoundedCornerShape(50),
                            containerColor = if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color(0xFF7C3AED)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = tab.label,
                                    color = Color.White,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Top-Right Quick Now-Playing Pill (Jump to Fullscreen TV Player)
                if (displayTitle.isNotBlank()) {
                    TvFocusableSurface(
                        onClick = { activeTab = TvDestination.NOW_PLAYING },
                        shape = RoundedCornerShape(50),
                        containerColor = Color(0xFF3B1254),
                        focusedContainerColor = Color(0xFF831886)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtImage(
                                uri = displayArt,
                                title = displayTitle,
                                artist = displayArtist,
                                size = 34.dp,
                                shape = CircleShape,
                                iconSize = 18.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.widthIn(max = 180.dp)) {
                                Text(displayTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(displayArtist, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // ── MAIN TV PAGE BODY ──
            Box(modifier = Modifier.fillMaxSize()) {
                when (activeTab) {
                    TvDestination.HOME -> TvHomeBrowseContent(
                        homeViewModel = homeViewModel,
                        onOpenNowPlaying = { activeTab = TvDestination.NOW_PLAYING }
                    )
                    TvDestination.HOTLIST -> TvHotlistContent(
                        homeViewModel = homeViewModel,
                        onOpenNowPlaying = { activeTab = TvDestination.NOW_PLAYING }
                    )
                    TvDestination.SEARCH -> TvSearchContent(
                        searchViewModel = searchViewModel,
                        onOpenNowPlaying = { activeTab = TvDestination.NOW_PLAYING }
                    )
                    TvDestination.LIBRARY -> TvLibraryContent(
                        homeViewModel = homeViewModel,
                        onOpenNowPlaying = { activeTab = TvDestination.NOW_PLAYING }
                    )
                    TvDestination.NOW_PLAYING -> TvNowPlayingContent(
                        title = displayTitle,
                        artist = displayArtist,
                        artUri = displayArt,
                        isPlaying = isPlaying,
                        isFavorite = isFavorite,
                        positionMs = positionMs,
                        durationMs = durationMs.coerceAtLeast(1L),
                        codecLabel = buildString {
                            append(audioQuality.format.ifBlank { "AUTO" })
                            audioQuality.sampleRateHz?.let { append(" • ${it / 1000.0} kHz") }
                        },
                        mainViewModel = mainViewModel,
                        lyricsViewModel = lyricsViewModel
                    )
                    TvDestination.PHONE_REMOTE -> TvPhoneRemotePairingContent(
                        lanUrl = remoteLanUrl ?: "Connect TV to Wi-Fi LAN"
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHomeBrowseContent(
    homeViewModel: HomeViewModel,
    onOpenNowPlaying: () -> Unit
) {
    val aiSongs by homeViewModel.recommendations.collectAsStateWithLifecycle()
    val hotlist by homeViewModel.hotlistSongs.collectAsStateWithLifecycle()
    val recent by homeViewModel.recentSongs.collectAsStateWithLifecycle()

    val moodCategories = listOf("All", "Party", "Chill", "Workout", "Hip Hop", "Sad", "Late Night", "Deep Focus")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // Mood & Vibe Filter Row
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(moodCategories) { category ->
                    TvFocusableSurface(
                        onClick = {
                            if (category == "All") {
                                homeViewModel.selectCategory("All")
                            } else {
                                homeViewModel.playCategory(category) { onOpenNowPlaying() }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        containerColor = Color(0xFF211C35),
                        focusedContainerColor = Color(0xFF9333EA)
                    ) {
                        Text(
                            text = category,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // Row 1: AI Neural Hash Recommendations
        if (aiSongs.isNotEmpty()) {
            item {
                TvSectionHeader("AI Recommended For You", "128-D Neural Hash + Bandit Engine")
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(aiSongs.take(20)) { idx, song ->
                        TvRecommendationSongCard(
                            song = song,
                            onClick = {
                                homeViewModel.playRecommendedSongs(aiSongs, idx)
                                onOpenNowPlaying()
                            }
                        )
                    }
                }
            }
        }

        // Row 2: World Trending Hotlist
        if (hotlist.isNotEmpty()) {
            item {
                TvSectionHeader("World Trending Hotlist", "Instant 0ms Prefetched Streams")
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(hotlist.take(20)) { idx, song ->
                        TvRecommendationSongCard(
                            song = song,
                            onClick = {
                                homeViewModel.playRecommendedSongs(hotlist, idx)
                                onOpenNowPlaying()
                            }
                        )
                    }
                }
            }
        }

        // Row 3: Offline & Recently Downloaded
        if (recent.isNotEmpty()) {
            item {
                TvSectionHeader("Offline & Recent Songs", "Saved on Your TV Storage")
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(recent.take(20)) { idx, song ->
                        TvLocalSongCard(
                            song = song,
                            onClick = {
                                homeViewModel.playSong(recent, idx)
                                onOpenNowPlaying()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHotlistContent(
    homeViewModel: HomeViewModel,
    onOpenNowPlaying: () -> Unit
) {
    val hotlist by homeViewModel.hotlistSongs.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        TvSectionHeader("Top 50 Trending Hotlist", "Use D-Pad Remote to Select Any Track")
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            itemsIndexed(hotlist) { index, song ->
                TvFocusableSurface(
                    onClick = {
                        homeViewModel.playRecommendedSongs(hotlist, index)
                        onOpenNowPlaying()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1.02f
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${index + 1}",
                            color = Color(0xFFA855F7),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            modifier = Modifier.width(48.dp)
                        )
                        AlbumArtImage(
                            uri = song.thumbnailUrl,
                            title = song.title,
                            artist = song.artist,
                            size = 54.dp,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.PlayCircleFilled, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearchContent(
    searchViewModel: OnlineSearchViewModel,
    onOpenNowPlaying: () -> Unit
) {
    val query by searchViewModel.query.collectAsStateWithLifecycle()
    val pagingItems = searchViewModel.searchResults.collectAsLazyPagingItems()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { searchViewModel.onQueryChange(it) },
            placeholder = { Text("Search songs, artists, or use TV remote mic...", color = Color.White.copy(alpha = 0.55f)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFA855F7),
                unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchViewModel.onSearch(query) }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            items(pagingItems.itemCount) { index ->
                val song = pagingItems[index] ?: return@items
                TvFocusableSurface(
                    onClick = {
                        val snapshot = (0 until minOf(pagingItems.itemCount, 25)).mapNotNull { pagingItems[it] }
                        searchViewModel.playSongFromSearch(song, snapshot)
                        onOpenNowPlaying()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    focusedScale = 1.02f
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtImage(
                            uri = song.thumbnailUrl,
                            title = song.title,
                            artist = song.artist,
                            size = 56.dp,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(song.durationText, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvLibraryContent(
    homeViewModel: HomeViewModel,
    onOpenNowPlaying: () -> Unit
) {
    val favorites by homeViewModel.favoriteSongs.collectAsStateWithLifecycle()
    val downloads by homeViewModel.allSongs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 36.dp)
    ) {
        item {
            TvSectionHeader("Favorite Songs (${favorites.size})", "Synced Across Your Library")
            Spacer(Modifier.height(12.dp))
            if (favorites.isEmpty()) {
                Text("No favorite songs yet. Press the Heart icon on any playing track!", color = Color.White.copy(alpha = 0.6f))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(favorites) { idx, song ->
                        TvLocalSongCard(song = song) {
                            homeViewModel.playSong(favorites, idx)
                            onOpenNowPlaying()
                        }
                    }
                }
            }
        }

        item {
            TvSectionHeader("Offline Downloads (${downloads.size})", "Plays Instantly Without Internet")
            Spacer(Modifier.height(12.dp))
            if (downloads.isEmpty()) {
                Text("No offline downloads saved yet.", color = Color.White.copy(alpha = 0.6f))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(downloads) { idx, song ->
                        TvLocalSongCard(song = song) {
                            homeViewModel.playSong(downloads, idx)
                            onOpenNowPlaying()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvNowPlayingContent(
    title: String,
    artist: String,
    artUri: String?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    codecLabel: String,
    mainViewModel: MainViewModel,
    lyricsViewModel: LyricsViewModel
) {
    val lyricsState by lyricsViewModel.lyricsState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Big Album Art + Metadata + D-Pad Transport Controls
        Column(
            modifier = Modifier.weight(0.48f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AlbumArtImage(
                uri = artUri,
                title = title,
                artist = artist,
                size = 250.dp,
                shape = RoundedCornerShape(28.dp),
                iconSize = 64.dp
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = title.ifBlank { "Nothing Playing" },
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = artist.ifBlank { "Select any song to start playback" },
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF00E676).copy(alpha = 0.18f))
                    .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(codecLabel, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = Color(0xFFEC4899),
                trackColor = Color.White.copy(alpha = 0.18f)
            )

            Spacer(Modifier.height(20.dp))
            // D-Pad Transport Controls Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvIconControl(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = { mainViewModel.toggleFavorite() }
                )
                TvIconControl(
                    icon = Icons.Rounded.SkipPrevious,
                    onClick = { mainViewModel.skipToPrevious() }
                )
                TvFocusableSurface(
                    onClick = { mainViewModel.playPause() },
                    shape = CircleShape,
                    containerColor = Color.White,
                    focusedContainerColor = Color(0xFFEC4899),
                    focusedScale = 1.12f
                ) { focused ->
                    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = if (focused) Color.White else Color(0xFF5D218E),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                TvIconControl(
                    icon = Icons.Rounded.SkipNext,
                    onClick = { mainViewModel.skipToNext() }
                )
                TvIconControl(
                    icon = Icons.Rounded.Repeat,
                    onClick = { mainViewModel.cycleRepeatMode() }
                )
            }
        }

        // Right Column: Big 10-Foot Synced Karaoke Lyrics Display
        Box(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            when (val st = lyricsState) {
                is LyricsUiState.Found -> {
                    val synced = st.syncedLines
                    if (synced.isNotEmpty()) {
                        val activeIdx = remember(positionMs, synced) {
                            synced.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
                        }
                        LaunchedEffect(activeIdx) {
                            runCatching { listState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0)) }
                        }
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(vertical = 40.dp)
                        ) {
                            itemsIndexed(synced) { idx, (lineTimeMs, lineText) ->
                                val isCurrent = idx == activeIdx
                                TvFocusableSurface(
                                    onClick = { mainViewModel.seekTo(lineTimeMs) },
                                    containerColor = if (isCurrent) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                    focusedContainerColor = Color(0xFF6D28D9).copy(alpha = 0.55f),
                                    showFocusRing = false
                                ) {
                                    Text(
                                        text = lineText,
                                        color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.42f),
                                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        fontSize = if (isCurrent) 24.sp else 18.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = st.result.plainLyrics ?: "No lyrics available",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 18.sp,
                            lineHeight = 28.sp
                        )
                    }
                }
                is LyricsUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Lyrics will appear here automatically when a track plays", color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPhoneRemotePairingContent(lanUrl: String) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(42.dp)
    ) {
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            TvPairingMatrixBadge(url = lanUrl, modifier = Modifier.fillMaxSize())
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Control MyPlayer TV From Your Phone", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            Text(
                text = "1. Connect your Android or iPhone to the same Wi-Fi network as this TV.\n2. Open this address in your phone browser (or scan the pairing badge):",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF6D28D9).copy(alpha = 0.35f))
                    .border(1.5.dp, Color(0xFFA855F7), RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text(lanUrl, color = Color(0xFF00E676), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
            Text(
                text = "You can Play/Pause, Skip, Seek, Like songs, and type any song name on your phone to play it on the TV immediately!",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun TvIconControl(icon: ImageVector, onClick: () -> Unit) {
    TvFocusableSurface(
        onClick = onClick,
        shape = CircleShape,
        containerColor = Color.White.copy(alpha = 0.12f),
        focusedContainerColor = Color(0xFF7C3AED),
        focusedScale = 1.12f
    ) {
        Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun TvSectionHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
        Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
    }
}

@Composable
private fun TvRecommendationSongCard(song: RecommendationSong, onClick: () -> Unit) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier.width(186.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            AlbumArtImage(
                uri = song.thumbnailUrl,
                title = song.title,
                artist = song.artist,
                size = 162.dp,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TvLocalSongCard(song: SongEntity, onClick: () -> Unit) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier.width(186.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            AlbumArtImage(
                uri = song.albumArt,
                title = song.title,
                artist = song.artist,
                size = 162.dp,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}