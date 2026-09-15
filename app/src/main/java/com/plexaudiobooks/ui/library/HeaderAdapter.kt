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
import com.plexaudiobooks.databinding.HeaderMyLibraryBinding
import com.plexaudiobooks.databinding.HeaderRecentlyAddedBinding
import com.plexaudiobooks.databinding.ItemBookListBinding

/**
 * Shared contract for the three collapsible library sections. The owning ViewModel
 * persists the flag in SessionManager; the adapter holds the in-memory field and applies
 * it during bind. Collapsed = only the header row shows, content hidden.
 */
interface CollapsibleSection {
    var isCollapsed: Boolean
}


/**
 * A single-row adapter that holds the Continue Listening section.
 * In grid mode: horizontal carousel.
 * In list mode: vertical list using item_book_list.xml.
 */
class ContinueListeningHeaderAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (ContinueListeningItem) -> Unit,
    private val onBookLongClick: ((ContinueListeningItem) -> Unit)? = null,
    private val onCollapseToggle: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<ContinueListeningHeaderAdapter.VH>(), CollapsibleSection {

    private var items: List<ContinueListeningItem> = emptyList()
    private var innerAdapter: ContinueListeningAdapter? = null

    override var isCollapsed: Boolean = false
        set(value) {
            field = value
            notifyItemChanged(0)
        }

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
        //boundHolder = holder
        if (items.isEmpty()) {
            holder.itemView.visibility = View.GONE
            holder.itemView.layoutParams = RecyclerView.LayoutParams(0, 0)
        } else {
            holder.itemView.visibility = View.VISIBLE
            holder.itemView.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            holder.bind(items, isGridMode, isCollapsed)
        }
    }

    inner class VH(private val binding: HeaderContinueListeningBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(list: List<ContinueListeningItem>, gridMode: Boolean, collapsed: Boolean) {
            // Collapse toggle on the header row.
            val current = isCollapsed
            binding.headerRow.setOnClickListener { onCollapseToggle?.invoke(!current) }
            binding.ivCollapseChevron.rotation = if (current) -90f else 0f
            binding.ivCollapseChevron.contentDescription = binding.root.context.getString(
                if (current) R.string.expand_section else R.string.collapse_section
            )

            binding.rvContinueListening.visibility =
                if (!collapsed && gridMode) View.VISIBLE else View.GONE
            binding.listContainer.visibility =
                if (!collapsed && !gridMode) View.VISIBLE else View.GONE
            if (collapsed) return

            if (gridMode) {
                if (innerAdapter == null) {
                    val adapter = ContinueListeningAdapter(thumbUrlBuilder, onBookClick, onBookLongClick)
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
                    onBookLongClick?.let { handler ->
                        row.root.setOnLongClickListener { handler(item); true }
                    }
                    binding.listContainer.addView(row.root)
                }
            }
        }
    }
}

/**
 * Recently Added section: newest 20 books in the library, ordered by addedAt DESC.
 * Collapseable like the other two sections.
 */
class RecentlyAddedSectionAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (CachedLibraryEntity) -> Unit,
    private val onCollapseToggle: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<RecentlyAddedSectionAdapter.VH>(), CollapsibleSection {

    private var items: List<CachedLibraryEntity> = emptyList()
    private var innerAdapter: RecentlyAddedInnerAdapter? = null

    override var isCollapsed: Boolean = false
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    var isGridMode: Boolean = true
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    fun submitList(list: List<CachedLibraryEntity>) {
        val wasEmpty = items.isEmpty()
        items = list
        when {
            wasEmpty && list.isNotEmpty() -> notifyItemChanged(0)
            !wasEmpty && list.isEmpty()   -> notifyItemChanged(0)
            list.isNotEmpty()             -> notifyItemChanged(0)
        }
    }

    fun hasContent(): Boolean = items.isNotEmpty()

    override fun getItemCount() = if (items.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(HeaderRecentlyAddedBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items, isGridMode, isCollapsed)
    }

    inner class VH(private val binding: HeaderRecentlyAddedBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(list: List<CachedLibraryEntity>, gridMode: Boolean, collapsed: Boolean) {
            val current = isCollapsed
            binding.headerRow.setOnClickListener { onCollapseToggle?.invoke(!current) }
            binding.ivCollapseChevron.rotation = if (current) -90f else 0f
            binding.ivCollapseChevron.contentDescription = binding.root.context.getString(
                if (current) R.string.expand_section else R.string.collapse_section
            )

            binding.rvRecentlyAdded.visibility =
                if (!collapsed && gridMode) View.VISIBLE else View.GONE
            binding.listContainer.visibility =
                if (!collapsed && !gridMode) View.VISIBLE else View.GONE
            if (collapsed) return

            if (gridMode) {
                if (innerAdapter == null) {
                    innerAdapter = RecentlyAddedInnerAdapter(thumbUrlBuilder, onBookClick)
                    binding.rvRecentlyAdded.apply {
                        adapter = innerAdapter
                        layoutManager = LinearLayoutManager(
                            context, LinearLayoutManager.HORIZONTAL, false
                        )
                    }
                }
                innerAdapter?.submitList(list)
            } else {
                binding.listContainer.removeAllViews()
                list.forEach { entity ->
                    val row = ItemBookListBinding.inflate(
                        LayoutInflater.from(binding.listContainer.context),
                        binding.listContainer,
                        false
                    )
                    row.tvBookTitle.text = entity.title
                    row.tvBookAuthor.text = entity.author ?: ""
                    row.progressBook.progress = 0
                    row.tvOfflineBadge.visibility = View.GONE
                    Glide.with(binding.listContainer.context)
                        .load(thumbUrlBuilder(entity.thumbPath))
                        .placeholder(R.drawable.ic_book_placeholder)
                        .centerCrop()
                        .into(row.ivCover)
                    row.root.setOnClickListener { onBookClick(entity) }
                    binding.listContainer.addView(row.root)
                }
            }
        }
    }
}

