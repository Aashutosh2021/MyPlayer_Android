package com.myplayer.app.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.myplayer.app.data.local.dao.FolderDao
import com.myplayer.app.data.local.dao.SongDao
import com.myplayer.app.data.local.entity.FolderEntity
import com.myplayer.app.data.local.entity.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans local device audio files from:
 * 1. User-selected folders (Storage Access Framework `content://` Tree URIs or file paths like `/storage/emulated/0/Music`)
 * 2. Android `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`
 */
@Singleton
class MediaScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val folderDao: FolderDao
) {
    private val supportedExtensions = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma")

    suspend fun scanAllFolders() = withContext(Dispatchers.IO) {
        try {
            songDao.clearAllLocalSongs()
            val folders = folderDao.getFoldersSync()
            if (folders.isEmpty()) {
                val defaultDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "myplayer")
                if (defaultDir.exists() && defaultDir.isDirectory) {
                    val discovered = mutableListOf<SongEntity>()
                    scanFileDirectoryDirect(defaultDir, discovered)
                    scanMediaStoreAudio(pathFilter = defaultDir.absolutePath, outList = discovered)
                    if (discovered.isNotEmpty()) {
                        songDao.insertSongs(discovered.distinctBy { it.id })
                        Log.i("MediaScanner", "Scanned ${discovered.size} local songs from default directory ($defaultDir)")
                    }
                }
            } else {
                for (folder in folders) {
                    scanFolder(folder)
                }
            }
        } catch (e: Exception) {
            Log.e("MediaScanner", "Error scanning local folders", e)
        }
    }

    suspend fun scanFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        val uriStr = folder.uri.trim()
        if (uriStr.isBlank()) return@withContext

        val discovered = mutableListOf<SongEntity>()
        try {
            if (uriStr.startsWith("content://")) {
                val treeUri = Uri.parse(uriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null && rootDoc.exists()) {
                    scanDocumentFolderDirect(rootDoc, folder.name, discovered)
                }
                val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                val resolvedPath = if (treeDocId != null && treeDocId.startsWith("primary:", ignoreCase = true)) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/${treeDocId.removePrefix("primary:").removePrefix("PRIMARY:").trimStart('/')}"
                } else null
                if (!resolvedPath.isNullOrBlank()) {
                    scanMediaStoreAudio(pathFilter = resolvedPath, outList = discovered)
                }
            } else {
                val cleanPath = if (uriStr.startsWith("file://")) {
                    Uri.parse(uriStr).path ?: uriStr.removePrefix("file://")
                } else {
                    uriStr
                }
                val dir = File(cleanPath)
                if (dir.exists() && dir.isDirectory) {
                    scanFileDirectoryDirect(dir, discovered)
                }
                // Also query MediaStore filtered to direct files inside this folder path (no subfolders)
                scanMediaStoreAudio(pathFilter = cleanPath, outList = discovered)
            }

            if (discovered.isNotEmpty()) {
                songDao.insertSongs(discovered.distinctBy { it.id })
                Log.i("MediaScanner", "Scanned ${discovered.size} local songs from folder '${folder.name}' ($uriStr)")
            }
        } catch (e: Exception) {
            Log.e("MediaScanner", "Failed to scan folder $uriStr", e)
        }
    }

    private fun scanDocumentFolderDirect(
        dir: DocumentFile,
        albumFallback: String,
        out: MutableList<SongEntity>
    ) {
        val expectedTreeId = runCatching { DocumentsContract.getTreeDocumentId(dir.uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(dir.uri) }.getOrNull()

        for (doc in dir.listFiles()) {
            if (!doc.isFile || doc.isDirectory) continue
            if (DocumentsContract.isDocumentUri(context, doc.uri)) {
                val docId = DocumentsContract.getDocumentId(doc.uri)
                if (!expectedTreeId.isNullOrBlank() && docId.contains('/')) {
                    if (docId.substringBeforeLast('/') != expectedTreeId.trimEnd('/')) continue
                }
            }
            val name = doc.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = doc.type ?: ""
            if (ext in supportedExtensions || mime.startsWith("audio/")) {
                parseDocumentAudioFile(doc, albumFallback)?.let { out.add(it) }
            }
        }
    }

    private fun parseDocumentAudioFile(doc: DocumentFile, albumFallback: String): SongEntity? {
        val fileUri = doc.uri
        val rawName = doc.name?.substringBeforeLast('.') ?: "Local Track"
        var title = rawName
        var artist = "Local Artist"
        var album = albumFallback.ifBlank { "Local Music" }
        var durationMs = 0L

        val parts = rawName.split(" - ", limit = 2)
        if (parts.size == 2) {
            artist = parts[0].trim().ifBlank { "Local Artist" }
            title = parts[1].trim().ifBlank { rawName }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, fileUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
                title = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let {
                artist = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let {
                album = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                durationMs = it
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val pathStr = fileUri.toString()
        val id = "local_${md5Hex(pathStr).take(16)}"
        return SongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = durationMs.coerceAtLeast(0L),
            path = pathStr,
            albumArt = null,
            dateAdded = doc.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            playCount = 0,
            videoId = null
        )
    }

    private fun scanFileDirectoryDirect(dir: File, out: MutableList<SongEntity>) {
        val files = dir.listFiles() ?: return
        val targetDir = dir.absolutePath.trimEnd('/', '\\')
        for (f in files) {
            if (f.isFile && !f.isDirectory &&
                f.parentFile?.absolutePath?.trimEnd('/', '\\').equals(targetDir, ignoreCase = true) &&
                f.extension.lowercase() in supportedExtensions
            ) {
                parseFilesystemAudioFile(f)?.let { out.add(it) }
            }
        }
    }

    private fun parseFilesystemAudioFile(file: File): SongEntity? {
        if (!file.exists() || file.length() <= 0L) return null
        val rawName = file.nameWithoutExtension
        var title = rawName
        var artist = file.parentFile?.name ?: "Local Artist"
        var album = file.parentFile?.name ?: "Local Music"
        var durationMs = ((file.length() / (128 * 1024 / 8)) * 1000L).coerceAtLeast(0L)

        val parts = rawName.split(" - ", limit = 2)
        if (parts.size == 2) {
            artist = parts[0].trim().ifBlank { artist }
            title = parts[1].trim().ifBlank { rawName }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
                title = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let {
                artist = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let {
                album = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                durationMs = it
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val keyForHash = file.absolutePath.replace('\\', '/').lowercase()
        val id = "local_${md5Hex(keyForHash).take(16)}"
        val companionArt = File(file.parentFile, "${file.nameWithoutExtension}.jpg")
            .takeIf { it.exists() }?.absolutePath

        return SongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = durationMs,
            path = file.absolutePath,
            albumArt = companionArt,
            dateAdded = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            playCount = 0,
            videoId = null
        )
    }

    private suspend fun scanMediaStoreAudio(
        pathFilter: String? = null,
        outList: MutableList<SongEntity>? = null
    ) = withContext(Dispatchers.IO) {
        val normalizedFilter = pathFilter?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() } ?: return@withContext
        val collected = outList ?: mutableListOf()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?"
            val selectionArgs = arrayOf("$normalizedFilter/%", "$normalizedFilter/%/%")

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(dataCol)?.trim() ?: ""
                    if (filePath.isBlank()) continue
                    val fileObj = File(filePath)
                    val parentPath = fileObj.parentFile?.absolutePath?.trimEnd('/', '\\') ?: continue
                    if (!parentPath.equals(normalizedFilter, ignoreCase = true)) continue
                    if (fileObj.extension.lowercase() !in supportedExtensions) continue

                    val mediaId = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol)?.trim() ?: continue
                    if (rawTitle.isBlank()) continue
                    val rawArtist = cursor.getString(artistCol)?.trim()
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Local Artist"
                    val rawAlbum = cursor.getString(albumCol)?.trim()
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Local Music"
                    val durationMs = cursor.getLong(durCol).coerceAtLeast(0L)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaId
                    ).toString()

                    val effectivePath = if (fileObj.canRead()) {
                        filePath
                    } else {
                        contentUri
                    }

                    val albumId = cursor.getLong(albumIdCol)
                    val albumArtUri = if (albumId > 0) {
                        ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                    } else null

                    val dateAddedSec = cursor.getLong(dateCol)
                    val keyForHash = filePath.replace('\\', '/').lowercase()
                    val songId = "local_${md5Hex(keyForHash).take(16)}"

                    collected.add(
                        SongEntity(
                            id = songId,
                            title = rawTitle,
                            artist = rawArtist,
                            album = rawAlbum,
                            duration = durationMs,
                            path = effectivePath,
                            albumArt = albumArtUri,
                            dateAdded = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis(),
                            playCount = 0,
                            videoId = null
                        )
                    )
                }
            }

            if (outList == null && collected.isNotEmpty()) {
                songDao.insertSongs(collected.distinctBy { it.id })
                Log.i("MediaScanner", "Indexed ${collected.size} local audio tracks from MediaStore")
            }
        } catch (e: SecurityException) {
            Log.w("MediaScanner", "MediaStore audio permission not granted yet: ${e.message}")
        } catch (e: Exception) {
            Log.e("MediaScanner", "MediaStore scan error", e)
        }
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
