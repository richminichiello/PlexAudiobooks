package com.plexaudiobooks.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE ratingKey = :ratingKey")
    suspend fun getProgress(ratingKey: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress ORDER BY lastUpdated DESC LIMIT 1")
    fun getLastPlayed(): Flow<PlaybackProgressEntity?>

    @Query("DELETE FROM playback_progress WHERE ratingKey = :ratingKey")
    suspend fun deleteProgress(ratingKey: String)
}

@Dao
interface DownloadedBookDao {
    @Query("SELECT * FROM downloaded_books")
    fun getAllDownloads(): Flow<List<DownloadedBookEntity>>

    @Query("SELECT * FROM downloaded_books WHERE ratingKey = :ratingKey")
    suspend fun getDownload(ratingKey: String): DownloadedBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(book: DownloadedBookEntity)

    @Delete
    suspend fun deleteDownload(book: DownloadedBookEntity)

    @Query("DELETE FROM downloaded_books WHERE ratingKey = :ratingKey")
    suspend fun deleteDownloadByKey(ratingKey: String)

    @Query("SELECT COUNT(*) FROM downloaded_books")
    suspend fun getDownloadCount(): Int
}

@Dao
interface CachedLibraryDao {
    @Query("SELECT * FROM cached_library ORDER BY title ASC")
    fun getAllCached(): Flow<List<CachedLibraryEntity>>

    @Query("SELECT * FROM cached_library WHERE ratingKey = :ratingKey")
    suspend fun getBook(ratingKey: String): CachedLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<CachedLibraryEntity>)

    @Query("DELETE FROM cached_library")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM cached_library")
    suspend fun getCount(): Int

    @Query("SELECT * FROM cached_library WHERE title LIKE :query OR author LIKE :query ORDER BY title ASC")
    fun searchLibrary(query: String): Flow<List<CachedLibraryEntity>>

    /** All cached books as a suspend one-shot read — used by the Auto browse tree.
     *  Not paging-safe for huge libraries, but that's acceptable for the Auto use case
     *  which caps at the 20 most recent titles anyway. */
    @Query("SELECT * FROM cached_library ORDER BY title ASC")
    suspend fun getAllBooks(): List<CachedLibraryEntity>

    /** Recently added books — newest first, capped at 20 for the Auto browse tree. */
    @Query("SELECT * FROM cached_library ORDER BY addedAt DESC, title ASC LIMIT 20")
    suspend fun getRecentlyAddedRecent(): List<CachedLibraryEntity>

    @Query("UPDATE cached_library SET completed = :completed WHERE ratingKey = :ratingKey")
    suspend fun setCompleted(ratingKey: String, completed: Boolean)

    @Query("SELECT ratingKey FROM cached_library WHERE completed = 1")
    suspend fun getCompletedKeys(): List<String>

    @Query("UPDATE cached_library SET shelved = :shelved WHERE ratingKey = :ratingKey")
    suspend fun setShelved(ratingKey: String, shelved: Boolean)

    @Query("SELECT ratingKey FROM cached_library WHERE shelved = 1")
    suspend fun getShelvedKeys(): List<String>

    /**
     * Atomically refresh the cached library so reactive Flows (notably
     * getContinueListening, which JOINs this table) emit exactly once instead of
     * once-per-intermediate-write. Doing clearAll/insertAll/setCompleted/setShelved
     * as separate statements caused a transient empty-list emission that made the
     * Continue Listening section flicker and disappear on swipe-refresh.
     */
    @Transaction
    suspend fun refreshCachedLibrary(
        fresh: List<CachedLibraryEntity>,
        completedKeys: List<String>,
        shelvedKeys: List<String>
    ) {
        clearAll()
        insertAll(fresh)
        completedKeys.forEach { setCompleted(it, true) }
        shelvedKeys.forEach { setShelved(it, true) }
    }
}

// Paging 3 support — returns a PagingSource so the library grid loads
// items in chunks as the user scrolls, keeping memory flat for large libraries.
@Dao
interface CachedLibraryPagingDao {

    @Query("SELECT * FROM cached_library WHERE completed = 0 OR completed IS NULL ORDER BY title ASC")
    fun getPagedLibrary(): androidx.paging.PagingSource<Int, CachedLibraryEntity>

    @Query("SELECT * FROM cached_library WHERE completed = 0 OR completed IS NULL ORDER BY title ASC")
    fun getPagedByTitle(): androidx.paging.PagingSource<Int, CachedLibraryEntity>

    @Query("SELECT * FROM cached_library WHERE completed = 0 OR completed IS NULL ORDER BY LOWER(author) ASC, title ASC")
    fun getPagedByAuthor(): androidx.paging.PagingSource<Int, CachedLibraryEntity>

    @Query("SELECT * FROM cached_library WHERE completed = 0 OR completed IS NULL ORDER BY durationMs ASC")
    fun getPagedByDuration(): androidx.paging.PagingSource<Int, CachedLibraryEntity>

    @Query("SELECT * FROM cached_library WHERE completed = 0 OR completed IS NULL ORDER BY addedAt DESC")
    fun getPagedByDateAdded(): androidx.paging.PagingSource<Int, CachedLibraryEntity>

    @Query("SELECT * FROM cached_library WHERE completed = 0 OR completed IS NULL ORDER BY title ASC")
    fun getPagedExcludeCompleted(): androidx.paging.PagingSource<Int, CachedLibraryEntity>

