package com.plexaudiobooks.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * A ForwardingPlayer that wraps ExoPlayer and overrides position/duration
 * to be chapter-relative instead of book-relative.
 *
 * This is how Chronicle achieves chapter-level progress bars in the
 * notification and car display — the MediaSession reads getCurrentPosition()
 * and getDuration() from this player, so it naturally sees chapter progress.
 *
 * No media item replacement needed — we just change what position/duration
 * report. ExoPlayer internals are completely unaffected.
 */
class ChapterAwarePlayer(private val exoPlayer: ExoPlayer) : ForwardingPlayer(exoPlayer) {

    @Volatile private var chapterStartMs: Long = 0L
    @Volatile private var chapterDurationMs: Long = 0L

    fun updateChapter(startMs: Long, durationMs: Long) {
        chapterStartMs = startMs
        chapterDurationMs = durationMs
    }

    fun clearChapter() {
        chapterStartMs = 0L
        chapterDurationMs = 0L
    }

    override fun getCurrentPosition(): Long {
        val raw = exoPlayer.currentPosition
        return if (chapterDurationMs > 0) {
            (raw - chapterStartMs).coerceAtLeast(0L)
        } else {
            raw
        }
    }

    override fun getDuration(): Long {
        return if (chapterDurationMs > 0) chapterDurationMs
        else exoPlayer.duration
    }

    override fun getBufferedPosition(): Long {
        val raw = exoPlayer.bufferedPosition
        return if (chapterDurationMs > 0) {
            (raw - chapterStartMs).coerceAtLeast(0L)
        } else {
            raw
        }
    }

    // Seeking: translate chapter-relative seek to book-absolute before passing to ExoPlayer
    override fun seekTo(positionMs: Long) {
        val absolute = if (chapterDurationMs > 0) {
            (positionMs + chapterStartMs).coerceAtMost(exoPlayer.duration)
        } else {
            positionMs
        }
        exoPlayer.seekTo(absolute)
    }

    // Always use ExoPlayer's raw position for internal checks
    val absolutePosition: Long get() = exoPlayer.currentPosition
}
