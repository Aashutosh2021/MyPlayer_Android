package com.myplayer.app.data.update

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.myplayer.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val versionName: String,
    val tagName: String = versionName,
    val releaseUrl: String = UpdateChecker.UPDATE_WEBSITE_URL,
    val releaseNotes: String = "",
    val apkUrl: String? = null,
    val apkName: String? = null,
    val apkSizeBytes: Long = 0L,
    val publishedAt: String = "",
    val isPreRelease: Boolean = false,
    val githubReleaseUrl: String? = null
)

class NoInternetException(
    message: String = "No internet connection detected. Please check your network or turn off Airplane Mode."
) : IOException(message)

/**
 * Polls the GitHub Releases API (MyPlayer multi-release & architecture-aware APK asset resolver)
 * and compares it against the currently installed app version.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "UpdateChecker"

        const val GITHUB_OWNER = "Aashutosh2021"
        const val GITHUB_REPO = "MyPlayer"

        const val UPDATE_WEBSITE_URL = "https://myplayer-660ee.web.app"

        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        private const val ALL_RELEASES_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=15"

        private const val PREFS_NAME = "update_check_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val KEY_SNOOZED_UNTIL_MS = "update_snoozed_until_ms"
        private const val KEY_SNOOZED_VERSION = "update_snoozed_version"
        private const val KEY_INCLUDE_BETA = "update_include_beta"

        private val VERSION_REGEX = Regex("""(?i)\bv?(\d+(?:\.\d+)+)\b""")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var includeBetaReleases: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_BETA, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INCLUDE_BETA, value).apply()
        }

    fun snoozeUpdateFor24Hours(versionName: String) {
        val until = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        prefs.edit()
            .putString(KEY_SNOOZED_VERSION, versionName)
            .putLong(KEY_SNOOZED_UNTIL_MS, until)
            .apply()
    }

    fun isUpdateSnoozed(versionName: String): Boolean {
        val snoozedVer = prefs.getString(KEY_SNOOZED_VERSION, null) ?: return false
        val until = prefs.getLong(KEY_SNOOZED_UNTIL_MS, 0L)
        return snoozedVer == versionName && System.currentTimeMillis() < until
    }

    /**
     * Checks if the device has an active internet-capable network connection.
     */
    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns UpdateInfo if a newer release exists than the app's own versionName.
     * Supports both `/releases` list (with ABI-specific or universal APK asset selection, like MyPlayer)
     * and `/releases/latest` fallback.
     */
    suspend fun checkForUpdate(forceCheckBeta: Boolean = includeBetaReleases): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            throw NoInternetException("No internet connection detected. Please turn off Airplane Mode or connect to Wi-Fi/mobile data.")
        }

        val listRequest = Request.Builder()
            .url(ALL_RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MyPlayer-UpdateChecker")
            .build()

        try {
            okHttpClient.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub releases check returned HTTP ${response.code}")
                    if (response.code == 403) {
                        throw IOException("GitHub API rate limit exceeded. Please try again later.")
                    }
                    if (response.code >= 500) {
                        throw IOException("GitHub server error (${response.code}). Please try again later.")
                    }
                    return@withContext checkLatestEndpointFallback()
                }

                val bodyStr = response.body?.string() ?: return@withContext null
                val releasesArray = try {
                    JSONArray(bodyStr)
                } catch (e: Exception) {
                    return@withContext parseSingleReleaseJson(bodyStr)
                }

                var bestCandidate: UpdateInfo? = null
                var bestRemoteVersion = BuildConfig.VERSION_NAME

                for (i in 0 until releasesArray.length()) {
                    val releaseJson = releasesArray.optJSONObject(i) ?: continue
                    if (releaseJson.optBoolean("draft", false)) continue
                    val isPre = releaseJson.optBoolean("prerelease", false)
                    if (isPre && !forceCheckBeta) continue

                    val parsed = parseReleaseObject(releaseJson) ?: continue
                    val remoteVer = extractVersion(
                        parsed.tagName,
                        parsed.versionName,
                        parsed.releaseNotes,
                        parsed.apkName
                    )
                    if (isNewerVersion(remoteVer, bestRemoteVersion)) {
                        bestRemoteVersion = remoteVer
                        bestCandidate = parsed
                    }
                }

                bestCandidate
            }
        } catch (e: UnknownHostException) {
            throw NoInternetException("Unable to reach GitHub. Please check your internet connection or turn off Airplane Mode.")
        } catch (e: ConnectException) {
            throw NoInternetException("Failed to connect to GitHub. Please check your network connection.")
        } catch (e: SocketTimeoutException) {
            throw IOException("Connection timed out while checking for updates. Please try again.")
        }
    }

    private fun checkLatestEndpointFallback(): UpdateInfo? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MyPlayer-UpdateChecker")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
            return parseSingleReleaseJson(bodyStr)
        }
    }

    private fun parseSingleReleaseJson(bodyStr: String): UpdateInfo? {
        val json = try {
            JSONObject(bodyStr)
        } catch (_: Exception) {
            return null
        }
        val parsed = parseReleaseObject(json) ?: return null
        val remoteVer = extractVersion(parsed.tagName, parsed.versionName, parsed.releaseNotes, parsed.apkName)
        return if (isNewerVersion(remoteVer, BuildConfig.VERSION_NAME)) parsed else null
    }

    /**
     * Parses a GitHub Release JSON object and selects the best matching `.apk` (or `.zip`) asset
     * for the device's CPU ABI (ported from MyPlayer's `GithubUpdates.kt`).
     */
    private fun parseReleaseObject(json: JSONObject): UpdateInfo? {
        val tagName = json.optString("tag_name", "")
        val releaseName = json.optString("name", "")
        val notes = json.optString("body", "")
        val publishedAt = json.optString("published_at", "")
        val isPreRelease = json.optBoolean("prerelease", false)

        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase().orEmpty()

        var abiUrl: String? = null
        var abiName: String? = null
        var abiSize = 0L

        var universalUrl: String? = null
        var universalName: String? = null
        var universalSize = 0L

        var fallbackUrl: String? = null
        var fallbackName: String? = null
        var fallbackSize = 0L

        val assetsArray = json.optJSONArray("assets")
        if (assetsArray != null) {
            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true) && !name.endsWith(".zip", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url", "")
                if (url.isBlank()) continue
                val size = asset.optLong("size", 0L)

                if (primaryAbi.isNotBlank() && name.contains(primaryAbi, ignoreCase = true)) {
                    abiUrl = url
                    abiName = name
                    abiSize = size
                } else if (name.contains("universal", ignoreCase = true) || name.endsWith(".apk", ignoreCase = true)) {
                    if (universalUrl == null) {
                        universalUrl = url
                        universalName = name
                        universalSize = size
                    }
                } else if (fallbackUrl == null) {
                    fallbackUrl = url
                    fallbackName = name
                    fallbackSize = size
                }
            }
        }

        val apkDownloadUrl = abiUrl ?: universalUrl ?: fallbackUrl
        val apkFileName = abiName ?: universalName ?: fallbackName
        val apkSizeBytes = when {
            abiUrl != null -> abiSize
            universalUrl != null -> universalSize
            else -> fallbackSize
        }

        val remoteVersion = extractVersion(tagName, releaseName, notes, apkFileName)
        val defaultUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
        val releaseHtmlUrl = json.optString("html_url", defaultUrl)
        val displayName = releaseName.ifBlank { "v$remoteVersion" }

        return UpdateInfo(
            versionName = displayName,
            tagName = tagName.ifBlank { "v$remoteVersion" },
            releaseUrl = UPDATE_WEBSITE_URL,
            releaseNotes = notes,
            apkUrl = apkDownloadUrl,
            apkName = apkFileName ?: "MyPlayer-v$remoteVersion.apk",
            apkSizeBytes = apkSizeBytes,
            publishedAt = publishedAt,
            isPreRelease = isPreRelease,
            githubReleaseUrl = releaseHtmlUrl
        )
    }

    /** True only if this exact version hasn't already triggered a notification. */
    fun shouldNotifyFor(update: UpdateInfo): Boolean {
        return prefs.getString(KEY_LAST_NOTIFIED_VERSION, null) != update.versionName
    }

    fun markNotified(update: UpdateInfo) {
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, update.versionName).apply()
    }

    /**
     * Extracts semantic version numbers (e.g. "3.1") from tagName, releaseName, APK name, or notes.
     */
    fun extractVersion(
        tagName: String,
        releaseName: String,
        body: String,
        assetName: String?
    ): String {
        VERSION_REGEX.find(tagName)?.groupValues?.get(1)?.let { return it }
        VERSION_REGEX.find(releaseName)?.groupValues?.get(1)?.let { return it }
        if (!assetName.isNullOrBlank()) {
            VERSION_REGEX.find(assetName)?.groupValues?.get(1)?.let { return it }
        }
        VERSION_REGEX.find(body)?.groupValues?.get(1)?.let { return it }
        val clean = tagName.removePrefix("v").removePrefix("V").trim()
        return clean.ifBlank { "0.0" }
    }

    /** Numeric, per-segment comparison — "2.10" > "2.9", unlike plain string comparison. */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val l = local.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val maxLen = maxOf(r.size, l.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
