package com.plexaudiobooks.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.Result
import com.plexaudiobooks.data.local.CachedLibraryEntity
import com.plexaudiobooks.data.local.CachedLibraryPagingDao
import com.plexaudiobooks.data.local.ContinueListeningItem
import com.plexaudiobooks.data.local.DownloadedBookDao
import com.plexaudiobooks.data.local.PlaybackProgressDao
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PlexRepository,
    private val libraryPagingDao: CachedLibraryPagingDao,
    private val progressDao: PlaybackProgressDao,
    private val downloadDao: DownloadedBookDao,
    val session: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState

    // Reactive sort + hide-completed signal — emitting a new value re-creates the pager
    private val _sort = MutableStateFlow(session.librarySort)
    private val _hideCompleted = MutableStateFlow(session.hideCompleted)

    val sortMode: StateFlow<String> = _sort
    val hideCompleted: StateFlow<Boolean> = _hideCompleted

    private val pagingConfig = PagingConfig(
        pageSize = 20,
        prefetchDistance = 10,
        enablePlaceholders = false
    )

    // Set of downloaded ratingKeys — used to set the offline badge on book cards
    val downloadedKeys: Flow<Set<String>> = downloadDao.getAllDownloads()
        .map { list -> list.map { it.ratingKey }.toSet() }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedBooks: Flow<PagingData<BookDisplayItem>> =
        combine(_sort, _hideCompleted) { sort, hide -> Pair(sort, hide) }
            .flatMapLatest { (sort, hide) ->
                val source = when {
                    hide -> libraryPagingDao::getPagedExcludeCompleted
                    sort == "author"   -> libraryPagingDao::getPagedByAuthor
                    sort == "duration" -> libraryPagingDao::getPagedByDuration
                    sort == "added"    -> libraryPagingDao::getPagedByDateAdded
                    else               -> libraryPagingDao::getPagedByTitle
                }
                Pager(pagingConfig, pagingSourceFactory = source).flow
                    .map { pagingData ->
                        pagingData.map { entity ->
                            BookDisplayItem(
                                ratingKey      = entity.ratingKey,
                                title          = entity.title,
                                author         = entity.author,
                                thumbPath      = entity.thumbPath,
                                durationMs     = entity.durationMs,
                                progressPercent = if (entity.durationMs > 0)
                                    entity.viewOffset.toFloat() / entity.durationMs else 0f,
                                isDownloaded   = false  // updated reactively via downloadedKeys
                            )
                        }
                    }
            }
            .cachedIn(viewModelScope)

    // Continue Listening: books with progress, not finished, ordered by most recent
    val continueListening: Flow<List<ContinueListeningItem>> =
        libraryPagingDao.getContinueListening()

    // Completed books section
    val completedBooks: Flow<List<CachedLibraryEntity>> =
        libraryPagingDao.getCompleted()

    //val lastPlayed = progressDao.getLastPlayed()
    //val downloads = downloadDao.getAllDownloads()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LibraryUiState(isLoading = true)
            when (val result = repository.fetchLibrary(forceRefresh = true)) {
                is Result.Success -> _uiState.value = LibraryUiState(isLoading = false)
                is Result.Error   -> _uiState.value = LibraryUiState(
                    isLoading = false, error = result.message
                )
            }
        }
    }

    fun setSort(sort: String) {
        session.librarySort = sort
        _sort.value = sort
    }

    fun setHideCompleted(hide: Boolean) {
        session.hideCompleted = hide
        _hideCompleted.value = hide
    }

    fun markCompleted(ratingKey: String, completed: Boolean) {
        viewModelScope.launch {
            repository.setCompleted(ratingKey, completed)
        }
    }

    // "Put this book back on the shelf": remove from Continue Listening without
    // marking it completed. Resume position is preserved and the book stays in the
    // main browse grid. It returns to Continue Listening on the next play.
    fun markShelved(ratingKey: String, shelved: Boolean) {
        viewModelScope.launch {
            repository.setShelved(ratingKey, shelved)
        }
    }

    fun searchBooks(query: String): Flow<List<CachedLibraryEntity>> =
        repository.searchLibrary(query)

    fun buildThumbUrl(thumbPath: String?): String? = session.buildThumbUrl(thumbPath)
}
