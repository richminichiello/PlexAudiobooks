package com.plexaudiobooks.ui.library
// Anchor comment Date:5/29/2026 Time: 4:15 PM
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.local.CachedLibraryEntity
import com.plexaudiobooks.data.local.ContinueListeningItem
import com.plexaudiobooks.databinding.HeaderContinueListeningBinding
import com.plexaudiobooks.databinding.ItemBookListBinding


/**
 * A single-row adapter that holds the Continue Listening section.
 * In grid mode: horizontal carousel.
 * In list mode: vertical list using item_book_list.xml.
 */
class ContinueListeningHeaderAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (ContinueListeningItem) -> Unit
) : RecyclerView.Adapter<ContinueListeningHeaderAdapter.VH>() {

    private var items: List<ContinueListeningItem> = emptyList()
    private var innerAdapter: ContinueListeningAdapter? = null
    private var boundHolder: VH? = null

    var isGridMode: Boolean = true
        set(value) {
            field = value
            notifyItemChanged(0)
        }

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
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        boundHolder = holder
        if (items.isEmpty()) {
            holder.itemView.visibility = View.GONE
            holder.itemView.layoutParams = RecyclerView.LayoutParams(0, 0)
        } else {
            holder.itemView.visibility = View.VISIBLE
            holder.itemView.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            holder.bind(items, isGridMode)
        }
    }

    inner class VH(private val binding: HeaderContinueListeningBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(list: List<ContinueListeningItem>, gridMode: Boolean) {
            if (gridMode) {
                // Horizontal carousel
                binding.rvContinueListening.visibility = View.VISIBLE
                binding.listContainer.visibility = View.GONE
                if (innerAdapter == null) {
                    val adapter = ContinueListeningAdapter(thumbUrlBuilder, onBookClick)
                    innerAdapter = adapter
                    binding.rvContinueListening.apply {
                        this.adapter = adapter
                        layoutManager = LinearLayoutManager(
                            context, LinearLayoutManager.HORIZONTAL, false
                        )
                    }
                }
                innerAdapter?.submitList(list)
            } else {
                // Vertical list
                binding.rvContinueListening.visibility = View.GONE
                binding.listContainer.visibility = View.VISIBLE
                binding.listContainer.removeAllViews()
                list.forEach { item ->
                    val row = ItemBookListBinding.inflate(
                        LayoutInflater.from(binding.listContainer.context),
                        binding.listContainer,
                        false
                    )
                    row.tvBookTitle.text = item.title
                    row.tvBookAuthor.text = item.author ?: ""
                    val progress = if (item.durationMs > 0)
                        ((item.positionMs.toFloat() / item.durationMs) * 100).toInt()
                    else 0
                    row.progressBook.progress = progress
                    row.tvOfflineBadge.visibility = View.GONE
                    Glide.with(binding.listContainer.context)
                        .load(thumbUrlBuilder(item.thumbPath))
                        .placeholder(R.drawable.ic_book_placeholder)
                        .centerCrop()
                        .into(row.ivCover)
                    row.root.setOnClickListener { onBookClick(item) }
                    binding.listContainer.addView(row.root)
                }
            }
        }
    }
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
                val rv = RecyclerView(container.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    layoutManager = GridLayoutManager(context, 2)
                    isNestedScrollingEnabled = false
                }
                val adapter = CompletedGridAdapter(thumbUrlBuilder, onBookClick, onMarkUnread)
                rv.adapter = adapter
                adapter.submitList(list)
                container.addView(rv)
            } else {
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