/**
 * "My Library" section header — a pure header row (no content of its own; the content
 * is the Paging3 BookAdapter that follows it in the ConcatAdapter). Exists purely to
 * host the collapse toggle; the fragment gates the paged grid on its isCollapsed value.
 */
class MyLibraryHeaderAdapter(
    private val onCollapseToggle: (Boolean) -> Unit
) : RecyclerView.Adapter<MyLibraryHeaderAdapter.VH>(), CollapsibleSection {

    override var isCollapsed: Boolean = false
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(HeaderMyLibraryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.headerRow.setOnClickListener { onCollapseToggle(!isCollapsed) }
        holder.binding.ivCollapseChevron.rotation = if (isCollapsed) -90f else 0f
        holder.binding.ivCollapseChevron.contentDescription =
            holder.itemView.context.getString(
                if (isCollapsed) R.string.expand_section else R.string.collapse_section
            )
    }

    inner class VH(val binding: HeaderMyLibraryBinding) : RecyclerView.ViewHolder(binding.root)
}

// Inner adapter for the Recently Added horizontal carousel (grid mode).
private class RecentlyAddedInnerAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (CachedLibraryEntity) -> Unit,
) : androidx.recyclerview.widget.ListAdapter<CachedLibraryEntity,
        RecentlyAddedInnerAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        com.plexaudiobooks.databinding.ItemContinueListeningBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: com.plexaudiobooks.databinding.ItemContinueListeningBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(entity: CachedLibraryEntity) {
            b.tvTitle.text = entity.title
            b.tvAuthor.text = entity.author ?: ""
            b.progressBook.progress = 0
            Glide.with(b.root.context)
                .load(thumbUrlBuilder(entity.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(b.ivCover)
            b.root.setOnClickListener { onBookClick(entity) }
        }
    }

    object Diff : androidx.recyclerview.widget.DiffUtil.ItemCallback<CachedLibraryEntity>() {
        override fun areItemsTheSame(a: CachedLibraryEntity, b: CachedLibraryEntity) =
            a.ratingKey == b.ratingKey
        override fun areContentsTheSame(a: CachedLibraryEntity, b: CachedLibraryEntity) =
            a == b
    }
}

/**
 * A single-row adapter that holds the Completed section header + list.
 */
class CompletedSectionAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (CachedLibraryEntity) -> Unit,
    private val onMarkUnread: (CachedLibraryEntity) -> Unit,
    private val onCollapseToggle: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<CompletedSectionAdapter.VH>(), CollapsibleSection {

    private var items: List<CachedLibraryEntity> = emptyList()

    override var isCollapsed: Boolean = false
        set(value) {
            field = value
            if (items.isNotEmpty()) notifyItemChanged(0)
        }

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
        holder.bind(items, isGridMode, isCollapsed)
    }

    inner class VH(private val container: android.widget.LinearLayout) :
        RecyclerView.ViewHolder(container) {

        fun bind(list: List<CachedLibraryEntity>, gridMode: Boolean, collapsed: Boolean) {
            // Header row is the FIRST child of the container LinearLayout (inflated in
            // onCreateViewHolder). Wire the collapse toggle on it.
            val header = container.getChildAt(0)
            val chevron = header?.findViewById<android.widget.ImageView>(R.id.ivCollapseChevron)
            val row = header?.findViewById<View>(R.id.headerRow)
            val current = isCollapsed
            row?.setOnClickListener { onCollapseToggle?.invoke(!current) }
            chevron?.rotation = if (current) -90f else 0f
            chevron?.contentDescription = container.context.getString(
                if (current) R.string.expand_section else R.string.collapse_section
            )

            while (container.childCount > 1) container.removeViewAt(1)
            if (collapsed) return

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