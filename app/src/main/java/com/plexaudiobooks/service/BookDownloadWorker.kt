package com.plexaudiobooks.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.local.DownloadedBookEntity
import com.plexaudiobooks.util.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads audio files for an audiobook using OkHttp directly.
 *
 * URL format:  {serverUrl}{partKey}?download=1
 * Auth:        X-Plex-Token header  (NOT a query param — some Plex setups reject token in URL)
 *
 * We use OkHttp rather than Fetch2 because:
 * - Full control over the request/response lifecycle
 * - No persistent SQLite state that causes listener race conditions
 * - Simpler coroutine integration — just sequential IO
 *
 * Sliding cache window:
 * - On first play, downloads from byte 0 up to (currentPosition + downloadHours).
 * - On subsequent plays, triggerReadAheadIfNeeded() in AudiobookPlaybackService
 *   checks whether downloadedUpToMs >= currentPosition + downloadHours. If not,
 *   it enqueues a new request with targetCachedUpToMs set to the new target.
 * - For single-file books, extendExistingDownload() appends only the missing bytes
 *   using an HTTP Range request rather than re-downloading from byte 0.
 * - If the server does not return 206 Partial Content the extension is skipped
 *   safely — no corruption, existing cache is preserved.
 * - Multi-part books fall back to the original full-restart path for now.
 */
