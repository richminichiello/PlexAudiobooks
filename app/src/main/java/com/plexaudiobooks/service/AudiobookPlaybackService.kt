package com.plexaudiobooks.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
//import android.net.ConnectivityManager
//import android.net.NetworkCapabilities
//import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.plexaudiobooks.R
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.ui.MainActivity
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Audiobook playback service using MediaBrowserServiceCompat + MediaSessionCompat.
 *
 * WHY NOT Media3 MediaSessionService:
 * Media3's MediaSessionService derives PlaybackState.position directly from ExoPlayer's
 * internal clock and does not allow overriding it. This makes chapter-relative progress
 * impossible in the notification and car display. Chronicle (the reference implementation)
 * uses MediaBrowserServiceCompat + PlaybackStateCompat.setState(state, chapterPosition, speed)
 * where chapterPosition = absolutePosition - chapter.startMs. This is the ONLY way to
 * show chapter-level progress bars in Android media notifications and car displays.
 */
@AndroidEntryPoint
@androidx.media3.common.util.UnstableApi
class AudiobookPlaybackService : MediaBrowserServiceCompat() {

    @Inject lateinit var repository: PlexRepository
    @Inject lateinit var session: SessionManager

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Uncaught exception in service coroutine: ${e.message}", e)
            })
    private var progressJob: Job? = null
    private var stateUpdateJob: Job? = null

    private var currentRatingKey: String? = null
    private var currentKey: String? = null
    private var currentTitle: String? = null
    private var currentAuthor: String? = null
    private var currentThumbUri: String? = null
    private var currentChapters: List<Chapter> = emptyList()
    private var currentChapterIndex: Int = 0
    private var pausedForFocusLoss = false
    // True while the current ExoPlayer media item is a local file:// URI. Used by the
    // STATE_ENDED handler to tell a genuine server-stream end (→ mark complete, evict
    // cache) from a truncated local-file end (→ switch to server stream and keep going).
    // Storing this explicitly (rather than reading exoPlayer.currentMediaItem) is robust:
    // currentMediaItem can be null/stale across the setMediaItem transition mid-callback.
    private var currentSourceIsLocal: Boolean = false

    // Notification / car-display cover art. The system MediaStyle notification + lock
    // screen + Android Auto + Bluetooth AVRCP render artwork ONLY from Bitmap metadata
    // (METADATA_KEY_ART / METADATA_KEY_ALBUM_ART); the URI variants we set below are
    // un-resolved hints the framework never fetches for MediaSessionCompat. So we load the
    // cover into a Bitmap here (via Glide, which already caches covers on disk+memory for
    // the library screen) and put the Bitmap into the metadata. Cached by thumbUrl so the
    // every-500ms chapter loop / seeks don't re-fetch. Cleared on book switch + onDestroy.
    private var cachedArtBitmap: Bitmap? = null
    private var cachedArtThumbUrl: String? = null
    private var artTarget: CustomTarget<Bitmap>? = null
    // The thumbUrl a Glide load is currently fetching, or null when idle. Distinct from
    // cachedArtThumbUrl (which only advances on success) so a chapter change mid-fetch
    // for the same URL doesn't fire a second duplicate load.
    private var inflightArtUrl: String? = null

    companion object {
        private const val TAG = "AudiobookService"
        private const val NOTIFICATION_CHANNEL_ID = "plex_audiobooks_playback"
        private const val NOTIFICATION_ID = 1001
        const val MEDIA_ROOT_ID = "plex_audiobooks_root"

        // Stored absolute position in PlaybackState extras (Chronicle pattern)
        const val EXTRA_ABSOLUTE_POSITION = "absolute_position_ms"

        @Volatile
        var pendingChapters: List<Chapter> = emptyList()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Build ExoPlayer
        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 10 * 60_000, 2_500, 5_000)
                .build())
            .build()
            .apply { addListener(exoPlayerListener) }

        // Build MediaSessionCompat — this is what notifications and car displays connect to
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(pendingIntent)
            setCallback(mediaSessionCallback)
            isActive = true
        }

        // This is required by MediaBrowserServiceCompat — tells clients the session token
        sessionToken = mediaSession.sessionToken

        // Post an initial stopped state so the session is immediately usable
        updatePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MainActivity.ACTION_PLAY) {
            val ratingKey  = intent.getStringExtra(MainActivity.EXTRA_RATING_KEY) ?: return START_NOT_STICKY
            val key        = intent.getStringExtra(MainActivity.EXTRA_KEY) ?: ""
            val streamUrl  = intent.getStringExtra(MainActivity.EXTRA_STREAM_URL) ?: return START_NOT_STICKY
            val title      = intent.getStringExtra(MainActivity.EXTRA_TITLE) ?: ""
            val author     = intent.getStringExtra(MainActivity.EXTRA_AUTHOR)
            val thumbUrl   = intent.getStringExtra(MainActivity.EXTRA_THUMB_URL)
            val startPos   = intent.getLongExtra(MainActivity.EXTRA_START_POSITION, 0L)
            val speed      = intent.getFloatExtra(MainActivity.EXTRA_SPEED, 1.0f)
            val partKeys   = intent.getStringExtra(MainActivity.EXTRA_PART_KEYS) ?: ""
            val durationMs = intent.getLongExtra(MainActivity.EXTRA_DURATION_MS, 0L)

            if (currentRatingKey != null && currentRatingKey != ratingKey) {
                Log.d(TAG, "Switching book: $currentRatingKey → $ratingKey")
                serviceScope.launch { saveProgress("stopped") }
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                currentChapters = emptyList()
                currentChapterIndex = 0
                currentSourceIsLocal = false  // reset; playBook() sets it for the new book
                clearArt()
                stateUpdateJob?.cancel()
                abandonAudioFocus()
            }

            currentThumbUri = thumbUrl
            // Accept any pre-loaded chapters
            if (pendingChapters.isNotEmpty()) {
                currentChapters = pendingChapters
                pendingChapters = emptyList()
                // If already playing this book, just update chapters without restarting
                if (currentRatingKey == ratingKey && exoPlayer.playbackState != Player.STATE_IDLE) {
                    val pos = exoPlayer.currentPosition
                    currentChapterIndex = currentChapters.indexOfLast { pos >= it.startMs }.coerceAtLeast(0)
                    startStateUpdating()
                    updateSessionMetadata()
                }
            }

            val alreadyPlayingThisBook = currentRatingKey == ratingKey &&
                (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY ||
                 exoPlayer.playbackState == Player.STATE_BUFFERING)

            if (alreadyPlayingThisBook) {
                // Same book already loaded — just ensure it's playing, don't restart
                Log.d(TAG, "Same book already loaded — resuming without restart")
                if (!exoPlayer.isPlaying && requestAudioFocus()) {
                    exoPlayer.play()
                }
            } else if (requestAudioFocus()) {
                playBook(ratingKey, key, streamUrl, title, author, startPos, speed)
            }

            val keys = partKeys.split(",").filter { it.isNotBlank() }
            if (keys.isNotEmpty() && durationMs > 0) {
                triggerReadAheadIfNeeded(ratingKey, title, author, thumbUrl, keys, durationMs)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch { saveProgress("stopped") }
        stateUpdateJob?.cancel()
        progressJob?.cancel()
        abandonAudioFocus()
        clearArt()
        mediaSession.isActive = false
        mediaSession.release()
        exoPlayer.removeListener(exoPlayerListener)
        exoPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        serviceScope.launch { saveProgress("stopped") }
    }

    // ── MediaBrowserServiceCompat (required overrides) ────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(MEDIA_ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    // ── MediaSessionCompat Callback ───────────────────────────────────────────

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (requestAudioFocus()) {
                exoPlayer.play()
                safeStartForeground()
            }
        }

        override fun onPause() {
            exoPlayer.pause()
            serviceScope.launch { saveProgress("paused") }
            stopForeground(STOP_FOREGROUND_DETACH)
        }

        override fun onStop() {
            exoPlayer.stop()
            serviceScope.launch { saveProgress("stopped") }
            stopPlaybackAndService()
        }

        override fun onSeekTo(pos: Long) {
            // pos is CHAPTER-RELATIVE (0..chapterDuration).
            // Sources:
            //   1. Notification bar drag → always chapter-relative
            //   2. sendAbsoluteSeek() in PlayerFragment → subtracts chapter.startMs first
            // Convert to absolute by adding current chapter's start offset.
            val chapter = currentChapters.getOrNull(currentChapterIndex)
            val absolutePos = if (chapter != null) pos + chapter.startMs else pos
            exoPlayer.seekTo(absolutePos)
            // Re-derive chapter index from absolute position (handles edge cases)
            if (currentChapters.isNotEmpty()) {
                currentChapterIndex = currentChapters.indexOfLast { absolutePos >= it.startMs }
                    .coerceAtLeast(0)
            }
            updatePlaybackState()
            updateSessionMetadata()
        }

        override fun onSkipToQueueItem(id: Long) {
            // id is the chapter index — jump directly without any position conversion
            val idx = id.toInt().coerceIn(0, currentChapters.size - 1)
            val chapter = currentChapters.getOrNull(idx) ?: return
            exoPlayer.seekTo(chapter.startMs)
            currentChapterIndex = idx
            updatePlaybackState()
            updateSessionMetadata()
        }

        override fun onSkipToNext() {
            if (currentChapters.isEmpty()) return
            val nextIdx = (currentChapterIndex + 1).coerceAtMost(currentChapters.size - 1)
            val nextChapter = currentChapters[nextIdx]
            exoPlayer.seekTo(nextChapter.startMs)
            currentChapterIndex = nextIdx
            updatePlaybackState()
            updateSessionMetadata()
        }

        override fun onSkipToPrevious() {
            if (currentChapters.isEmpty()) return
            val pos = exoPlayer.currentPosition
            val chapter = currentChapters.getOrNull(currentChapterIndex)
            if (chapter != null && pos - chapter.startMs > 3000) {
                exoPlayer.seekTo(chapter.startMs)
            } else {
                val prevIdx = (currentChapterIndex - 1).coerceAtLeast(0)
                exoPlayer.seekTo(currentChapters[prevIdx].startMs)
                currentChapterIndex = prevIdx
            }
            updatePlaybackState()
            updateSessionMetadata()
        }

        override fun onSetPlaybackSpeed(speed: Float) {
            exoPlayer.playbackParameters = PlaybackParameters(speed)
            session.playbackSpeed = speed
            updatePlaybackState()
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun playBook(
        ratingKey: String, key: String, streamUrl: String,
        title: String, author: String?,
        startPositionMs: Long, playbackSpeed: Float
    ) {
        currentRatingKey = ratingKey
        currentKey = key
        currentTitle = title
        currentAuthor = author
        pausedForFocusLoss = false
        // A file:// streamUrl means we're playing the cached local file; anything else
        // (http/https) is a direct server stream. Recorded so STATE_ENDED can tell a
        // genuine stream end from a truncated local-file end.
        currentSourceIsLocal = streamUrl.startsWith("file://")

        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare()
        exoPlayer.seekTo(startPositionMs)
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
        exoPlayer.play()

        // Update currentChapterIndex based on saved position
        if (currentChapters.isNotEmpty() && startPositionMs > 0) {
            currentChapterIndex = currentChapters.indexOfLast { startPositionMs >= it.startMs }
                .coerceAtLeast(0)
        }

        updateSessionMetadata()
        updatePlaybackState()
        safeStartForeground()
        startProgressReporting()
        startStateUpdating()
    }

    // ── Chapter-relative PlaybackState (Chronicle pattern) ────────────────────

    /**
     * Builds and sets PlaybackStateCompat with chapter-relative position.
     * This is what makes the notification and car display show chapter progress.
     *
     * Chronicle reference: buildPlaybackState() in MediaPlayerService.kt lines 538-600
     * position = max(0, trackPosition - chapter.startTimeOffset)
     */
    private fun updatePlaybackState() {
        val trackPos = exoPlayer.currentPosition
        val playbackSpeed = exoPlayer.playbackParameters.speed

        val state = when {
            exoPlayer.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_PAUSED
        }

        // Chapter-relative position for notification/car progress bar
        val chapter = currentChapters.getOrNull(currentChapterIndex)
        val chapterRelativePos = if (chapter != null) {
            (trackPos - chapter.startMs).coerceAtLeast(0L)
        } else {
            trackPos
        }

        val extras = Bundle().apply {
            putLong(EXTRA_ABSOLUTE_POSITION, trackPos)
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            )
            .setState(state, chapterRelativePos, playbackSpeed)
            .setExtras(extras)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    /**
     * Sets MediaMetadataCompat with chapter title and chapter duration.
     * Chronicle reference: lines 651-680 in MediaPlayerService.kt
     * METADATA_KEY_DURATION = chapter.endTimeOffset - chapter.startTimeOffset
     */
    private fun updateSessionMetadata() {
        val chapter = currentChapters.getOrNull(currentChapterIndex)
        // When chapters are available: show chapter title and chapter duration.
        // When no chapters yet: show book title and book duration so notification
        // still has a valid progress bar instead of showing nothing.
        val displayTitle = when {
            chapter != null -> chapter.title
            else -> currentTitle ?: ""
        }
        val displaySubtitle = when {
            chapter != null -> currentTitle ?: ""
            else -> currentAuthor ?: ""
        }
        val duration = when {
            chapter != null -> chapter.endMs - chapter.startMs
            exoPlayer.duration > 0 -> exoPlayer.duration
            else -> -1L
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentRatingKey ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentTitle ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentAuthor ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .apply {
                currentThumbUri?.let {
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it)
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it)
                }
                // The system MediaStyle notification / lock screen / car / Bluetooth render
                // cover art ONLY from these Bitmap keys (not the URI variants above). Glide
                // already disk-caches covers for the library, so this usually hits cache.
                cachedArtBitmap?.let { bmp ->
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                }
            }
            .build()

        mediaSession.setMetadata(metadata)

        // If the cover URL changed (new book, or first time we have a thumb), fetch it
        // async. The fetch re-sets metadata once the bitmap is ready — the system observes
        // the session and re-renders the MediaStyle notification, so no explicit notification
        // refresh is needed here. Chapter changes call this method but hit the cache (same
        // thumbUrl) and skip the fetch.
        loadArtIfNeeded()
    }

    /**
     * Fetches the book cover into [cachedArtBitmap] via Glide when the thumb URL changed.
     * Cached by [cachedArtThumbUrl]; chapter changes / seeks that re-call
     * [updateSessionMetadata] hit the cache and skip. In-flight fetches are cancelled when
     * a new URL arrives so a slow old load can't overwrite a fresh one. Runs on the main
     * thread (Glide handles the IO + decode off-thread); on completion it stores the
     * bitmap and re-applies metadata so the notification picks up the art.
     */
    private fun loadArtIfNeeded() {
        val url = currentThumbUri
        // Already have it cached, or already fetching this exact URL → nothing to do.
        if (url == null) return
        if (url == cachedArtThumbUrl && cachedArtBitmap != null) return
        if (url == inflightArtUrl) return
        // Cancel any in-flight Glide load for a different URL.
        artTarget?.let { Glide.with(applicationContext).clear(it) }
        cachedArtBitmap = null
        inflightArtUrl = url
        val target = object : CustomTarget<Bitmap>(512, 512) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                cachedArtBitmap = resource
                cachedArtThumbUrl = url
                inflightArtUrl = null
                // Re-apply metadata so the bitmap lands in the session — the system
                // re-renders the MediaStyle notification from the new metadata.
                updateSessionMetadata()
                // Refresh the foreground notification so its large icon updates too.
                if (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY ||
                    exoPlayer.playbackState == Player.STATE_BUFFERING) {
                    safeStartForeground()
                }
            }
            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                inflightArtUrl = null
            }
            override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                inflightArtUrl = null
                // Non-fatal: notification shows with no art (text-only), as before.
                Log.w(TAG, "Notification art load failed for $url")
            }
        }
        artTarget = target
        // applicationContext so the load Survives service teardown and rides Glide's
        // disk/memory cache (covers are already cached from the library screen).
        Glide.with(applicationContext).asBitmap().load(url).into(target)
    }

    /** Drops the cached cover bitmap + cancels any in-flight Glide load. Called on book
     *  switch and in onDestroy so a stale cover can't bleed into the next book. */
    private fun clearArt() {
        artTarget?.let { Glide.with(applicationContext).clear(it) }
        artTarget = null
        inflightArtUrl = null
        cachedArtBitmap = null
        cachedArtThumbUrl = null
    }

    // ── State update loop ─────────────────────────────────────────────────────

    /**
     * Continuously updates PlaybackState and chapter index.
     * Runs every 500ms so notification/car display stays in sync.
     */
    private fun startStateUpdating() {
        stateUpdateJob?.cancel()
        stateUpdateJob = serviceScope.launch {
            var lastChapterIdx = currentChapterIndex
            while (isActive) {
                // Pick up chapters delivered via companion object at any time
                // (e.g. when fragment loads chapters after service already started)
                if (pendingChapters.isNotEmpty()) {
                    currentChapters = pendingChapters
                    pendingChapters = emptyList()
                    val pos = exoPlayer.currentPosition
                    currentChapterIndex = currentChapters.indexOfLast { pos >= it.startMs }
                        .coerceAtLeast(0)
                    lastChapterIdx = currentChapterIndex
                    Log.d(TAG, "Chapters updated: ${currentChapters.size} chapters, idx=$currentChapterIndex")
                    updateSessionMetadata()
                }

                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration

                if (dur > 0 && currentChapters.isNotEmpty()) {
                    val idx = currentChapters.indexOfLast { pos >= it.startMs }.coerceAtLeast(0)
                    if (idx != lastChapterIdx) {
                        lastChapterIdx = idx
                        currentChapterIndex = idx
                        Log.d(TAG, "Chapter → $idx: '${currentChapters[idx].title}'")
                        updateSessionMetadata()
                    }
                }
                updatePlaybackState()
                delay(500)
            }
        }
    }

    // ── ExoPlayer listener ────────────────────────────────────────────────────

    private val exoPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
            if (isPlaying) {
                safeStartForeground()
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                if (!pausedForFocusLoss) {
                    serviceScope.launch { saveProgress("paused") }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlaybackState()
            if (playbackState == Player.STATE_ENDED) {
                serviceScope.launch {
                    val ratingKey = currentRatingKey ?: return@launch
                    val pos = exoPlayer.currentPosition
                    val exoDuration = exoPlayer.duration
                    val download = repository.getDownload(ratingKey)

                    Log.w(TAG, "STATE_ENDED fired: pos=${pos}ms, exoDuration=${exoDuration}ms, " +
                            "downloadedUpToMs=${download?.downloadedUpToMs}ms, " +
                            "bookDurationMs=${download?.durationMs}ms, " +
                            "streamUrl=${session.serverUrl != null}")

                    // If we WERE playing a local file and that file is a partial cache
                    // (downloadedUpToMs < durationMs) AND the position hasn't actually
                    // reached the full book duration, ExoPlayer hit the end of the truncated
                    // file — not the real end of the book. Switch to streaming from the
                    // server to continue playback.
                    //
                    // Gated on currentSourceIsLocal: a streamed (http) book that ends here
                    // is the genuine end of the server stream, even if a partial read-ahead
                    // cache row exists (downloadedUpToMs < durationMs) — switching to the
                    // same server stream would just loop forever. So server-stream ends fall
                    // straight through to the real-end branch below.
                    if (currentSourceIsLocal && download != null &&
                        download.downloadedUpToMs < download.durationMs &&
                        pos < download.durationMs - 10_000L) {

                        val partKey = download.mediaPartKey
                        val streamUrl = session.buildStreamUrl(partKey)
                        if (streamUrl != null) {
                            Log.i(TAG, "Local file ended before book end " +
                                    "(pos=${pos}ms, cachedTo=${download.downloadedUpToMs}ms, " +
                                    "dur=${download.durationMs}ms) — switching to stream")
                            exoPlayer.setMediaItem(
                                androidx.media3.common.MediaItem.fromUri(streamUrl)
                            )
                            exoPlayer.prepare()
                            exoPlayer.seekTo(pos)
                            exoPlayer.play()
                            // We just switched to a server stream — the next STATE_ENDED
                            // (from this new stream) is the real book end, not another
                            // truncated-file end. Flip the flag so it routes to real-end.
                            currentSourceIsLocal = false
                            return@launch  // not a real end — don't mark complete or stop
                        } else {
                            Log.w(TAG, "STATE_ENDED: could not build stream URL — " +
                                    "serverUrl is null. Cannot switch to streaming.")
                        }
                    }

                    // Real end of book — save, mark complete, reset position, stop service.
                    // Reached for: a server stream that ended, OR a fully-cached local file
                    // that played to the end (downloadedUpToMs >= durationMs or pos near it).
                    Log.i(TAG, "STATE_ENDED: treating as real book end")
                    val endDur = download?.durationMs ?: exoDuration
                    saveProgress("stopped")
                    repository.setCompleted(ratingKey, true)
                    // Free a read-ahead cache when the book is genuinely done — but NEVER
                    // a durable download: those are explicit user downloads meant to persist
                    // (a book downloaded for a flight shouldn't vanish because you finished
                    // it mid-flight). `download?.durable != true` covers download == null
                    // (streamed book with no cache row — no-op) AND non-durable rows, while
                    // sparing durable ones. deleteDownloadAndFile is a no-op when no row exists.
                    if (download?.durable != true) {
                        repository.deleteDownloadAndFile(ratingKey)
                    }
                    // Reset saved position to 0 so replaying starts from the beginning
                    repository.saveProgress(ratingKey, currentTitle ?: "", currentAuthor, 0L, endDur)
                    // Hand teardown to the same path the MediaSession onStop() callback uses,
                    // so the player is stopped and the service stopSelf()s exactly once. The
                    // old code did this teardown INLINE inside the ExoPlayer listener (no
                    // exoPlayer.stop(), no stopSelf()) — racing the MediaSession while a
                    // suspend DB delete (deleteDownloadAndFile) interleaved, and leaving the
                    // service with no foreground notification. On Android 12+ the system then
                    // killed the service → the app just disappeared at end of book. Routing
                    // through stopPlaybackAndService() stops ExoPlayer on the main thread and
                    // actually stops the service, so neither the race nor the no-notification
                    // kill happens.
                    stopPlaybackAndService()
                }
            }
        }
    }

    // ── Audio focus ───────────────────────────────────────────────────────────

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (exoPlayer.isPlaying) {
                    pausedForFocusLoss = true
                    exoPlayer.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedForFocusLoss) {
                    pausedForFocusLoss = false
                    exoPlayer.play()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        audioFocusRequest = req
        return audioManager.requestAudioFocus(req).let {
            it == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                    it == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    /** Consistent teardown: cancel state/progress jobs, drop audio focus, stop ExoPlayer,
     *  remove the foreground notification, and stop the service. Used by BOTH the
     *  MediaSession onStop() callback and the STATE_ENDED real-end branch so teardown
     *  happens through one path exactly once. Previously the real-end branch did a subset
     *  of this inline inside the ExoPlayer onPlaybackStateChanged callback (with no
     *  exoPlayer.stop() and no stopSelf()) — that raced the MediaSession while a suspend
     *  DB delete interleaved and left the service with no foreground notification, so on
     *  Android 12+ the system killed it and the app vanished at end of book. Do NOT revert
     *  to inline teardown in the real-end branch. */
    private fun stopPlaybackAndService() {
        stateUpdateJob?.cancel()
        progressJob?.cancel()
        abandonAudioFocus()
        exoPlayer.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Audiobook Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controls for audiobook playback"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * Promotes the service to foreground promotion wrapped in a try/catch.
     *
     * Android 12+ throws ForegroundServiceStartNotAllowedException when audio focus
     * returns / a play command fires while the app is in the background. There are THREE
     * call sites that can reach this path (onPlay, playBook, onIsPlayingChanged). All
     * used to call startForegroundNotification() directly; onIsPlayingChanged was the only
     * one guarded. Guarding all three here means a background-restricted start logs a
     * warning instead of crashing the service (which was the "playback dies, can't restart
     * without force-quit" symptom). The notification reappears the next time the app is
     * foregrounded. Do NOT remove this wrapper.
     */
    private fun safeStartForeground() {
        try {
            startForegroundNotification()
        } catch (e: Exception) {
            Log.w(TAG, "startForeground blocked (app in background): ${e.message}")
        }
    }

    private fun startForegroundNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val controller = MediaControllerCompat(this, mediaSession.sessionToken)
        val description = controller.metadata?.description

        val playPauseIcon = if (exoPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseAction = if (exoPlayer.isPlaying)
            PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_book_placeholder)
            .setContentTitle(description?.title ?: currentTitle ?: "PlexAudiobooks")
            .setContentText(description?.subtitle ?: currentAuthor ?: "")
            .setSubText(currentTitle ?: "")
            // Book cover as the large icon of the expanded notification. The full-bleed
            // background art for the system MediaStyle / lock screen comes from the
            // METADATA_KEY_ART bitmap set in updateSessionMetadata(); setLargeIcon covers
            // the inline large-icon slot so the dropdown thumbnail also shows the cover
            // (matching Spotify / Plex Amp).
            .setLargeIcon(cachedArtBitmap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_skip_previous, "Previous",
                buildMediaPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(playPauseIcon,
                if (exoPlayer.isPlaying) "Pause" else "Play",
                buildMediaPendingIntent(playPauseAction))
            .addAction(R.drawable.ic_skip_next, "Next",
                buildMediaPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun buildMediaPendingIntent(action: Long): PendingIntent {
        val intent = Intent(this, AudiobookPlaybackService::class.java).apply {
            this.action = when (action) {
                PlaybackStateCompat.ACTION_PLAY -> "ACTION_PLAYER_PLAY"
                PlaybackStateCompat.ACTION_PAUSE -> "ACTION_PLAYER_PAUSE"
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT -> "ACTION_PLAYER_NEXT"
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS -> "ACTION_PLAYER_PREV"
                else -> "ACTION_PLAYER_PLAY"
            }
        }
        return PendingIntent.getService(
            this, action.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Progress reporting ────────────────────────────────────────────────────

    private fun startProgressReporting() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress(if (exoPlayer.isPlaying) "playing" else "paused")
            }
        }
    }

    private suspend fun saveProgress(state: String) {
        val ratingKey = currentRatingKey ?: return
        val key = currentKey ?: return
        val pos = exoPlayer.currentPosition
        val dur = when {
            exoPlayer.duration > 0 -> exoPlayer.duration
            currentChapters.isNotEmpty() -> currentChapters.last().endMs
            else -> return
        }
        if (pos <= 0) return
        try {
            // Local resume position is keyed by the ALBUM ratingKey (so Continue Listening,
            // which JOINs on ratingKey, matches). The Plex timeline sync uses the TRACK
            // ratingKey — currentKey now carries that (see PlaybackManager.sendPlayIntent).
            // reportProgressToPlex passes it as both the ratingKey and the `key` it builds
            // /library/metadata/{key} from. Sending the album ratingKey here (or the part
            // key the old PlaybackManager path sent) is silently dropped by Plex.
            repository.saveProgress(ratingKey, currentTitle ?: "", currentAuthor, pos, dur)
            repository.reportProgressToPlex(key, key, pos, dur, state)
        } catch (e: Exception) {
            Log.e(TAG, "saveProgress failed: ${e.message}", e)
        }
    }

    // ── Read-ahead ────────────────────────────────────────────────────────────

    private fun triggerReadAheadIfNeeded(
        ratingKey: String, title: String, author: String?,
        thumbPath: String?, partKeys: List<String>, durationMs: Long
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val existing = repository.getDownload(ratingKey)
                val currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val targetCachedUpToMs = (currentPositionMs + session.downloadHours * 3600 * 1000L)
                    .coerceAtMost(durationMs)

                if (existing != null && existing.downloadedUpToMs >= targetCachedUpToMs) {
                    // Cache already covers the desired read-ahead window from here — nothing to do
                    return@launch
                }

                Log.i(TAG, "Read-ahead: extending cache (have ${existing?.downloadedUpToMs ?: 0}ms, " +
                        "need ${targetCachedUpToMs}ms from position ${currentPositionMs}ms)")

                WorkManager.getInstance(applicationContext).enqueue(
                    BookDownloadWorker.buildRequest(
                        ratingKey, title, author, thumbPath, partKeys, durationMs,
                        targetCachedUpToMs = targetCachedUpToMs
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Read-ahead failed: ${e.message}")
            }
        }
    }
}
