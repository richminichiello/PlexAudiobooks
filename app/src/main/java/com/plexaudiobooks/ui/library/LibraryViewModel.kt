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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
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

    // Continue Listening: books with progress, not finished, ordered by most recent.
    //
    // getContinueListening() is a JOIN of cached_library ⋈ playback_progress. The JOIN can
    // emit a transient empty list in TWO situations:
    //   1. A library refresh (refreshCachedLibrary clears + re-inserts cached_library) — the
    //      @Transaction is supposed to batch the writes, but concurrent playback_progress
    //      saves plus large inserts leak the empty intermediate on some Room versions.
    //   2. An ordinary playback_progress save while NOT refreshing — the service's 10s loop
    //      and PlaybackManager.play()'s seed-progress write touch playback_progress, re-emit
    //      the JOIN, and can transiently produce []. This fires whenever the user interacts
    //      with the mini-player / player sheet (play/pause, seek, chapter taps).
    // The 1.6.3 fix only guarded case #1 (gated the hold on ui.isLoading), so opening the
    // mini-player and tapping in it blanked the section. A pull-to-refresh sometimes brought
    // rows back because the refresh path IS covered; the playback-save path was not.
    //
    // Suppress both: debounce the source so a rapid []→pop pair collapses to the pop, then
    // use a scan that bridges AT MOST ONE transient empty (holding the last non-empty list
    // for exactly one empty emission) so a genuine persistent empty still passes through —
    // finishing the last book removes it from the section instead of trapping a stale list.
    // distinctUntilChanged avoids redundant submitList calls.
    @OptIn(ExperimentalCoroutinesApi::class)
    val continueListening: Flow<List<ContinueListeningItem>> =
        libraryPagingDao.getContinueListening()
            .debounce(250)
            .combine(_uiState) { items, _ -> items }
            .scan(emptyList<ContinueListeningItem>() to false) { (held, heldOnce), items ->
                when {
                    items.isNotEmpty() -> items to false
                    // Source just emitted empty while we have a non-empty list to bridge with,
                    // and this is the FIRST empty in a row → hold the last real list so a
                    // one-emission transient gap (a mid-save re-emit) never blanks the section.
                    held.isNotEmpty() && !heldOnce -> held to true
                    // A SECOND consecutive empty (or a genuine empty with nothing to bridge):
                    // pass it through so a book that actually finished leaves the section.
                    else -> items to false
                }
            }
            .map { it.first }
            .distinctUntilChanged()

    // Completed books section
    val completedBooks: Flow<List<CachedLibraryEntity>> =
        libraryPagingDao.getCompleted()

    // Recently Added: newest 20 books, always sorted by addedAt DESC. Independent of the
    // library sort preference — the section is definitionally "recent", so reordering it
    // by title/author would defeat its purpose.
    val recentlyAdded: Flow<List<CachedLibraryEntity>> =
        libraryPagingDao.getRecentlyAdded()

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
            // Auto-evict a non-durable read-ahead cache when marking complete — but NEVER a
            // durable download (those persist across completion; a book downloaded for a
            // flight shouldn't vanish mid-flight). Only when completed == true: marking a
            // book UNREAD must not free a downloaded book (the user may be undoing an
            // accidental completion).
            if (completed) {
                val d = repository.getDownload(ratingKey)
                if (d != null && !d.durable) repository.deleteDownloadAndFile(ratingKey)
            }
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

    fun setContinueListeningCollapsed(v: Boolean) { session.clContinueListening = v }
    fun setRecentlyAddedCollapsed(v: Boolean)     { session.clRecentlyAdded = v }
    fun setCompletedCollapsed(v: Boolean)         { session.clCompleted = v }
    fun setMyLibraryCollapsed(v: Boolean)        { session.clMyLibrary = v }

    fun buildThumbUrl(thumbPath: String?): String? = session.buildThumbUrl(thumbPath)
}