@HiltWorker
class BookDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: PlexRepository,
    private val sessionManager: SessionManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BookDownloadWorker"

        const val KEY_RATING_KEY             = "rating_key"
        const val KEY_TITLE                  = "title"
        const val KEY_AUTHOR                 = "author"
        const val KEY_THUMB_PATH             = "thumb_path"
        const val KEY_DURATION_MS            = "duration_ms"
        const val KEY_PART_KEYS              = "part_keys"  // comma-separated list
        const val TAG_DOWNLOAD               = "book_download"  // fixed tag on every download worker
        const val KEY_PROGRESS               = "progress"
        const val KEY_ERROR                  = "error"
        const val KEY_TARGET_CACHED_UP_TO_MS = "target_cached_up_to_ms"  // 0 = use downloadHours from 0

        fun buildRequest(
            ratingKey: String,
            title: String,
            author: String?,
            thumbPath: String?,
            partKeys: List<String>,
            durationMs: Long,
            targetCachedUpToMs: Long = 0L
        ): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<BookDownloadWorker>()
                .setInputData(workDataOf(
                    KEY_RATING_KEY             to ratingKey,
                    KEY_TITLE                  to title,
                    KEY_AUTHOR                 to author,
                    KEY_THUMB_PATH             to thumbPath,
                    KEY_PART_KEYS              to partKeys.joinToString(","),
                    KEY_DURATION_MS            to durationMs,
                    KEY_TARGET_CACHED_UP_TO_MS to targetCachedUpToMs
                ))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .addTag(ratingKey)      // per-book tag for individual cancellation
                .addTag(TAG_DOWNLOAD)   // fixed tag so we can query all book downloads
                .build()
        }
    }

    // Dedicated OkHttpClient for downloads — long timeouts for large files
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)   // large M4B files need time to download
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ratingKey          = inputData.getString(KEY_RATING_KEY)  ?: return@withContext fail("Missing ratingKey")
        val title              = inputData.getString(KEY_TITLE)        ?: return@withContext fail("Missing title")
        val author             = inputData.getString(KEY_AUTHOR)
        val thumbPath          = inputData.getString(KEY_THUMB_PATH)
        val durationMs         = inputData.getLong(KEY_DURATION_MS, 0)
        val partKeysStr        = inputData.getString(KEY_PART_KEYS)    ?: return@withContext fail("No part keys provided — book has no playable media")
        val partKeys           = partKeysStr.split(",").filter { it.isNotBlank() }
        val targetCachedUpToMs = inputData.getLong(KEY_TARGET_CACHED_UP_TO_MS, 0L)

        if (partKeys.isEmpty()) {
            return@withContext fail("No part keys — tap on the book to load its details first, then try downloading again")
        }

        // Resolve the best reachable server URL right now
        val serverUrl = repository.resolveAndRefreshServerUrl()
            ?: sessionManager.serverUrl
            ?: return@withContext fail("Cannot reach Plex server — are you connected to the internet?")

        val token = sessionManager.serverToken ?: sessionManager.authToken
        ?: return@withContext fail("Not authenticated — please sign in again")

        Log.i(TAG, "=== Download starting ===")
        Log.i(TAG, "Title:      $title")
        Log.i(TAG, "ServerUrl:  $serverUrl")
        Log.i(TAG, "Parts:      ${partKeys.size}")
        Log.i(TAG, "Target:     ${targetCachedUpToMs}ms")
        Log.i(TAG, "OutputDir:  ${sessionManager.getDownloadDir().absolutePath}")

        val outputDir = sessionManager.getDownloadDir()
        outputDir.mkdirs()

        // ── Extend path: single-file book with existing download ──────────────
        // Use Range request to append only missing bytes rather than restart.
        // Multi-part books fall through to the full-restart path below.
        val existingDownload = repository.getDownload(ratingKey)
        val isSingleFileExtendCandidate = partKeys.size == 1 &&
                existingDownload != null &&
                File(existingDownload.localFilePath).exists()

        if (isSingleFileExtendCandidate) {
            return@withContext extendExistingDownload(
                ratingKey     = ratingKey,
                title         = title,
                author        = author,
                thumbPath     = thumbPath,
                partKey       = partKeys.first(),
                durationMs    = durationMs,
                targetCachedUpToMs = targetCachedUpToMs,
                existing      = existingDownload!!,
                serverUrl     = serverUrl,
                token         = token,
                outputDir     = outputDir
            )
        }

        // ── Fresh download path ───────────────────────────────────────────────
        // Only delete the existing DB record if the output file doesn't already
        // exist (i.e. we're starting fresh, not replacing). This prevents wiping
        // a good download record before we know the new download will succeed.
        val extension0 = partKeys.first().substringAfterLast('.', "m4b")
        val firstOutputFile = File(outputDir, "${ratingKey}_part0.$extension0")
        if (!firstOutputFile.exists()) {
            repository.deleteDownload(ratingKey)
        }

        var totalBytesDownloaded = 0L
        var primaryFilePath: String? = null
        var totalContentLength = 0L

        for ((index, partKey) in partKeys.withIndex()) {
            if (isStopped) {
                Log.i(TAG, "Worker stopped — cancelling download")
                return@withContext Result.failure()
            }

            // Build the download URL exactly as Chronicle does:
            // {serverUrl}{partKey}?download=1
            // partKey looks like: /library/parts/12345/67890/filename.m4b
            val downloadUrl = "$serverUrl$partKey?download=1"
            val extension   = partKey.substringAfterLast('.', "m4b")
            val outputFile  = File(outputDir, "${ratingKey}_part$index.$extension")

            Log.i(TAG, "--- Part $index ---")
            Log.i(TAG, "URL:  $downloadUrl")
            Log.i(TAG, "File: ${outputFile.absolutePath}")

            setProgress(workDataOf(KEY_PROGRESS to 0))

            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("X-Plex-Token", token)
                .addHeader("X-Plex-Client-Identifier", sessionManager.clientId)
                .addHeader("X-Plex-Product", "PlexAudiobooks")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val code = response.code

                Log.i(TAG, "HTTP response: $code")

                if (!response.isSuccessful) {
                    response.close()
                    val msg = when (code) {
                        401  -> "Authentication failed (401) — please sign out and sign in again"
                        403  -> "Download not permitted (403) — in Plex Web, go to Settings > Users & Sharing and enable 'Allow Downloads' for your account"
                        404  -> "File not found on server (404) — the media file may have moved"
                        else -> "Server returned HTTP $code for the download request"
                    }
                    return@withContext fail(msg)
                }

                val body = response.body ?: return@withContext fail("Server sent an empty response body")

                val contentLength = body.contentLength()
                Log.i(TAG, "Content-Length: $contentLength bytes (${contentLength / 1024 / 1024}MB)")
                if (contentLength > 0) totalContentLength += contentLength

                // Calculate byte cap. If the caller passed an explicit targetCachedUpToMs
                // use that; otherwise fall back to downloadHours from position 0 (first play).
                val maxBytes: Long = if (contentLength > 0 && durationMs > 0) {
                    val bytesPerMs = contentLength.toDouble() / durationMs
                    val desiredMs  = if (targetCachedUpToMs > 0) targetCachedUpToMs
                    else sessionManager.downloadHours * 3600 * 1000L
                    val cap = (bytesPerMs * desiredMs).toLong()
                    if (cap >= contentLength) {
                        Log.i(TAG, "Download cap ($cap bytes) >= file size ($contentLength) — downloading full file")
                        contentLength
                    } else {
                        Log.i(TAG, "Capping download at ${desiredMs / 3_600_000.0}h = $cap bytes of $contentLength total")
                        cap
                    }
                } else {
                    Log.i(TAG, "Duration unknown — downloading full file")
                    Long.MAX_VALUE
                }

                // Stream to disk, stopping at maxBytes
                var bytesWritten = 0L
                FileOutputStream(outputFile).use { fos ->
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)  // 64 KB chunks
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            if (isStopped) {
                                fos.close()
                                outputFile.delete()
                                return@withContext Result.failure()
                            }
                            fos.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead

                            // Report progress against our cap (not total file)
                            if (maxBytes > 0 && maxBytes < Long.MAX_VALUE) {
                                val partPct    = (bytesWritten * 100 / maxBytes).toInt()
                                val overallPct = if (partKeys.size == 1) partPct
                                else (index * 100 + partPct) / partKeys.size
                                setProgress(workDataOf(KEY_PROGRESS to overallPct.coerceIn(0, 100)))
                            } else if (contentLength > 0) {
                                val partPct = (bytesWritten * 100 / contentLength).toInt()
                                setProgress(workDataOf(KEY_PROGRESS to partPct.coerceIn(0, 100)))
                            }

                            // Stop at cap
                            if (bytesWritten >= maxBytes) {
                                Log.i(TAG, "Reached download cap at ${bytesWritten / 1024 / 1024}MB")
                                break
                            }
                        }
                    }
                }

                if (bytesWritten == 0L) {
                    outputFile.delete()
                    return@withContext fail("Downloaded 0 bytes — the server may not allow downloads for this file")
                }

                Log.i(TAG, "Part $index complete: ${bytesWritten / 1024 / 1024}MB written")
                totalBytesDownloaded += bytesWritten
                if (primaryFilePath == null) primaryFilePath = outputFile.absolutePath

            } catch (e: Exception) {
                outputFile.delete()
                Log.e(TAG, "Exception during download of part $index: ${e.message}", e)
                return@withContext if (runAttemptCount < 2) Result.retry()
                else fail("Network error: ${e.message}")
            }
        }

        if (primaryFilePath == null || totalBytesDownloaded == 0L) {
            return@withContext fail("No files were downloaded successfully")
        }

        Log.i(TAG, "=== Download complete: ${totalBytesDownloaded / 1024 / 1024}MB total ===")

        repository.saveDownload(DownloadedBookEntity(
            ratingKey        = ratingKey,
            title            = title,
            author           = author,
            thumbPath        = thumbPath,
            mediaPartKey     = partKeys.first(),
            localFilePath    = primaryFilePath!!,
            durationMs       = durationMs,
            fileSizeBytes    = totalBytesDownloaded,
            downloadedUpToMs = if (totalContentLength > 0 && durationMs > 0)
                (totalBytesDownloaded.toDouble() / totalContentLength * durationMs)
                    .toLong().coerceAtMost(durationMs)
            else durationMs
        ))

        setProgress(workDataOf(KEY_PROGRESS to 100))
        Result.success()
    }

    /**
     * Extends an existing single-file download by requesting only the missing
     * byte range and appending it to the existing file on disk.
     *
     * Uses HTTP Range header: "bytes=N-" where N = current file size.
     * If the server returns 206 Partial Content we append the new bytes.
     * If the server returns 200 (doesn't support Range) we skip safely —
     * no corruption, existing cache is preserved intact.
     */
    private suspend fun extendExistingDownload(
        ratingKey: String,
        title: String,
        author: String?,
        thumbPath: String?,
        partKey: String,
        durationMs: Long,
        targetCachedUpToMs: Long,
        existing: DownloadedBookEntity,
        serverUrl: String,
        token: String,
        outputDir: File
    ): Result {
        val existingFile  = File(existing.localFilePath)
        val currentBytes  = existingFile.length()

        // Nothing to do if we already cover the target
        if (targetCachedUpToMs > 0 && existing.downloadedUpToMs >= targetCachedUpToMs) {
            Log.i(TAG, "Cache already covers target " +
                    "(${existing.downloadedUpToMs}ms >= ${targetCachedUpToMs}ms) — skipping extend")
            setProgress(workDataOf(KEY_PROGRESS to 100))
            return Result.success()
        }

        Log.i(TAG, "=== Extending download ===")
        Log.i(TAG, "Existing file: $currentBytes bytes, downloadedUpToMs: ${existing.downloadedUpToMs}ms")
        Log.i(TAG, "Target: ${targetCachedUpToMs}ms")

        val downloadUrl = "$serverUrl$partKey?download=1"
        val request = Request.Builder()
            .url(downloadUrl)
            .addHeader("X-Plex-Token", token)
            .addHeader("X-Plex-Client-Identifier", sessionManager.clientId)
            .addHeader("X-Plex-Product", "PlexAudiobooks")
            .addHeader("Range", "bytes=$currentBytes-")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code     = response.code
            Log.i(TAG, "Extend HTTP response: $code")

            if (code != 206) {
                // Server doesn't support Range — keep existing cache as-is.
                // This is not an error; playback continues from local file up to
                // downloadedUpToMs just as before.
                response.close()
                Log.w(TAG, "Server returned $code instead of 206 — Range not supported. Keeping existing cache.")
                setProgress(workDataOf(KEY_PROGRESS to 100))
                return Result.success()
            }

            val body = response.body
            if (body == null) {
                Log.w(TAG, "Empty body on 206 response — keeping existing cache")
                setProgress(workDataOf(KEY_PROGRESS to 100))
                return Result.success()
            }

            // Total file length = bytes already on disk + bytes remaining in response
            val remainingLength = body.contentLength()
            val totalFileLength = if (remainingLength > 0) currentBytes + remainingLength else 0L

            var totalBytesOnDisk = currentBytes
            FileOutputStream(existingFile, /* append = */ true).use { fos ->
                body.byteStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            // Worker cancelled — keep bytes written so far; the DB
                            // will be updated below to reflect the new file size.
                            Log.i(TAG, "Worker stopped during extend — saving partial progress")
                            break
                        }
                        fos.write(buffer, 0, bytesRead)
                        totalBytesOnDisk += bytesRead

                        if (totalFileLength > 0) {
                            val pct = (totalBytesOnDisk * 100 / totalFileLength).toInt()
                            setProgress(workDataOf(KEY_PROGRESS to pct.coerceIn(0, 100)))
                        }

                        // Stop once we've covered the target — no need to keep going
                        if (targetCachedUpToMs > 0 && totalFileLength > 0 && durationMs > 0) {
                            val estimatedMsCovered =
                                (totalBytesOnDisk.toDouble() / totalFileLength * durationMs).toLong()
                            if (estimatedMsCovered >= targetCachedUpToMs) {
                                Log.i(TAG, "Reached extend target at ~${estimatedMsCovered}ms")
                                break
                            }
                        }
                    }
                }
            }

            // Compute new downloadedUpToMs from total bytes now on disk
            val newDownloadedUpToMs = if (totalFileLength > 0 && durationMs > 0)
                (totalBytesOnDisk.toDouble() / totalFileLength * durationMs)
                    .toLong().coerceAtMost(durationMs)
            else
                existing.downloadedUpToMs

            repository.saveDownload(existing.copy(
                fileSizeBytes    = totalBytesOnDisk,
                downloadedUpToMs = newDownloadedUpToMs
            ))

            Log.i(TAG, "=== Extend complete: cached up to ${newDownloadedUpToMs}ms " +
                    "(${totalBytesOnDisk / 1024 / 1024}MB total on disk) ===")
            setProgress(workDataOf(KEY_PROGRESS to 100))
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Exception during extend: ${e.message}", e)
            // Non-fatal — existing cached portion is intact, playback can continue
            // from local file up to the previous downloadedUpToMs boundary.
            setProgress(workDataOf(KEY_PROGRESS to 100))
            Result.success()
        }
    }

    private fun fail(reason: String): Result {
        Log.e(TAG, "Download FAILED: $reason")
        return Result.failure(workDataOf(KEY_ERROR to reason))
    }
}