package com.myplayer.app.data.model

import com.myplayer.app.data.local.entity.SongEntity
import com.myplayer.app.data.repository.PlayableSong

// ── PlayableSong → SongEntity (Stubs for DB cross-referencing) ───────────────

fun PlayableSong.Online.toSongEntity(): SongEntity = SongEntity(
    id = id,
    title = title,
    artist = artist,
    album = "YouTube Music",
    duration = durationMs,
    path = "online://$id",
    albumArt = thumbnailUrl,
    dateAdded = System.currentTimeMillis(),
    videoId = id
)

fun PlayableSong.Downloaded.toSongEntity(): SongEntity = SongEntity(
    id = id,
    title = title,
    artist = artist,
    album = entity.album.ifBlank { "Downloads" },
    duration = durationMs,
    path = entity.localPath,
    albumArt = thumbnailUrl,
    dateAdded = System.currentTimeMillis(),
    videoId = id
)
