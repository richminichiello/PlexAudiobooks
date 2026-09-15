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
    val downloadedUpToMs: Long,   // how many ms of audio are cached
    // v1.6.2: durable flag — distinguishes an explicit full-book download (true, set by
    // the Detail "Download" button) from an invisible read-ahead cache file (false, set by
    // triggerReadAheadIfNeeded). Only non-durable cache files are auto-deleted on book
    // completion; durable downloads persist until the user explicitly removes them.
    // DEFAULT 0 (false) so every existing read-ahead row on disk stays cache after migration.
    val durable: Boolean = false
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

/**
 * Persisted chapter table (DB v5).
 *
 * WHY THIS TABLE EXISTS: chapters used to be fetched fresh from the Plex server on EVERY
 * play() via a 3-round-trip call chain (album → children → per-track chapters). That made
 * "continue a book" slow and made the chapter list appear/disappear depending on network
 * health. Chapters are immutable for a given book — they never change once the file is on
 * the server — so we cache them permanently, keyed by the book's ratingKey. First successful
 * fetch populates this table; every later play reads it from Room instantly (offline-safe).
 */
@Entity(tableName = "cached_chapters", primaryKeys = ["ratingKey", "chapterIndex"])
data class CachedChapterEntity(
    val ratingKey: String,
    val chapterIndex: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Persisted per-book playback-detail cache (DB v5).
 *
 * WHY THIS TABLE EXISTS SEPARATELY FROM cached_library: the library /all endpoint returns
 * ALBUM-level rows with NO part keys and the album.duration is often wrong. The correct
 * streaming part key (mediaKey), the full part-key list (for downloads/read-ahead), the
 * TRACK ratingKey (for /:/timeline progress sync), and the true summed-track duration are
 * only known after fetchBookDetail(). We cache them here the first time a book is played
 * so every later play() can build its stream URL and timeline key from Room with no
 * network round-trip.
 *
 * This CANNOT live in cached_library: refreshCachedLibrary() wipes and rebuilds that table
 * on every library refresh, so detail fields stored there would be silently destroyed.
 * This table keys only by ratingKey and is never bulk-cleared.
 */
@Entity(tableName = "book_detail_cache")
data class BookDetailCacheEntity(
    @PrimaryKey val ratingKey: String,
    val mediaPartKey: String?,        // first track's /library/parts/... key — the stream URL source
    val allPartKeysCsv: String,       // comma-joined part keys for every track file
    val trackRatingKey: String?,      // first track's ratingKey — /:/timeline sync target
    val trackDurationMs: Long,        // first track duration (timeline duration arg)
    val durationMs: Long,             // true book duration (sum of track durations)
    val thumbPath: String?,           // resolved at detail time; kept so resumed plays never lose art
    val updatedAt: Long = System.currentTimeMillis()
)
