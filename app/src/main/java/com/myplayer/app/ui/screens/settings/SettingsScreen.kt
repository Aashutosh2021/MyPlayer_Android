package com.myplayer.app.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myplayer.app.BuildConfig
import com.myplayer.app.data.update.UpdateChecker
import com.myplayer.app.ui.components.WhatsNewDialog
import com.myplayer.app.ui.myplayer.MyPlayerAccentGreen
import com.myplayer.app.ui.myplayer.MyPlayerAncSettingsCard
import com.myplayer.app.ui.myplayer.MyPlayerAudioFxDspBridge
import com.myplayer.app.ui.myplayer.MyPlayerFunnyVibeCheckSettingsCard
import com.myplayer.app.ui.myplayer.MyPlayerInteractiveFeatureTour
import com.myplayer.app.ui.myplayer.MyPlayerRemoteNotificationManager
import com.myplayer.app.ui.myplayer.MyPlayerUiStateHolder
import com.myplayer.app.ui.opentune.OpenTuneAutoEqDialog
import com.myplayer.app.ui.opentune.OpenTuneCastDialog
import com.myplayer.app.ui.opentune.OpenTuneGroupCard
import com.myplayer.app.ui.opentune.OpenTuneGroupLabel
import com.myplayer.app.ui.opentune.OpenTuneNavRow
import com.myplayer.app.ui.opentune.OpenTunePillSegmented
import com.myplayer.app.ui.opentune.OpenTuneRadioDialog
import com.myplayer.app.ui.opentune.OpenTuneRecognizeDialog
import com.myplayer.app.ui.opentune.OpenTuneReplayDialog
import com.myplayer.app.ui.opentune.OpenTuneRowDivider
import com.myplayer.app.ui.opentune.OpenTuneSettingRow
import com.myplayer.app.ui.opentune.OpenTuneSponsorBlockDialog
import com.myplayer.app.ui.opentune.OpenTuneToggleRow
import com.myplayer.app.ui.opentune.OpenTuneUiPrefs
import com.myplayer.app.ui.theme.NeonLimePrimary
import com.myplayer.app.ui.theme.ThemeState

private data class SettingsCategoryMeta(
    val key: String,
    val title: String,
    val summary: String,
    val icon: ImageVector
)

private data class SearchableSettingEntry(
    val categoryKey: String,
    val categoryTitle: String,
    val groupTitle: String,
    val title: String,
    val summary: String,
    val render: @Composable () -> Unit
)

private val SETTINGS_CATEGORY_LIST = listOf(
    SettingsCategoryMeta(
        key = "account",
        title = "Account & integrations",
        summary = "Display name, YouTube Music, Spotify, SyncPlay, DualBud and Wi-Fi Cast",
        icon = Icons.Filled.Person
    ),
    SettingsCategoryMeta(
        key = "history",
        title = "Listening history & Replay",
        summary = "Replay stats, Wrapped milestones, Live Radio and Shazam song recognition",
        icon = Icons.Filled.History
    ),
    SettingsCategoryMeta(
        key = "audio",
        title = "Sound and quality",
        summary = "Streaming quality, 15-band EQ, AutoEq (8,000+ headphones), Audio FX Lab & ANC",
        icon = Icons.Filled.GraphicEq
    ),
    SettingsCategoryMeta(
        key = "playback",
        title = "Playback & SponsorBlock",
        summary = "Automix crossfade, autoplay recommendations, skip silence and SponsorBlock",
        icon = Icons.Filled.PlayCircle
    ),
    SettingsCategoryMeta(
        key = "lyrics",
        title = "Lyrics",
        summary = "Synced lyrics, shaders, word-by-word glow and script transliteration",
        icon = Icons.Filled.Lyrics
    ),
    SettingsCategoryMeta(
        key = "look",
        title = "Appearance",
        summary = "Theme (System / Light / Dark), Pure Black OLED, glass blur and colors",
        icon = Icons.Filled.Palette
    ),
    SettingsCategoryMeta(
        key = "player",
        title = "Player & Navigation",
        summary = "6 signature player styles, OpenTune vs Classic MiniPlayer and dock bar",
        icon = Icons.Filled.Album
    ),
    SettingsCategoryMeta(
        key = "motion",
        title = "Motion and feel",
        summary = "Smooth 120 FPS animations, transitions and interactive mood vibe-check",
        icon = Icons.Filled.Animation
    ),
    SettingsCategoryMeta(
        key = "library",
        title = "Home and Library",
        summary = "Speed dial, Daylist, Quick picks, Made for you mixes and playlist imports",
        icon = Icons.Filled.LibraryMusic
    ),
    SettingsCategoryMeta(
        key = "data",
        title = "Downloads and storage",
        summary = "Local music folders, phone audio scanner, download path and JSON backups",
        icon = Icons.Filled.Storage
    ),
    SettingsCategoryMeta(
        key = "support",
        title = "Help, Tour & Support",
        summary = "User Manual, Interactive Feature Tour, Buy Me a Coffee (UPI) & Bug Report",
        icon = Icons.AutoMirrored.Filled.MenuBook
    ),
    SettingsCategoryMeta(
        key = "about",
        title = "About",
        summary = "Updates, What's new in v${BuildConfig.VERSION_NAME}, official website & notifications",
        icon = Icons.Filled.Info
    )
)

