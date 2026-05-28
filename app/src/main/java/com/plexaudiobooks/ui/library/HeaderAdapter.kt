package com.plexaudiobooks.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.local.CachedLibraryEntity
import com.plexaudiobooks.data.local.ContinueListeningItem
import com.plexaudiobooks.databinding.HeaderContinueListeningBinding
import com.plexaudiobooks.databinding.ItemBookListBinding


/**
 * A single-row adapter that holds the Continue Listening horizontal carousel.
 * Used as the first adapter in a ConcatAdapter so the carousel sits above
 * the main paged grid/list without requiring NestedScrollView.
 */
class ContinueListeningHeaderAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (ContinueListeningItem) -> Unit
) : RecyclerView.Adapter<ContinueListeningHeaderAdapter.VH>() {

    private var items: List<ContinueListeningItem> = emptyList()
    private var innerAdapter: ContinueListeningAdapter? = null

    fun submitList(list: List<ContinueListeningItem>) {
        items = list
        innerAdapter?.submitList(list)
        notifyItemChanged(0)
    }

    fun hasContent(): Boolean = items.isNotEmpty()

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = HeaderContinueListeningBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val adapter = ContinueListeningAdapter(thumbUrlBuilder, onBookClick)
        innerAdapter = adapter
        binding.rvContinueListening.apply {
            this.adapter = adapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }
        adapter.submitList(items)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (items.isEmpty()) {
            holder.itemView.visibility = View.GONE
            holder.itemView.layoutParams = RecyclerView.LayoutParams(0, 0)
        } else {
            holder.itemView.visibility = View.VISIBLE
            holder.itemView.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
    }

    class VH(binding: HeaderContinueListeningBinding) :
        RecyclerView.ViewHolder(binding.root)
}

/**
 * A single-row adapter that holds the Completed section header + list.
 */
class CompletedSectionAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (CachedLibraryEntity) -> Unit,
    private val onMarkUnread: (CachedLibraryEntity) -> Unit
) : RecyclerView.Adapter<CompletedSectionAdapter.VH>() {

    private var items: List<CachedLibraryEntity> = emptyList()
    var isGridMode: Boolean = false
        set(value) {
            field = value
            if (items.isNotEmpty()) notifyItemChanged(0)
        }

    fun submitList(list: List<CachedLibraryEntity>) {
        val wasEmpty = items.isEmpty()
        items = list
        when {
            wasEmpty && list.isNotEmpty() -> notifyItemInserted(0)
            !wasEmpty && list.isEmpty()   -> notifyItemRemoved(0)
            list.isNotEmpty()             -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (items.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val header = LayoutInflater.from(parent.context)
            .inflate(R.layout.header_completed, parent, false)
        val container = android.widget.LinearLayout(parent.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(header)
        }
        return VH(container)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items, isGridMode)
    }

    inner class VH(private val container: android.widget.LinearLayout) :
        RecyclerView.ViewHolder(container) {

        fun bind(list: List<CachedLibraryEntity>, gridMode: Boolean) {
            while (container.childCount > 1) container.removeViewAt(1)

            if (gridMode) {
                // Grid mode: use a RecyclerView with GridLayoutManager so we get
                // the same item_book.xml layout that BookAdapter uses
                val rv = RecyclerView(container.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 2)
                    // Disable nested scrolling so the outer RecyclerView scrolls, not this one
                    isNestedScrollingEnabled = false
                }
                val adapter = CompletedGridAdapter(thumbUrlBuilder, onBookClick, onMarkUnread)
                rv.adapter = adapter
                adapter.submitList(list)
                container.addView(rv)
            } else {
                // List mode: inflate item_book_list.xml for each book directly into the container
                list.forEach { entity ->
                    val binding = ItemBookListBinding.inflate(
                        LayoutInflater.from(container.context), container, false
                    )
                    binding.tvBookTitle.text = entity.title
                    binding.tvBookAuthor.text = entity.author ?: ""
                    binding.progressBook.progress = 100
                    binding.ivCompleted.visibility = View.VISIBLE
                    binding.tvOfflineBadge.visibility = View.GONE
                    Glide.with(container.context)
                        .load(thumbUrlBuilder(entity.thumbPath))
                        .placeholder(R.drawable.ic_book_placeholder)
                        .centerCrop()
                        .into(binding.ivCover)
                    binding.root.setOnClickListener { onBookClick(entity) }
                    binding.root.setOnLongClickListener { onMarkUnread(entity); true }
                    container.addView(binding.root)
                }
            }
        }
    }
}
// Grid adapter for completed books — reuses item_book.xml same as BookAdapter
private class CompletedGridAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (CachedLibraryEntity) -> Unit,
    private val onMarkUnread: (CachedLibraryEntity) -> Unit
) : androidx.recyclerview.widget.ListAdapter<CachedLibraryEntity,
        CompletedGridAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(com.plexaudiobooks.databinding.ItemBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    inner class VH(private val b: com.plexaudiobooks.databinding.ItemBookBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(entity: CachedLibraryEntity) {
            b.tvBookTitle.text = entity.title
            b.tvBookAuthor.text = entity.author ?: ""
            b.tvOfflineBadge.visibility = View.GONE
            Glide.with(b.root.context)
                .load(thumbUrlBuilder(entity.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(b.ivCover)
            b.root.setOnClickListener { onBookClick(entity) }
            b.root.setOnLongClickListener { onMarkUnread(entity); true }
        }
    }

    object Diff : androidx.recyclerview.widget.DiffUtil.ItemCallback<CachedLibraryEntity>() {
        override fun areItemsTheSame(a: CachedLibraryEntity, b: CachedLibraryEntity) =
            a.ratingKey == b.ratingKey
        override fun areContentsTheSame(a: CachedLibraryEntity, b: CachedLibraryEntity) =
            a == b
    }
}