package com.myplayer.app.data.online.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class PoTokenBundle(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
    val visitorData: String,
    val generatedAtMs: Long = System.currentTimeMillis()
)

/**
 * OpenTune BotGuard PoToken (Proof-of-Origin Token) Generator for MyPlayer.
 * Generates WebView-backed BotGuard tokens so YouTube /player streams
 * avoid HTTP 403 Forbidden mid-song.
 */
@Singleton
class PoTokenGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PoTokenGenerator"
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L
        private const val TIMEOUT_MS = 10_000L
    }

    private val mutex = Mutex()
    @Volatile
    private var cachedBundle: PoTokenBundle? = null

    suspend fun getOrGeneratePoToken(visitorData: String, videoId: String): PoTokenBundle? = mutex.withLock {
        val existing = cachedBundle
        if (existing != null &&
            existing.visitorData == visitorData &&
            System.currentTimeMillis() - existing.generatedAtMs < TOKEN_TTL_MS
        ) {
            return existing
        }

        return withTimeoutOrNull(TIMEOUT_MS) {
            generateViaWebView(visitorData, videoId)
        }?.also {
            cachedBundle = it
            Log.d(TAG, "Generated fresh PoToken for visitorData=${visitorData.take(12)}...")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun generateViaWebView(visitorData: String, videoId: String): PoTokenBundle? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<PoTokenBundle?>()
            var webView: WebView? = null
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onPoTokenReady(playerToken: String, streamToken: String) {
                            if (playerToken.isNotBlank()) {
                                deferred.complete(
                                    PoTokenBundle(
                                        playerRequestPoToken = playerToken,
                                        streamingDataPoToken = streamToken.ifBlank { playerToken },
                                        visitorData = visitorData
                                    )
                                )
                            } else {
                                deferred.complete(null)
                            }
                        }

                        @JavascriptInterface
                        fun onPoTokenError(reason: String) {
                            Log.w(TAG, "BotGuard JS error: $reason")
                            deferred.complete(null)
                        }
                    }, "MyPlayerPoTokenBridge")
                }

                webView.loadUrl("file:///android_asset/po_token.html?visitorData=$visitorData&videoId=$videoId")
                deferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "WebView PoToken generation failed: ${e.message}")
                null
            } finally {
                Handler(Looper.getMainLooper()).post {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (_: Exception) {}
                }
            }
        }
}
