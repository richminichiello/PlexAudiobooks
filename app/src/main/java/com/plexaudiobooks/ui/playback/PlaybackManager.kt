package com.plexaudiobooks.ui.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.plexaudiobooks.R
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.model.AudioBook
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.service.AudiobookPlaybackService
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Singleton owner of playback state for the whole app.
 *
 * CHAPTER-CLIP REARCHITECTURE: each chapter is now its own Media3 playlist item, built
 * with a [MediaItem.ClippingConfiguration] scoped to [chapter.startMs, chapter.endMs] on the
 * same underlying stream/file URI. The native ExoPlayer position for the active item IS
 * chapter-relative — no wrapper, no manual position translation on the playback hot path. The
 * service's [androidx.media3.session.MediaSession] reads that native position straight through,
 * so the notification / lock-screen seekbar and subtitle are natively chapter-relative for
 * free (subtitle = chapter title, replacing author).
 *
 * Book-absolute position (used by everything ABOVE this layer — mini-player, player sheet,
 * "time remaining in book") is the DERIVED value, reconstructed once here as
 * `chapters[currentMediaItemIndex].startMs + controller.currentPosition` in
 * [updateStateFromController]. Every consumer of [state] (NowPlayingUiState.positionMs) still
 * sees book-absolute position.
 *
 * Degraded mode: when chapter data is unavailable, playback falls back to a single synthetic
 * chapter spanning the whole book — structurally identical to plain continuous playback.
 *
 * Skip-by-seconds ([skipBy]) clamps at the CURRENT chapter's boundary rather than crossing into
 * the next/previous chapter — a deliberate product decision. The next chapter starts naturally
 * via the playlist's normal auto-advance.
 *
 * COMPLETED-BOOK REPLAY: rather than silently deciding whether a saved position is "near
 * enough to the end" to auto-restart from 0 (a heuristic that compares position against a
 * duration value that can itself be stale — see the duration-overlay fix history — and was
 * the suspected cause of a replay crash), a completed book ALWAYS pauses play() and asks the
 * user via [CompletedRestartPrompt]. Confirming always restarts at exactly 0, which cannot be
 * out of range for any chapter clip regardless of what the duration data says.
 *
 * This still holds ONE persistent [MediaController] bound to the service for the lifetime of
 * the process — not torn down on fragment onStop. Every screen (mini-player, expanded player
 * sheet) collects [state] instead of re-deriving book/position/cover from scratch.
 */
