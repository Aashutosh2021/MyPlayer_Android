package com.myplayer.app.tv

import android.app.SearchManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.myplayer.app.ui.theme.MyPlayerTheme
import com.myplayer.app.ui.theme.ThemeState
import dagger.hilt.android.AndroidEntryPoint

/**
 * Utility to detect if the current device is an Android TV / Google TV / Fire TV / Leanback Box.
 */
fun isTelevisionDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
}

/**
 * Dedicated Android TV / Google TV Activity for MyPlayer (`LEANBACK_LAUNCHER`).
 * Also handles TV Voice Search ("Play Arijit Singh on MyPlayer" via `MEDIA_PLAY_FROM_SEARCH`).
 */
@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {

    private var voiceQueryState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeState.isDarkMode = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        extractVoiceSearchQuery(intent)?.let { voiceQueryState = it }

        setContent {
            MyPlayerTheme(darkTheme = true) {
                TvMainScreen(
                    initialVoiceQuery = voiceQueryState,
                    onConsumeVoiceQuery = { voiceQueryState = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractVoiceSearchQuery(intent)?.let { voiceQueryState = it }
    }

    private fun extractVoiceSearchQuery(intent: Intent?): String? {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return null
        return intent.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotBlank() }
    }
}