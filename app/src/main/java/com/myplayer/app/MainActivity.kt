package com.myplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.myplayer.app.data.local.datastore.SettingsDataStore
import com.myplayer.app.data.update.InAppUpdateManager
import com.myplayer.app.data.update.UpdateChecker
import com.myplayer.app.data.update.UpdateInfo
import com.myplayer.app.data.update.UpdateNotifier
import com.myplayer.app.tv.TvMainScreen
import com.myplayer.app.tv.isTelevisionDevice
import com.myplayer.app.ui.components.MyPlayerInAppUpdateSheet
import com.myplayer.app.ui.components.WhatsNewDialog
import com.myplayer.app.ui.components.WhatsNewRepository
import com.myplayer.app.ui.screens.main.MainScreen
import com.myplayer.app.ui.theme.MyPlayerTheme
import com.myplayer.app.ui.theme.ThemeState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var updateNotifier: UpdateNotifier
    @Inject lateinit var inAppUpdateManager: InAppUpdateManager
    @Inject lateinit var settingsDataStore: SettingsDataStore

    private var launchUpdateAvailable by mutableStateOf<UpdateInfo?>(null)
    private var showWhatsNew by mutableStateOf(false)

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                checkForUpdatesOnLaunch(forceShowEvenIfSnoozed = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Read saved theme synchronously before 1st frame (prevents white flash on startup)
        val prefs = getSharedPreferences("theme_sync_prefs", MODE_PRIVATE)
        val initialDark = prefs.getBoolean("dark_mode_enabled", true)
        ThemeState.isDarkMode = initialDark

        enableEdgeToEdge()

        val openedFromUpdateNotif = intent?.action == UpdateNotifier.ACTION_OPEN_IN_APP_UPDATE
        handleNotificationIntent(intent)

        requestNotificationPermissionIfNeeded()
        checkForUpdatesOnLaunch(forceShowEvenIfSnoozed = openedFromUpdateNotif)

        // Fresh users get What's New right after onboarding (MainScreen shows it),
        // so skip the launch-time dialog until onboarding is done.
        val onboardingDone = getSharedPreferences("myplayer_ui_prefs", MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        if (onboardingDone && WhatsNewRepository.shouldShow(this)) {
            showWhatsNew = true
        }

        setContent {
            val scope = rememberCoroutineScope()
            val dataStoreDark by settingsDataStore.isDarkMode.collectAsStateWithLifecycle(
                initialValue = initialDark
            )
            val savedUserName by settingsDataStore.userName.collectAsStateWithLifecycle(
                initialValue = ""
            )

            // 2. When DataStore emits a value, keep ThemeState & SharedPreferences synced
            LaunchedEffect(dataStoreDark) {
                ThemeState.isDarkMode = dataStoreDark
                prefs.edit().putBoolean("dark_mode_enabled", dataStoreDark).apply()
            }

            // 3. When HomeScreen or SettingsScreen calls ThemeState.toggle() / setDark(),
            // automatically persist to both SharedPreferences and DataStore!
            DisposableEffect(Unit) {
                ThemeState.onThemeChanged = { enabled ->
                    prefs.edit().putBoolean("dark_mode_enabled", enabled).apply()
                    scope.launch {
                        settingsDataStore.setDarkMode(enabled)
                    }
                }
                onDispose {
                    ThemeState.onThemeChanged = null
                }
            }

            MyPlayerTheme(darkTheme = ThemeState.isDarkMode) {
                if (isTelevisionDevice(this@MainActivity)) {
                    TvMainScreen()
                } else {
                    MainScreen()
                }

                launchUpdateAvailable?.let { update ->
                    MyPlayerInAppUpdateSheet(
                        updateInfo = update,
                        inAppUpdateManager = inAppUpdateManager,
                        userName = savedUserName,
                        onSnooze24h = {
                            updateChecker.snoozeUpdateFor24Hours(update.versionName)
                            launchUpdateAvailable = null
                        },
                        onDismiss = {
                            launchUpdateAvailable = null
                        }
                    )
                }

                if (launchUpdateAvailable == null && showWhatsNew) {
                    WhatsNewDialog(
                        onDismiss = {
                            showWhatsNew = false
                            WhatsNewRepository.markSeen(this@MainActivity)
                        }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkForUpdatesOnLaunch(forceShowEvenIfSnoozed: Boolean = false) {
        lifecycleScope.launch {
            try {
                val update = updateChecker.checkForUpdate()
                if (update != null) {
                    if (forceShowEvenIfSnoozed || !updateChecker.isUpdateSnoozed(update.versionName)) {
                        launchUpdateAvailable = update
                    }
                    if (updateChecker.shouldNotifyFor(update)) {
                        updateNotifier.show(update)
                        updateChecker.markNotified(update)
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Launch update check failed: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UpdateNotifier.ACTION_OPEN_IN_APP_UPDATE) {
            checkForUpdatesOnLaunch(forceShowEvenIfSnoozed = true)
        }
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_donate", false) == true ||
            intent?.getStringExtra("action_url")?.contains("donate", ignoreCase = true) == true) {
            com.myplayer.app.ui.myplayer.MyPlayerRemoteNotificationManager.highlightDonateSection = true
            com.myplayer.app.ui.myplayer.MyPlayerRemoteNotificationManager.pendingNavigateToSettings = true
        }
    }
}