package com.myplayer.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.myplayer.app.data.backup.BackupRestoreManager
import com.myplayer.app.data.local.dao.DownloadedSongDao
import com.myplayer.app.data.local.dao.SongDao
import com.myplayer.app.data.local.entity.DownloadedSongEntity
import com.myplayer.app.data.local.prefs.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val downloadedSongDao: DownloadedSongDao,
    private val preferencesManager: PreferencesManager,
    private val lyricsRepository: com.myplayer.app.data.lyrics.LyricsRepository,
    private val songDao: SongDao,
    private val audioTagHelper: AudioTagHelper
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG_DOWNLOAD = "download"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_STREAM_URL = "stream_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIF_ID = 1001
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Music download progress"
                }
            )
        }
        val notifId = NOTIF_ID + (inputData.getString(KEY_VIDEO_ID)?.hashCode()?.and(0xFFFF) ?: 0)
        return ForegroundInfo(
            notifId,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading Song")
                .setContentText("Fetching audio stream...")
                .setOngoing(true)
                .setSilent(true)
                .build(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /**
     * Downloads the song's poster/thumbnail image to permanent local storage
     * (`filesDir/artwork/<videoId>.jpg` + companion `<songFile>.jpg`) so artwork
     * is 100% available when internet is OFF.
     */
    private suspend fun downloadThumbnailLocally(
        videoId: String,
        remoteThumbnailUrl: String,
        audioSavedPath: String
    ): String = withContext(Dispatchers.IO) {
        val artDir = File(context.filesDir, "artwork").apply { mkdirs() }
        val localArtFile = File(artDir, "$videoId.jpg")

        // If remoteThumbnailUrl is already a valid local file, ensure it's copied to filesDir/artwork/<videoId>.jpg
        if (remoteThumbnailUrl.startsWith("file://") || remoteThumbnailUrl.startsWith("/")) {
            val srcPath = if (remoteThumbnailUrl.startsWith("file://")) {
                Uri.parse(remoteThumbnailUrl).path
            } else remoteThumbnailUrl
            val srcFile = srcPath?.let { File(it) }
            if (srcFile != null && srcFile.exists() && srcFile.length() > 512L) {
                if (srcFile.absolutePath != localArtFile.absolutePath) {
                    runCatching { srcFile.copyTo(localArtFile, overwrite = true) }
                }
                if (!audioSavedPath.startsWith("content://") && audioSavedPath.isNotBlank()) {
                    val audioFile = File(audioSavedPath)
                    audioFile.parentFile?.let { parent ->
                        runCatching { srcFile.copyTo(File(parent, "${audioFile.nameWithoutExtension}.jpg"), overwrite = true) }
                    }
                }
                return@withContext Uri.fromFile(localArtFile).toString()
            }
        }

        val candidateUrls = buildList {
            if (remoteThumbnailUrl.isNotBlank() && (remoteThumbnailUrl.startsWith("http://") || remoteThumbnailUrl.startsWith("https://"))) {
                add(remoteThumbnailUrl)
            }
            if (videoId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                add("https://i.ytimg.com/vi/$videoId/hq720.jpg")
                add("https://i.ytimg.com/vi/$videoId/sddefault.jpg")
                add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                add("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
            }
        }.distinct()

        for (url in candidateUrls) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.size > 512) {
                            localArtFile.writeBytes(bytes)
                            // Also save companion .jpg next to the audio file if stored on FileSystem
                            if (!audioSavedPath.startsWith("content://")) {
                                val audioFile = File(audioSavedPath)
                                audioFile.parentFile?.let { parent ->
                                    val companionJpg = File(parent, "${audioFile.nameWithoutExtension}.jpg")
                                    runCatching { companionJpg.writeBytes(bytes) }
                                }
                            }
                            Log.i("DownloadWorker", "Saved offline poster for $videoId at ${localArtFile.absolutePath}")
                            return@withContext Uri.fromFile(localArtFile).toString()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("DownloadWorker", "Failed downloading thumbnail from $url: ${e.message}")
            }
        }

        if (localArtFile.exists() && localArtFile.length() > 512L) {
            return@withContext Uri.fromFile(localArtFile).toString()
        }
        return@withContext remoteThumbnailUrl
    }

    private fun extractDurationMs(savedPath: String): Long {
        if (savedPath.isBlank()) return 0L
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            if (savedPath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(savedPath))
            } else {
                retriever.setDataSource(savedPath)
            }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun cacheOfflineLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        savedPath: String
    ) {
        try {
            val lyricsResult = lyricsRepository.fetchLyrics(
                songId = videoId,
                trackName = title,
                artistName = artist,
                durationSeconds = (durationMs / 1000L).toInt(),
                localPath = savedPath
            )
            val lrcText = lyricsResult?.syncedLyrics?.takeIf { it.isNotBlank() }
                ?: lyricsResult?.plainLyrics?.takeIf { it.isNotBlank() }
            if (!lrcText.isNullOrBlank()) {
                // 1. Always save permanent offline .lrc in app storage (works even with SAF content:// folders)
                lyricsRepository.saveOfflineLrcFile(videoId, lrcText)
                // 2. Also save companion .lrc next to filesystem audio file
                if (!savedPath.startsWith("content://")) {
                    val targetFile = File(savedPath)
                    if (targetFile.exists()) {
                        targetFile.parentFile?.let { parent ->
                            runCatching {
                                File(parent, "${targetFile.nameWithoutExtension}.lrc").writeText(lrcText)
                            }
                        }
                    }
                }
                Log.i("DownloadWorker", "Saved offline lyrics for $videoId")
            }
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Failed to auto-cache lyrics or save companion .lrc for $videoId", e)
        }
    }

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Failed to set foreground", e)
        }

        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"
        val artist = inputData.getString(KEY_ARTIST) ?: "Unknown"
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL) ?: ""
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val streamUrl = inputData.getString(KEY_STREAM_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No stream URL provided"))

        setProgress(workDataOf(KEY_PROGRESS to 0))

        var tempFileToClean: File? = null
        var tmpDocToClean: DocumentFile? = null
        var isSuccess = false

        return try {
            val customFolderUriString = preferencesManager.downloadFolderUri.firstOrNull()
            val hasCustomFolder = !customFolderUriString.isNullOrEmpty()

            val safeTitle = title
                .replace(Regex("[^\\p{L}\\p{N} _\\-]"), "")
                .replace(Regex(" {2,}"), " ")
                .trim()
                .take(60)

            val cleanTitle = safeTitle.ifBlank { "track" }
            val finalFileName = "$cleanTitle [$videoId].m4a"
            val tmpFileName = "$cleanTitle [$videoId].tmp"

            val existing = downloadedSongDao.getById(videoId)
            if (existing != null &&
                BackupRestoreManager.checkFileExists(applicationContext, existing.localPath)
            ) {
                // Backfill offline artwork & lyrics if an older download didn't save them locally
                val artFile = File(File(context.filesDir, "artwork"), "$videoId.jpg")
                if (!artFile.exists() || artFile.length() <= 512L || !existing.thumbnailUrl.startsWith("file://")) {
                    val fixedThumb = downloadThumbnailLocally(
                        videoId = videoId,
                        remoteThumbnailUrl = existing.thumbnailUrl.ifBlank { thumbnailUrl },
                        audioSavedPath = existing.localPath
                    )
                    if (fixedThumb.startsWith("file://")) {
                        downloadedSongDao.insert(existing.copy(thumbnailUrl = fixedThumb))
                        runCatching {
                            val dbSong = songDao.getSongById(videoId) ?: songDao.getSongByVideoId(videoId)
                            if (dbSong != null) songDao.updateSongArtwork(dbSong.id, fixedThumb)
                        }
                    }
                }
                val lrcBackup = File(File(context.filesDir, "lyrics"), "$videoId.lrc")
                if (!lrcBackup.exists() || lrcBackup.length() <= 0L) {
                    val durMs = if (existing.durationMs > 0L) existing.durationMs else extractDurationMs(existing.localPath)
                    cacheOfflineLyrics(videoId, existing.title.ifBlank { title }, existing.artist.ifBlank { artist }, durMs, existing.localPath)
                }
                return Result.success(workDataOf(KEY_PROGRESS to 100))
            }

            if (isStopped) return Result.failure(workDataOf(KEY_ERROR to "Stopped before start"))

            var savedPath = ""
            var fileSize = 0L

            val userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
            val CHUNK_SIZE = 512L * 1024L // 512 KB chunks for reliable download on slow internet

            var totalSize = -1L
            try {
                val headReq = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Range", "bytes=0-0")
                    .build()
                okHttpClient.newCall(headReq).execute().use { response ->
                    val cr = response.header("Content-Range")
                    totalSize = cr?.substringAfterLast('/')?.toLongOrNull()
                        ?: (if (response.isSuccessful) response.body?.contentLength() ?: -1L else -1L)
                }
            } catch (e: Exception) {
                Log.e("DownloadWorker", "Failed to fetch file size", e)
            }

            suspend fun downloadToStream(out: java.io.OutputStream, startOffset: Long): Long {
                var downloaded = startOffset
                var serverIgnoredRange = false
                while (true) {
                    if (isStopped) throw IOException("Stopped by user")
                    val endOffset = if (totalSize > 0) minOf(downloaded + CHUNK_SIZE - 1, totalSize - 1) else downloaded + CHUNK_SIZE - 1

                    var bytesReadThisChunk = -1L
                    var lastErr: Exception? = null
                    for (attempt in 1..4) {
                        try {
                            val req = Request.Builder()
                                .url(streamUrl)
                                .addHeader("User-Agent", userAgent)
                                .addHeader("Range", "bytes=$downloaded-$endOffset")
                                .build()

                            bytesReadThisChunk = withContext(Dispatchers.IO) {
                                okHttpClient.newCall(req).execute().use { response ->
                                    if (response.code == 416) return@use -1L
                                    if (!response.isSuccessful && response.code != 206) throw IOException("HTTP ${response.code}")
                                    if (response.code == 200) {
                                        serverIgnoredRange = true
                                    }
                                    val body = response.body ?: throw IOException("Empty body")
                                    val stream = body.byteStream()
                                    val buf = ByteArray(8192)
                                    var readThisChunk = 0L
                                    while (true) {
                                        val r = stream.read(buf)
                                        if (r == -1) break
                                        out.write(buf, 0, r)
                                        readThisChunk += r
                                    }
                                    readThisChunk
                                }
                            }
                            lastErr = null
                            break
                        } catch (e: Exception) {
                            lastErr = e
                            if (attempt < 4) kotlinx.coroutines.delay(500L * attempt)
                        }
                    }
                    if (lastErr != null) throw lastErr

                    if (bytesReadThisChunk <= 0L) break
                    downloaded += bytesReadThisChunk
                    if (totalSize > 0) {
                        val prog = ((downloaded.toFloat() / totalSize) * 100).toInt()
                        setProgress(workDataOf(KEY_PROGRESS to prog.coerceIn(0, 99)))
                    } else {
                        setProgress(workDataOf(KEY_PROGRESS to 50))
                    }

                    if (serverIgnoredRange) break
                    if (totalSize > 0 && downloaded >= totalSize) break
                    if (bytesReadThisChunk < CHUNK_SIZE && totalSize == -1L) break
                }
                return downloaded
            }

            if (hasCustomFolder) {
                val treeUri = Uri.parse(customFolderUriString!!)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return Result.failure(workDataOf(KEY_ERROR to "Could not access custom folder"))

                rootDoc.findFile(tmpFileName)?.delete()
                val tmpDoc = rootDoc.createFile("application/octet-stream", tmpFileName)
                    ?: return Result.failure(workDataOf(KEY_ERROR to "Could not create temp file"))
                tmpDocToClean = tmpDoc

                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(tmpDoc.uri)?.use { out ->
                        downloadToStream(out, 0L)
                    }
                }

                rootDoc.findFile(finalFileName)?.delete()
                tmpDoc.renameTo(finalFileName)
                savedPath = tmpDoc.uri.toString()
                fileSize = tmpDoc.length()
            } else {
                val musicDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir
                musicDir.mkdirs()
                val destFile = File(musicDir, finalFileName)
                val tempFile = File(musicDir, tmpFileName)
                tempFileToClean = tempFile

                var alreadyDownloaded = if (tempFile.exists()) tempFile.length() else 0L
                if (alreadyDownloaded > 0 && totalSize > 0 && alreadyDownloaded >= totalSize) {
                    alreadyDownloaded = 0L
                    tempFile.delete()
                }

                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(tempFile, alreadyDownloaded > 0L).use { out ->
                        downloadToStream(out, alreadyDownloaded)
                    }
                }

                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                savedPath = destFile.absolutePath
                fileSize = destFile.length()
            }

            if (fileSize <= 0L) {
                return Result.failure(workDataOf(KEY_ERROR to "Downloaded file is empty or missing"))
            }

            // Download & save poster locally for offline display!
            val localThumbnailUri = downloadThumbnailLocally(
                videoId = videoId,
                remoteThumbnailUrl = thumbnailUrl,
                audioSavedPath = savedPath
            )

            val entity = DownloadedSongEntity(
                id = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = localThumbnailUri,
                durationMs = durationMs,
                localPath = savedPath,
                fileSizeBytes = fileSize,
                downloadedAt = System.currentTimeMillis()
            )
            downloadedSongDao.insert(entity)

            audioTagHelper.scanDownloadedFile(context, savedPath, title, artist)

            // Synchronize offline poster & ensure song entry exists in SongDao without losing online:// marker
            try {
                val existingSong = songDao.getSongById(videoId) ?: songDao.getSongByVideoId(videoId)
                if (existingSong != null) {
                    songDao.updateSongVideoId(existingSong.id, videoId)
                    songDao.updateSongArtwork(existingSong.id, localThumbnailUri)
                } else {
                    songDao.insertSong(
                        com.myplayer.app.data.local.entity.SongEntity(
                            id = videoId,
                            title = title,
                            artist = artist,
                            album = "Downloads",
                            duration = durationMs,
                            path = "online://$videoId",
                            albumArt = localThumbnailUri,
                            dateAdded = System.currentTimeMillis(),
                            playCount = 0,
                            videoId = videoId
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("DownloadWorker", "Failed to synchronize song metadata in SongDao", e)
            }

            // Automatically download & cache lyrics in Room + filesDir/lyrics/<videoId>.lrc + companion .lrc
            val effectiveDurationMs = if (durationMs > 0L) durationMs else extractDurationMs(savedPath)
            cacheOfflineLyrics(videoId, title, artist, effectiveDurationMs, savedPath)

            setProgress(workDataOf(KEY_PROGRESS to 100))
            isSuccess = true
            Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Download failed for $videoId", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        } finally {
            if (!isSuccess) {
                try {
                    tempFileToClean?.let { if (it.exists()) it.delete() }
                    tmpDocToClean?.let { if (it.exists()) it.delete() }
                } catch (_: Exception) {}
            }
        }
    }
}
