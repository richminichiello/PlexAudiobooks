package com.plexaudiobooks.ui.playback

import com.plexaudiobooks.data.model.AudioBook
import com.plexaudiobooks.data.model.Chapter

/**
 * Single source of truth for Now Playing, observed by the mini-player and the expanded
 * player sheet. Produced by PlaybackManager.
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
    val showResumePrompt: ResumePrompt? = null,
    /**
     * Non-null when PlaybackManager.play() paused because the tapped book is marked
     * completed — the user is asked to explicitly confirm starting over rather than the
     * app silently guessing (from a saved-position/duration comparison) whether it's safe
     * to auto-restart. See [CompletedRestartPrompt] for why this replaced that heuristic.
     */
    val showCompletedRestartPrompt: CompletedRestartPrompt? = null
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

/**
 * Shown when the user taps play on a book already marked completed. PlaybackManager used
 * to auto-decide whether to restart from 0 by comparing the saved position against a
 * duration value that can itself be stale or mismatched (the same class of bug fixed once
 * already this project — see the duration-overlay fix in play()). That comparison could
 * silently misjudge "near the end" and hand a resume position to the chapter-clip playlist
 * that falls outside the resolved clip's range — the suspected cause of the replay-completed
 * -book crash. This prompt removes the heuristic entirely for this case: completed books
 * always ask, and always restart from 0 on confirmation. No resume-from-saved-position
 * option is offered — a completed book's saved position isn't a meaningful place to resume.
 */
data class CompletedRestartPrompt(
    val ratingKey: String,
    val title: String
)