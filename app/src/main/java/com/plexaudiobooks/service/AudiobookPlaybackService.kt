package com.plexaudiobooks.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
class AudiobookPlaybackService : MediaBrowserServiceCompat() {

    @Inject lateinit var repository: PlexRepository
    @Inject lateinit var session: SessionManager

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
                playBook(ratingKey, key, streamUrl, title, author, startPos, speed, durationMs)
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
                startForegroundNotification()
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
            exoPlayer.setPlaybackParameters(PlaybackParameters(speed))
            session.playbackSpeed = speed
            updatePlaybackState()
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun playBook(
        ratingKey: String, key: String, streamUrl: String,
        title: String, author: String?,
        startPositionMs: Long, playbackSpeed: Float, durationMs: Long
    ) {
        currentRatingKey = ratingKey
        currentKey = key
        currentTitle = title
        currentAuthor = author
        pausedForFocusLoss = false

        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare()
        exoPlayer.seekTo(startPositionMs)
        exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed))
        exoPlayer.play()

        // Update currentChapterIndex based on saved position
        if (currentChapters.isNotEmpty() && startPositionMs > 0) {
            currentChapterIndex = currentChapters.indexOfLast { startPositionMs >= it.startMs }
                .coerceAtLeast(0)
        }

        updateSessionMetadata()
        updatePlaybackState()
        startForegroundNotification()
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
            }
            .build()

        mediaSession.setMetadata(metadata)
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
                startForegroundNotification()
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
                    val dur = exoPlayer.duration.takeIf { it > 0 } ?: return@launch
                    saveProgress("stopped")
                }
                stateUpdateJob?.cancel()
                progressJob?.cancel()
                abandonAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req).let {
                it == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                it == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusRequest = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        val pos = exoPlayer.currentPosition  // always absolute
        // exoPlayer.duration is -1 until buffered — fall back to the last known chapter
        // end or skip saving rather than storing a broken duration of 0/-1
        val dur = when {
            exoPlayer.duration > 0 -> exoPlayer.duration
            currentChapters.isNotEmpty() -> currentChapters.last().endMs
            else -> return
        }
        if (pos <= 0) return  // don't save a zero position — nothing meaningful to resume
        repository.saveProgress(ratingKey, currentTitle ?: "", currentAuthor, pos, dur)
        repository.reportProgressToPlex(ratingKey, key, pos, dur, state)
    }

    // ── Read-ahead ────────────────────────────────────────────────────────────

    private fun triggerReadAheadIfNeeded(
        ratingKey: String, title: String, author: String?,
        thumbPath: String?, partKeys: List<String>, durationMs: Long
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (repository.getDownload(ratingKey) != null) return@launch
                WorkManager.getInstance(applicationContext).enqueue(
                    BookDownloadWorker.buildRequest(ratingKey, title, author, thumbPath, partKeys, durationMs)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Read-ahead failed: ${e.message}")
            }
        }
    }
}