private val SETTINGS_HOME_GROUPS = listOf(
    "You" to listOf("account", "history"),
    "Listening" to listOf("audio", "playback", "lyrics"),
    "Look and feel" to listOf("look", "player", "motion"),
    "Your music" to listOf("library", "data"),
    "App" to listOf("support", "about")
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToDualBud: () -> Unit = {},
    onNavigateToSyncPlay: () -> Unit = {},
    onNavigateToAudioPlayback: (() -> Unit)? = null,
    myplayerState: MyPlayerUiStateHolder? = null,
    onNavigateToPlayerStyles: () -> Unit = {},
    onNavigateToMyPlayerAppearance: () -> Unit = {},
    onNavigateToMyPlayerAudioLab: () -> Unit = {},
    onNavigateToRemoteNotifications: () -> Unit = {},
    bottomPadding: Dp = 120.dp,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        OpenTuneUiPrefs.load(context)
    }

    val isAutoplayEnabled by viewModel.isAutoplayEnabled.collectAsStateWithLifecycle()
    val downloadFolderUri by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    val localFolders by viewModel.localFolders.collectAsStateWithLifecycle()
    val localSongsCount by viewModel.localSongsCount.collectAsStateWithLifecycle()
    val isScanningLocal by viewModel.isScanningLocal.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val audioConfig by viewModel.audioConfig.collectAsStateWithLifecycle()
    val mostPlayed by viewModel.mostPlayed.collectAsStateWithLifecycle()
    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()

    val isSpotifyLoggedIn by viewModel.isSpotifyLoggedIn.collectAsStateWithLifecycle()
    val spotifyUsername by viewModel.spotifyUsername.collectAsStateWithLifecycle()
    val isSpotifySyncing by viewModel.isSpotifySyncing.collectAsStateWithLifecycle()
    val spotifySyncMessage by viewModel.spotifySyncMessage.collectAsStateWithLifecycle()

    val isYouTubeLoggedIn by viewModel.isYouTubeLoggedIn.collectAsStateWithLifecycle()
    val youtubeUsername by viewModel.youtubeUsername.collectAsStateWithLifecycle()
    val isYouTubeSyncing by viewModel.isYouTubeSyncing.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var openCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }

    var showManualFolderDialog by remember { mutableStateOf(false) }
    var manualFolderPathInput by remember { mutableStateOf("/storage/emulated/0/Music") }
    var showDialogText by remember { mutableStateOf<String?>(null) }
    var isLoadingDialog by remember { mutableStateOf(false) }
    var showSpotifyDialog by remember { mutableStateOf(false) }
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var editNameInput by remember { mutableStateOf("") }
    var showWhatsNewDialog by remember { mutableStateOf(false) }
    var showUserManualDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var showInteractiveTour by remember { mutableStateOf(false) }
    var showAudioSettingsDialog by remember { mutableStateOf(false) }
    var showAudioPlaybackScreen by remember { mutableStateOf(false) }

    // OpenTune Feature Dialogs
    var showAutoEqDialog by remember { mutableStateOf(false) }
    var showSponsorBlockDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }
    var showReplayDialog by remember { mutableStateOf(false) }
    var showRadioDialog by remember { mutableStateOf(false) }
    var showRecognizeDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = openCategoryKey != null) {
        openCategoryKey = null
    }

    LaunchedEffect(spotifySyncMessage) {
        spotifySyncMessage?.let {
            showDialogText = it
            viewModel.clearSpotifySyncMessage()
        }
    }

    if (showInteractiveTour && myplayerState != null) {
        Dialog(
            onDismissRequest = { showInteractiveTour = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            MyPlayerInteractiveFeatureTour(
                myplayerState = myplayerState,
                onApplyEqToEngine = { enabled, gains, preamp ->
                    MyPlayerAudioFxDspBridge.setEq(enabled, gains, preamp)
                },
                onFinishTour = {
                    myplayerState.completeFeatureTour()
                    showInteractiveTour = false
                }
            )
        }
    }

    if (showAudioPlaybackScreen) {
        com.myplayer.app.ui.settings.AudioAndPlaybackScreen(
            store = viewModel.audioPlaybackStore,
            featureManagers = viewModel.audioFeatureManagers,
            onBack = { showAudioPlaybackScreen = false },
            myplayerState = myplayerState
        )
        return
    }

    if (showAudioSettingsDialog) {
        com.myplayer.app.ui.settings.AudioPlaybackSettingsDialog(
            config = audioConfig,
            onDismiss = { showAudioSettingsDialog = false },
            onWifiQualityChange = { viewModel.updateWifiQuality(it) },
            onMobileQualityChange = { viewModel.updateMobileQuality(it) },
            onClarityChange = { viewModel.updateClarityPreset(it) },
            onDolbyToggle = { enabled, width -> viewModel.updateTrueDolby(enabled, width) },
            onLoudnessToggle = { enabled, gain -> viewModel.updateLoudness(enabled, gain) },
            onSkipSilenceToggle = { viewModel.updateSkipSilence(it) }
        )
    }

    if (showAutoEqDialog) {
        OpenTuneAutoEqDialog(
            autoEqManager = viewModel.autoEqManager,
            onApplied = { model ->
                val msg = if (model == "None") "AutoEq headphone profile reset."
                else "Applied AutoEq curve for $model!"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAutoEqDialog = false }
        )
    }

    if (showSponsorBlockDialog) {
        OpenTuneSponsorBlockDialog(
            sponsorBlockManager = viewModel.sponsorBlockManager,
            onDismiss = { showSponsorBlockDialog = false }
        )
    }

    if (showCastDialog) {
        OpenTuneCastDialog(
            localCastManager = viewModel.localCastManager,
            onDismiss = { showCastDialog = false }
        )
    }

    if (showReplayDialog) {
        OpenTuneReplayDialog(
            mostPlayed = mostPlayed,
            favoriteSongs = favoriteSongs,
            downloadedSongs = downloadedSongs,
            onPlaySong = { viewModel.playSong(it) },
            onDismiss = { showReplayDialog = false }
        )
    }

    if (showRadioDialog) {
        OpenTuneRadioDialog(
            liveRadioRepository = viewModel.liveRadioRepository,
            onPlayStation = { station ->
                viewModel.musicController.playOnlineSong(
                    com.myplayer.app.data.online.model.OnlineSong(
                        videoId = "radio:${station.uuid}",
                        title = station.name,
                        artist = listOf(station.state, station.country)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                            .ifBlank { "Live Radio" },
                        thumbnailUrl = station.faviconUrl ?: "",
                        durationMs = 0L,
                        durationText = "LIVE",
                        streamUrl = station.streamUrl
                    )
                )
            },
            onDismiss = { showRadioDialog = false }
        )
    }

    if (showRecognizeDialog) {
        OpenTuneRecognizeDialog(
            shazamRecognizer = viewModel.shazamRecognizer,
            onPlayMatchedSong = { title, artist, _ ->
                Toast.makeText(context, "Matched: $title — $artist", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showRecognizeDialog = false }
        )
    }

    if (showWhatsNewDialog) {
        WhatsNewDialog(onDismiss = { showWhatsNewDialog = false })
    }

    if (showUserManualDialog) {
        UserManualDialog(
            onDismiss = { showUserManualDialog = false },
            onNavigateToPlayerStyles = onNavigateToPlayerStyles,
            onNavigateToAudioPlayback = {
                if (onNavigateToAudioPlayback != null) {
                    onNavigateToAudioPlayback()
                } else {
                    showAudioPlaybackScreen = true
                }
            },
            onNavigateToAudioLab = onNavigateToMyPlayerAudioLab,
            onNavigateToDualBud = onNavigateToDualBud,
            onNavigateToSyncPlay = onNavigateToSyncPlay,
            onConnectSpotify = { showSpotifyDialog = true },
            onConnectYouTube = { showYouTubeDialog = true }
        )
    }

    if (showSpotifyDialog) {
        SpotifyLoginDialog(
            spotifyManager = viewModel.spotifyManager,
            onDismiss = { showSpotifyDialog = false },
            onLoginSuccess = { user ->
                showDialogText = "Connected to Spotify as $user! Taste profile synced into AI recommendations."
            }
        )
    }

    if (showYouTubeDialog) {
        YouTubeLoginDialog(
            youtubeManager = viewModel.youtubeManager,
            onDismiss = { showYouTubeDialog = false },
            onLoginSuccess = { info ->
                showDialogText = "YouTube Music connected ($info)! Playlists & personalized recommendations synced into AI."
            }
        )
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportBackup(it) }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importBackup(it) }
    }

    val localMusicFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val folderName = DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast(':')
                ?: "Local Music"
            viewModel.addLocalMusicFolder(uri.toString(), folderName)
        }
    }

    val customDownloadFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setDownloadFolder(uri.toString())
            showDialogText = "Download folder updated! New offline & MP3/M4A files will be saved here."
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.rescanLocalMusic()
    }

    if (showManualFolderDialog) {
        AlertDialog(
            onDismissRequest = { showManualFolderDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("Set Local Music Folder Path", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter the folder path on your device containing your local MP3 / M4A / FLAC / WAV songs, or use 'Open System Folder Picker' to browse:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = manualFolderPathInput,
                        onValueChange = { manualFolderPathInput = it },
                        singleLine = true,
                        label = { Text("Local Folder Path") },
                        placeholder = { Text("/storage/emulated/0/Music") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            showManualFolderDialog = false
                            localMusicFolderLauncher.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open System Folder Picker")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = manualFolderPathInput.trim()
                        if (cleaned.isNotBlank()) {
                            val name = cleaned.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { "Local Music" }
                            viewModel.addLocalMusicFolder(cleaned, name)
                            showManualFolderDialog = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save & Scan Path", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(backupState) {
        when (val state = backupState) {
            is BackupRestoreState.Loading -> {
                isLoadingDialog = true
                showDialogText = state.progress ?: "Creating backup file..."
            }
            is BackupRestoreState.Success -> {
                isLoadingDialog = false
                showDialogText = state.message
            }
            is BackupRestoreState.Error -> {
                isLoadingDialog = false
                showDialogText = "Error: ${state.error}"
            }
            BackupRestoreState.Idle -> {}
        }
    }

    LaunchedEffect(restoreState) {
        when (val state = restoreState) {
            is BackupRestoreState.Loading -> {
                isLoadingDialog = true
                showDialogText = state.progress ?: "Restoring database & downloads..."
            }
            is BackupRestoreState.Success -> {
                isLoadingDialog = false
                showDialogText = state.message
            }
            is BackupRestoreState.Error -> {
                isLoadingDialog = false
                showDialogText = "Error: ${state.error}"
            }
            BackupRestoreState.Idle -> {}
        }
    }

    if (showDonateDialog) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showDonateDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(24.dp),
            icon = {
                Icon(
                    Icons.Filled.LocalCafe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    "Buy Me a Coffee",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "MyPlayer is 100% free and open with zero ads. If you enjoy using the app, consider supporting its development with a small contribution!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "UPI ID (GPay / PhonePe / Paytm)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    DEVELOPER_UPI_ID,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(DEVELOPER_UPI_ID))
                                Toast.makeText(context, "UPI ID copied: $DEVELOPER_UPI_ID", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copy UPI ID",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val upiUri = Uri.parse("upi://pay?pa=$DEVELOPER_UPI_ID&pn=Ashutosh%20Gupta&cu=INR&tn=Support%20MyPlayer")
                        val upiIntent = Intent(Intent.ACTION_VIEW, upiUri)
                        try {
                            context.startActivity(Intent.createChooser(upiIntent, "Pay with UPI"))
                        } catch (_: Exception) {
                            clipboard.setText(AnnotatedString(DEVELOPER_UPI_ID))
                            Toast.makeText(context, "No UPI app found. UPI ID copied to clipboard!", Toast.LENGTH_LONG).show()
                        }
                        showDonateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Pay with UPI (GPay)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDonateDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(24.dp),
            icon = { Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
            title = { Text("Set Display Name", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    Text("Enter your name to show on the Home Screen:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = editNameInput,
                        onValueChange = { editNameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Your Name", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editNameInput.isNotBlank()) {
                            val trimmed = editNameInput.trim()
                            viewModel.setUserName(trimmed)
                            myplayerState?.completeOnboarding(trimmed, myplayerState.pickedArtists)
                        }
                        showNameDialog = false
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
                    onClick = { showNameDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDialogText != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isLoadingDialog) {
                    showDialogText = null
                    viewModel.resetStates()
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(if (isLoadingDialog) "Please Wait" else "MyPlayer Status", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoadingDialog) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(showDialogText!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (!isLoadingDialog) {
                    TextButton(
                        onClick = {
                            showDialogText = null
                            viewModel.resetStates()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    when (val state = updateState) {
        ManualUpdateState.Idle -> {}
        ManualUpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Checking for Updates", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Connecting to GitHub...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {}
            )
        }
        is ManualUpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Update Available", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "A newer version (${state.updateInfo.versionName}) is available!",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Visit our official website to download the latest update.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.updateInfo.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.updateInfo.releaseNotes.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetUpdateState()
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(state.updateInfo.releaseUrl)
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Update Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.resetUpdateState() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("Later")
                    }
                }
            )
        }
        ManualUpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Up to Date", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "You're using the latest version of MyPlayer (v${BuildConfig.VERSION_NAME}).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.resetUpdateState() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
        is ManualUpdateState.NoInternet -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = { Text("No Internet Connection", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = { Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.resetUpdateState() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
        is ManualUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Update Check", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = { Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.resetUpdateState() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }

    val openAudioPlaybackAction: () -> Unit = {
        if (onNavigateToAudioPlayback != null) {
            onNavigateToAudioPlayback()
        } else {
            showAudioPlaybackScreen = true
        }
    }

    // Build all searchable setting entries across every category
    val allEntries: List<SearchableSettingEntry> = listOf(
        // 1. Account & integrations
        SearchableSettingEntry(
            categoryKey = "account",
            categoryTitle = "Account & integrations",
            groupTitle = "Profile",
            title = "Display Name",
            summary = userName.ifBlank { "Tap to set your name for Home greeting & Speed dial" },
            render = {
                OpenTuneNavRow(
                    title = "Display Name",
                    summary = userName.ifBlank { "Tap to set your name" },
                    icon = Icons.Filled.Person,
                    onClick = {
                        editNameInput = userName
                        showNameDialog = true
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "account",
            categoryTitle = "Account & integrations",
            groupTitle = "YouTube Music",
            title = if (isYouTubeLoggedIn) "YouTube Music Connected ($youtubeUsername)" else "Sign in to YouTube Music",
            summary = "Sync your playlists, liked songs and personalized AI recommendations",
            render = {
                if (!isYouTubeLoggedIn) {
                    OpenTuneNavRow(
                        title = "Sign in to YouTube Music",
                        summary = "Sync your playlists, liked songs and personalized recommendations",
                        icon = Icons.Filled.PlayCircleFilled,
                        onClick = { showYouTubeDialog = true }
                    )
                } else {
                    Column {
                        OpenTuneNavRow(
                            title = "YouTube Music ($youtubeUsername)",
                            summary = if (isYouTubeSyncing) "Syncing YouTube Music..." else "Tap to sync playlists & AI taste now",
                            icon = Icons.Filled.CheckCircle,
                            dot = true,
                            onClick = { if (!isYouTubeSyncing) viewModel.syncYouTube() }
                        )
                        OpenTuneRowDivider()
                        OpenTuneNavRow(
                            title = "Import YouTube Playlist by Link",
                            summary = "Paste any YouTube or YouTube Music playlist URL",
                            icon = Icons.Filled.PlaylistAdd,
                            onClick = { showYouTubeDialog = true }
                        )
                        OpenTuneRowDivider()
                        OpenTuneNavRow(
                            title = "Disconnect YouTube Music",
                            summary = "Clear YouTube Music session cookies and tokens",
                            icon = Icons.Filled.Delete,
                            onClick = {
                                viewModel.logoutYouTube()
                                showDialogText = "Disconnected from YouTube Music."
                            }
                        )
                    }
                }
            }
        ),
        SearchableSettingEntry(
            categoryKey = "account",
            categoryTitle = "Account & integrations",
            groupTitle = "Connected services",
            title = if (isSpotifyLoggedIn) "Spotify Connected (@$spotifyUsername)" else "Connect Spotify",
            summary = "Import your Spotify top tracks, playlists and taste profile",
            render = {
                if (!isSpotifyLoggedIn) {
                    OpenTuneNavRow(
                        title = "Connect Spotify",
                        summary = "Import your Spotify top tracks, playlists & taste profile",
                        icon = Icons.Filled.MusicNote,
                        onClick = { showSpotifyDialog = true }
                    )
                } else {
                    Column {
                        OpenTuneNavRow(
                            title = "Spotify (@$spotifyUsername)",
                            summary = if (isSpotifySyncing) "Syncing Spotify Library..." else "Tap to refresh Spotify playlists & AI recommendations",
                            icon = Icons.Filled.CheckCircle,
                            dot = true,
                            onClick = { if (!isSpotifySyncing) viewModel.syncSpotify() }
                        )
                        OpenTuneRowDivider()
                        OpenTuneNavRow(
                            title = "Disconnect Spotify",
                            summary = "Clear Spotify session cookies and tokens",
                            icon = Icons.Filled.Delete,
                            onClick = {
                                viewModel.logoutSpotify()
                                showDialogText = "Disconnected from Spotify."
                            }
                        )
                    }
                }
            }
        ),
        SearchableSettingEntry(
            categoryKey = "account",
            categoryTitle = "Account & integrations",
            groupTitle = "Listen together & multi-device",
            title = "Listen Together & SyncPlay",
            summary = "Share a room code (Nostr) or sync over local Wi-Fi with friends",
            render = {
                OpenTuneNavRow(
                    title = "Listen Together & SyncPlay",
                    summary = "Online room codes (Nostr) & local Wi-Fi synchronized listening",
                    icon = Icons.Filled.GroupWork,
                    onClick = onNavigateToSyncPlay
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "account",
            categoryTitle = "Account & integrations",
            groupTitle = "Listen together & multi-device",
            title = "Dual Bud Mode",
            summary = "Play two independent songs simultaneously in left & right earbuds",
            render = {
                OpenTuneNavRow(
                    title = "Dual Bud Mode",
                    summary = "Play two independent songs simultaneously in left & right earbuds",
                    icon = Icons.Filled.Headphones,
                    onClick = onNavigateToDualBud
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "account",
            categoryTitle = "Account & integrations",
            groupTitle = "Listen together & multi-device",
            title = "Cast to Wi-Fi Speakers & TVs (DLNA)",
            summary = "Stream audio to DLNA / UPnP network speakers and Smart TVs",
            render = {
                OpenTuneNavRow(
                    title = "Cast to Wi-Fi Speakers & TVs",
                    summary = "Discover DLNA / UPnP speakers on your local Wi-Fi",
                    icon = Icons.Filled.Cast,
                    onClick = { showCastDialog = true }
                )
            }
        ),

        // 2. Listening history & Replay
        SearchableSettingEntry(
            categoryKey = "history",
            categoryTitle = "Listening history & Replay",
            groupTitle = "Your year & stats",
            title = "Replay & Wrapped",
            summary = "Weekly, monthly, yearly and all-time top songs, artists & minutes",
            render = {
                OpenTuneNavRow(
                    title = "Replay & Wrapped",
                    summary = "Weekly, monthly, yearly and all-time listening milestones",
                    icon = Icons.Filled.History,
                    onClick = { showReplayDialog = true }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "history",
            categoryTitle = "Listening history & Replay",
            groupTitle = "Discovery tools",
            title = "What's this song? (Shazam Recognizer)",
            summary = "Identify any song playing around you using microphone fingerprinting",
            render = {
                OpenTuneNavRow(
                    title = "What's this song? (Recognize)",
                    summary = "Hold your phone up to identify music playing nearby",
                    icon = Icons.Filled.Mic,
                    onClick = { showRecognizeDialog = true }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "history",
            categoryTitle = "Listening history & Replay",
            groupTitle = "Discovery tools",
            title = "Live Radio Directory (50,000+ Stations)",
            summary = "Browse and stream live radio stations from around the world",
            render = {
                OpenTuneNavRow(
                    title = "Live Radio (50,000+ Stations)",
                    summary = "Stream live stations near you, by country or genre",
                    icon = Icons.Filled.Radio,
                    onClick = { showRadioDialog = true }
                )
            }
        ),

        // 3. Sound and quality
        SearchableSettingEntry(
            categoryKey = "audio",
            categoryTitle = "Sound and quality",
            groupTitle = "Equalizer & headphone correction",
            title = "Headphone correction (AutoEq)",
            summary = "Active: ${OpenTuneUiPrefs.activeAutoEqModel} • 8,000+ measured headphone profiles",
            render = {
                OpenTuneNavRow(
                    title = "Headphone correction (AutoEq)",
                    summary = "Measured neutral frequency correction for 8,000+ headphones",
                    value = OpenTuneUiPrefs.activeAutoEqModel,
                    icon = Icons.Filled.Headphones,
                    onClick = { showAutoEqDialog = true }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "audio",
            categoryTitle = "Sound and quality",
            groupTitle = "Equalizer & headphone correction",
            title = "Equalizer & Streaming Quality Hub",
            summary = "15-band Equalizer, 7 Quality Tiers, Lossless & Bit-Perfect USB DAC",
            render = {
                OpenTuneNavRow(
                    title = "Audio & Streaming Output",
                    summary = "15-Band Equalizer, 7 Quality Tiers, Lossless & Bit-Perfect USB DAC",
                    icon = Icons.Filled.GraphicEq,
                    onClick = openAudioPlaybackAction
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "audio",
            categoryTitle = "Sound and quality",
            groupTitle = "Equalizer & headphone correction",
            title = "Studio Audio FX Lab & 10-Band ISO EQ",
            summary = "Apple Crossfade, Reverb, 3D Spatial Audio, Loudness Boost, Speed & Pitch",
            render = {
                OpenTuneNavRow(
                    title = "Studio Audio FX Lab",
                    summary = "Apple Crossfade, Reverb, 3D Spatial, Loudness Boost, Speed & Pitch",
                    icon = Icons.Filled.Tune,
                    onClick = onNavigateToMyPlayerAudioLab
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "audio",
            categoryTitle = "Sound and quality",
            groupTitle = "Quick streaming quality",
            title = "Wi-Fi & Mobile Streaming Dialog",
            summary = "Wi-Fi: ${audioConfig.wifiQuality.name} • Mobile: ${audioConfig.mobileQuality.name} • Clarity: ${audioConfig.clarityPreset.name}",
            render = {
                OpenTuneNavRow(
                    title = "Quick Streaming & Clarity Dialog",
                    summary = "Wi-Fi: ${audioConfig.wifiQuality.name} • Mobile: ${audioConfig.mobileQuality.name}",
                    icon = Icons.Filled.Equalizer,
                    onClick = { showAudioSettingsDialog = true }
                )
            }
        ),

        // 4. Playback & SponsorBlock
        SearchableSettingEntry(
            categoryKey = "playback",
            categoryTitle = "Playback & SponsorBlock",
            groupTitle = "Autoplay & queue",
            title = "Autoplay similar songs",
            summary = "Automatically play AI-recommended songs when the queue ends",
            render = {
                OpenTuneToggleRow(
                    title = "Autoplay similar songs",
                    summary = "Automatically play similar songs when the queue ends",
                    icon = Icons.Filled.AutoAwesome,
                    checked = isAutoplayEnabled,
                    onChange = { viewModel.setAutoplayEnabled(it) }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "playback",
            categoryTitle = "Playback & SponsorBlock",
            groupTitle = "Between songs",
            title = "Skip silence",
            summary = "Trim dead air at the start and end of tracks",
            render = {
                OpenTuneToggleRow(
                    title = "Skip silence",
                    summary = "Automatically skip silent gaps in audio tracks",
                    icon = Icons.Filled.FastForward,
                    checked = audioConfig.skipSilence,
                    onChange = { viewModel.updateSkipSilence(it) }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "playback",
            categoryTitle = "Playback & SponsorBlock",
            groupTitle = "Between songs",
            title = "Automix & Crossfade",
            summary = "Configure equal-power DJ crossfade & transition duration in Audio FX Lab",
            render = {
                OpenTuneNavRow(
                    title = "Automix & Crossfade",
                    summary = "Configure Apple-grade equal-power DJ crossfade duration",
                    icon = Icons.Filled.Tune,
                    onClick = onNavigateToMyPlayerAudioLab
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "playback",
            categoryTitle = "Playback & SponsorBlock",
            groupTitle = "Music videos",
            title = "SponsorBlock",
            summary = if (OpenTuneUiPrefs.sponsorBlockEnabled) {
                "On — skipping ${OpenTuneUiPrefs.sponsorBlockCategories.size} non-music segment types"
            } else {
                "Off — tap to choose segments to skip"
            },
            render = {
                OpenTuneNavRow(
                    title = "SponsorBlock",
                    summary = if (OpenTuneUiPrefs.sponsorBlockEnabled) {
                        "On • Skipping ${OpenTuneUiPrefs.sponsorBlockCategories.size} segment categories"
                    } else {
                        "Off • Tap to configure skipped video segments"
                    },
                    icon = Icons.Filled.PlayCircle,
                    onClick = { showSponsorBlockDialog = true }
                )
            }
        ),

        // 5. Lyrics
        SearchableSettingEntry(
            categoryKey = "lyrics",
            categoryTitle = "Lyrics",
            groupTitle = "Display & shaders",
            title = "Lyrics Appearance, Blur & Shaders",
            summary = "Configure synced lyrics glow, progressive blur, font scale & script transliteration",
            render = {
                OpenTuneNavRow(
                    title = "Lyrics Shaders, Glow & Appearance",
                    summary = "Word-by-word highlight, progressive blur, bloom & transliteration",
                    icon = Icons.Filled.Lyrics,
                    onClick = onNavigateToMyPlayerAppearance
                )
            }
        ),

        // 6. Appearance
        SearchableSettingEntry(
            categoryKey = "look",
            categoryTitle = "Appearance",
            groupTitle = "Theme",
            title = "Theme Mode",
            summary = if (ThemeState.isDarkMode) "Dark theme active" else "Light theme active",
            render = {
                OpenTuneSettingRow(
                    title = "Theme",
                    summary = "Switch between Light and Dark appearance",
                    icon = Icons.Filled.DarkMode,
                    below = {
                        OpenTunePillSegmented(
                            options = listOf(false, true),
                            selected = ThemeState.isDarkMode,
                            label = { isDark -> if (isDark) "Dark" else "Light" },
                            onSelect = { isDark -> ThemeState.setDark(isDark) }
                        )
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "look",
            categoryTitle = "Appearance",
            groupTitle = "Glass & shaders",
            title = "Player Appearance & Liquid Glass Shaders",
            summary = "Glass blur, progressive blur, bloom & animated canvas settings",
            render = {
                OpenTuneNavRow(
                    title = "Player Appearance & Glass Shaders",
                    summary = "Glass blur, progressive blur, bloom & animated canvas settings",
                    icon = Icons.Filled.BlurOn,
                    onClick = onNavigateToMyPlayerAppearance
                )
            }
        ),

        // 7. Player & Navigation
        SearchableSettingEntry(
            categoryKey = "player",
            categoryTitle = "Player & Navigation",
            groupTitle = "Now Playing layout",
            title = "Customize Player Styles (6 Styles)",
            summary = "Active: ${myplayerState?.playerStyle?.title ?: "Radial (MyPlayer)"} • Radial, Vinyl, Editorial, Waveform, Lyrics Stage, Classic",
            render = {
                OpenTuneNavRow(
                    title = "Customize Player Styles",
                    summary = "Active: ${myplayerState?.playerStyle?.title ?: "Radial (MyPlayer)"} • 6 signature styles",
                    icon = Icons.Filled.Palette,
                    onClick = onNavigateToPlayerStyles
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "player",
            categoryTitle = "Player & Navigation",
            groupTitle = "MiniPlayer design",
            title = "MiniPlayer Style",
            summary = if (OpenTuneUiPrefs.useOpenTuneMiniPlayer) {
                "OpenTune Sleek Glass MiniPlayer (Circular progress ring around Play)"
            } else {
                "MyPlayer Classic Protruding Purple-Magenta MiniPlayer"
            },
            render = {
                OpenTuneSettingRow(
                    title = "MiniPlayer Style",
                    summary = "Choose between OpenTune's glass pill miniplayer or MyPlayer's protruding card",
                    icon = Icons.Filled.Album,
                    below = {
                        OpenTunePillSegmented(
                            options = listOf(true, false),
                            selected = OpenTuneUiPrefs.useOpenTuneMiniPlayer,
                            label = { isOpenTune -> if (isOpenTune) "OpenTune Glass" else "MyPlayer Classic" },
                            onSelect = { isOpenTune ->
                                OpenTuneUiPrefs.useOpenTuneMiniPlayer = isOpenTune
                                OpenTuneUiPrefs.save(context)
                            }
                        )
                    }
                )
            }
        ),

        // 8. Motion and feel
        SearchableSettingEntry(
            categoryKey = "motion",
            categoryTitle = "Motion and feel",
            groupTitle = "Smoothness",
            title = "Smooth 120 FPS Page Animations",
            summary = "Spring page transitions, scale-in reveals and dock lens motion",
            render = {
                OpenTuneToggleRow(
                    title = "Smooth Page & Dock Animations",
                    summary = "Spring page transitions and stretching dock capsule lens",
                    icon = Icons.Filled.Animation,
                    checked = myplayerState?.smoothAnimations ?: true,
                    onChange = { enabled ->
                        myplayerState?.let {
                            it.smoothAnimations = enabled
                            it.saveAppearanceToPrefs()
                        }
                    }
                )
            }
        ),

        // 9. Home and Library
        SearchableSettingEntry(
            categoryKey = "library",
            categoryTitle = "Home and Library",
            groupTitle = "Home screen sections",
            title = "Speed dial on Home",
            summary = "Show 3x3 paged grid of your most played & favorite songs at the top of Home",
            render = {
                OpenTuneToggleRow(
                    title = "Speed dial on Home",
                    summary = "3x3 paged square cover grid at the top of Home",
                    icon = Icons.Filled.Explore,
                    checked = OpenTuneUiPrefs.showSpeedDialOnHome,
                    onChange = {
                        OpenTuneUiPrefs.showSpeedDialOnHome = it
                        OpenTuneUiPrefs.save(context)
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "library",
            categoryTitle = "Home and Library",
            groupTitle = "Home screen sections",
            title = "Daylist Hero Card on Home",
            summary = "Show time-of-day dynamic mix card (Morning, Afternoon, Evening, Late Night)",
            render = {
                OpenTuneToggleRow(
                    title = "Daylist on Home",
                    summary = "Dynamic time-of-day mix card on Home",
                    icon = Icons.Filled.AutoAwesome,
                    checked = OpenTuneUiPrefs.showDaylistOnHome,
                    onChange = {
                        OpenTuneUiPrefs.showDaylistOnHome = it
                        OpenTuneUiPrefs.save(context)
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "library",
            categoryTitle = "Home and Library",
            groupTitle = "Home screen sections",
            title = "Quick picks & Made for you mixes on Home",
            summary = "Show 4-row paged Quick picks and Daily Mixes shelves on Home",
            render = {
                OpenTuneToggleRow(
                    title = "Quick picks & Made for you mixes",
                    summary = "4-row paged Quick picks and Daily Mixes shelves on Home",
                    icon = Icons.Filled.LibraryMusic,
                    checked = OpenTuneUiPrefs.showQuickPicksOnHome,
                    onChange = {
                        OpenTuneUiPrefs.showQuickPicksOnHome = it
                        OpenTuneUiPrefs.showMixesOnHome = it
                        OpenTuneUiPrefs.save(context)
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "library",
            categoryTitle = "Home and Library",
            groupTitle = "Home screen sections",
            title = "Festival wishes banner",
            summary = "Show greeting banners on festival days",
            render = {
                OpenTuneToggleRow(
                    title = "Festival wishes on Home",
                    summary = "Greet you on regional & global festivals",
                    icon = Icons.Filled.Celebration,
                    checked = OpenTuneUiPrefs.showFestivalBanner,
                    onChange = {
                        OpenTuneUiPrefs.showFestivalBanner = it
                        OpenTuneUiPrefs.save(context)
                    }
                )
            }
        ),

        // 10. Downloads and storage
        SearchableSettingEntry(
            categoryKey = "data",
            categoryTitle = "Downloads and storage",
            groupTitle = "Music on this phone",
            title = "Select Local Music Folder",
            summary = "Grant access to any folder on your phone to play local MP3, M4A, FLAC & WAV songs",
            render = {
                OpenTuneNavRow(
                    title = "Select Local Music Folder",
                    summary = "Grant access to any folder on your phone for MP3, M4A, FLAC & WAV files",
                    icon = Icons.Filled.FolderOpen,
                    onClick = { localMusicFolderLauncher.launch(null) }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "data",
            categoryTitle = "Downloads and storage",
            groupTitle = "Music on this phone",
            title = "Set Local Folder Path Manually",
            summary = "Type or paste a local directory path (e.g. /storage/emulated/0/Music)",
            render = {
                OpenTuneNavRow(
                    title = "Set Local Folder Path Manually",
                    summary = "Type or paste a local directory path (e.g. /storage/emulated/0/Music)",
                    icon = Icons.Filled.CreateNewFolder,
                    onClick = { showManualFolderDialog = true }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "data",
            categoryTitle = "Downloads and storage",
            groupTitle = "Music on this phone",
            title = "Scan All Phone Local Songs ($localSongsCount songs)",
            summary = "Index all local audio files on your phone into Library > Local Files",
            render = {
                OpenTuneNavRow(
                    title = if (isScanningLocal) "Scanning Local Audio Files..." else "Scan All Phone Local Songs ($localSongsCount songs)",
                    summary = "Index all local audio files on your phone into Library > Local Files",
                    icon = Icons.Filled.LibraryMusic,
                    onClick = {
                        if (!isScanningLocal) {
                            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                android.Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                android.Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                            audioPermissionLauncher.launch(perm)
                        }
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "data",
            categoryTitle = "Downloads and storage",
            groupTitle = "Downloads",
            title = "Set Download Folder Path",
            summary = if (downloadFolderUri.isNullOrBlank()) {
                "Default: Local Folder or Music/myplayer (Tap to choose custom folder)"
            } else {
                "Custom Folder: $downloadFolderUri"
            },
            render = {
                Column {
                    OpenTuneNavRow(
                        title = "Set Download Folder Path",
                        summary = if (downloadFolderUri.isNullOrBlank()) {
                            "Default: Local Folder or Music/myplayer"
                        } else {
                            "Custom Folder: $downloadFolderUri"
                        },
                        icon = Icons.Filled.Download,
                        onClick = { customDownloadFolderLauncher.launch(null) }
                    )
                    if (!downloadFolderUri.isNullOrBlank()) {
                        OpenTuneRowDivider()
                        OpenTuneNavRow(
                            title = "Reset Download Folder to Default",
                            summary = "Save MP3 / M4A files to configured Local Folder or Music/myplayer",
                            icon = Icons.Filled.Restore,
                            onClick = {
                                viewModel.setDownloadFolder(null)
                                showDialogText = "Download folder reset to default (Local Folder or Music/myplayer)."
                            }
                        )
                    }
                }
            }
        ),
        SearchableSettingEntry(
            categoryKey = "data",
            categoryTitle = "Downloads and storage",
            groupTitle = "Backup & restore",
            title = "Export Backup (Create Backup)",
            summary = "Save library, playlists, favorites, history and settings to JSON",
            render = {
                OpenTuneNavRow(
                    title = "Export backup",
                    summary = "Save likes, playlists, history and settings to a JSON file",
                    icon = Icons.Filled.Backup,
                    onClick = { backupLauncher.launch("myplayer_backup.json") }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "data",
            categoryTitle = "Downloads and storage",
            groupTitle = "Backup & restore",
            title = "Restore Backup (Import Data)",
            summary = "Load a previously exported backup file & auto-restore downloads",
            render = {
                OpenTuneNavRow(
                    title = "Restore backup",
                    summary = "Import playlists and auto-restore downloaded music",
                    icon = Icons.Filled.SettingsBackupRestore,
                    onClick = { restoreLauncher.launch(arrayOf("application/json")) }
                )
            }
        ),

        // 11. Help, Tour & Support
        SearchableSettingEntry(
            categoryKey = "support",
            categoryTitle = "Help, Tour & Support",
            groupTitle = "Guides & interactive tour",
            title = "User Manual & Feature Guide",
            summary = "Complete guide to gestures, audio engine, equalizer, styles & access paths",
            render = {
                OpenTuneNavRow(
                    title = "User Manual & Feature Guide",
                    summary = "Complete guide to gestures, audio engine, equalizer, styles & access paths",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    onClick = { showUserManualDialog = true }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "support",
            categoryTitle = "Help, Tour & Support",
            groupTitle = "Guides & interactive tour",
            title = "Interactive Feature Tour",
            summary = "Replay the step-by-step click-through tour of Player Styles, EQ, Audio FX & Crossfade",
            render = {
                if (myplayerState != null) {
                    OpenTuneNavRow(
                        title = "Interactive Feature Tour",
                        summary = "Step-by-step tour of Player Styles, EQ, Audio FX & Crossfade",
                        icon = Icons.Filled.Explore,
                        onClick = {
                            myplayerState.startFeatureTour()
                            showInteractiveTour = true
                        }
                    )
                }
            }
        ),
        SearchableSettingEntry(
            categoryKey = "support",
            categoryTitle = "Help, Tour & Support",
            groupTitle = "Support development",
            title = "Buy Me a Coffee (UPI Donate)",
            summary = "Support MyPlayer development via GPay, PhonePe, or UPI (0% fees)",
            render = {
                OpenTuneNavRow(
                    title = "Buy Me a Coffee",
                    summary = "Support MyPlayer development via GPay, PhonePe, or UPI (0% fees)",
                    icon = Icons.Filled.LocalCafe,
                    onClick = {
                        MyPlayerRemoteNotificationManager.highlightDonateSection = false
                        showDonateDialog = true
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "support",
            categoryTitle = "Help, Tour & Support",
            groupTitle = "Contact developer",
            title = "Complaint & Bug Report",
            summary = "Send complaints, bugs, or feedback to $DEVELOPER_EMAIL",
            render = {
                OpenTuneNavRow(
                    title = "Complaint & Bug Report",
                    summary = "Send complaints, bugs, or feedback to $DEVELOPER_EMAIL",
                    icon = Icons.Filled.Feedback,
                    onClick = {
                        sendEmail(
                            context = context,
                            recipient = DEVELOPER_EMAIL,
                            subject = "[MyPlayer Complaint / Bug Report] v${BuildConfig.VERSION_NAME}",
                            body = """
                                Hi Developer,

                                --- Complaint / Bug Details ---
                                [Please describe your complaint, bug, or feedback here]

                                --- Device & System Diagnostics ---
                                App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
                                Device: ${Build.MANUFACTURER} ${Build.MODEL}
                                Android Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                            """.trimIndent()
                        )
                    }
                )
            }
        ),

        // 12. About
        SearchableSettingEntry(
            categoryKey = "about",
            categoryTitle = "About",
            groupTitle = "Updates",
            title = "What's New in v${BuildConfig.VERSION_NAME}",
            summary = "See new features and improvements in this release",
            render = {
                OpenTuneNavRow(
                    title = "What's New in v${BuildConfig.VERSION_NAME}",
                    summary = "See new features and improvements in this release",
                    icon = Icons.Filled.Info,
                    onClick = { showWhatsNewDialog = true }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "about",
            categoryTitle = "About",
            groupTitle = "Updates",
            title = "Check for Updates",
            summary = "Check official website & GitHub for new releases",
            render = {
                OpenTuneNavRow(
                    title = "Check for updates",
                    summary = "Check official website & GitHub for new releases",
                    icon = Icons.Filled.SystemUpdate,
                    onClick = { viewModel.checkForUpdates() }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "about",
            categoryTitle = "About",
            groupTitle = "Updates",
            title = "Notifications Center",
            summary = "View announcements and remote updates",
            render = {
                OpenTuneNavRow(
                    title = "Notifications Center",
                    summary = "View announcements and remote updates",
                    icon = Icons.Filled.Notifications,
                    dot = MyPlayerRemoteNotificationManager.unreadCount > 0,
                    onClick = onNavigateToRemoteNotifications
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "about",
            categoryTitle = "About",
            groupTitle = "Community & website",
            title = "Official Website",
            summary = UpdateChecker.UPDATE_WEBSITE_URL,
            render = {
                OpenTuneNavRow(
                    title = "Official Website",
                    summary = UpdateChecker.UPDATE_WEBSITE_URL,
                    icon = Icons.Filled.Language,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.UPDATE_WEBSITE_URL)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                )
            }
        ),
        SearchableSettingEntry(
            categoryKey = "about",
            categoryTitle = "About",
            groupTitle = "Community & website",
            title = "Powered By",
            summary = "FlexFly Company Pvt Ltd • MyPlayer v${BuildConfig.VERSION_NAME}",
            render = {
                OpenTuneSettingRow(
                    title = "Powered By",
                    summary = "FlexFly Company Pvt Ltd • MyPlayer v${BuildConfig.VERSION_NAME}",
                    icon = Icons.Filled.MusicNote
                )
            }
        )
    )

    val mainListState = rememberLazyListState()
    val isDonateHighlighted = MyPlayerRemoteNotificationManager.highlightDonateSection
    val activeCategory = SETTINGS_CATEGORY_LIST.firstOrNull { it.key == openCategoryKey }

    AnimatedContent(
        targetState = activeCategory,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentKey = { it?.key ?: "settings_root" },
        transitionSpec = {
            val forward = targetState != null
            val enter = fadeIn(tween(200)) + slideInHorizontally(tween(240)) { if (forward) it / 6 else -it / 6 }
            val exit = fadeOut(tween(150))
            enter togetherWith exit
        },
        label = "openTuneSettingsTransition"
    ) { categoryPage ->
        if (categoryPage != null) {
            // ── Category Detail Page (OpenTune Clean Subpage Layout) ──────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + 36.dp)
            ) {
                item(key = "subpage_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { openCategoryKey = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = categoryPage.title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = categoryPage.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                val categoryEntries = allEntries.filter { it.categoryKey == categoryPage.key }
                categoryEntries.groupBy { it.groupTitle }.forEach { (groupName, rows) ->
                    item(key = "group_label_$groupName") {
                        OpenTuneGroupLabel(text = groupName)
                    }
                    item(key = "group_card_$groupName") {
                        OpenTuneGroupCard {
                            rows.forEachIndexed { idx, entry ->
                                if (idx > 0) OpenTuneRowDivider()
                                entry.render()
                            }
                            // Also render configured local folders inside "Music on this phone"
                            if (categoryPage.key == "data" && groupName == "Music on this phone" && localFolders.isNotEmpty()) {
                                localFolders.forEach { folder ->
                                    OpenTuneRowDivider()
                                    OpenTuneNavRow(
                                        title = "Folder: ${folder.name}",
                                        summary = "${folder.uri} • Tap to remove",
                                        icon = Icons.Filled.Folder,
                                        onClick = { viewModel.removeLocalMusicFolder(folder) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Preserve Phone-Mic ANC & Desi Mood Vibe-Check cards inside Sound/Audio & Motion pages!
                if (categoryPage.key == "audio") {
                    item(key = "anc_vibe_label") {
                        OpenTuneGroupLabel(text = "Active Noise Cancellation & Vibe Lab")
                    }
                    item(key = "anc_vibe_cards") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MyPlayerAncSettingsCard()
                            if (myplayerState != null) {
                                MyPlayerFunnyVibeCheckSettingsCard(myplayerState = myplayerState)
                            }
                        }
                    }
                } else if (categoryPage.key == "motion" && myplayerState != null) {
                    item(key = "vibe_card_motion") {
                        OpenTuneGroupLabel(text = "Desi Mood Vibe-Check")
                    }
                    item(key = "vibe_card_motion_body") {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            MyPlayerFunnyVibeCheckSettingsCard(myplayerState = myplayerState)
                        }
                    }
                }
            }
        } else {
            // ── Root Settings Page (OpenTune Clean Categorized & Searchable UI) ─
            val q = searchQuery.trim()
            LazyColumn(
                state = mainListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + 36.dp)
            ) {
                // Top Header Bar
                item(key = "root_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, end = 8.dp)
                        ) {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Notifications Bell Button
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(onClick = onNavigateToRemoteNotifications),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Notifications,
                                contentDescription = "Notifications",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            if (MyPlayerRemoteNotificationManager.unreadCount > 0 ||
                                MyPlayerRemoteNotificationManager.notificationHistory.isNotEmpty()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 9.dp, end = 9.dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MyPlayerAccentGreen)
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // Compact Status Badge Card
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.size(width = 86.dp, height = 58.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "MYPLAYER",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                                val barColor = MaterialTheme.colorScheme.primary
                                Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                                    val barWidth = 3.dp.toPx()
                                    val count = 7
                                    val gap = (size.width - (count * barWidth)) / (count - 1).toFloat()
                                    val heights = listOf(0.4f, 0.75f, 0.5f, 1.0f, 0.65f, 0.85f, 0.45f)
                                    heights.forEachIndexed { index, fraction ->
                                        val x = index * (barWidth + gap)
                                        val bh = size.height * fraction
                                        val y = size.height - bh
                                        drawRoundRect(
                                            color = barColor,
                                            topLeft = Offset(x, y),
                                            size = Size(barWidth, bh),
                                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // OpenTune "Search settings" Bar
                item(key = "search_bar") {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search settings") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        shape = RoundedCornerShape(22.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // Highlighted Donate Banner if triggered by Remote Notification
                if (isDonateHighlighted) {
                    item(key = "donate_highlight_banner") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = NeonLimePrimary.copy(alpha = 0.16f),
                            border = BorderStroke(1.5.dp, NeonLimePrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable {
                                    MyPlayerRemoteNotificationManager.highlightDonateSection = false
                                    showDonateDialog = true
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("☕", fontSize = 24.sp)
                                    Column {
                                        Text(
                                            text = "Support MyPlayer Here!",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "100% free app • Zero ads • Tap to donate via UPI",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        MyPlayerRemoteNotificationManager.highlightDonateSection = false
                                        showDonateDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonLimePrimary,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("Donate 👍", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                if (q.isNotEmpty()) {
                    // ── Search Results Mode ───────────────────────────────────
                    val hits = allEntries.filter { entry ->
                        entry.title.contains(q, ignoreCase = true) ||
                            entry.summary.contains(q, ignoreCase = true) ||
                            entry.groupTitle.contains(q, ignoreCase = true) ||
                            entry.categoryTitle.contains(q, ignoreCase = true)
                    }
                    if (hits.isEmpty()) {
                        item(key = "no_hits") {
                            Text(
                                text = "No settings match \"$q\".",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        hits.groupBy { "${it.categoryTitle} · ${it.groupTitle}" }.forEach { (where, rows) ->
                            item(key = "hit_label_$where") {
                                OpenTuneGroupLabel(text = where)
                            }
                            item(key = "hit_card_$where") {
                                OpenTuneGroupCard {
                                    rows.forEachIndexed { idx, entry ->
                                        if (idx > 0) OpenTuneRowDivider()
                                        entry.render()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ── Clean OpenTune Categorized Home Mode ──────────────────
                    SETTINGS_HOME_GROUPS.forEach { (sectionLabel, categoryKeys) ->
                        val cats = categoryKeys.mapNotNull { k ->
                            SETTINGS_CATEGORY_LIST.firstOrNull { it.key == k }
                        }
                        item(key = "section_label_$sectionLabel") {
                            OpenTuneGroupLabel(text = sectionLabel)
                        }
                        item(key = "section_card_$sectionLabel") {
                            OpenTuneGroupCard {
                                cats.forEachIndexed { idx, cat ->
                                    if (idx > 0) OpenTuneRowDivider()

                                    // Put Headphone Correction (AutoEq) right above Sound and quality, like OpenTune's Audio sources row!
                                    if (cat.key == "audio") {
                                        OpenTuneNavRow(
                                            title = "Headphone correction (AutoEq)",
                                            summary = if (OpenTuneUiPrefs.activeAutoEqModel == "None") {
                                                "8,000+ measured headphone profiles • Tap to select"
                                            } else {
                                                "Active: ${OpenTuneUiPrefs.activeAutoEqModel}"
                                            },
                                            icon = Icons.Filled.Headphones,
                                            onClick = { showAutoEqDialog = true }
                                        )
                                        OpenTuneRowDivider()
                                    }

                                    val dynamicSummary = when (cat.key) {
                                        "account" -> {
                                            val connectedList = buildList {
                                                if (userName.isNotBlank()) add(userName)
                                                if (isYouTubeLoggedIn) add("YouTube ($youtubeUsername)")
                                                if (isSpotifyLoggedIn) add("Spotify (@$spotifyUsername)")
                                            }
                                            if (connectedList.isNotEmpty()) {
                                                connectedList.joinToString(" · ")
                                            } else {
                                                cat.summary
                                            }
                                        }
                                        "player" -> "Active style: ${myplayerState?.playerStyle?.title ?: "Radial"} • MiniPlayer & Dock"
                                        "data" -> "$localSongsCount local songs • ${downloadedSongs.size} downloads • Backups"
                                        else -> cat.summary
                                    }

                                    OpenTuneNavRow(
                                        title = cat.title,
                                        summary = dynamicSummary,
                                        icon = cat.icon,
                                        dot = (cat.key == "account" && (isYouTubeLoggedIn || isSpotifyLoggedIn)) ||
                                            (cat.key == "about" && MyPlayerRemoteNotificationManager.unreadCount > 0),
                                        onClick = { openCategoryKey = cat.key }
                                    )
                                }
                            }
                        }
                    }

                    // Quick Access Phone-Mic ANC & Desi Mood Vibe-Check cards at bottom of Root Settings
                    item(key = "quick_lab_label") {
                        OpenTuneGroupLabel(text = "Quick Audio & Mood Lab")
                    }
                    item(key = "quick_lab_cards") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MyPlayerAncSettingsCard()
                            if (myplayerState != null) {
                                MyPlayerFunnyVibeCheckSettingsCard(myplayerState = myplayerState)
                            }
                        }
                    }

                    item(key = "footer_text") {
                        Text(
                            text = "MyPlayer v${BuildConfig.VERSION_NAME} • OpenTune UI Edition • Powered by FlexFly Company Pvt Ltd",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

private const val DEVELOPER_EMAIL = "flexflycompany.in@gmail.com"
private const val DEVELOPER_UPI_ID = "gaashutoshgupta52-2@okaxis"

private fun sendEmail(
    context: Context,
    recipient: String,
    subject: String,
    body: String = ""
) {
    try {
        val mailtoUri = Uri.parse("mailto:$recipient?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserIntent = Intent.createChooser(emailIntent, "Send Email via...").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    } catch (_: Exception) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Developer Email", recipient))
            Toast.makeText(
                context,
                "No email app found. Email copied to clipboard: $recipient",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Please contact: $recipient",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}