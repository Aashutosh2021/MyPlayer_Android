package com.myplayer.app.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.myplayer.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface InAppDownloadState {
    object Idle : InAppDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val versionName: String
    ) : InAppDownloadState
    data class ReadyToInstall(
        val apkFile: File,
        val versionName: String,
        val needsInstallPermission: Boolean = false
    ) : InAppDownloadState
    data class Error(val message: String) : InAppDownloadState
}

/**
 * MyPlayer In-App APK Updater & Package Installer Manager
 * (ported from `MyPlayer`'s `GithubUpdates.kt`, `downloadmanager.kt`, `UpdateDownloadWorker.kt`,
 * and `downloadnotificationmanager.kt`).
 *
 * Features:
 * 1. Downloads the release `.apk` (or extracts `.apk` from `.zip` nightly artifacts) directly inside
 *    the app with real-time [downloadState] progress (`0f..1f`), byte counters, and system progress notification.
 * 2. Caches downloaded APKs in `Android/data/com.myplayer.app/files/Download/myplayer_updates/` and
 *    verifies exact file size (`isDownloaded`) so already-downloaded updates install in 1 tap.
 * 3. Checks `PackageManager.canRequestPackageInstalls()` on Android 8.0+ (API 26+); if not granted,
 *    opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for `package:com.myplayer.app` and lets the
 *    user tap "Install Now" immediately upon returning.
 * 4. Launches Android's native Package Installer via `FileProvider` (`${packageName}.fileprovider`)
 *    with `FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_ACTIVITY_NEW_TASK`.
 */
