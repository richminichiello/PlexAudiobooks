package com.plexaudiobooks.ui.library
//Anchor date: 6/4/2026 Time: 9:17AM ET
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.FragmentLibraryBinding
import com.plexaudiobooks.ui.playback.PlaybackManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()
    @Inject lateinit var playbackManager: PlaybackManager

    private lateinit var continueHeader: ContinueListeningHeaderAdapter
    private lateinit var recentlyAddedSection: RecentlyAddedSectionAdapter
    private lateinit var myLibraryHeader: MyLibraryHeaderAdapter
    private lateinit var bookAdapter: BookAdapter
    private lateinit var completedSection: CompletedSectionAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var searchJob: Job? = null
    private var isGridMode = true
    private var currentSearchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isGridMode = viewModel.session.libraryViewMode == "grid"

        setupAdapters()
        setupToolbar()
        observeData()

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        continueHeader = ContinueListeningHeaderAdapter(
            thumbUrlBuilder = { viewModel.buildThumbUrl(it) },
            onBookClick = { item ->
                // Direct to player (fast resume), per the user's decision. The player is a
                // sheet now, not a nav destination — start playback and expand the sheet.
                playbackManager.play(item.ratingKey)
                (requireActivity() as? com.plexaudiobooks.ui.MainActivity)?.showPlayerSheet()
            },
            onBookLongClick = { item -> showContinueListeningBookMenu(item) },
            onCollapseToggle = { collapsed ->
                viewModel.setContinueListeningCollapsed(collapsed)
                continueHeader.isCollapsed = collapsed
            }
        )
        continueHeader.isCollapsed = viewModel.session.clContinueListening

        recentlyAddedSection = RecentlyAddedSectionAdapter(
            thumbUrlBuilder = { viewModel.buildThumbUrl(it) },
            onBookClick = { entity ->
                findNavController().navigate(
                    LibraryFragmentDirections.actionLibraryToDetail(entity.ratingKey)
                )
            },
            onCollapseToggle = { collapsed ->
                viewModel.setRecentlyAddedCollapsed(collapsed)
                recentlyAddedSection.isCollapsed = collapsed
            }
        )
        recentlyAddedSection.isCollapsed = viewModel.session.clRecentlyAdded
        recentlyAddedSection.isGridMode = isGridMode

        myLibraryHeader = MyLibraryHeaderAdapter { collapsed ->
            viewModel.setMyLibraryCollapsed(collapsed)
            myLibraryHeader.isCollapsed = collapsed
            performSearch(currentSearchQuery)
        }
        myLibraryHeader.isCollapsed = viewModel.session.clMyLibrary

        bookAdapter = BookAdapter(
            onBookClick = { book ->
                findNavController().navigate(
                    LibraryFragmentDirections.actionLibraryToDetail(book.ratingKey)
                )
            },
            onMarkCompleted = { book ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Mark as Read?")
                    .setMessage("\"${book.title}\" will be moved to the Completed section.")
                    .setPositiveButton("Mark Read") { _, _ ->
                        viewModel.markCompleted(book.ratingKey, true)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            thumbUrlBuilder = { viewModel.buildThumbUrl(it) }
        )
        bookAdapter.isGridMode = isGridMode
        continueHeader.isGridMode = isGridMode

        completedSection = CompletedSectionAdapter(
            thumbUrlBuilder = { viewModel.buildThumbUrl(it) },
            onBookClick = { entity ->
                findNavController().navigate(
                    LibraryFragmentDirections.actionLibraryToDetail(entity.ratingKey)
                )
            },
            onMarkUnread = { entity ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Mark as Unread?")
                    .setMessage("\"${entity.title}\" will be moved back to your library.")
                    .setPositiveButton("Mark Unread") { _, _ ->
                        viewModel.markCompleted(entity.ratingKey, false)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onCollapseToggle = { collapsed ->
                viewModel.setCompletedCollapsed(collapsed)
                completedSection.isCollapsed = collapsed
            }
        )
        completedSection.isGridMode = isGridMode
        completedSection.isCollapsed = viewModel.session.clCompleted

        // ConcatAdapter: Continue Listening header | paged books | completed section
        // GridLayoutManager with span control so header/footer take full width
        // isolateViewTypes=true (default): each adapter has its own ViewHolder pool
        // This prevents view type clashes between header/book/completed adapters
        concatAdapter = ConcatAdapter(
            continueHeader,
            recentlyAddedSection,
            myLibraryHeader,
            bookAdapter,
            completedSection
        )

        val layoutManager = makeLayoutManager()
        applySpanSizeLookup(layoutManager)

        binding.rvBooks.apply {
            adapter = concatAdapter
            this.layoutManager = layoutManager
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                bookAdapter.loadStateFlow.collectLatest { states ->
                    binding.swipeRefresh.isRefreshing =
                        states.refresh is LoadState.Loading
                }
            }
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        val toolbar = binding.toolbar
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                performSearch(q ?: ""); return true
            }
        })
        searchView?.setOnCloseListener { performSearch(""); false }

        toolbar.menu.findItem(R.id.action_hide_completed)?.isChecked =
            viewModel.session.hideCompleted

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_view_toggle  -> { toggleViewMode(); true }
                R.id.sort_title          -> { viewModel.setSort("title"); true }
                R.id.sort_author         -> { viewModel.setSort("author"); true }
                R.id.sort_duration       -> { viewModel.setSort("duration"); true }
                R.id.sort_added          -> { viewModel.setSort("added"); true }
                R.id.action_hide_completed -> {
                    val v = !viewModel.hideCompleted.value
                    item.isChecked = v
                    viewModel.setHideCompleted(v)
                    true
                }
                R.id.action_downloads ->
                { findNavController().navigate(R.id.downloadsFragment); true }
                R.id.action_settings  ->
                { findNavController().navigate(R.id.settingsFragment); true }
                else -> false
            }
        }
    }

    private fun toggleViewMode() {
        isGridMode = !isGridMode
        viewModel.session.libraryViewMode = if (isGridMode) "grid" else "list"
        bookAdapter.isGridMode = isGridMode
        continueHeader.isGridMode = isGridMode
        recentlyAddedSection.isGridMode = isGridMode
        completedSection.isGridMode = isGridMode
        val lm = makeLayoutManager()
        applySpanSizeLookup(lm)
        binding.rvBooks.layoutManager = lm
    }

    private fun applySpanSizeLookup(lm: androidx.recyclerview.widget.RecyclerView.LayoutManager) {
        if (lm !is GridLayoutManager) return
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (!::completedSection.isInitialized || !::concatAdapter.isInitialized) return 1
                // Continue Listening is always row 0; Recently Added row 1 when present;
                // My Library header is a single item immediately before the paged book grid.
                // Count the preceding one-item adapters so the math scales correctly whether
                // or not each section currently has items.
                var bookGridStart = 1 // continueHeader always occupies position 0
                if (recentlyAddedSection.hasContent()) bookGridStart += 1
                val myLibraryHeaderPos = bookGridStart
                bookGridStart += 1 // myLibraryHeader always occupies one row

                if (position == 0) return lm.spanCount                        // Continue Listening
                if (recentlyAddedSection.hasContent() && position == 1) return lm.spanCount
                if (position == myLibraryHeaderPos) return lm.spanCount         // "My Library" header
                val sc = completedSection.itemCount
                val total = concatAdapter.itemCount
                return if (sc > 0 && position >= total - sc) lm.spanCount else 1
            }
        }
    }

    private fun makeLayoutManager() =
        if (isGridMode) GridLayoutManager(requireContext(), 2)
        else LinearLayoutManager(requireContext())

    // ── Data observation ──────────────────────────────────────────────────────

    private fun observeData() {
        // Continue Listening
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.continueListening.collect { items ->
                    continueHeader.submitList(items)
                }
            }
        }

        // Recently added
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentlyAdded.collectLatest { items ->
                    recentlyAddedSection.submitList(items)
                }
            }
        }

        // Completed books
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.completedBooks.collectLatest { items ->
                    completedSection.submitList(items)
                }
            }
        }

        // Paged main list
        performSearch("")

        // Offline badges
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadedKeys.collect { keys ->
                    bookAdapter.downloadedKeys = keys
                }
            }
        }

        // UI state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    if (!state.isLoading) binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    // ── Continue Listening context menu ──────────────────────────────────────

    private fun showContinueListeningBookMenu(item: com.plexaudiobooks.data.local.ContinueListeningItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(arrayOf("Put this book back on the shelf", "Mark as Read")) { _, which ->
                when (which) {
                    0 -> viewModel.markShelved(item.ratingKey, true)
                    1 -> viewModel.markCompleted(item.ratingKey, true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performSearch(query: String) {
        currentSearchQuery = query
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (myLibraryHeader.isCollapsed) {
                // Collapse the grid to 0 rows; the header stays visible above it.
                bookAdapter.submitData(androidx.paging.PagingData.empty())
                return@launch
            }
            if (query.isBlank()) {
                viewModel.pagedBooks.collectLatest { bookAdapter.submitData(it) }
            } else {
                viewModel.searchBooks(query).collectLatest { entities ->
                    val keys = bookAdapter.downloadedKeys
                    bookAdapter.submitData(
                        androidx.paging.PagingData.from(
                            entities.map { it.toDisplayItem(it.ratingKey in keys) }
                        )
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

fun com.plexaudiobooks.data.local.CachedLibraryEntity.toDisplayItem(
    isDownloaded: Boolean = false
) = BookDisplayItem(
    ratingKey = ratingKey,
    title = title,
    author = author,
    thumbPath = thumbPath,
    durationMs = durationMs,
    progressPercent = if (durationMs > 0) viewOffset.toFloat() / durationMs else 0f,
    isDownloaded = isDownloaded
)