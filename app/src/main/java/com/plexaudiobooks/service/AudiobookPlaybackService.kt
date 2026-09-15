package com.plexaudiobooks.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.work.WorkManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.plexaudiobooks.R
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.local.CachedLibraryEntity
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.ui.MainActivity
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Audiobook playback service, Media3 session-based.
 *
 * 1.8.0 CHAPTER-CLIP REARCHITECTURE: playback is now driven by a PLAYLIST of MediaItems, one
 * per chapter, each carrying a [MediaItem.ClippingConfiguration] scoped to that chapter's
 * [startMs, endMs] on the same underlying stream/file URI. The raw ExoPlayer's native
 * position/duration for the active item are therefore CHAPTER-RELATIVE — this is what makes
 * the Media3 MediaSession's system notification / lock-screen seekbar natively chapter-relative,
 * with no wrapper and no manual position override anywhere in this class. Book-absolute
 * position (needed for local Room progress + /:/timeline sync + read-ahead targeting) is
 * reconstructed at each read site as `currentChapters[exoPlayer.currentMediaItemIndex].startMs +
 * exoPlayer.currentPosition` — see [saveProgress] and [triggerReadAheadForCurrentBook].
 *
 * Degraded mode (no chapter data available) is NOT a separate code path: PlaybackManager sends
 * a playlist of exactly one clip spanning the whole book, which behaves identically to plain
 * continuous playback.
 *
 * Because [Player.STATE_ENDED] now only fires after the LAST item in the playlist finishes —
 * Media3 auto-advances between chapter clips via onMediaItemTransition, which PlaybackManager
 * listens for — the old reactive "was this STATE_ENDED a truncated local file or a real book
 * end?" disambiguation is GONE. The local-vs-stream decision is made per chapter, up front, at
 * playlist-build time in PlaybackManager.buildChapterMediaItem(); by the time a clip is playing,
 * its source is already known-correct for that chapter. See [handleStateEnded].
 *
 * START PATH (controller-driven): PlaybackManager calls `controller.setMediaItems(list,
 * startIndex, startPositionMs)` + `prepare()` + `play()`. Each item in `list` is a MediaItem
 * whose `mediaId` is "{ratingKey}#{chapterIndex}" and whose `mediaMetadata.extras` carries the
 * X_* constants below, including the new chapter-scoped ones. The session forwards the whole
 * batch to [MediaSession.Callback.onAddMediaItems], which resolves each thin item into the real
 * playable item (real stream/file URI + clipping config + display metadata).
 *
 * The old pending/apply-once start-position gate (`pendingStartPosition`/`applyPendingStart`) is
 * GONE — `setMediaItems(list, startIndex, startPositionMs)` specifies exactly where playback
 * starts in one call, so there's nothing left to apply reactively on first READY.
 */
@AndroidEntryPoint
@androidx.media3.common.util.UnstableApi
class AudiobookPlaybackService : MediaSessionService() {

