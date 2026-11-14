package com.myplayer.app.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.myplayer.app.data.youtube.YouTubeIntegrationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val USER_AGENT_GOOGLE_AUTH =
    "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    youtubeManager: YouTubeIntegrationManager,
    onDismiss: () -> Unit,
    onLoginSuccess: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Web Sign-In, 1 = Import Playlist Link, 2 = Paste Cookie
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(YouTubeIntegrationManager.LOGIN_URL) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                clearHistory()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webViewRef = null
        }
    }

    var loginError by remember { mutableStateOf<String?>(null) }
    var lastCookieAttempt by remember { mutableStateOf<String?>(null) }

    var playlistUrlInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    val ytRed = Color(0xFFFF0033)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    fun startCookieLogin(rawCookies: String, loadingMessage: String) {
        if (!tokenFetchStarted.compareAndSet(false, true)) return
        loginError = null
        statusText = loadingMessage
        scope.launch {
            youtubeManager.completeLoginWithCookies(rawCookies)
                .onSuccess { accountName ->
                    Toast.makeText(
                        context,
                        "YouTube Music Connected ($accountName)! Syncing playlists & AI recommendations...",
                        Toast.LENGTH_LONG
                    ).show()
                    onLoginSuccess(accountName)
                    onDismiss()
                }
                .onFailure { err ->
                    statusText = null
                    loginError = err.message ?: "YouTube login failed"
                    Toast.makeText(
                        context,
                        "Login error: ${err.message ?: "Unknown"}",
                        Toast.LENGTH_SHORT
                    ).show()
                    tokenFetchStarted.set(false)
                }
        }
    }

    fun startPlaylistUrlImport(urlOrId: String) {
        if (!tokenFetchStarted.compareAndSet(false, true)) return
        loginError = null
        statusText = "Importing YouTube playlist & training AI..."
        scope.launch {
            youtubeManager.importPlaylistByUrl(urlOrId)
                .onSuccess { (title, count) ->
                    Toast.makeText(
                        context,
                        "Imported '$title' ($count songs) from YouTube!",
                        Toast.LENGTH_LONG
                    ).show()
                    onLoginSuccess("$title ($count songs)")
                    onDismiss()
                }
                .onFailure { err ->
                    statusText = null
                    loginError = err.message ?: "Playlist import failed"
                    tokenFetchStarted.set(false)
                }
        }
    }

    fun checkCookiesAndCompleteLogin(): Boolean {
        if (tokenFetchStarted.get()) return true
        if (loginError != null) return true

        val cm = CookieManager.getInstance()
        val combinedCookies = buildString {
            cm.getCookie("https://music.youtube.com")?.let { append(it).append("; ") }
            cm.getCookie("https://www.youtube.com")?.let { append(it).append("; ") }
            cm.getCookie("https://youtube.com")?.let { append(it) }
        }
        if (combinedCookies.isBlank()) return false

        val sapisid = youtubeManager.extractSapIsid(combinedCookies)
        // Wait until user actually lands on youtube.com / music.youtube.com with a valid SAPISID
        val isOnYouTubeDomain = currentUrl.contains("music.youtube.com") ||
            (currentUrl.contains("youtube.com") && !currentUrl.contains("accounts.google.com"))
        if (sapisid.isNullOrBlank() || !isOnYouTubeDomain) return false

        webViewRef?.stopLoading()
        lastCookieAttempt = combinedCookies
        startCookieLogin(combinedCookies, "Connecting to YouTube Music...")
        return true
    }

    LaunchedEffect(Unit) {
        while (!tokenFetchStarted.get()) {
            delay(500L)
            CookieManager.getInstance().flush()
            if (checkCookiesAndCompleteLogin()) break
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(surfaceColor)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedTab == 0 && webViewRef?.canGoBack() == true) {
                        IconButton(onClick = { webViewRef?.goBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = ytRed
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = "Connect YouTube Music",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = onSurfaceColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceColor)
                }
            }

            // Mode Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; loginError = null },
                    label = { Text("Web Sign-In", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ytRed,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; loginError = null },
                    label = { Text("Playlist Link", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ytRed,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; loginError = null },
                    label = { Text("Paste Cookie", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ytRed,
                        selectedLabelColor = Color.White
                    )
                )
            }

            if ((isLoading && selectedTab == 0) || statusText != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = ytRed
                )
            }

            when (selectedTab) {
                0 -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)

                                WebView(ctx).apply {
                                    webViewRef = this
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    requestFocus()

                                    cookieManager.setAcceptThirdPartyCookies(this, true)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    settings.setSupportMultipleWindows(true)
                                    settings.userAgentString = USER_AGENT_GOOGLE_AUTH

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            val mainWebView = view ?: return false
                                            val popupWebView = WebView(mainWebView.context).apply {
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.userAgentString = USER_AGENT_GOOGLE_AUTH
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        v: WebView?,
                                                        request: WebResourceRequest?
                                                    ): Boolean {
                                                        val popupUrl = request?.url?.toString() ?: return false
                                                        mainWebView.loadUrl(popupUrl)
                                                        v?.destroy()
                                                        return true
                                                    }
                                                }
                                            }
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            transport.webView = popupWebView
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            isLoading = true
                                            url?.let { currentUrl = it }
                                            checkCookiesAndCompleteLogin()

                                            // Disable broken WebView Passkey prompt so standard Google login works smoothly
                                            if (url?.contains("google.") == true) {
                                                view?.evaluateJavascript(
                                                    """
                                                    (function() {
                                                      try {
                                                        delete window.PublicKeyCredential;
                                                        if (navigator.credentials) {
                                                          navigator.credentials.get = function() {
                                                            return Promise.reject(new Error('WebAuthn disabled in WebView'));
                                                          };
                                                        }
                                                      } catch(e) {}
                                                    })();
                                                    """.trimIndent(),
                                                    null
                                                )
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                            url?.let { currentUrl = it }
                                            CookieManager.getInstance().flush()
                                            checkCookiesAndCompleteLogin()
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val reqUrl = request?.url?.toString() ?: return false
                                            currentUrl = reqUrl
                                            checkCookiesAndCompleteLogin()

                                            if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) {
                                                if (reqUrl.startsWith("intent://")) {
                                                    try {
                                                        val intent = Intent.parseUri(reqUrl, Intent.URI_INTENT_SCHEME)
                                                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                        if (!fallbackUrl.isNullOrBlank()) {
                                                            view?.loadUrl(fallbackUrl)
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }

                                    loadUrl(YouTubeIntegrationManager.LOGIN_URL)
                                }
                            }
                        )

                        if (statusText != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(surfaceColor.copy(alpha = 0.94f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = ytRed)
                                    Spacer(Modifier.height(16.dp))
                                    Text(statusText!!, color = onSurfaceColor, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        loginError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(12.dp)
                                    .fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "Login failed: $errMsg",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                lastCookieAttempt?.let { raw ->
                                                    startCookieLogin(raw, "Syncing YouTube Music profile...")
                                                }
                                            }
                                        ) { Text("Try Again", color = ytRed) }
                                        TextButton(onClick = { loginError = null }) {
                                            Text("Close", color = onSurfaceColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // Tab 1: Direct YouTube / YouTube Music Playlist URL Import
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "Import Any YouTube or YouTube Music Playlist:",
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "1. Copy any playlist link from YouTube or YouTube Music (e.g. https://music.youtube.com/playlist?list=PL...).\n" +
                                    "2. Paste it below and tap 'Import Playlist'.\n" +
                                    "3. All songs will be added to your Library Playlists & used to train your AI recommendations!",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = playlistUrlInput,
                            onValueChange = { playlistUrlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("YouTube / YT Music Playlist URL or ID") },
                            placeholder = { Text("https://music.youtube.com/playlist?list=PL...") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ytRed,
                                cursorColor = ytRed
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let { text ->
                                        playlistUrlInput = text.trim()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Paste Link")
                            }

                            Button(
                                onClick = {
                                    if (playlistUrlInput.isNotBlank()) {
                                        startPlaylistUrlImport(playlistUrlInput.trim())
                                    }
                                },
                                enabled = playlistUrlInput.isNotBlank() && statusText == null,
                                colors = ButtonDefaults.buttonColors(containerColor = ytRed),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Import Playlist", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        if (statusText != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                CircularProgressIndicator(color = ytRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(statusText!!, fontSize = 12.sp, color = onSurfaceColor)
                            }
                        }

                        loginError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        errMsg,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { loginError = null }) {
                                        Text("Close", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // Tab 2: Paste Cookie Fallback
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "How to connect via YouTube Music Cookie:",
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "1. Log in to music.youtube.com in your browser.\n" +
                                    "2. Open Developer Tools > Network or Application > Cookies > music.youtube.com.\n" +
                                    "3. Copy the full Cookie header (or 'SAPISID' value) and paste it below.",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = { cookieInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("YouTube Cookie or SAPISID") },
                            placeholder = { Text("SAPISID=...; __Secure-3PAPISID=...") },
                            singleLine = false,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ytRed,
                                cursorColor = ytRed
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let { text ->
                                        cookieInput = text.trim()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Paste from Clipboard")
                            }

                            Button(
                                onClick = {
                                    val clean = cookieInput.trim()
                                    if (clean.isNotBlank()) {
                                        lastCookieAttempt = clean
                                        startCookieLogin(clean, "Validating YouTube session & syncing...")
                                    }
                                },
                                enabled = cookieInput.isNotBlank() && statusText == null,
                                colors = ButtonDefaults.buttonColors(containerColor = ytRed),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        if (statusText != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                CircularProgressIndicator(color = ytRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(statusText!!, fontSize = 12.sp, color = onSurfaceColor)
                            }
                        }

                        loginError?.let { errMsg ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Validation failed: $errMsg",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { loginError = null }) {
                                        Text("Close", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
