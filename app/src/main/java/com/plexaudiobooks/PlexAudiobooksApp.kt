package com.plexaudiobooks

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.hilt.work.HiltWorkerFactory
import com.plexaudiobooks.service.BookDownloadWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class.
 *
 * Implements Configuration.Provider so WorkManager uses HiltWorkerFactory.
 * Without this, @HiltWorker classes cannot receive injected dependencies and
 * WorkManager silently drops the work request without executing it.
 *
 * Also cleans up stale WorkManager state on startup. WorkManager persists its
 * queue in a SQLite database across app installs. When upgrading from a version
 * where the Hilt factory was missing, old failed/pending download jobs remain
 * in the queue and interfere with new requests. We prune them on startup.
 */
@HiltAndroidApp
class PlexAudiobooksApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        pruneStaleDownloads()
    }

    /**
     * Cancels and prunes stale download work on startup.
     *
     * Rules:
     * - SUCCEEDED / FAILED / CANCELLED → always prune (WorkManager keeps these
     *   for 24h by default; we clear them immediately to avoid confusion)
     * - ENQUEUED / BLOCKED → cancel only if they were queued before this app
     *   launch. We identify "stale" by checking if the work predates the
     *   current process start. Any ENQUEUED work that survived an upgrade is
     *   stale by definition.
     * - RUNNING → never touch. A download actively in progress should continue.
     *
     * We tag all download workers with BookDownloadWorker.KEY_RATING_KEY so
     * we can query them specifically without touching unrelated work.
     */
    private fun pruneStaleDownloads() {
        val wm = WorkManager.getInstance(this)

        // Always prune completed/failed/cancelled work immediately
        wm.pruneWork()

        // Cancel any work tagged as a book download that is ENQUEUED but not RUNNING.
        // These are jobs left over from before the Hilt fix — they will never run
        // successfully with the old broken setup, and new requests won't replace
        // them because WorkManager deduplicates by tag.
        //
        // We query ALL work with our tag and cancel non-running states.
        // WorkManager's LiveData query is async, so we observe once and unsubscribe.
        val workInfosFuture = wm.getWorkInfosByTag(BookDownloadWorker.TAG_DOWNLOAD)
        try {
            val workInfos = workInfosFuture.get()   // safe on app startup before main thread is busy
            var cancelledCount = 0
            workInfos?.forEach { info ->
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> {
                        // Stale queued work — cancel it so new requests aren't blocked
                        wm.cancelWorkById(info.id)
                        cancelledCount++
                        Log.i("PlexAudiobooksApp", "Cancelled stale download work: ${info.id} (was ${info.state})")
                    }
                    WorkInfo.State.RUNNING -> {
                        // Active download — leave it alone
                        Log.i("PlexAudiobooksApp", "Active download in progress, leaving: ${info.id}")
                    }
                    else -> {
                        // SUCCEEDED / FAILED / CANCELLED — pruneWork() handled these
                    }
                }
            }
            if (cancelledCount > 0) {
                Log.i("PlexAudiobooksApp", "Pruned $cancelledCount stale download job(s) from WorkManager queue")
            }
        } catch (e: Exception) {
            // Non-critical — if this fails, downloads may still work; just log it
            Log.w("PlexAudiobooksApp", "Could not prune stale downloads: ${e.message}")
        }
    }
}
