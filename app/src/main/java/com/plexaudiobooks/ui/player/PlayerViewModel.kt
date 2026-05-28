package com.plexaudiobooks.ui.player
//This is my test comment in file PlayerViewModel.kt
import androidx.lifecycle.*
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.model.AudioBook
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class PlayerUiState(
    val book: AudioBook? = null,
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val downloadedUpToMs: Long = 0L,  // how many ms are cached locally (0 = full book or unknown)
    val playbackSpeed: Float = 1.0f,
    val isOffline: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: PlexRepository,
    val session: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private var positionPollJob: Job? = null

    // Called when user navigates to the player screen
    fun loadBook(ratingKey: String) {
        viewModelScope.launch {
            _uiState.value = PlayerUiState(isLoading = true)

            val download = repository.getDownload(ratingKey)
            val savedPosition = repository.getProgress(ratingKey) ?: 0L
            val speed = session.playbackSpeed

            // FIX 4: If a local download exists, emit playable state immediately —
            // don't wait for network. ExoPlayer starts from file:// instantly.
            if (download != null) {
                _uiState.value = PlayerUiState(
                    book = com.plexaudiobooks.data.model.AudioBook(
                        ratingKey = ratingKey,
                        title = download.title,
                        author = download.author,
                        summary = null,
                        thumbPath = download.thumbPath,
                        duration = download.durationMs,
                        viewOffset = savedPosition,
                        addedAt = 0L,
                        mediaKey = download.mediaPartKey,
                        isDownloaded = true,
                        downloadedPath = download.localFilePath
                    ),
                    chapters = emptyList(),   // chapters fetched below in background
                    positionMs = savedPosition,
                    durationMs = download.durationMs,
                    downloadedUpToMs = download.downloadedUpToMs,
                    playbackSpeed = speed,
                    isOffline = true,
                    isLoading = false
                )
                // Fetch fresh metadata in background to get chapters and sync progress
                viewModelScope.launch {
                    repository.resolveAndRefreshServerUrl()
                    val result = repository.fetchBookDetail(ratingKey)
                    if (result is com.plexaudiobooks.data.Result.Success) {
                        val (book, chapters) = result.data
                        // Keep downloadedUpToMs from the download entity
                        val cachedUpTo = _uiState.value.downloadedUpToMs
                        _uiState.value = _uiState.value.copy(
                            book = book.copy(
                                viewOffset = savedPosition,
                                isDownloaded = true,
                                downloadedPath = download.localFilePath
                            ),
                            chapters = chapters,
                            durationMs = book.duration,
                            downloadedUpToMs = cachedUpTo
                        )
                    }
                }
                return@launch
            }

            // No local download — resolve server URL then fetch over network
            repository.resolveAndRefreshServerUrl()

            when (val result = repository.fetchBookDetail(ratingKey)) {
                is com.plexaudiobooks.data.Result.Success -> {
                    val (book, chapters) = result.data
                    _uiState.value = PlayerUiState(
                        book = book.copy(viewOffset = savedPosition),
                        chapters = chapters,
                        positionMs = savedPosition,
                        durationMs = book.duration,
                        playbackSpeed = speed,
                        isOffline = false,
                        isLoading = false
                    )
                }
                is com.plexaudiobooks.data.Result.Error -> {
                    _uiState.value = PlayerUiState(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun buildStreamUrl(ratingKey: String): String? {
        val state = _uiState.value
        val book = state.book ?: return null

        // Use local file ONLY if the saved position is within the downloaded portion.
        // If the position is beyond what's cached (partial read-ahead), fall back to
        // streaming so ExoPlayer doesn't try to seek past end of the truncated file.
        if (state.isOffline && book.downloadedPath != null) {
            val downloadedUpToMs = state.downloadedUpToMs
            val positionMs = state.positionMs
            if (downloadedUpToMs <= 0 || positionMs <= downloadedUpToMs) {
                // Position is within cached portion — play local file
                return "file://${book.downloadedPath}"
            } else {
                // Position is beyond cached portion — stream from server
                android.util.Log.d("PlayerVM",
                    "Resume pos ${positionMs}ms > cached ${downloadedUpToMs}ms — streaming")
                _uiState.value = _uiState.value.copy(isOffline = false)
                val partKey = book.mediaKey ?: return null
                return session.buildStreamUrl(partKey)
            }
        }

        val partKey = book.mediaKey ?: return null
        return session.buildStreamUrl(partKey)
    }

    fun onPlaybackStarted() {
        _uiState.value = _uiState.value.copy(isPlaying = true)
    }

    fun onPlaybackPaused() {
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun updatePosition(positionMs: Long, durationMs: Long) {
        val chapters = _uiState.value.chapters
        val chapterIndex = chapters.indexOfLast { positionMs >= it.startMs }
        _uiState.value = _uiState.value.copy(
            positionMs = positionMs,
            durationMs = durationMs,
            currentChapterIndex = chapterIndex
        )
    }

    fun setSpeed(speed: Float) {
        session.playbackSpeed = speed
        _uiState.value = _uiState.value.copy(playbackSpeed = speed)
    }

    fun updateChapterIndex(index: Int) {
        if (index != _uiState.value.currentChapterIndex) {
            _uiState.value = _uiState.value.copy(currentChapterIndex = index)
        }
    }

    fun getCurrentChapter(): Chapter? {
        val state = _uiState.value
        return state.chapters.getOrNull(state.currentChapterIndex)
    }

    fun getNextChapterStart(): Long? {
        val state = _uiState.value
        return state.chapters.getOrNull(state.currentChapterIndex + 1)?.startMs
    }

    fun getPrevChapterStart(): Long? {
        val state = _uiState.value
        val idx = state.currentChapterIndex
        return if (idx > 0) state.chapters[idx - 1].startMs
        else state.chapters.getOrNull(0)?.startMs
    }

    fun buildThumbUrl(thumbPath: String?): String? = session.buildThumbUrl(thumbPath)

    override fun onCleared() {
        super.onCleared()
        positionPollJob?.cancel()
    }
}