@Singleton
@androidx.media3.common.util.UnstableApi
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

    /** Inline direct executor (runs the callback on the caller's thread) for
     *  [Futures.addCallback]. */
    private val directExecutor = java.util.concurrent.Executor { it.run() }

    // ── Phase-2 enrichment (1.8.0) ─────────────────────────────────────────────
    /**
     * After a fast-path play (started from purely local data), run fetchBookDetail() in
     * the background to refresh the Room caches. If the chapter list we started with was
     * empty or differs materially from the authoritative server copy, hot-reload the
     * MediaItem playlist so chapter titles/boundaries fill in without restarting audio.
     *
     * Runs on the manager scope; cancellable by the next play().
     */
    private fun launchEnrichment(
        ratingKey: String,
        startedBook: AudioBook,
        startPositionMs: Long,
        startedChapters: List<Chapter>
    ) {
        scope.launch {
            try {
                val detail = try {
                    when (val result = repository.fetchBookDetail(ratingKey)) {
                        is Result.Success -> result.data
                        else -> return@launch
                    }
                } catch (e: Exception) {
                    return@launch
                }

                val (detailBook, freshChapters) = detail
                // Room caches upserted inside fetchBookDetail — nothing extra to do there.

                // If we started with no chapters and now have them, OR the shape changed
                // materially (count or boundaries differ by >1s), rebuild the playlist at
                // the current position so the UI/notification get real chapter data.
                val needsReload = startedChapters.isEmpty() ||
                        freshChapters.size != startedChapters.size ||
                        freshChapters.zip(startedChapters).any { (f, s) ->
                            kotlin.math.abs(f.startMs - s.startMs) > 1_000 ||
                                    kotlin.math.abs(f.endMs - s.endMs) > 1_000
                        }
                if (!needsReload) return@launch

                // Upgrade the visible state now so the sheet's chapter list fills in.
                _state.value = _state.value.copy(chapters = freshChapters)

                // Hot-swap the playlist items at the current book-absolute position.
                val ctrl = controller ?: return@launch
                val absPos = _state.value.positionMs
                val newIdx = freshChapters.indexOfLast { absPos >= it.startMs }.coerceAtLeast(0)
                val newChapterStart: Long = freshChapters.getOrNull(newIdx)?.startMs ?: 0L
                val newRel: Long = (absPos - newChapterStart).coerceAtLeast(0L)
                val timelineKey = detailBook.trackRatingKey ?: ratingKey
                val thumbUrl = session.buildThumbUrl(
                    startedBook.thumbPath ?: detailBook.thumbPath
                )
                val newItems = freshChapters.mapIndexed { idx, ch ->
                    buildChapterMediaItem(
                        ratingKey, timelineKey,
                        // Keep the original display fields; only chapters change.
                        startedBook, thumbUrl,
                        chapterIndex = idx, chapterTitle = ch.title,
                        startMs = ch.startMs, endMs = ch.endMs
                    )
                }
                if (newItems.any { it == MediaItem.EMPTY }) return@launch

                // Seamless: the service's onAddMediaItems resolves these into real URIs;
                // Media3 handles the position reset atomically.
                ctrl.setMediaItems(newItems, newIdx, newRel)
            } catch (e: Exception) {
                Log.w(TAG, "Enrichment refresh failed (keeping local playback): ${e.message}")
            }
        }
    }

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Guards one initial connect per process; cleared on disconnect so a later play() reconnects. */
    private var connected = false

    /** The in-flight play() coroutine. A rapid second tap cancels this instead of racing
     *  a second play() that would re-fetch chapters and fire a duplicate start. */
    private var playJob: Job? = null

    private var pollJob: Job? = null

    /** Set by play() when it suspends to ask the user whether to resume a fresh-install book
     *  from the server-saved position; resolved by [confirmResume] (true/false) or cancelled
     *  by [cancelResumePrompt] (resolves to null = abort this play). Null when no prompt
     *  is pending. */
    private var resumeDeferred: CompletableDeferred<Boolean?>? = null
    /** The ratingKey the pending resume prompt is for, so a stale confirmResume() is ignored. */
    private var resumePromptRatingKey: String? = null

    /** Set by play() when it suspends to ask the user whether to restart a completed book.
     *  Resolved by [confirmCompletedRestart] (true = restart from 0, false = cancel) or
     *  [cancelCompletedRestartPrompt] (dialog dismissed — also treated as cancel). */
    private var completedRestartDeferred: CompletableDeferred<Boolean?>? = null

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@PlaybackManager.controller === controller) {
                this@PlaybackManager.controller = null
                controllerFuture = null
                connected = false
                _state.value = _state.value.copy(connected = false)
            }
        }

        override fun onError(controller: MediaController, sessionError: androidx.media3.session.SessionError) {
            Log.e(TAG, "MediaController session error: ${sessionError.message}")
            _state.value = _state.value.copy(
                connected = false,
                error = "Player session error: ${sessionError.message}"
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isPlaying = controller?.isPlaying == true
            )
        }

        /** Fires when Media3 auto-advances between chapter clips in the playlist (natural
         *  chapter transitions) — NOT on manual seeks (those are caught by the next poll tick
         *  via [updateStateFromController]). */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val ctrl = controller ?: return
            val chapters = _state.value.chapters
            val idx = ctrl.currentMediaItemIndex
            _state.value = _state.value.copy(
                currentChapterIndex = if (chapters.isNotEmpty()) idx else -1,
                currentChapterTitle = chapters.getOrNull(idx)?.title
            )
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            _state.value = _state.value.copy(
                currentChapterTitle = mediaMetadata.title?.toString()
            )
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            _state.value = _state.value.copy(error = "Playback error: ${error.message}")
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun ensureConnected() {
        if (connected || controllerFuture != null || controller != null) return
        val token = SessionToken(appContext, serviceComponent)
        val future = MediaController.Builder(appContext, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        Futures.addCallback(
            future,
            object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController) { /* handoff is in ensureControllerAwaited */ }
                override fun onFailure(e: Throwable) {
                    Log.e(TAG, "MediaController build failed: ${e.message}", e)
                    if (controllerFuture === future) {
                        controllerFuture = null
                        connected = false
                        _state.value = _state.value.copy(
                            connected = false,
                            error = "Unable to connect to the player service."
                        )
                    }
                }
            },
            directExecutor
        )
    }

    private suspend fun ensureControllerAwaited(): MediaController? {
        controller?.let { return it }
        if (controllerFuture == null) ensureConnected()
        val future = controllerFuture ?: return null
        val ctrl = future.awaitCancellable() ?: return null
        return withContext(Dispatchers.Main) {
            if (controllerFuture !== future) {
                Log.w(TAG, "Controller built but superseded; releasing stale controller.")
                MediaController.releaseFuture(future)
                null
            } else {
                controller = ctrl
                connected = true
                ctrl.addListener(playerListener)
                _state.value = _state.value.copy(connected = true)
                pollPosition()
                ctrl
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.awaitCancellable(): T? =
        suspendCancellableCoroutine { cont ->
            Futures.addCallback(
                this,
                object : FutureCallback<T> {
                    override fun onSuccess(result: T) { if (cont.isActive) cont.resume(result) }
                    override fun onFailure(e: Throwable) { if (cont.isActive) cont.resume(null) }
                },
                directExecutor
            )
            cont.invokeOnCancellation { this.cancel(true) }
        }

    // ── Play ───────────────────────────────────────────────────────────────────

    fun play(
        ratingKey: String,
        chapters: List<Chapter> = emptyList(),
        startPositionMsOverride: Long? = null
    ) {
        playJob?.cancel()
        _state.value = _state.value.copy(isStarting = true, error = null)

        playJob = scope.launch {
            val me = coroutineContext[Job]
            try {
                try {
                    resumeDeferred?.complete(null)
                    completedRestartDeferred?.complete(null)

                    if (!connected) ensureConnected()

                    val cached = repository.getCachedBook(ratingKey)
                    val download = repository.getDownload(ratingKey)

                    // Completed-book replay: always ask, never auto-decide. Restarting always
                    // means position 0 — folded into effectiveStartOverride so it flows through
                    // the SAME code paths as an explicit caller override below (skips local/
                    // server-resume lookups entirely, exactly like any other override).
                    var effectiveStartOverride = startPositionMsOverride
                    if (cached?.completed == true) {
                        val deferred = CompletableDeferred<Boolean?>()
                        completedRestartDeferred = deferred
                        _state.value = _state.value.copy(
                            showCompletedRestartPrompt = CompletedRestartPrompt(
                                ratingKey = ratingKey,
                                title = cached.title
                            )
                        )
                        val restart = try {
                            deferred.await()
                        } finally {
                            if (completedRestartDeferred === deferred) {
                                completedRestartDeferred = null
                                if (_state.value.showCompletedRestartPrompt != null) {
                                    _state.value = _state.value.copy(showCompletedRestartPrompt = null)
                                }
                            }
                        }
                        if (restart != true) {
                            // Dismissed or declined — abort, no playback.
                            return@launch
                        }
                        repository.setCompleted(ratingKey, false)
                        effectiveStartOverride = 0L
                    }

                    val localPosition = if (effectiveStartOverride != null) null else repository.getProgress(ratingKey)
                    val serverPosition = cached?.viewOffset?.takeIf { it > 0 } ?: 0L
                    val isServerResume = effectiveStartOverride == null &&
                            localPosition == null && serverPosition > 0

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
                                if (_state.value.showResumePrompt != null) {
                                    _state.value = _state.value.copy(showResumePrompt = null)
                                }
                            }
                        }
                        if (resumeChoice == null) {
                            return@launch
                        }
                    }

                    val rawPosition = when {
                        effectiveStartOverride != null -> effectiveStartOverride
                        localPosition != null -> localPosition
                        isServerResume -> if (resumeChoice == true) serverPosition else 0L
                        else -> localPosition ?: serverPosition.takeIf { it > 0 } ?: 0L
                    }
                    val bookDuration = download?.durationMs ?: cached?.durationMs ?: 0L

                    // Kept as a redundant safety net — should be unreachable now that completed
                    // books are always routed through the explicit prompt above (which already
                    // forces rawPosition to 0), but harmless if `cached` is ever stale.
                    val isCompleted = cached?.completed == true
                    val startPositionMs = if (isCompleted && rawPosition >= bookDuration - 30_000) 0L else rawPosition
                    if (isCompleted && startPositionMs == 0L) {
                        repository.setCompleted(ratingKey, false)
                    }
                    if (cached?.shelved == true) {
                        repository.setShelved(ratingKey, false)
                    }

                    // ─── TWO-PHASE PLAY (DB v5) ────────────────────────────────────
                    //
                    // PHASE 1 (this block, all Room-local): read the merged detail cache,
                    // the chapter cache, and the download row; build displayBook; emit
                    // state + setMediaItems IMMEDIATELY if we have enough local data to
                    // start audio (isDownloaded-file OR cached stream URL). Playback
                    // begins in well under a second for any book we've seen before.
                    //
                    // PHASE 2 (background, after setMediaItems): hit fetchBookDetail()
                    // ONLY to refresh the cache and, if the local copy was incomplete,
                    // hot-upgrade chapters/metadata mid-playback without restarting audio.
                    //
                    // Falls THROUGH to the legacy inline fetch when we have no cached
                    // detail yet (first-ever play of a book with no network before start).

                    val detailCache = repository.getBookDetailCache(ratingKey)
                    val cachedChapters = repository.getCachedChapters(ratingKey)

                    val displayBook: AudioBook = run {
                        val baseThumb = detailCache?.thumbPath
                            ?: download?.thumbPath
                            ?: cached?.thumbPath
                        val baseDuration = detailCache?.durationMs?.takeIf { it > 0 }
                            ?: download?.durationMs?.takeIf { it > 0 }
                            ?: cached?.durationMs
                            ?: 0L
                        val baseMediaKey = detailCache?.mediaPartKey
                            ?: download?.mediaPartKey
                            ?: cached?.mediaPartKey
                        val baseAllParts = detailCache?.allPartKeysCsv
                            ?.split(",")?.filter { it.isNotBlank() }
                            ?: download?.let { listOf(it.mediaPartKey) }
                            ?: emptyList()

                        when {
                            download != null -> AudioBook(
                                ratingKey = ratingKey,
                                title = download.title,
                                author = download.author,
                                summary = cached?.summary,
                                thumbPath = baseThumb,
                                duration = baseDuration,
                                viewOffset = startPositionMs,
                                addedAt = cached?.addedAt ?: 0L,
                                mediaKey = baseMediaKey,
                                isDownloaded = true,
                                downloadedPath = download.localFilePath,
                                allPartKeys = baseAllParts,
                                trackRatingKey = detailCache?.trackRatingKey,
                                trackDurationMs = detailCache?.trackDurationMs ?: 0L
                            )
                            cached != null -> AudioBook(
                                ratingKey = ratingKey,
                                title = cached.title,
                                author = cached.author,
                                summary = cached.summary,
                                thumbPath = baseThumb,
                                duration = baseDuration,
                                viewOffset = startPositionMs,
                                addedAt = cached.addedAt,
                                mediaKey = baseMediaKey,
                                allPartKeys = baseAllParts,
                                trackRatingKey = detailCache?.trackRatingKey,
                                trackDurationMs = detailCache?.trackDurationMs ?: 0L
                            )
                            else -> {
                                _state.value = _state.value.copy(error = "Book not found.")
                                return@launch
                            }
                        }
                    }

                    // Merge order: caller-supplied > Room cache > (Phase 2 network fill).
                    val localChapters =
                        if (chapters.isNotEmpty()) chapters else cachedChapters

                    // Can we start playback WITHOUT touching the network?
                    //  - downloaded book with a file on disk → always
                    //  - non-downloaded: needs a resolvable stream URL (part key + server URL)
                    val canStartLocally = displayBook.isDownloaded &&
                            !displayBook.downloadedPath.isNullOrEmpty()
                    val canStream = !canStartLocally &&
                            !displayBook.mediaKey.isNullOrBlank() &&
                            session.serverUrl != null
                    val startImmediately = canStartLocally || canStream

                    if (startImmediately) {
                        // ── FAST PATH: emit state + start playback from local data ─────

                        // Resolve the server URL ONLY if we're about to stream — and only
                        // as a background refresh. We do NOT block on it here.
                        if (canStream) {
                            scope.launch(Dispatchers.IO) {
                                try { repository.resolveAndRefreshServerUrl() }
                                catch (e: Exception) { /* logged by repo */ }
                            }
                        }

                        // Chapters flow to the service via Room (loaded in onAddMediaItems).
                        // No companion-object relay needed.
                        if (localPosition == null && startPositionMs > 0) {
                            repository.saveProgress(
                                ratingKey, displayBook.title, displayBook.author,
                                startPositionMs, displayBook.duration
                            )
                        }

                        _state.value = _state.value.copy(
                            book = displayBook,
                            chapters = localChapters,
                            positionMs = startPositionMs,
                            bookDurationMs = displayBook.duration,
                            playbackSpeed = session.playbackSpeed,
                            currentChapterIndex = localChapters
                                .indexOfLast { startPositionMs >= it.startMs }
                                .let { if (it >= 0) it else -1 },
                            isOffline = displayBook.isDownloaded,
                            error = null
                        )

                        val timelineKey = displayBook.trackRatingKey ?: ratingKey
                        val thumbUrl = session.buildThumbUrl(displayBook.thumbPath)

                        val ctrl = ensureControllerAwaited() ?: run {
                            _state.value = _state.value.copy(error = "Player not available. Try again.")
                            return@launch
                        }
                        val startIdx = if (localChapters.isNotEmpty())
                            localChapters.indexOfLast { startPositionMs >= it.startMs }.coerceAtLeast(0)
                        else 0
                        val startRel = if (localChapters.isNotEmpty())
                            (startPositionMs - localChapters[startIdx].startMs).coerceAtLeast(0L)
                        else startPositionMs

                        val items: List<MediaItem> = if (localChapters.isEmpty()) {
                            listOf(buildChapterMediaItem(
                                ratingKey, timelineKey, displayBook, thumbUrl,
                                chapterIndex = 0, chapterTitle = displayBook.title,
                                startMs = 0L, endMs = displayBook.duration
                            ))
                        } else {
                            localChapters.mapIndexed { idx, ch ->
                                buildChapterMediaItem(
                                    ratingKey, timelineKey, displayBook, thumbUrl,
                                    chapterIndex = idx, chapterTitle = ch.title,
                                    startMs = ch.startMs, endMs = ch.endMs
                                )
                            }
                        }
                        if (items.any { it == MediaItem.EMPTY }) {
                            _state.value = _state.value.copy(
                                error = "Could not build a stream URL for this book."
                            )
                            return@launch
                        }

                        ctrl.setMediaItems(items, startIdx, startRel)
                        ctrl.prepare()
                        ctrl.play()

                        // ── PHASE 2: refresh metadata in the background ──────────────
                        // Update the Room caches (chapters + detail) so the NEXT play is
                        // fully local. If the chapter list we just used was missing or
                        // wrong AND the book hasn't started playing far, refresh the
                        // playlist with authoritative chapters without audible disruption.
                        launchEnrichment(ratingKey, displayBook, startPositionMs, localChapters)
                    } else {
                        // ── FALLBACK: no local detail yet — must hit the network first ─
                        // This is the first-ever play of a book (or a streaming book with
                        // no cached part key). We resolve the server URL and fetch detail
                        // synchronously, then proceed exactly like the fast path.
                        try {
                            repository.resolveAndRefreshServerUrl()
                        } catch (e: Exception) {
                            _state.value = _state.value.copy(error = "Cannot reach server: ${e.message}")
                            return@launch
                        }

                        val detailBook: Pair<AudioBook, List<Chapter>>? = try {
                            when (val result = repository.fetchBookDetail(ratingKey)) {
                                is Result.Success -> result.data
                                else -> null
                            }
                        } catch (e: Exception) {
                            null
                        }
                        val finalChapters = chapters.ifEmpty {
                            cachedChapters.ifEmpty { detailBook?.second ?: emptyList() }
                        }
                        val finalBook = if (detailBook != null) {
                            val d = detailBook.first
                            displayBook.copy(
                                mediaKey = displayBook.mediaKey ?: d.mediaKey,
                                allPartKeys = displayBook.allPartKeys.ifEmpty { d.allPartKeys },
                                trackRatingKey = displayBook.trackRatingKey ?: d.trackRatingKey,
                                trackDurationMs = if (displayBook.trackDurationMs > 0)
                                    displayBook.trackDurationMs else d.trackDurationMs,
                                duration = if (d.duration > 0) d.duration else displayBook.duration
                            )
                        } else displayBook

                        if (finalBook.mediaKey.isNullOrBlank() && finalBook.downloadedPath.isNullOrEmpty()) {
                            _state.value = _state.value.copy(
                                error = "Could not build a stream URL for this book."
                            )
                            return@launch
                        }

                        // Chapters flow to the service via Room (loaded in onAddMediaItems).
                        if (localPosition == null && startPositionMs > 0) {
                            repository.saveProgress(
                                ratingKey, finalBook.title, finalBook.author,
                                startPositionMs, finalBook.duration
                            )
                        }

                        _state.value = _state.value.copy(
                            book = finalBook,
                            chapters = finalChapters,
                            positionMs = startPositionMs,
                            bookDurationMs = finalBook.duration,
                            playbackSpeed = session.playbackSpeed,
                            currentChapterIndex = finalChapters
                                .indexOfLast { startPositionMs >= it.startMs }
                                .let { if (it >= 0) it else -1 },
                            isOffline = finalBook.isDownloaded,
                            error = null
                        )

                        val timelineKey = finalBook.trackRatingKey ?: ratingKey
                        val thumbUrl = session.buildThumbUrl(finalBook.thumbPath)

                        val ctrl = ensureControllerAwaited() ?: run {
                            _state.value = _state.value.copy(error = "Player not available. Try again.")
                            return@launch
                        }
                        val startIdx = if (finalChapters.isNotEmpty())
                            finalChapters.indexOfLast { startPositionMs >= it.startMs }.coerceAtLeast(0)
                        else 0
                        val startRel = if (finalChapters.isNotEmpty())
                            (startPositionMs - finalChapters[startIdx].startMs).coerceAtLeast(0L)
                        else startPositionMs

                        val items: List<MediaItem> = if (finalChapters.isEmpty()) {
                            listOf(buildChapterMediaItem(
                                ratingKey, timelineKey, finalBook, thumbUrl,
                                chapterIndex = 0, chapterTitle = finalBook.title,
                                startMs = 0L, endMs = finalBook.duration
                            ))
                        } else {
                            finalChapters.mapIndexed { idx, ch ->
                                buildChapterMediaItem(
                                    ratingKey, timelineKey, finalBook, thumbUrl,
                                    chapterIndex = idx, chapterTitle = ch.title,
                                    startMs = ch.startMs, endMs = ch.endMs
                                )
                            }
                        }
                        if (items.any { it == MediaItem.EMPTY }) {
                            _state.value = _state.value.copy(
                                error = "Could not build a stream URL for this book."
                            )
                            return@launch
                        }

                        ctrl.setMediaItems(items, startIdx, startRel)
                        ctrl.prepare()
                        ctrl.play()
                    }
                } catch (e: Exception) {
                    // Diagnostic safety net: this scope previously had no catch at all, so any
                    // exception here (e.g. an invalid ClippingConfiguration range) propagated
                    // uncaught and killed the process silently — no toast, no log, no error
                    // state. This turns that into a visible, logged failure instead. If this
                    // ever fires, the logcat line below has the real exception to diagnose from.
                    Log.e(TAG, "play() failed: ${e.message}", e)
                    _state.value = _state.value.copy(error = "Playback failed to start: ${e.message}")
                }
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
     * Builds one chapter's thin MediaItem: resolves the local-vs-stream URL for THIS
     * chapter's range, and packs the chapter's index/title/start/end into extras for the
     * service to read in resolveThinItem. Returns [MediaItem.EMPTY] if no usable stream URL
     * could be built — the caller checks for this and surfaces an error instead of starting.
     */
    private suspend fun buildChapterMediaItem(
        ratingKey: String,
        timelineKey: String,
        book: AudioBook,
        thumbUrl: String?,
        chapterIndex: Int,
        chapterTitle: String,
        startMs: Long,
        endMs: Long
    ): MediaItem {
        val streamUrl = buildStreamUrl(book, ratingKey, startMs) ?: return MediaItem.EMPTY

        val extras = Bundle().apply {
            putString(AudiobookPlaybackService.X_RATING_KEY, ratingKey)
            putString(AudiobookPlaybackService.X_TIMELINE_KEY, timelineKey)
            putString(AudiobookPlaybackService.X_STREAM_URL, streamUrl)
            putString(AudiobookPlaybackService.X_TITLE, book.title)
            putString(AudiobookPlaybackService.X_AUTHOR, book.author)
            putString(AudiobookPlaybackService.X_THUMB_URL, thumbUrl)
            putFloat(AudiobookPlaybackService.X_SPEED, session.playbackSpeed)
            putString(AudiobookPlaybackService.X_PART_KEYS, book.allPartKeys.joinToString(","))
            putLong(AudiobookPlaybackService.X_DURATION_MS, book.duration)
            putInt(AudiobookPlaybackService.X_CHAPTER_INDEX, chapterIndex)
            putString(AudiobookPlaybackService.X_CHAPTER_TITLE, chapterTitle)
            putLong(AudiobookPlaybackService.X_CHAPTER_START_MS, startMs)
            putLong(AudiobookPlaybackService.X_CHAPTER_END_MS, endMs)
        }

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(book.title)          // notification large line: book title
            .setArtist(chapterTitle)       // notification subtitle line: chapter title (was author)
            .setAlbumTitle(book.title)
            .setSubtitle(chapterTitle)
            .setExtras(extras)
        if (thumbUrl != null) metaBuilder.setArtworkUri(Uri.parse(thumbUrl))

        return MediaItem.Builder()
            .setMediaId("$ratingKey#$chapterIndex")
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    /**
     * Routes local file vs. server stream for a given chapter's start position — called once
     * per CHAPTER now instead of once per book.
     */
    private suspend fun buildStreamUrl(book: AudioBook, ratingKey: String, positionMs: Long): String? {
        if (book.isDownloaded && !book.downloadedPath.isNullOrEmpty()) {
            val download = repository.getDownload(ratingKey)
            val cachedUpTo = download?.downloadedUpToMs ?: 0L
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

    // ── Resume / restart prompts ────────────────────────────────────────────────

    fun confirmResume(resume: Boolean) {
        resumeDeferred?.complete(resume)
    }

    fun cancelResumePrompt() {
        resumeDeferred?.complete(null)
    }

    /** Called by the host when the user answers the completed-book restart dialog.
     *  `restart == true` → restart from 0; `false` → abort, no playback. */
    fun confirmCompletedRestart(restart: Boolean) {
        completedRestartDeferred?.complete(restart)
    }

    /** Called by the host when the completed-restart dialog is dismissed without an explicit
     *  choice (back press, tap outside) — treated the same as declining. */
    fun cancelCompletedRestartPrompt() {
        completedRestartDeferred?.complete(null)
    }

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
        val ctrl = controller
        if (ctrl == null) { ensureConnected(); return }
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun pause() { controller?.pause() }
    fun resume() { controller?.play() }

    /** Seek to a BOOK-absolute position. Translates into (chapterIndex, clip-relative
     *  position) and issues a cross-item `controller.seekTo(index, pos)`. Used by the
     *  seekbar and chapter-list tap. Clamped to [0, bookDurationMs]. */
    fun seekAbsolute(absoluteMs: Long) {
        val ctrl = controller ?: return
        val chapters = _state.value.chapters
        val maxMs = _state.value.bookDurationMs
        val target = if (maxMs > 0) absoluteMs.coerceIn(0L, maxMs) else absoluteMs.coerceAtLeast(0L)

        if (chapters.isEmpty()) {
            ctrl.seekTo(target)
            return
        }
        val idx = chapters.indexOfLast { target >= it.startMs }.coerceAtLeast(0)
        val relMs = (target - chapters[idx].startMs).coerceAtLeast(0L)
        ctrl.seekTo(idx, relMs)
    }

    /** Next chapter: native playlist advance. No-op at the last chapter. */
    fun nextChapter() {
        controller?.let { if (it.hasNextMediaItem()) it.seekToNext() }
    }

    /** Previous chapter: if more than 3s into the current chapter, restart it; otherwise
     *  jump to the previous playlist item. */
    fun previousChapter() {
        val ctrl = controller ?: return
        when {
            ctrl.currentPosition > 3000L -> ctrl.seekTo(0L)
            ctrl.hasPreviousMediaItem() -> ctrl.seekToPrevious()
            else -> ctrl.seekTo(0L)
        }
    }

    /** Skip by a signed delta of ms, clamped at the CURRENT CHAPTER's boundary — does NOT
     *  cross into the next/previous chapter (deliberate: a 30s skip 9s from a chapter's end
     *  lands at that chapter's end; the next chapter then starts naturally on its own). */
    fun skipBy(deltaMs: Long) {
        val ctrl = controller ?: return
        val s = _state.value
        val chapterDur = if (s.chapters.isNotEmpty()) s.chapterDurationMs else s.bookDurationMs
        val ceiling = chapterDur.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (ctrl.currentPosition + deltaMs).coerceIn(0L, ceiling)
        ctrl.seekTo(ctrl.currentMediaItemIndex, target)
    }

    fun setSpeed(speed: Float) {
        session.playbackSpeed = speed
        _state.value = _state.value.copy(playbackSpeed = speed)
        controller?.setPlaybackSpeed(speed)
    }

    /** Stop playback and reset state — used by sign-out and back-to-exit. Does NOT release
     *  the controller (it persists for the process lifetime); only [release] tears it down. */
    fun stop() {
        controller?.stop()
        pollJob?.cancel()
        _state.value = NowPlayingUiState()
    }

    // ── Position polling ──────────────────────────────────────────────────────

    private fun pollPosition() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                updateStateFromController()
                delay(500)
            }
        }
    }

    /**
     * Native controller position/duration are CHAPTER-RELATIVE (each playlist item is a clip
     * scoped to one chapter). Book-absolute position is reconstructed here as chapterStart +
     * clip-relative position — the single translation point in the whole app.
     */
    private fun updateStateFromController() {
        val ctrl = controller ?: run {
            _state.value = _state.value.copy(connected = false)
            return
        }
        if (!connected || ctrl.currentMediaItem == null) return

        val chapters = _state.value.chapters
        val chapterIdx = ctrl.currentMediaItemIndex
        val clipRelPos = ctrl.currentPosition.coerceAtLeast(0L)

        val chapterStart = chapters.getOrNull(chapterIdx)?.startMs ?: 0L
        val absPos = chapterStart + clipRelPos
        val chapterDur = chapters.getOrNull(chapterIdx)?.let { it.endMs - it.startMs }
            ?: ctrl.duration.coerceAtLeast(0L)

        _state.value = _state.value.copy(
            positionMs = absPos,
            currentChapterIndex = if (chapters.isNotEmpty()) chapterIdx else -1,
            chapterDurationMs = chapterDur,
            isPlaying = ctrl.isPlaying,
            isBuffering = ctrl.playbackState == Player.STATE_BUFFERING,
            connected = true
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called from MainActivity.onDestroy to disconnect cleanly. */
    fun release() {
        pollJob?.cancel()
        val future = controllerFuture
        val ctrl = controller
        controller = null
        controllerFuture = null
        connected = false
        ctrl?.removeListener(playerListener)
        if (future != null) {
            MediaController.releaseFuture(future)
        } else {
            ctrl?.release()
        }
    }

    companion object {
        private const val TAG = "PlaybackManager"
    }
}