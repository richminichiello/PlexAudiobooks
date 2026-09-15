package com.plexaudiobooks.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaybackProgressEntity::class,
        DownloadedBookEntity::class,
        CachedLibraryEntity::class,
        CachedChapterEntity::class,
        BookDetailCacheEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AudiobookDatabase : RoomDatabase() {
    abstract fun progressDao(): PlaybackProgressDao
    abstract fun downloadDao(): DownloadedBookDao
    abstract fun libraryDao(): CachedLibraryDao
    abstract fun libraryPagingDao(): CachedLibraryPagingDao
    abstract fun chapterDao(): CachedChapterDao
    abstract fun bookDetailCacheDao(): BookDetailCacheDao

    companion object {
        // Migration 1→2: add 'completed' column to cached_library
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cached_library ADD COLUMN completed INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration 2→3: add 'shelved' column to cached_library.
        // A shelved book is removed from Continue Listening without being marked completed;
        // resume position is preserved and the book stays visible in the main grid.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cached_library ADD COLUMN shelved INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration 3→4: add 'durable' column to downloaded_books.
        // Distinguishes an explicit full-book download (durable=1, set by the Detail
        // "Download" button) from a read-ahead cache file (durable=0, set on play). Only
        // non-durable cache files are auto-deleted on book completion; durable downloads
        // persist until the user explicitly removes them. DEFAULT 0 so every existing
        // read-ahead row on disk stays cache after migration (none were durable before).
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloaded_books ADD COLUMN durable INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration 4→5: add the cached_chapters table.
        //
        // Chapters are immutable per book and were previously re-fetched from the Plex
        // server on EVERY play() via a 3-call chain (album → children → per-track
        // chapters includeChapters=1). That made continue-a-book slow (the network fetch
        // gated setMediaItems) and made the chapter list appear/disappear depending on
        // network health. This table is the write-once-read-forever local copy:
        // populated by the first successful fetchBookDetail for a ratingKey, read from
        // Room on every subsequent play. No columns to alter on existing tables — this
        // is a pure CREATE TABLE, so no data is touched.
        //
        // Composite primary key (ratingKey, chapterIndex) matches CachedChapterEntity.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_chapters` (
                        `ratingKey` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        PRIMARY KEY(`ratingKey`, `chapterIndex`)
                    )
                    """.trimIndent()
                )
                // book_detail_cache lives in the same migration: it caches the per-book
                // stream part key / part-key list / track ratingKey / true duration that
                // are only known after fetchBookDetail(). It is deliberately a SEPARATE
                // table from cached_library because refreshCachedLibrary() wipes and
                // rebuilds cached_library on every library refresh — any detail data
                // stored there would be silently destroyed. This table is keyed only by
                // ratingKey and is never bulk-cleared.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_detail_cache` (
                        `ratingKey` TEXT NOT NULL PRIMARY KEY,
                        `mediaPartKey` TEXT,
                        `allPartKeysCsv` TEXT NOT NULL,
                        `trackRatingKey` TEXT,
                        `trackDurationMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `thumbPath` TEXT,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
