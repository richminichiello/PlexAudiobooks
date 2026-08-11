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
        CachedLibraryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AudiobookDatabase : RoomDatabase() {
    abstract fun progressDao(): PlaybackProgressDao
    abstract fun downloadDao(): DownloadedBookDao
    abstract fun libraryDao(): CachedLibraryDao
    abstract fun libraryPagingDao(): CachedLibraryPagingDao

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
    }
}
