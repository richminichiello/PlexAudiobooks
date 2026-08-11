package com.plexaudiobooks.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.model.AudioBook
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.service.BookDownloadWorker
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val book: AudioBook? = null,
    val chapters: List<Chapter> = emptyList(),
    val isDownloaded: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: PlexRepository,
    val session: SessionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState

    // Stored so startDownload() can pass all part keys to the worker
    private var allPartKeys: List<String> = emptyList()

    fun loadBook(ratingKey: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState(isLoading = true)
            val download = repository.getDownload(ratingKey)
            when (val result = repository.fetchBookDetail(ratingKey)) {
                is Result.Success -> {
                    val (book, chapters) = result.data
                    // Use allPartKeys from the book model (populated in fetchBookDetail).
                    // Falls back to mediaKey for backward compatibility.
                    allPartKeys = book.allPartKeys.ifEmpty { listOfNotNull(book.mediaKey) }
                    android.util.Log.d("DetailViewModel", "Book '${book.title}': ${allPartKeys.size} downloadable part(s): $allPartKeys")
                    _uiState.value = DetailUiState(
                        book = book.copy(isDownloaded = download != null),
                        chapters = chapters,
                        isDownloaded = download != null,
                        isLoading = false
                    )
                }
                is Result.Error -> {
                    _uiState.value = DetailUiState(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun startDownload() {
        val book = _uiState.value.book ?: return
        if (allPartKeys.isEmpty()) {
            android.util.Log.e("DetailViewModel", "Cannot download '${book.title}': no part keys found")
            _uiState.value = _uiState.value.copy(
                error = "Cannot download: no media parts found for this book. Try reloading the book details."
            )
            return
        }

        val request = BookDownloadWorker.buildRequest(
            ratingKey  = book.ratingKey,
            title      = book.title,
            author     = book.author,
            thumbPath  = book.thumbPath,
            partKeys   = allPartKeys,
            durationMs = book.duration,
            // Grab the WHOLE book (not the read-ahead hours-cap): pass the full duration as
            // the cache target so the worker treats anything >= contentLength as "download
            // full file" and ignores downloadHours. durable=true so the result persists
            // across completion (only read-ahead cache is auto-deleted on book end).
            targetCachedUpToMs = book.duration,
            durable  = true
        )
        WorkManager.getInstance(context).enqueue(request)
    }

    fun markDownloaded() {
        _uiState.value = _uiState.value.copy(isDownloaded = true)
    }

    fun deleteDownload() {
        val ratingKey = _uiState.value.book?.ratingKey ?: return
        viewModelScope.launch {
            WorkManager.getInstance(context).cancelAllWorkByTag(ratingKey)
            repository.deleteDownloadAndFile(ratingKey)  // frees the file too, not just the row
            _uiState.value = _uiState.value.copy(isDownloaded = false)
        }
    }

    fun buildThumbUrl(thumbPath: String?): String? = session.buildThumbUrl(thumbPath)
}
