package com.myplayer.app.aria.providers

import com.myplayer.app.aria.model.*
import com.myplayer.app.aria.performance.AriaPerformanceMonitor
import com.myplayer.app.data.repository.MusicRepository
import com.myplayer.app.playback.MusicController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistProvider @Inject constructor(
    private val musicRepository: MusicRepository,
    private val musicController: MusicController,
    private val performanceMonitor: AriaPerformanceMonitor
) {
    suspend fun createPlaylist(name: String, commandOrdinal: Int, transactionId: String?): PlaylistResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (name.isBlank()) {
            return@withContext PlaylistResponse(AriaStatus.BAD_REQUEST, commandOrdinal, transactionId)
        }

        val all = musicRepository.getAllPlaylists().first()
        // Task 6: Request Validation (Playlist already exists)
        if (all.any { it.name.equals(name, ignoreCase = true) }) {
            return@withContext PlaylistResponse(AriaStatus.ALREADY_EXISTS, commandOrdinal, transactionId)
        }

        try {
            val playlistId = musicRepository.createPlaylist(name)
            performanceMonitor.recordLatency("playlist_ops", System.currentTimeMillis() - startTime)
            return@withContext PlaylistResponse(
                status = AriaStatus.SUCCESS,
                commandOrdinal = commandOrdinal,
                transactionId = transactionId,
                playlistId = playlistId,
                playlistName = name
            )
        } catch (e: Exception) {
            return@withContext PlaylistResponse(AriaStatus.FAILURE, commandOrdinal, transactionId)
        }
    }
}
