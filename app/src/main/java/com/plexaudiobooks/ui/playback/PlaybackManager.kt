package com.plexaudiobooks.ui.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.plexaudiobooks.R
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.model.AudioBook
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.service.AudiobookPlaybackService
import com.plexaudiobooks.ui.MainActivity
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton owner of playback state for the whole app.
 *
 * The single fix for "playback dies, can't restart without force-quitting": this holds ONE
 * persistent [MediaControllerCompat] bound to [AudiobookPlaybackService] for the lifetime of
 * the process — it is NOT torn down on fragment onStop the way the old PlayerFragment
 * controller was. Every screen (mini-player, expanded player sheet, Now Playing tab)
 * collects [state] instead of re-deriving book/position/cover from scratch on each entry.
 *
 * The service stays [MediaSessionCompat]-based (not Media3) to preserve chapter-relative
 * notification progress + the existing seek contract. The manager only talks to the service
 * through the [MediaControllerCompat] transport controls and the [MainActivity.EXTRA_*]
 * start intent — it does not touch the service's seek/end-of-book internals.
 */
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PlexRepository,
    val session: SessionManager
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(NowPlayingUiState())
    val state: StateFlow<NowPlayingUiState> = _state.asStateFlow()

    private val serviceComponent =
        ComponentName(appContext, AudiobookPlaybackService::class.java)

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    /** Guards one initial connect per process; reconnects are cheap and reuse the session. */
    private var connected = false

    /** Set once per play() command so we don't fire duplicate start intents. */
    private var currentPlayToken: String? = null

    /** The in-flight play() coroutine. A rapid second tap cancels this instead of racing
     *  a second play() that would re-fetch chapters and fire a duplicate start intent. */
    private var playJob: Job? = null

    private var pollJob: Job? = null

    /** Set by play() when it suspends to ask the user whether to resume from the
     *  server-saved position; resolved by [confirmResume] (true/false) or cancelled by
     *  [cancelResumePrompt] (resolves to null = abort this play). Null when no prompt
     *  is pending. */
    private var resumeDeferred: CompletableDeferred<Boolean?>? = null
    /** The ratingKey the pending resume prompt is for, so a stale confirmResume() (e.g.
     *  after the user backed out and started a different book) is ignored. */
    private var resumePromptRatingKey: String? = null

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updateStateFromController(state)
        }

        override fun onMetadataChanged(metadata: android.support.v4.media.MediaMetadataCompat?) {
            // Chapter title is carried in METADATA_KEY_TITLE.
            _state.value = _state.value.copy(
                currentChapterTitle = metadata?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE)
            )
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Ensures the browser+controller are connected. Safe to call repeatedly. Lazy: the
     * first caller triggers the (async) connect; subsequent callers are no-ops. Survives
     * fragment lifecycle — only [release] (app teardown) disconnects.
     */
    fun ensureConnected() {
        if (connected || mediaBrowser != null) return
        val browser = MediaBrowserCompat(
            appContext,
            serviceComponent,
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val b = mediaBrowser ?: return
                    val token = b.sessionToken
                    mediaController = MediaControllerCompat(appContext, token).also { ctrl ->
                        ctrl.registerCallback(controllerCallback)
                    }
                    connected = true
                    // Seed state from whatever the service already has.
                    updateStateFromController(mediaController?.playbackState)
                    pollPosition()
                }

                override fun onConnectionSuspended() {
                    // Service dropped the session (e.g. system killed it). Keep the browser
                    // reference so a later play() can reconnect; clear the stale controller.
                    mediaController?.unregisterCallback(controllerCallback)
                    mediaController = null
                    connected = false
                    _state.value = _state.value.copy(connected = false)
                }

                override fun onConnectionFailed() {
                    connected = false
                    _state.value = _state.value.copy(
                        connected = false,
                        error = "Unable to connect to the player service."
                    )
                    Log.e(TAG, "MediaBrowser connection failed")
                }
            },
            null
        )
        mediaBrowser = browser
        try {
            browser.connect()
        } catch (e: SecurityException) {
            // Some OEMs throw if the service isn't exported as expected.
            _state.value = _state.value.copy(error = "Could not start the player: ${e.message}")
        }
    }

    // ── Play ───────────────────────────────────────────────────────────────────

    /**
     * The single entry point for starting playback from anywhere (Continue Listening,
     * Detail, Downloads, Now Playing tab). Resolves server+stream URL ONCE per play,
     * applies the un-complete-on-replay / un-shelve-on-play rules that used to live in
     * PlayerViewModel.loadBook, pushes chapters to the service, and starts the service
     * with [Context.startForegroundService] (not startService) so a system-killed service
     * has a reliable resurrection path.
     *
     * [chapters] may be empty if the caller hasn't fetched them yet — the service's
     * startStateUpdating loop picks up pendingChapters whenever they arrive (same pattern
     * as the old PlayerFragment).
     */
    fun play(
        ratingKey: String,
        chapters: List<Chapter> = emptyList(),
        startPositionMsOverride: Long? = null
    ) {
        // Cancel any in-flight play() coroutine from a rapid re-tap so the latest tap wins
        // instead of racing a second fetch + duplicate start intent.
        playJob?.cancel()

        // Immediate feedback: show the mini-player + a loading affordance within a frame,
        // before any network work. Cleared in the finally below no matter how play() exits
        // (abort, error, success). hasContent now includes isStarting so the bar appears.
        _state.value = _state.value.copy(isStarting = true, error = null)

        playJob = scope.launch {
            val me = coroutineContext[Job]
            try {
            // If a previous resume prompt is still pending (user started one book, then
            // tapped another before deciding) or this is a re-tap, abort the suspended
            // play() so only the latest play() proceeds. cancelResumePrompt() resolves
            // its deferred to null, which makes the old coroutine return early below.
            resumeDeferred?.complete(null)

            // Always reconnect if the service died (onConnectionSuspended cleared us).
            if (!connected) ensureConnected()

            val cached = repository.getCachedBook(ratingKey)
            val download = repository.getDownload(ratingKey)
            // Position source priority:
            //   1. explicit override (a caller forcing a position)
            //   2. local Room progress (normal returning-user case — resumes silently)
            //   3. server-saved viewOffset from the cached_library row (fresh install:
            //      no local row but the Plex server has progress) → PROMPT the user
            //   4. 0 (never started)
            val localPosition = if (startPositionMsOverride != null) null else repository.getProgress(ratingKey)
            val serverPosition = cached?.viewOffset?.takeIf { it > 0 } ?: 0L
            val isServerResume = startPositionMsOverride == null &&
                                 localPosition == null && serverPosition > 0

            // If this is a fresh-install resume from server progress, pause and ask the
            // user whether to resume from the saved position or start from the beginning.
            // The host (MainActivity) observes state.showResumePrompt, shows an
            // AlertDialog, and calls back into confirmResume(true/false). We suspend here
            // until that happens. A null result (cancelResumePrompt — user dismissed the
            // dialog or started another book) aborts this play without starting audio.
            // Returning users with local progress skip this and resume silently.
            var resumeChoice: Boolean? = null
            if (isServerResume) {
                val deferred = CompletableDeferred<Boolean?>()
                resumeDeferred = deferred
                resumePromptRatingKey = ratingKey
                _state.value = _state.value.copy(
                    showResumePrompt = ResumePrompt(
                        ratingKey = ratingKey,
                        serverPositionMs = serverPosition,
                        formattedPosition = formatPosition(serverPosition)
                    )
                )
                try {
                    resumeChoice = deferred.await()
                } finally {
                    val stillActive = resumeDeferred === deferred
                    if (stillActive) {
                        resumeDeferred = null
                        resumePromptRatingKey = null
                        // Clear the prompt bit so the dialog doesn't re-fire on
                        // recomposition. Only when we are still the active prompt — a
                        // later play() may have already emitted its own prompt, which we
                        // must not clobber back to null.
                        if (_state.value.showResumePrompt != null) {
                            _state.value = _state.value.copy(showResumePrompt = null)
                        }
                    }
                }
                if (resumeChoice == null) {
                    // Dismissed without a choice — don't start playback.
                    return@launch
                }
            }

            val rawPosition = when {
                startPositionMsOverride != null -> startPositionMsOverride
                localPosition != null -> localPosition
                isServerResume -> if (resumeChoice == true) serverPosition else 0L
                else -> localPosition ?: serverPosition.takeIf { it > 0 } ?: 0L
            }
            val bookDuration = download?.durationMs ?: cached?.durationMs ?: 0L

            // Reuse PlayerViewModel.loadBook's completed-replay contract: if the book is
            // marked complete AND the saved position is near the end, reset to 0 and clear
            // the completed flag so it returns to Continue Listening.
            val isCompleted = cached?.completed == true
            val startPositionMs = if (isCompleted && rawPosition >= bookDuration - 30_000) 0L else rawPosition
            if (isCompleted && startPositionMs == 0L) {
                repository.setCompleted(ratingKey, false)
            }
            // Un-shelve on play: a shelved book returns to Continue Listening the moment
            // the user resumes it. Mirrors the un-complete-on-replay rule.
            if (cached?.shelved == true) {
                repository.setShelved(ratingKey, false)
            }

            // Resolve server URL once, exactly like PlayerViewModel.loadBook's network path.
            try {
                repository.resolveAndRefreshServerUrl()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Cannot reach server: ${e.message}")
                return@launch
            }

            // Build the playable book for the UI (fast path) while the service starts.
            val displayBook: AudioBook = download?.let { d ->
                AudioBook(
                    ratingKey = ratingKey,
                    title = d.title,
                    author = d.author,
                    summary = null,
                    thumbPath = d.thumbPath,
                    duration = d.durationMs,
                    viewOffset = startPositionMs,
                    addedAt = 0L,
                    mediaKey = d.mediaPartKey,
                    isDownloaded = true,
                    downloadedPath = d.localFilePath
                )
            } ?: cached?.let { c ->
                AudioBook(
                    ratingKey = ratingKey,
                    title = c.title,
                    author = c.author,
                    summary = c.summary,
                    thumbPath = c.thumbPath,
                    duration = c.durationMs,
                    viewOffset = startPositionMs,
                    addedAt = c.addedAt,
                    mediaKey = c.mediaPartKey
                )
            } ?: run {
                _state.value = _state.value.copy(error = "Book not found.")
                return@launch
            }

            // If network is reachable, refresh metadata/chapters (mirror loadBook).
            // The cached_library row does NOT carry a part key (the /all endpoint returns
            // album-level items with no parts), so displayBook.mediaKey is null for a
            // streaming book. fetchBookDetail fetches the track children and returns a
            // fresh AudioBook whose mediaKey is the real first-track part key — we keep
            // that here so buildStreamUrl()/sendPlayIntent get a usable streaming URL.
            // Without this, buildStreamUrl hits `partKey = book.mediaKey ?: return null`
            // and play() bails before the service intent is sent → no audio for streams.
            val detailBook: Pair<AudioBook, List<Chapter>>? = try {
                when (val result = repository.fetchBookDetail(ratingKey)) {
                    is Result.Success -> result.data
                    else -> null
                }
            } catch (e: Exception) {
                null // offline is fine for a downloaded book
            }
            val finalChapters = chapters.ifEmpty { detailBook?.second ?: emptyList() }

            // Upgrade displayBook with freshly-fetched metadata. Two concerns:
            //   1. mediaKey/allPartKeys — only matters for the streaming branch (downloaded
            //      books short-circuit on file:// before reading mediaKey).
            //   2. trackRatingKey/trackDurationMs — matters for BOTH branches. /:/timeline
            //      keys off the TRACK ratingKey, and neither the cached_library row nor the
            //      downloaded_books row carries it — only fetchBookDetail does. Without this
            //      overlay, reportProgressToPlex falls back to the album ratingKey, which
            //      Plex drops for multi-file books (the pre-1.6.5 progress-sync regression).
            // The overlay is applied outside the mediaKey gate so a downloaded book (which
            // has a mediaKey and skips that gate) still gets the correct track ratingKey.
            val playBook =
                if (displayBook.mediaKey.isNullOrBlank() && detailBook != null) {
                    displayBook.copy(
                        mediaKey = detailBook.first.mediaKey,
                        allPartKeys = displayBook.allPartKeys
                            .ifEmpty { detailBook.first.allPartKeys }
                    )
                } else displayBook
            val playBookWithTrack = if (detailBook != null) {
                playBook.copy(
                    trackRatingKey = playBook.trackRatingKey
                        ?: detailBook.first.trackRatingKey,
                    trackDurationMs = if (playBook.trackDurationMs > 0)
                        playBook.trackDurationMs else detailBook.first.trackDurationMs
                )
            } else playBook

            // Push chapters to the service companion (same channel as the old PlayerFragment).
            AudiobookPlaybackService.pendingChapters = finalChapters

            // Seed local progress so this book resumes silently next session (the service's
            // 10s save loop would do this eventually, but writing it now also makes Continue
            // Listening populate immediately). Only when we had no local row AND a non-zero
            // start position — a fresh start-from-beginning choice skips seeding (let the
            // service save naturally once playback moves off 0).
            if (localPosition == null && startPositionMs > 0) {
                repository.saveProgress(
                    ratingKey, playBookWithTrack.title, playBookWithTrack.author, startPositionMs, playBookWithTrack.duration
                )
            }

            _state.value = _state.value.copy(
                book = playBookWithTrack,
                chapters = finalChapters,
                positionMs = startPositionMs,
                bookDurationMs = playBookWithTrack.duration,
                playbackSpeed = session.playbackSpeed,
                isOffline = download != null,
                error = null
            )

            // Build + fire the start intent. Use startForegroundService so a system-killed
            // service is resurrected even if the app is backgrounded (the old startService
            // path could silently fail to start a background service on Android 8+).
            // The timeline `key` is the TRACK ratingKey (what /:/timeline keys off), NOT
            // the part key in playBook.mediaKey nor the album ratingKey. Sending the part
            // key makes reportProgressToPlex build `key=/library/parts/…`, which Plex can't
            // resolve → progress sync silently dies. Fall back to the album ratingKey for
            // books with no track children. See reportProgressToPlex().
            val timelineRatingKey = playBookWithTrack.trackRatingKey ?: ratingKey
            val streamUrl = buildStreamUrl(playBookWithTrack, ratingKey, startPositionMs)
            if (streamUrl == null) {
                _state.value = _state.value.copy(error = "Could not build a stream URL for this book.")
                return@launch
            }

            sendPlayIntent(
                ratingKey = ratingKey,
                key = timelineRatingKey,
                streamUrl = streamUrl,
                title = playBookWithTrack.title,
                author = playBookWithTrack.author,
                thumbUrl = session.buildThumbUrl(playBookWithTrack.thumbPath),
                startPositionMs = startPositionMs,
                speed = session.playbackSpeed,
                partKeys = playBookWithTrack.allPartKeys,
                durationMs = playBookWithTrack.duration
            )
            currentPlayToken = ratingKey
            } finally {
                // Loading done for THIS play. Only clear if we're still the active playJob —
                // a rapid re-tap cancels this job and starts a new one; the old finally must
                // not clobber the new job's isStarting=true.
                if (me === playJob) {
                    _state.value = _state.value.copy(isStarting = false)
                }
            }
        }
    }

    /**
     * Routes local file vs. server stream — same logic as PlayerViewModel.buildStreamUrl:
     * use the local file only if the resume position is within the downloaded window,
     * otherwise stream so ExoPlayer doesn't seek past a truncated local file.
     */
    private suspend fun buildStreamUrl(book: AudioBook, ratingKey: String, positionMs: Long): String? {
        if (book.isDownloaded && !book.downloadedPath.isNullOrEmpty()) {
            val download = repository.getDownload(ratingKey)
            val cachedUpTo = download?.downloadedUpToMs ?: 0L
            // Play the local file only if the resume position is within the cached window.
            // If cachedUpTo is unknown (0), only use the file from the very start; otherwise
            // stream so ExoPlayer never seeks past the end of a truncated local file.
            val safeLocal = cachedUpTo <= 0 && positionMs == 0L ||
                            cachedUpTo > 0 && positionMs <= cachedUpTo
            if (safeLocal) {
                return "file://${book.downloadedPath}"
            } else {
                _state.value = _state.value.copy(isOffline = false)
            }
        }
        val partKey = book.mediaKey ?: return null
        return session.buildStreamUrl(partKey)
    }

    private fun sendPlayIntent(
        ratingKey: String, key: String, streamUrl: String,
        title: String, author: String?, thumbUrl: String?,
        startPositionMs: Long, speed: Float,
        partKeys: List<String>, durationMs: Long
    ) {
        val serviceIntent = Intent(appContext, AudiobookPlaybackService::class.java).apply {
            action = MainActivity.ACTION_PLAY
            putExtra(MainActivity.EXTRA_RATING_KEY, ratingKey)
            putExtra(MainActivity.EXTRA_KEY, key)
            putExtra(MainActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(MainActivity.EXTRA_TITLE, title)
            putExtra(MainActivity.EXTRA_AUTHOR, author)
            putExtra(MainActivity.EXTRA_THUMB_URL, thumbUrl)
            putExtra(MainActivity.EXTRA_START_POSITION, startPositionMs)
            putExtra(MainActivity.EXTRA_SPEED, speed)
            putExtra(MainActivity.EXTRA_PART_KEYS, partKeys.joinToString(","))
            putExtra(MainActivity.EXTRA_DURATION_MS, durationMs)
        }
        // startForegroundService (Android 8+) so the service can promote itself; the service
        // then calls safeStartForeground() which is guarded against background restrictions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(serviceIntent)
        } else {
            appContext.startService(serviceIntent)
        }
    }

    // ── Resume prompt ────────────────────────────────────────────────────────────

    /**
     * Called by the host (MainActivity) when the user answers the resume/start-over
     * dialog. `resume == true` → resume from the server-saved position; `false` → start
     * from the beginning. Resolves the deferred play() suspended on, so playback
     * proceeds with the chosen position. Safe to call when no prompt is pending (no-op).
     */
    fun confirmResume(resume: Boolean) {
        resumeDeferred?.complete(resume)
    }

    /**
     * Called by the host when the resume dialog is dismissed without a choice (back
     * press, tapping outside) or when a new play() supersedes a pending prompt. Resolves
     * the deferred to null — the suspended play() treats null as "aborted" and returns
     * without starting playback. The prompt bit is cleared in play()'s finally block.
     */
    fun cancelResumePrompt() {
        resumeDeferred?.complete(null)
    }

    /**
     * Formats a ms position as "1h 23m" / "5m" using the app's own string resources, so
     * the resume dialog host doesn't need duration-format helpers itself.
     */
    private fun formatPosition(ms: Long): String {
        if (ms <= 0) return "0m"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val mins = ((totalSeconds % 3600) / 60).toInt()
        return if (hours > 0) {
            appContext.getString(R.string.duration_hours_mins, hours.toInt(), mins)
        } else {
            appContext.getString(R.string.duration_mins, mins)
        }
    }

    // ── Transport wrappers (used by mini-player + player sheet) ────────────────

    fun togglePlayPause() {
        val ctrl = mediaController
        if (ctrl == null) { ensureConnected(); return }
        when (ctrl.playbackState?.state) {
            PlaybackStateCompat.STATE_PLAYING -> ctrl.transportControls.pause()
            else -> ctrl.transportControls.play()
        }
    }

    fun pause() { mediaController?.transportControls?.pause() }
    fun resume() { mediaController?.transportControls?.play() }

    /** absoluteMs is the BOOK-absolute position. Converted to chapter-relative for the
     *  service (matches the old PlayerFragment.sendAbsoluteSeek contract / onSeekTo). */
    fun seekAbsolute(absoluteMs: Long) {
        val ctrl = mediaController ?: return
        val chapter = currentChapterFor(absoluteMs)
        val chapterRel = if (chapter != null) absoluteMs - chapter.startMs else absoluteMs
        ctrl.transportControls.seekTo(chapterRel)
    }

    fun nextChapter() { mediaController?.transportControls?.skipToNext() }
    fun previousChapter() { mediaController?.transportControls?.skipToPrevious() }
    fun skipBy(deltaMs: Long) {
        val abs = _state.value.positionMs + deltaMs
        seekAbsolute(abs.coerceIn(0L, _state.value.bookDurationMs))
    }
    fun setSpeed(speed: Float) {
        session.playbackSpeed = speed
        _state.value = _state.value.copy(playbackSpeed = speed)
        mediaController?.transportControls?.setPlaybackSpeed(speed)
    }

    /** Stop playback and release audio — used by sign-out and back-to-exit. */
    fun stop() {
        mediaController?.transportControls?.stop()
        pollJob?.cancel()
        _state.value = NowPlayingUiState()
    }

    // ── Position polling ──────────────────────────────────────────────────────

    private fun pollPosition() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                updateStateFromController(mediaController?.playbackState)
                delay(500)
            }
        }
    }

    private fun updateStateFromController(pbState: PlaybackStateCompat?) {
        val ctrl = mediaController ?: run {
            _state.value = _state.value.copy(connected = false)
            return
        }
        if (pbState == null) return
        val absPos = pbState.extras?.getLong(AudiobookPlaybackService.EXTRA_ABSOLUTE_POSITION, -1L)
            ?.takeIf { it >= 0 } ?: pbState.position
        val chapter = currentChapterFor(absPos)
        val chapterDur = if (chapter != null) chapter.endMs - chapter.startMs else 0L
        val chapterIdx = chapters.indexOfLast { absPos >= it.startMs }.let {
            if (it >= 0) it else _state.value.currentChapterIndex
        }
        _state.value = _state.value.copy(
            positionMs = absPos,
            chapterDurationMs = chapterDur,
            currentChapterIndex = chapterIdx,
            isPlaying = pbState.state == PlaybackStateCompat.STATE_PLAYING,
            isBuffering = pbState.state == PlaybackStateCompat.STATE_BUFFERING,
            connected = true
        )
    }

    private fun currentChapterFor(absoluteMs: Long): Chapter? =
        chapters.lastOrNull { absoluteMs >= it.startMs }

    private val chapters: List<Chapter> get() = _state.value.chapters

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called from MainActivity.onDestroy to disconnect cleanly. */
    fun release() {
        pollJob?.cancel()
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser?.disconnect()
        mediaController = null
        mediaBrowser = null
        connected = false
    }

    companion object {
        private const val TAG = "PlaybackManager"
    }
}