    // Returns all ratingKeys that are downloaded — used to set offline badge on cards
    @Query("SELECT ratingKey FROM downloaded_books")
    fun getDownloadedKeys(): Flow<List<String>>

    /** Non-reactive one-shot Continue Listening list — used by the Auto browse tree
     *  (the Flow variant would require a collect; this returns the data immediately). */
    @Query("""
    SELECT cl.ratingKey, cl.title, cl.author, cl.thumbPath,
           cl.durationMs, cl.viewOffset, cl.addedAt,
           COALESCE(cl.completed, 0) AS completed,
           COALESCE(cl.shelved, 0) AS shelved,
           pp.positionMs, pp.lastUpdated
    FROM cached_library cl
    INNER JOIN playback_progress pp ON CAST(cl.ratingKey AS TEXT) = CAST(pp.ratingKey AS TEXT)
    WHERE pp.positionMs > 0
      AND (cl.completed = 0 OR cl.completed IS NULL)
      AND (cl.shelved = 0 OR cl.shelved IS NULL)
    ORDER BY pp.lastUpdated DESC
    LIMIT 20
""")
    suspend fun getContinueListeningSync(): List<ContinueListeningItem>

    // Books with progress but not completed and not shelved (Continue Listening)
    @Query("""
    SELECT cl.ratingKey, cl.title, cl.author, cl.thumbPath,
           cl.durationMs, cl.viewOffset, cl.addedAt,
           COALESCE(cl.completed, 0) AS completed,
           COALESCE(cl.shelved, 0) AS shelved,
           pp.positionMs, pp.lastUpdated
    FROM cached_library cl
    INNER JOIN playback_progress pp ON CAST(cl.ratingKey AS TEXT) = CAST(pp.ratingKey AS TEXT)
    WHERE pp.positionMs > 0
      AND (cl.completed = 0 OR cl.completed IS NULL)
      AND (cl.shelved = 0 OR cl.shelved IS NULL)
    ORDER BY pp.lastUpdated DESC
    LIMIT 20
""")
    fun getContinueListening(): Flow<List<ContinueListeningItem>>

    // Completed books
    @Query("SELECT * FROM cached_library WHERE completed = 1 ORDER BY title ASC")
    fun getCompleted(): Flow<List<CachedLibraryEntity>>

    // Recently added section: newest books first, capped at 20 to keep the home screen
    // snappy on large libraries. Includes completed books too — a just-added book should
    // surface here regardless of its read state (it ages out naturally).
    @Query("SELECT * FROM cached_library ORDER BY addedAt DESC, title ASC LIMIT 20")
    fun getRecentlyAdded(): Flow<List<CachedLibraryEntity>>
}

// Projection for Continue Listening — library item plus progress fields
data class ContinueListeningItem(
    val ratingKey: String,
    val title: String,
    val author: String?,
    val thumbPath: String?,
    val durationMs: Long,
    val viewOffset: Long,
    val addedAt: Long,
    val completed: Boolean = false,
    val shelved: Boolean = false,
    // from playback_progress JOIN
    val positionMs: Long,
    val lastUpdated: Long
)

/**
 * DAO for the cached chapter table (DB v5).
 *
 * Chapters are write-once-per-book: they're populated by the first successful
 * fetchBookDetail() for a given ratingKey and read on every subsequent play() from Room.
 * Because chapters are immutable for a given book, REPLACE-on-conflict idempotent inserts
 * are sufficient — a re-fetch that produces the same data overwrites cleanly, and a
 * re-fetch that produces nothing leaves the good cached copy intact.
 */
@Dao
interface CachedChapterDao {

    /** All chapters for one book, ordered. Empty list = never cached (or book genuinely
     *  has no chapters, in which case the synthetic single-chapter fallback is used). */
    @Query("SELECT * FROM cached_chapters WHERE ratingKey = :ratingKey ORDER BY chapterIndex ASC")
    suspend fun getChapters(ratingKey: String): List<CachedChapterEntity>

    /** Persist a book's chapters. REPLACE so a later re-fetch (e.g. after a library
     *  metadata update) silently overwrites; the normal path never re-fetches. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<CachedChapterEntity>)

    /** Whether we already have chapters cached for this book — let callers skip the
     *  network fetch entirely when the local copy exists. */
    @Query("SELECT COUNT(*) FROM cached_chapters WHERE ratingKey = :ratingKey")
    suspend fun getChapterCount(ratingKey: String): Int

    /** Remove cached chapters for a book (e.g. when the user removes the book). The
     *  auto-eviction on completion deliberately does NOT delete chapters — they're small
     *  and a replayed/re-downloaded book should keep its chapter map. */
    @Query("DELETE FROM cached_chapters WHERE ratingKey = :ratingKey")
    suspend fun deleteChapters(ratingKey: String)
}

/**
 * DAO for the per-book playback-detail cache (DB v5).
 *
 * Same write-once-read-forever model as the chapter cache: fetchBookDetail() upserts,
 * play() reads. Never bulk-cleared — this data survives library refreshes on purpose.
 */
@Dao
interface BookDetailCacheDao {

    @Query("SELECT * FROM book_detail_cache WHERE ratingKey = :ratingKey")
    suspend fun get(ratingKey: String): BookDetailCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookDetailCacheEntity)

    @Query("DELETE FROM book_detail_cache WHERE ratingKey = :ratingKey")
    suspend fun delete(ratingKey: String)
}
