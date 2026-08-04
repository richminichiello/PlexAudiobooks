package com.plexaudiobooks.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val ratingKey: String,
    val title: String,
    val author: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloaded_books")
data class DownloadedBookEntity(
    @PrimaryKey val ratingKey: String,
    val title: String,
    val author: String?,
    val thumbPath: String?,
    val mediaPartKey: String,
    val localFilePath: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis(),
    val downloadedUpToMs: Long   // how many ms of audio are cached
)

@Entity(tableName = "cached_library")
data class CachedLibraryEntity(
    @PrimaryKey val ratingKey: String,
    val title: String,
    val author: String?,
    val summary: String?,
    val thumbPath: String?,
    val durationMs: Long,
    val viewOffset: Long,
    val addedAt: Long,
    val mediaPartKey: String?,
    val cachedAt: Long = System.currentTimeMillis(),
    // v1.2.0: completed flag — user can mark a book as read
    // DEFAULT 0 so existing rows are treated as not completed after migration
    val completed: Boolean = false,
    // v1.6.0: shelved flag — user can "put a book back on the shelf": removed from
    // Continue Listening without marking it completed. Resume position is preserved;
    // the book stays visible in the main browse grid and returns to Continue Listening
    // when played again. DEFAULT 0 so existing rows are treated as not shelved after migration.
    val shelved: Boolean = false
)
