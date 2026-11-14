package com.myplayer.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.myplayer.app.data.backup.RedownloadQueueWorker
import com.myplayer.app.data.download.DownloadWorker
import com.myplayer.app.data.local.dao.CachedLyricsDao
import com.myplayer.app.data.local.dao.DownloadedSongDao
import com.myplayer.app.data.local.dao.FolderDao
import com.myplayer.app.data.local.dao.SongDao
import com.myplayer.app.data.local.entity.DownloadedSongEntity
import com.myplayer.app.data.local.entity.SongEntity
import com.myplayer.app.data.local.prefs.PreferencesManager
import com.myplayer.app.data.online.model.OnlineSong
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadedSongDao: DownloadedSongDao,
    private val songDao: SongDao,
    private val folderDao: FolderDao,
    private val cachedLyricsDao: CachedLyricsDao,
    private val okHttpClient: OkHttpClient,
    private val preferencesManager: PreferencesManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _manualProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val batchJobs = ConcurrentHashMap<Long, Job>()
    private val _playlistBatchStatus = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val playlistBatchStatus: StateFlow<Map<Long, Pair<Int, Int>>> = _playlistBatchStatus.asStateFlow()

    private val workManagerFlow: Flow<List<WorkInfo>> = flow {
        try {
            emitAll(WorkManager.getInstance(context).getWorkInfosByTagFlow("download"))
        } catch (e: Exception) {
            Log.w("DownloadRepository", "Could not observe WorkManager download tag flow", e)
            emit(emptyList())
        }
    }

    val downloadProgress: StateFlow<Map<String, Int>> = combine(
        workManagerFlow,
        _manualProgress
    ) { workInfos, manual ->
        val terminalIds = workInfos
            .filter { it.state.isFinished }
            .mapNotNull { info ->
                info.tags.firstOrNull { it.startsWith("videoId_") }?.removePrefix("videoId_")
            }
            .toSet()

        if (terminalIds.isNotEmpty()) {
            _manualProgress.update { current -> current - terminalIds }
        }

        val activeMap = workInfos
            .filter { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
            }
            .mapNotNull { info ->
                val videoId = info.tags.firstOrNull { it.startsWith("videoId_") }
                    ?.removePrefix("videoId_")
                    ?: return@mapNotNull null
                val prog = info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                videoId to prog
            }
            .toMap()
        (manual - terminalIds) + activeMap
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun setDownloadProgress(videoId: String, progress: Int) {
        _manualProgress.update { it + (videoId to progress) }
    }

    fun removeDownload(videoId: String) {
        _manualProgress.update { it - videoId }
    }

    fun isDownloading(videoId: String): Boolean {
        return downloadProgress.value.containsKey(videoId)
    }

    fun cancelPlaylistBatchDownload(playlistId: Long) {
        batchJobs.remove(playlistId)?.cancel()
        _playlistBatchStatus.update { it - playlistId }
    }

    fun startSequentialDownloads(
        playlistId: Long,
        songs: List<PlayableSong.Online>,
        resolveSong: suspend (PlayableSong.Online) -> OnlineSong?
    ) {
        if (songs.isEmpty()) return
        if (batchJobs[playlistId]?.isActive == true) return
        val job = scope.launch {
            try {
                songs.forEachIndexed { index, song ->
                    _playlistBatchStatus.update { it + (playlistId to (index + 1 to songs.size)) }
                    val resolved = runCatching { resolveSong(song) }.getOrNull() ?: return@forEachIndexed
                    if (isDownloaded(resolved.videoId)) return@forEachIndexed
                    if (!startDownload(resolved)) return@forEachIndexed
                    withTimeoutOrNull(1500L) {
                        downloadProgress.first { it.containsKey(resolved.videoId) }
                    }
                    downloadProgress.first { !it.containsKey(resolved.videoId) }
                }
            } finally {
                batchJobs.remove(playlistId)
                _playlistBatchStatus.update { it - playlistId }
            }
        }
        batchJobs[playlistId] = job
    }

    fun getAllDownloads(): Flow<List<DownloadedSongEntity>> = downloadedSongDao.getAllDownloads()

    suspend fun isDownloaded(videoId: String): Boolean = withContext(Dispatchers.IO) {
        downloadedSongDao.existsById(videoId)
    }

    fun getDownloadedById(id: String): Flow<List<DownloadedSongEntity>> =
        downloadedSongDao.searchDownloads(id)

    /**
     * Schedules an expedited background download for the given [song] (Offline in-app library).
     */
    fun startDownload(song: OnlineSong): Boolean {
        val streamUrl = song.streamUrl
        if (streamUrl.isNullOrBlank()) {
            Log.e("DownloadRepository", "[DOWNLOAD] No stream URL for ${song.videoId}")
            return false
        }

        Log.i("DownloadRepository", "[DOWNLOAD] Scheduling expedited download for: ${song.title} (${song.videoId})")
        setDownloadProgress(song.videoId, 0)

        val data = workDataOf(
            DownloadWorker.KEY_VIDEO_ID to song.videoId,
            DownloadWorker.KEY_TITLE to song.title,
            DownloadWorker.KEY_ARTIST to song.artist,
            DownloadWorker.KEY_THUMBNAIL_URL to song.thumbnailUrl,
            DownloadWorker.KEY_DURATION_MS to song.durationMs,
            DownloadWorker.KEY_STREAM_URL to streamUrl
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .addTag("download")
            .addTag("videoId_${song.videoId}")
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        return try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${song.videoId}",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i("DownloadRepository", "[DOWNLOAD] Successfully enqueued WorkManager job for: ${song.videoId}")
            true
        } catch (e: Exception) {
            Log.e("DownloadRepository", "[DOWNLOAD] Failed to enqueue WorkManager job for ${song.videoId}", e)
            removeDownload(song.videoId)
            false
        }
    }

    /**
     * Downloads and saves the song as a user-accessible `.mp3` or `.m4a` file into the configured
     * download/local folder path, or defaults to public `Music/myplayer`, and indexes it in `SongDao`.
     */
    suspend fun exportSongToDeviceStorage(
        song: OnlineSong,
        formatExt: String = "mp3"
    ): Result<String> = withContext(Dispatchers.IO) {
        val ext = if (formatExt.lowercase().trim() == "m4a") "m4a" else "mp3"
        val mimeType = if (ext == "mp3") "audio/mpeg" else "audio/mp4"

        val safeTitle = song.title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(55)
            .ifBlank { "Track_${song.videoId}" }
        val safeArtist = song.artist
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(40)
            .ifBlank { "MyPlayer" }

        val displayFileName = "$safeTitle - $safeArtist.$ext"
        var tempSourceFile: File? = null

        runCatching {
            setDownloadProgress(song.videoId, 1)

            // 1. Check if already downloaded locally; if so, reuse local audio bytes without re-downloading!
            val existingDownload = downloadedSongDao.getById(song.videoId)
            val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val stagingFile = File(cacheDir, "export_${song.videoId}_${System.currentTimeMillis()}.$ext")
            tempSourceFile = stagingFile

            var copiedFromLocal = false
            if (existingDownload != null) {
                copiedFromLocal = copyExistingPathToFile(existingDownload.localPath, stagingFile)
            }
            val rawStreamUrl = song.streamUrl
            if (!copiedFromLocal && !rawStreamUrl.isNullOrBlank() &&
                (rawStreamUrl.startsWith("/") || rawStreamUrl.startsWith("content://") || rawStreamUrl.startsWith("file://"))
            ) {
                copiedFromLocal = copyExistingPathToFile(rawStreamUrl, stagingFile)
            }

            if (!copiedFromLocal) {
                val streamUrl = song.streamUrl?.takeIf { it.startsWith("http") }
                    ?: throw IllegalStateException("No valid audio stream URL to download")
                downloadHttpStreamToFile(song.videoId, streamUrl, stagingFile)
            }

            if (!stagingFile.exists() || stagingFile.length() <= 0L) {
                throw IOException("Downloaded audio file is empty")
            }

            var savedDisplayLocation = "${Environment.DIRECTORY_MUSIC}/myplayer/$displayFileName"
            var localPlayablePath: String? = null

            // 2. Save ONLY to the configured download/local folder path when set
            val targetFolderUriStr = preferencesManager.downloadFolderUri.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: folderDao.getFoldersSync().lastOrNull()?.uri?.takeIf { it.isNotBlank() }

            if (!targetFolderUriStr.isNullOrBlank()) {
                try {
                    if (targetFolderUriStr.startsWith("content://")) {
                        val treeUri = Uri.parse(targetFolderUriStr)
                        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                        if (rootDoc != null && rootDoc.exists()) {
                            rootDoc.findFile(displayFileName)?.delete()
                            val destDoc = rootDoc.createFile(mimeType, displayFileName)
                            if (destDoc != null) {
                                context.contentResolver.openOutputStream(destDoc.uri)?.use { out ->
                                    FileInputStream(stagingFile).use { input -> input.copyTo(out) }
                                }
                                localPlayablePath = destDoc.uri.toString()
                                savedDisplayLocation = "${rootDoc.name ?: "Custom Folder"}/$displayFileName"
                            }
                        }
                    } else {
                        val dir = File(targetFolderUriStr)
                        if (dir.exists() || dir.mkdirs()) {
                            val destFile = File(dir, displayFileName)
                            stagingFile.copyTo(destFile, overwrite = true)
                            localPlayablePath = destFile.absolutePath
                            savedDisplayLocation = destFile.absolutePath
                            MediaScannerConnection.scanFile(
                                context.applicationContext,
                                arrayOf(destFile.absolutePath),
                                arrayOf(mimeType),
                                null
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DownloadRepository", "Configured folder write failed: ${e.message}", e)
                }

                // Scoped-storage fallback when a public filesystem path isn't directly writable via File API
                if (localPlayablePath == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !targetFolderUriStr.startsWith("content://")) {
                    val extRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                    val normalizedTarget = targetFolderUriStr.trimEnd('/', '\\')
                    if (normalizedTarget.startsWith("$extRoot/", ignoreCase = true)) {
                        val relPath = normalizedTarget.substring(extRoot.length + 1)
                        val isDownloadsTree = relPath.substringBefore('/').lowercase() in setOf("download", "downloads", "documents")
                        val uri = writeToMediaStore(
                            sourceFile = stagingFile,
                            title = song.title,
                            artist = song.artist,
                            displayFileName = displayFileName,
                            mimeType = mimeType,
                            relativePath = relPath,
                            useDownloadsCollection = isDownloadsTree
                        )
                        if (uri != null) {
                            localPlayablePath = uri.toString()
                            savedDisplayLocation = "$relPath/$displayFileName"
                        }
                    }
                }
            }

            // 3. Otherwise save ONE copy to default public Music/myplayer
            if (localPlayablePath == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val defaultRelPath = "${Environment.DIRECTORY_MUSIC}/myplayer"
                    val audioUri = writeToMediaStore(
                        sourceFile = stagingFile,
                        title = song.title,
                        artist = song.artist,
                        displayFileName = displayFileName,
                        mimeType = mimeType,
                        relativePath = defaultRelPath,
                        useDownloadsCollection = false
                    )
                    if (audioUri != null) {
                        localPlayablePath = audioUri.toString()
                        savedDisplayLocation = "$defaultRelPath/$displayFileName"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val publicDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "myplayer"
                    ).apply { mkdirs() }
                    val destFile = File(publicDir, displayFileName)
                    stagingFile.copyTo(destFile, overwrite = true)
                    localPlayablePath = destFile.absolutePath
                    savedDisplayLocation = destFile.absolutePath
                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                }
            }

            // 4. Fallback persistent copy in app's external Music folder if localPlayablePath is still null
            if (localPlayablePath == null) {
                val appMusicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                appMusicDir.mkdirs()
                val fallbackFile = File(appMusicDir, displayFileName)
                stagingFile.copyTo(fallbackFile, overwrite = true)
                localPlayablePath = fallbackFile.absolutePath
            }

            // 5. Register in SongDao so it immediately shows up under Library -> Local Files!
            val localId = "local_${md5Hex(localPlayablePath!!).take(16)}"
            songDao.insertSong(
                SongEntity(
                    id = localId,
                    title = song.title,
                    artist = song.artist,
                    album = "MyPlayer ($ext.uppercase())",
                    duration = song.durationMs,
                    path = localPlayablePath!!,
                    albumArt = song.thumbnailUrl.ifBlank { null },
                    dateAdded = System.currentTimeMillis(),
                    playCount = 0,
                    videoId = song.videoId.takeIf { it.length == 11 }
                )
            )

            setDownloadProgress(song.videoId, 100)
            savedDisplayLocation
        }.also {
            removeDownload(song.videoId)
            runCatching { tempSourceFile?.delete() }
        }
    }

    private fun writeToMediaStore(
        sourceFile: File,
        title: String,
        artist: String,
        displayFileName: String,
        mimeType: String,
        relativePath: String,
        useDownloadsCollection: Boolean
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val collectionUri = if (useDownloadsCollection) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (!useDownloadsCollection) {
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.ALBUM, "MyPlayer Downloads")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
            }
            val uri = context.contentResolver.insert(collectionUri, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.w("DownloadRepository", "MediaStore insert failed: ${e.message}")
            null
        }
    }

    private fun copyExistingPathToFile(sourcePath: String, targetFile: File): Boolean {
        return try {
            when {
                sourcePath.startsWith("content://") || sourcePath.startsWith("file://") -> {
                    val uri = Uri.parse(sourcePath)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { out -> input.copyTo(out) }
                    } ?: return false
                    targetFile.exists() && targetFile.length() > 0L
                }
                else -> {
                    val src = File(sourcePath)
                    if (src.exists() && src.length() > 0L) {
                        src.copyTo(targetFile, overwrite = true)
                        true
                    } else false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun downloadHttpStreamToFile(videoId: String, streamUrl: String, targetFile: File) {
        val userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
        val chunkSize = 512L * 1024L

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
        } catch (_: Exception) {}

        FileOutputStream(targetFile).use { out ->
            var downloaded = 0L
            var serverIgnoredRange = false
            while (true) {
                val endOffset = if (totalSize > 0) minOf(downloaded + chunkSize - 1, totalSize - 1) else downloaded + chunkSize - 1
                var bytesReadThisChunk = -1L
                var lastErr: Exception? = null

                for (attempt in 1..4) {
                    try {
                        val req = Request.Builder()
                            .url(streamUrl)
                            .addHeader("User-Agent", userAgent)
                            .addHeader("Range", "bytes=$downloaded-$endOffset")
                            .build()

                        bytesReadThisChunk = okHttpClient.newCall(req).execute().use { response ->
                            if (response.code == 416) return@use -1L
                            if (!response.isSuccessful && response.code != 206) throw IOException("HTTP ${response.code}")
                            if (response.code == 200) serverIgnoredRange = true
                            val body = response.body ?: throw IOException("Empty body")
                            val stream = body.byteStream()
                            val buf = ByteArray(8192)
                            var readChunk = 0L
                            while (true) {
                                val r = stream.read(buf)
                                if (r == -1) break
                                out.write(buf, 0, r)
                                readChunk += r
                            }
                            readChunk
                        }
                        lastErr = null
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        if (attempt < 4) delay(400L * attempt)
                    }
                }
                if (lastErr != null) throw lastErr
                if (bytesReadThisChunk <= 0L) break

                downloaded += bytesReadThisChunk
                if (totalSize > 0) {
                    val prog = ((downloaded.toFloat() / totalSize) * 100).toInt().coerceIn(1, 99)
                    setDownloadProgress(videoId, prog)
                } else {
                    setDownloadProgress(videoId, 50)
                }

                if (serverIgnoredRange) break
                if (totalSize > 0 && downloaded >= totalSize) break
                if (bytesReadThisChunk < chunkSize && totalSize == -1L) break
            }
            out.flush()
        }
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun deleteDownload(entity: DownloadedSongEntity) {
        try {
            if (entity.localPath.startsWith("content://")) {
                val uri = android.net.Uri.parse(entity.localPath)
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                doc?.delete()
            } else {
                val file = File(entity.localPath)
                if (file.exists()) {
                    file.delete()
                }
                file.parentFile?.let { parent ->
                    File(parent, "${file.nameWithoutExtension}.lrc").takeIf { it.exists() }?.delete()
                    File(parent, "${file.nameWithoutExtension}.jpg").takeIf { it.exists() }?.delete()
                }
            }
            val localArt = File(File(context.filesDir, "artwork"), "${entity.id}.jpg")
            if (localArt.exists()) {
                localArt.delete()
            }
        } catch (e: Exception) {
            Log.e("DownloadRepository", "Could not delete file", e)
        }

        try {
            cachedLyricsDao.deleteLyricsForSong(entity.id)
        } catch (e: Exception) {
            Log.e("DownloadRepository", "Could not delete cached lyrics for ${entity.id}", e)
        }

        downloadedSongDao.deleteById(entity.id)
        removeDownload(entity.id)
        Log.i("DownloadRepository", "Deleted download record for ${entity.id}. PlaybackSourceResolver will route online on next play.")
    }

    suspend fun deleteDownloadById(videoId: String) = withContext(Dispatchers.IO) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork("download_$videoId")
        } catch (e: Exception) {
            Log.e("DownloadRepository", "Could not cancel WorkManager download task for $videoId", e)
        }

        removeDownload(videoId)

        val entity = downloadedSongDao.getById(videoId)
        if (entity != null) {
            deleteDownload(entity)
        } else {
            downloadedSongDao.deleteById(videoId)
            try {
                cachedLyricsDao.deleteLyricsForSong(videoId)
            } catch (e: Exception) {
                Log.e("DownloadRepository", "Could not delete cached lyrics for $videoId", e)
            }
        }
    }

    suspend fun insertDownload(song: DownloadedSongEntity) {
        downloadedSongDao.insert(song)
    }

    suspend fun performIntegrityCheck() {
        if (RedownloadQueueWorker.isQueuePending(context)) {
            Log.i("DownloadRepository", "Integrity check skipped — redownload queue is active.")
            return
        }
        val downloads = withContext(Dispatchers.IO) {
            downloadedSongDao.getAllDownloadsSync()
        }

        withContext(Dispatchers.IO) {
            val toDelete = mutableListOf<String>()
            val seenFilePaths = mutableSetOf<String>()

            for (download in downloads) {
                val path = download.localPath
                if (path in seenFilePaths) {
                    toDelete.add(download.id)
                    continue
                }
                seenFilePaths.add(path)

                val workInfos: List<WorkInfo> = try {
                    WorkManager.getInstance(context).getWorkInfosForUniqueWork("download_${download.id}").get()
                } catch (e: Exception) {
                    emptyList()
                }
                
                var isDownloading = false
                for (info in workInfos) {
                    if (info.state == WorkInfo.State.ENQUEUED || 
                        info.state == WorkInfo.State.RUNNING || 
                        info.state == WorkInfo.State.BLOCKED) {
                        isDownloading = true
                        break
                    }
                }

                if (isDownloading) continue

                val fileExists = try {
                    if (path.startsWith("content://") || path.startsWith("file://")) {
                        val uri = android.net.Uri.parse(path)
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                            it.length > 0
                        } ?: false
                    } else {
                        val file = File(path)
                        file.exists() && file.length() > 0
                    }
                } catch (e: Exception) {
                    false
                }

                if (!fileExists) {
                    toDelete.add(download.id)
                }
            }

            if (toDelete.isNotEmpty()) {
                for (id in toDelete) {
                    downloadedSongDao.deleteById(id)
                }
            }
        }
    }

    fun searchDownloads(query: String): Flow<List<DownloadedSongEntity>> =
        downloadedSongDao.searchDownloads(query)
}
