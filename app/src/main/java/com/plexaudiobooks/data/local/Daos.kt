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
