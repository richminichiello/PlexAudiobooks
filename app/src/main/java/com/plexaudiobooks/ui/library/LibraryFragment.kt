package com.plexaudiobooks.ui.library
//Anchor date: 6/4/2026 Time: 9:17AM ET
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.FragmentLibraryBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()

    private lateinit var continueHeader: ContinueListeningHeaderAdapter
    private lateinit var bookAdapter: BookAdapter
    private lateinit var completedSection: CompletedSectionAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var searchJob: Job? = null
    private var isGridMode = true

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
                findNavController().navigate(
                    LibraryFragmentDirections.actionLibraryToPlayer(item.ratingKey)
                )
            }
        )

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
            }
        )
        completedSection.isGridMode = isGridMode

        // ConcatAdapter: Continue Listening header | paged books | completed section
        // GridLayoutManager with span control so header/footer take full width
        // isolateViewTypes=true (default): each adapter has its own ViewHolder pool
        // This prevents view type clashes between header/book/completed adapters
        concatAdapter = ConcatAdapter(
            continueHeader,
            bookAdapter,
            completedSection
        )

        val layoutManager = makeLayoutManager()
        applySpanSizeLookup(layoutManager)

        binding.rvBooks.apply {
            adapter = concatAdapter
            this.layoutManager = layoutManager
        }

        lifecycleScope.launch {
            bookAdapter.loadStateFlow.collectLatest { states ->
                binding.swipeRefresh.isRefreshing =
                    states.refresh is LoadState.Loading
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
                { findNavController().navigate(R.id.action_library_to_downloads); true }
                R.id.action_settings  ->
                { findNavController().navigate(R.id.action_library_to_settings); true }
                else -> false
            }
        }
    }

    private fun toggleViewMode() {
        isGridMode = !isGridMode
        viewModel.session.libraryViewMode = if (isGridMode) "grid" else "list"
        bookAdapter.isGridMode = isGridMode
        continueHeader.isGridMode = isGridMode
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
                if (position == 0) return lm.spanCount
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
        lifecycleScope.launch {
            viewModel.continueListening.collect { items ->
                continueHeader.submitList(items)
            }
        }

        // Completed books
        lifecycleScope.launch {
            viewModel.completedBooks.collectLatest { items ->
                completedSection.submitList(items)
            }
        }

        // Paged main list
        performSearch("")

        // Offline badges
        lifecycleScope.launch {
            viewModel.downloadedKeys.collect { keys ->
                bookAdapter.downloadedKeys = keys
            }
        }

        // UI state
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                if (!state.isLoading) binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
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