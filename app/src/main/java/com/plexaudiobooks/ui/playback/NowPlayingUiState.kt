package com.plexaudiobooks.ui.playback

import com.plexaudiobooks.data.model.AudioBook
import com.plexaudiobooks.data.model.Chapter

/**
 * Single source of truth for Now Playing, observed by the mini-player, the expanded
 * player sheet, and the Now Playing tab. Produced by PlaybackManager.
 */
data class NowPlayingUiState(
    val book: AudioBook? = null,
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = -1,
    val currentChapterTitle: String? = null,
    val positionMs: Long = 0L,
    val chapterDurationMs: Long = 0L,
    val bookDurationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isOffline: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    /**
     * True from the moment [com.plexaudiobooks.ui.playback.PlaybackManager.play] is invoked
     * until that coroutine finishes (state emitted / play intent sent / play aborted). Drives
     * immediate mini-player visibility + a loading affordance so a tap shows feedback within a
     * frame instead of several seconds later (previously users re-tapped, spawning duplicate
     * play jobs). Cleared in the play() finally block.
     */
    val isStarting: Boolean = false,
    /**
     * Non-null when PlaybackManager.play() paused to ask the user whether to resume a
     * fresh-install book from the server-saved position or start from the beginning.
     * MainActivity observes this and shows the resume/start-over dialog, then calls
     * [com.plexaudiobooks.ui.playback.PlaybackManager.confirmResume]. Cleared (set back
     * to null) once a choice is made or the play is abandoned.
     */
    val showResumePrompt: ResumePrompt? = null
) {
    /** True when something is loaded OR a play is starting — drives mini-player visibility
     *  so the bar appears immediately on tap, before the book is resolved. */
    val hasContent: Boolean get() = book != null || isStarting

    /** Remaining time in the whole book, clamped at 0. */
    val bookRemainingMs: Long
        get() = (bookDurationMs - positionMs).coerceAtLeast(0L)
}

/**
 * Carries what the resume/start-over dialog needs. [formattedPosition] is a pre-built
 * human label (e.g. "1h 23m") formatted by PlaybackManager so the dialog host doesn't
 * need duration-format helpers itself.
 */
data class ResumePrompt(
    val ratingKey: String,
    val serverPositionMs: Long,
    val formattedPosition: String
)
