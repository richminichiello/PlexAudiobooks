package com.plexaudiobooks.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.local.ContinueListeningItem
import com.plexaudiobooks.databinding.ItemContinueListeningBinding

class ContinueListeningAdapter(
    private val thumbUrlBuilder: (String?) -> String?,
    private val onBookClick: (ContinueListeningItem) -> Unit
) : ListAdapter<ContinueListeningItem, ContinueListeningAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemContinueListeningBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val b: ItemContinueListeningBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: ContinueListeningItem) {
            b.tvTitle.text = item.title
            b.tvAuthor.text = item.author ?: ""
            val progress = if (item.durationMs > 0)
                (item.positionMs * 100 / item.durationMs).toInt().coerceIn(0, 100) else 0
            b.progressBook.progress = progress

            Glide.with(b.root.context)
                .load(thumbUrlBuilder(item.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(b.ivCover)

            b.root.setOnClickListener { onBookClick(item) }
        }
    }

    object Diff : DiffUtil.ItemCallback<ContinueListeningItem>() {
        override fun areItemsTheSame(a: ContinueListeningItem, b: ContinueListeningItem) =
            a.ratingKey == b.ratingKey
        override fun areContentsTheSame(a: ContinueListeningItem, b: ContinueListeningItem) =
            a == b
    }
}
