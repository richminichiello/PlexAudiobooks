package com.plexaudiobooks.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.local.CachedLibraryEntity
import com.plexaudiobooks.databinding.ItemBookListBinding

class CompletedAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (CachedLibraryEntity) -> Unit,
    private val onMarkUnread: (CachedLibraryEntity) -> Unit
) : ListAdapter<CachedLibraryEntity, CompletedAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBookListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val b: ItemBookListBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: CachedLibraryEntity) {
            b.tvBookTitle.text = item.title
            b.tvBookAuthor.text = item.author ?: ""
            b.progressBook.progress = 100
            b.ivCompleted.visibility = android.view.View.VISIBLE
            b.tvOfflineBadge.visibility = android.view.View.GONE

            Glide.with(b.root.context)
                .load(thumbUrlBuilder(item.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(b.ivCover)

            b.root.setOnClickListener { onBookClick(item) }
            b.root.setOnLongClickListener {
                onMarkUnread(item)
                true
            }
        }
    }

    object Diff : DiffUtil.ItemCallback<CachedLibraryEntity>() {
        override fun areItemsTheSame(a: CachedLibraryEntity, b: CachedLibraryEntity) =
            a.ratingKey == b.ratingKey
        override fun areContentsTheSame(a: CachedLibraryEntity, b: CachedLibraryEntity) =
            a == b
    }
}