    @Inject lateinit var repository: PlexRepository
    @Inject lateinit var session: SessionManager

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Uncaught exception in service coroutine: ${e.message}", e)
            })
    private var progressJob: Job? = null

    private var currentRatingKey: String? = null
    private var currentKey: String? = null
    private var currentTitle: String? = null
    private var currentAuthor: String? = null
    private var currentThumbUri: String? = null
    private var currentChapters: List<Chapter> = emptyList()
    private var currentChaptersLoaded = false   // once per session; guards Room read
    private var pausedForFocusLoss = false

    /** True once the first STATE_READY of the current play() session has been handled
     *  (audio focus requested, progress loop started, foreground promoted, read-ahead
     *  triggered). Reset to false whenever chapterIndex == 0 arrives in resolveThinItem —
     *  the single signal for "this is a fresh play() call" (new book OR a replay of the
     *  same book), since chapter index 0 appears exactly once per setMediaItems() batch. */
    private var sessionStarted: Boolean = false

    /** Stashed at chapterIndex == 0 (start of a play() session) for use by
     *  [triggerReadAheadForCurrentBook], which fires once per session at first READY rather
     *  than once per chapter item. */
    private var currentAllPartKeys: List<String> = emptyList()
    private var currentBookDurationMs: Long = 0L

    companion object {
        private const val TAG = "AudiobookService"
        private const val NOTIFICATION_CHANNEL_ID = "plex_audiobooks_playback"
        private const val NOTIFICATION_ID = 1001

        // MediaItem extras keys — carried on every playlist item (1.8.0).
        // pendingChapters was REMOVED in 1.8.1: chapters now come from Room directly
        // in loadChaptersIfNeeded(), decoupling the service from the manager process.
        const val X_RATING_KEY = "x_rating_key"
        const val X_STREAM_URL = "x_stream_url"
        const val X_TITLE = "x_title"
        const val X_AUTHOR = "x_author"
        const val X_THUMB_URL = "x_thumb_url"
        const val X_SPEED = "x_speed"
        const val X_PART_KEYS = "x_part_keys"
        const val X_DURATION_MS = "x_duration_ms"
        const val X_TIMELINE_KEY = "x_timeline_key"
        const val X_CHAPTER_INDEX = "x_chapter_index"
        const val X_CHAPTER_TITLE = "x_chapter_title"
        const val X_CHAPTER_START_MS = "x_chapter_start_ms"
        const val X_CHAPTER_END_MS = "x_chapter_end_ms"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 10 * 60_000, 2_500, 5_000)
                .build())
            .build()
            .apply { addListener(exoPlayerListener) }

        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, sessionCallback)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(GlideBitmapLoader(this))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        serviceScope.launch { saveProgress("stopped") }
    }

    override fun onDestroy() {
        serviceScope.launch { saveProgress("stopped") }
        progressJob?.cancel()
        abandonAudioFocus()
        mediaSession.release()
        exoPlayer.removeListener(exoPlayerListener)
        exoPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── MediaSession Callback ──────────────────────────────────────────────────

    private val sessionCallback = object : MediaLibrarySession.Callback {

        /**
         * PlaybackManager calls `controller.setMediaItems(list, startIndex, startPositionMs)`
         * with one thin item per chapter. Drain the Room chapter cache (once per batch), then
         * resolve every item in the batch.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Chapters now come from Room instead of a companion-object relay.
            // Called here on the main thread so it's already loaded before READY.
            serviceScope.launch { loadChaptersIfNeeded() }
            val resolved = mediaItems.mapNotNull { item ->
                val extras = item.mediaMetadata.extras
                if (extras != null) resolveThinItem(extras) else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        // ── Android Auto browse tree ──────────────────────────────────────────

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("PlexAudiobooks")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return guavaFuture {
                when (parentId) {
                    "root" -> {
                        // Top-level nodes are the three sections
                        val items = listOf(
                            MediaItem.Builder()
                                .setMediaId("continue_listening")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Continue Listening")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build()
                                )
                                .build(),
                            MediaItem.Builder()
                                .setMediaId("recently_added")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Recently Added")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build()
                                )
                                .build(),
                            MediaItem.Builder()
                                .setMediaId("library")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Library")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build()
                                )
                                .build()
                        )
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    }
                    "continue_listening" -> {
                        val items = repository.getContinueListeningSync().map { entity ->
                            bookToMediaItem(entity)
                        }
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    }
                    "recently_added" -> {
                        val items = repository.getRecentlyAddedRecent().map { entity ->
                            bookToMediaItem(entity)
                        }
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    }
                    "library" -> {
                        val items = repository.getAllBooks().map { entity ->
                            bookToMediaItem(entity)
                        }
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    }
                    else -> LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return guavaFuture {
                val entity = repository.getCachedBook(mediaId)
                if (entity != null) {
                    LibraryResult.ofItem(bookToMediaItem(entity), null)
                } else {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            return guavaFuture {
                val results = repository.searchLibraryForAuto(query)
                val items = results.map { bookToMediaItem(it) }
                session.notifySearchResultChanged(
                    browser, query, items.size, params
                )
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return guavaFuture {
                val results = repository.searchLibraryForAuto(query)
                val items = results.map { bookToMediaItem(it) }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Helper for building a playable MediaItem from a cached library entity.
     * All extras needed by resolveThinItem are carried along so the car-facing
     * play path flows through the same code as the phone-side PlaybackManager.
     */
    private fun bookToMediaItem(entity: CachedLibraryEntity): MediaItem {
        val thumbUrl = session.buildThumbUrl(entity.thumbPath)
        val extras = Bundle().apply {
            putString(X_RATING_KEY, entity.ratingKey)
            putString(X_TIMELINE_KEY, entity.ratingKey)
            putString(X_TITLE, entity.title)
            putString(X_AUTHOR, entity.author)
            putString(X_THUMB_URL, thumbUrl)
            putString(X_PART_KEYS, entity.mediaPartKey ?: "")
            putLong(X_DURATION_MS, entity.durationMs)
        }

        val streamUrl = session.buildStreamUrl(
            entity.mediaPartKey ?: return MediaItem.EMPTY
        ) ?: return MediaItem.EMPTY

        val meta = MediaMetadata.Builder()
            .setTitle(entity.title)
            .setArtist(entity.author)
            .setAlbumTitle(entity.title)
            .setExtras(extras)
            .apply { thumbUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId(entity.ratingKey)
            .setUri(streamUrl)
            .setMediaMetadata(meta)
            .build()
    }

    /**
     * Simple Guava future wrapper to convert a suspend block into a ListenableFuture.
     */
    private fun <T> guavaFuture(
        block: suspend () -> T
    ): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    /**
     * Resolves a thin controller-built [MediaItem] (extras carrying the X_* constants,
     * including the per-chapter X_CHAPTER_* ones) into the real playable item: the actual
     * stream/file URI, a [MediaItem.ClippingConfiguration] scoped to this chapter's range,
     * and display metadata (chapter title as subtitle/artist).
     *
     * chapterIndex == 0 is treated as the single signal for "a fresh play() session started"
     * (new book OR a replay of the same book) — it appears exactly once per setMediaItems()
     * batch, so it replaces the old currentRatingKey-comparison book-switch detection.
     */
    private fun resolveThinItem(extras: Bundle): MediaItem {
        val ratingKey = extras.getString(X_RATING_KEY) ?: ""
        val key = extras.getString(X_TIMELINE_KEY) ?: ratingKey
        val streamUrl = extras.getString(X_STREAM_URL) ?: ""
        val title = extras.getString(X_TITLE) ?: ""
        val author = extras.getString(X_AUTHOR)
        val thumbUrl = extras.getString(X_THUMB_URL)
        val speed = extras.getFloat(X_SPEED, 1.0f)
        val chapterIndex = extras.getInt(X_CHAPTER_INDEX, 0)
        val chapterTitle = extras.getString(X_CHAPTER_TITLE) ?: title
        val chapterStartMs = extras.getLong(X_CHAPTER_START_MS, 0L)
        val chapterEndMs = extras.getLong(X_CHAPTER_END_MS, 0L)

        if (chapterIndex == 0) {
            // Start of a fresh play() session. If a DIFFERENT book was already loaded,
            // save its progress and reset player state before this new playlist takes over.
            if (currentRatingKey != null && currentRatingKey != ratingKey) {
                Log.d(TAG, "Switching book: $currentRatingKey -> $ratingKey")
                serviceScope.launch { saveProgress("stopped") }
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                abandonAudioFocus()
            }
            sessionStarted = false
            currentAllPartKeys = (extras.getString(X_PART_KEYS) ?: "")
                .split(",").filter { it.isNotBlank() }
            currentBookDurationMs = extras.getLong(X_DURATION_MS, 0L)
            // Only reset the focus-loss flag when a genuinely NEW session begins —
            // not on every playlist item resolution. Doing this on every item would
            // clear pausedForFocusLoss mid-phone-call, so AUDIOFOCUS_GAIN's resume
            // would incorrectly think playback never paused for the call.
            pausedForFocusLoss = false
        }

        // Track the active book for every item in the playlist — the service reads these
        // fields for progress reporting / read-ahead / notification. These are idempotent
        // per item, so assigning them on every resolveThinItem() call is safe and correct.
        currentRatingKey = ratingKey
        currentKey = key
        currentTitle = title
        currentAuthor = author
        currentThumbUri = thumbUrl

        // Applied per item — idempotent and cheap (no separate pending/apply-once gate
        // needed; the old design deferred this to first-READY because the item wasn't
        // fully resolved yet at this point, but setting it here is harmless and simpler).
        exoPlayer.playbackParameters = PlaybackParameters(speed)

        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(chapterTitle)     // notification subtitle: chapter title, not author
            .setAlbumTitle(title)
            .setSubtitle(chapterTitle)
            .setExtras(extras)
            .apply { thumbUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        return MediaItem.Builder()
            .setMediaId("$ratingKey#$chapterIndex")
            .setUri(streamUrl)
            .setMediaMetadata(meta)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(chapterStartMs)
                    .setEndPositionMs(chapterEndMs)
                    .build()
            )
            .build()
    }

    // ── Chapter data ───────────────────────────────────────────────────────────

    /**
     * Read chapters from Room once per play() session (guarded by currentChaptersLoaded).
     * Called from onAddMediaItems (first chance) and again at STATE_READY if chapters are
     * still empty. This replaces the old companion-object pendingChapters: DB v5's chapter
     * cache is persistent, so the service no longer needs an in-process relay.
     */
    private suspend fun loadChaptersIfNeeded() {
        if (currentChaptersLoaded) return
        val key = currentRatingKey ?: return
        val chapters = repository.getCachedChapters(key)
        if (chapters.isNotEmpty()) {
            currentChapters = chapters
            currentChaptersLoaded = true
            Log.d(TAG, "Loaded ${chapters.size} chapters from Room cache")
        }
    }

    // ── ExoPlayer listener ───────────────────────────────────────────────────────

    private val exoPlayerListener = object : Player.Listener {

        /**
         * First READY of a play() session (guarded by sessionStarted, reset at chapterIndex
         * == 0 in resolveThinItem): request audio focus, start the progress loop, promote to
         * foreground, and trigger read-ahead for the whole book. No position/speed apply
         * needed here anymore — setMediaItems(list, startIndex, startPositionMs) already put
         * playback at the right spot before this fires.
         *
         * STATE_ENDED now only means "the last item in the playlist finished" — see
         * [handleStateEnded]. Chapter-to-chapter transitions are Media3 playlist
         * auto-advances (onMediaItemTransition on the controller side), not STATE_ENDED.
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && !sessionStarted) {
                sessionStarted = true
                requestAudioFocus()
                // Room read: back-stop chapter load (should already be populated by
                // onAddMediaItems, but we guard against the singleton race anyway).
                serviceScope.launch { loadChaptersIfNeeded() }
                startProgressReporting()
                safeStartForeground()
                triggerReadAheadForCurrentBook()
            }
            if (playbackState == Player.STATE_ENDED) {
                serviceScope.launch { handleStateEnded() }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                safeStartForeground()
            } else if (!pausedForFocusLoss) {
                serviceScope.launch { saveProgress("paused") }
            }
        }
    }

    // ── End of book ──────────────────────────────────────────────────────────────

    /**
     * STATE_ENDED now fires ONLY after the final chapter clip in the playlist finishes —
     * Media3 auto-advances between chapter clips on its own, so there is no "was this a
     * truncated local file or the real end?" question left to answer here. The per-chapter
     * local-vs-stream decision was already made correctly at playlist-build time
     * (PlaybackManager.buildChapterMediaItem), before this clip ever started playing.
     */
    private suspend fun handleStateEnded() {
        val ratingKey = currentRatingKey ?: return
        val endDur = currentChapters.lastOrNull()?.endMs
            ?: currentBookDurationMs.takeIf { it > 0 }
            ?: exoPlayer.duration.coerceAtLeast(0L)

        Log.i(TAG, "STATE_ENDED: real book end (final chapter clip finished)")
        saveProgress("stopped")
        repository.setCompleted(ratingKey, true)
        if (repository.getDownload(ratingKey)?.durable != true) {
            repository.deleteDownloadAndFile(ratingKey)
        }
        repository.saveProgress(ratingKey, currentTitle ?: "", currentAuthor, 0L, endDur)
        stopPlaybackAndService()
    }

    // ── Audio focus ─────────────────────────────────────────────────────────────

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

    // ── Teardown ─────────────────────────────────────────────────────────────────

    private fun stopPlaybackAndService() {
        progressJob?.cancel()
        abandonAudioFocus()
        currentRatingKey = null
        currentKey = null
        sessionStarted = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        stopSelf()
    }

    /**
     * Wraps the framework's foreground promotion in a try/catch. Android 12+ throws
     * [android.app.ForegroundServiceStartNotAllowedException] when a play command fires while
     * the app is background-restricted. Swallowing it logs instead of crashing. Do NOT remove.
     */
    private fun safeStartForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground blocked (app in background): ${e.message}")
        }
    }

    override fun onUpdateNotification(session: MediaSession) {
        try {
            super.onUpdateNotification(session)
        } catch (e: Exception) {
            Log.w(TAG, "onUpdateNotification blocked (background): ${e.message}")
        }
    }

    // ── Notification (fallback for safeStartForeground; Media3's own MediaStyle
    // notification, built on this session, is the one shown to the user) ─────────────

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Audiobook Playback", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Controls for audiobook playback"; setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val title = currentTitle ?: "PlexAudiobooks"
        val text = currentAuthor ?: ""
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_book_placeholder)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    // ── Progress reporting ────────────────────────────────────────────────────────

    private fun startProgressReporting() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress(if (exoPlayer.isPlaying) "playing" else "paused")
            }
        }
    }

    /**
     * exoPlayer.currentPosition/duration are now CHAPTER-RELATIVE (the active playlist item
     * is clipped to the current chapter). Book-absolute position — what Room and /:/timeline
     * need — is reconstructed as chapterStart + clip-relative position, using
     * exoPlayer.currentMediaItemIndex against currentChapters.
     */
    private suspend fun saveProgress(state: String) {
        val ratingKey = currentRatingKey ?: return
        val key = currentKey ?: return
        val chapterIdx = exoPlayer.currentMediaItemIndex
        val chapterStart = currentChapters.getOrNull(chapterIdx)?.startMs ?: 0L
        val pos = chapterStart + exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = when {
            currentChapters.isNotEmpty() -> currentChapters.last().endMs
            exoPlayer.duration > 0 -> exoPlayer.duration
            else -> return
        }
        if (pos <= 0) return
        try {
            repository.saveProgress(ratingKey, currentTitle ?: "", currentAuthor, pos, dur)
            repository.reportProgressToPlex(key, key, pos, dur, state)
        } catch (e: Exception) {
            Log.e(TAG, "saveProgress failed: ${e.message}", e)
        }
    }

    // ── Read-ahead ─────────────────────────────────────────────────────────────────

    /**
     * Called once per play() session at first STATE_READY (not per chapter item). Reconstructs
     * the book-absolute position the same way [saveProgress] does, then delegates to
     * [triggerReadAheadIfNeeded] with that value instead of letting it read
     * exoPlayer.currentPosition directly — which, before this session, would have been
     * chapter-relative and wrong for this purpose.
     */
    private fun triggerReadAheadForCurrentBook() {
        val ratingKey = currentRatingKey ?: return
        if (currentAllPartKeys.isEmpty() || currentBookDurationMs <= 0) return
        val chapterIdx = exoPlayer.currentMediaItemIndex
        val chapterStart = currentChapters.getOrNull(chapterIdx)?.startMs ?: 0L
        val bookAbsPos = (chapterStart + exoPlayer.currentPosition).coerceAtLeast(0L)
        triggerReadAheadIfNeeded(
            ratingKey, currentTitle ?: "", currentAuthor, currentThumbUri,
            currentAllPartKeys, currentBookDurationMs, bookAbsPos
        )
    }

    private fun triggerReadAheadIfNeeded(
        ratingKey: String, title: String, author: String?,
        thumbPath: String?, partKeys: List<String>, durationMs: Long,
        bookAbsPositionMs: Long
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val existing = repository.getDownload(ratingKey)
                val currentPositionMs = bookAbsPositionMs
                val targetCachedUpToMs = (currentPositionMs + session.downloadHours * 3600 * 1000L)
                    .coerceAtMost(durationMs)

                if (existing != null && existing.downloadedUpToMs >= targetCachedUpToMs) {
                    return@launch // Cache already covers the desired read-ahead window from here.
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