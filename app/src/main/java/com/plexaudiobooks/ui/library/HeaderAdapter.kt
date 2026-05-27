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
        val wasEmpty = items.isEmpty()
        items = list
        innerAdapter?.submitList(list)
        if (wasEmpty && list.isNotEmpty()) notifyItemInserted(0)
        else if (!wasEmpty && list.isEmpty()) notifyItemRemoved(0)
    }

    override fun getItemCount() = if (items.isEmpty()) 0 else 1

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

    override fun onBindViewHolder(holder: VH, position: Int) {}

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

    fun submitList(list: List<CachedLibraryEntity>) {
        val wasEmpty = items.isEmpty()
        items = list
        if (wasEmpty != list.isEmpty()) {
            if (wasEmpty) notifyItemInserted(0) else notifyItemRemoved(0)
        } else if (list.isNotEmpty()) {
            notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (items.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val header = LayoutInflater.from(parent.context)
            .inflate(R.layout.header_completed, parent, false)
        // We embed a small LinearLayout below the header for the completed books list
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
        holder.bind(items)
    }

    inner class VH(private val container: android.widget.LinearLayout) :
        RecyclerView.ViewHolder(container) {

        fun bind(list: List<CachedLibraryEntity>) {
            // Remove any previously added book rows (keep only the header at index 0)
            while (container.childCount > 1) container.removeViewAt(1)

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