@Singleton
class InAppUpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "myplayer_inapp_update_download"
        private const val DOWNLOAD_NOTIFICATION_ID = 9002

        fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "Official APK"
            bytes >= 1_000_000L -> String.format("%.1f MB", bytes / (1024f * 1024f))
            else -> String.format("%.0f KB", bytes / 1024f)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null

    private val _downloadState = MutableStateFlow<InAppDownloadState>(InAppDownloadState.Idle)
    val downloadState: StateFlow<InAppDownloadState> = _downloadState.asStateFlow()

    init {
        ensureNotificationChannel()
        autoCleanOldApks()
    }

    private fun ensureNotificationChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    "In-App Update Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while downloading MyPlayer updates inside the app"
                }
            )
        }
    }

    fun getUpdatesDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        return File(base, "myplayer_updates").apply {
            if (!exists()) mkdirs()
        }
    }

    fun apkFileFor(update: UpdateInfo): File {
        val safeVer = update.tagName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val rawName = update.apkName?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "MyPlayer-$safeVer.apk"
        return File(getUpdatesDir(), rawName)
    }

    /**
     * Returns true if the APK for [update] is already downloaded and matches the expected size.
     */
    fun isDownloaded(update: UpdateInfo): Boolean {
        val target = apkFileFor(update)
        if (!target.exists() || target.length() <= 100_000L) return false
        return update.apkSizeBytes <= 0L || target.length() == update.apkSizeBytes
    }

    fun syncExistingDownloadState(update: UpdateInfo) {
        if (activeDownloadJob?.isActive == true) return
        if (isDownloaded(update)) {
            val file = apkFileFor(update)
            _downloadState.value = InAppDownloadState.ReadyToInstall(
                apkFile = file,
                versionName = update.versionName,
                needsInstallPermission = !canInstallPackages()
            )
        } else {
            _downloadState.value = InAppDownloadState.Idle
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pages = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            pages.firstOrNull { intent ->
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
        }
    }

    /**
     * Launches Android's package installer for [apkFile], or opens "Allow from this source"
     * settings if `REQUEST_INSTALL_PACKAGES` permission hasn't been granted yet.
     */
    fun installApk(apkFile: File, versionName: String = ""): Boolean {
        if (!apkFile.exists()) {
            _downloadState.value = InAppDownloadState.Error("Downloaded APK file not found. Tap to download again.")
            return false
        }
        if (!canInstallPackages()) {
            _downloadState.value = InAppDownloadState.ReadyToInstall(
                apkFile = apkFile,
                versionName = versionName,
                needsInstallPermission = true
            )
            requestInstallPermission()
            return false
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            _downloadState.value = InAppDownloadState.ReadyToInstall(
                apkFile = apkFile,
                versionName = versionName,
                needsInstallPermission = false
            )
            true
        } catch (e: Exception) {
            _downloadState.value = InAppDownloadState.Error("Failed to launch installer: ${e.message}")
            false
        }
    }

    /**
     * Starts downloading the update APK inside the app and automatically triggers installation
     * once the download finishes.
     */
    fun startDownloadAndInstall(update: UpdateInfo, autoInstallOnFinish: Boolean = true) {
        if (activeDownloadJob?.isActive == true) return

        if (isDownloaded(update)) {
            val existing = apkFileFor(update)
            _downloadState.value = InAppDownloadState.ReadyToInstall(
                apkFile = existing,
                versionName = update.versionName,
                needsInstallPermission = !canInstallPackages()
            )
            if (autoInstallOnFinish) {
                installApk(existing, update.versionName)
            }
            return
        }

        val downloadUrl = update.apkUrl?.takeIf { it.isNotBlank() }
        if (downloadUrl == null) {
            _downloadState.value = InAppDownloadState.Error(
                "No direct APK file attached to this release. Opening official download page..."
            )
            return
        }

        activeDownloadJob = scope.launch {
            val targetApk = apkFileFor(update)
            val isZip = downloadUrl.endsWith(".zip", ignoreCase = true) || downloadUrl.contains("nightly.link")
            val tempFile = File(getUpdatesDir(), if (isZip) "temp_update.zip" else "${targetApk.name}.part")

            try {
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = InAppDownloadState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = update.apkSizeBytes,
                    versionName = update.versionName
                )
                postProgressNotification(update.versionName, 0, indeterminate = true)

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "MyPlayer-InAppUpdater")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Server returned HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val totalBytes = update.apkSizeBytes.takeIf { it > 0L }
                        ?: body.contentLength().takeIf { it > 0L }
                        ?: -1L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastNotifPercent = -1

                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read

                                val progress = if (totalBytes > 0L) {
                                    (written.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                _downloadState.value = InAppDownloadState.Downloading(
                                    progress = progress,
                                    downloadedBytes = written,
                                    totalBytes = totalBytes,
                                    versionName = update.versionName
                                )

                                val pct = (progress * 100f).toInt()
                                if (pct != lastNotifPercent && (pct % 2 == 0 || pct == 100)) {
                                    lastNotifPercent = pct
                                    postProgressNotification(update.versionName, pct, indeterminate = totalBytes <= 0L)
                                }
                            }
                            output.flush()
                        }
                    }
                }

                if (!isActive) return@launch

                if (isZip) {
                    var extracted = false
                    ZipInputStream(tempFile.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                if (targetApk.exists()) targetApk.delete()
                                FileOutputStream(targetApk).use { fos -> zis.copyTo(fos) }
                                extracted = true
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                    tempFile.delete()
                    if (!extracted) {
                        throw IllegalStateException("No APK found inside the downloaded archive.")
                    }
                } else {
                    if (targetApk.exists()) targetApk.delete()
                    if (!tempFile.renameTo(targetApk)) {
                        tempFile.copyTo(targetApk, overwrite = true)
                        tempFile.delete()
                    }
                }

                postCompleteNotification(update.versionName, targetApk)

                withContext(Dispatchers.Main) {
                    _downloadState.value = InAppDownloadState.ReadyToInstall(
                        apkFile = targetApk,
                        versionName = update.versionName,
                        needsInstallPermission = !canInstallPackages()
                    )
                    if (autoInstallOnFinish) {
                        installApk(targetApk, update.versionName)
                    }
                }
            } catch (e: CancellationException) {
                tempFile.delete()
                _downloadState.value = InAppDownloadState.Idle
                cancelNotification()
            } catch (e: Exception) {
                tempFile.delete()
                _downloadState.value = InAppDownloadState.Error(
                    e.message ?: "Download failed. Please check your connection and try again."
                )
                cancelNotification()
            }
        }
    }

    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _downloadState.value = InAppDownloadState.Idle
        cancelNotification()
    }

    private fun autoCleanOldApks() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L
            getUpdatesDir().listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun postProgressNotification(versionName: String, percent: Int, indeterminate: Boolean) {
        if (!hasNotificationPermission()) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notif = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Downloading MyPlayer $versionName")
                .setContentText(if (indeterminate) "Downloading update package..." else "$percent% completed")
                .setProgress(100, percent.coerceIn(0, 100), indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(DOWNLOAD_NOTIFICATION_ID, notif)
        }
    }

    private fun postCompleteNotification(versionName: String, apkFile: File) {
        if (!hasNotificationPermission()) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                DOWNLOAD_NOTIFICATION_ID,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MyPlayer $versionName Ready to Install")
                .setContentText("Download complete • Tap to install update")
                .setContentIntent(pendingIntent)
                .addAction(0, "INSTALL NOW", pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(DOWNLOAD_NOTIFICATION_ID, notif)
        }
    }

    private fun cancelNotification() {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(DOWNLOAD_NOTIFICATION_ID)
        }
    }
}
