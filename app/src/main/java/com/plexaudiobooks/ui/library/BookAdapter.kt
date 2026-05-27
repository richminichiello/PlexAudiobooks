package com.plexaudiobooks.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.databinding.ItemBookBinding
import com.plexaudiobooks.databinding.ItemBookListBinding

class BookAdapter(
    private val onBookClick: (BookDisplayItem) -> Unit,
    private val onMarkCompleted: (BookDisplayItem) -> Unit = {},
    private val thumbUrlBuilder: (String?) -> String?
) : PagingDataAdapter<BookDisplayItem, RecyclerView.ViewHolder>(BookDiff) {

    var isGridMode: Boolean = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var downloadedKeys: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private const val VIEW_GRID = 0
        private const val VIEW_LIST = 1
    }

    override fun getItemViewType(position: Int) = if (isGridMode) VIEW_GRID else VIEW_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_GRID) {
            GridViewHolder(ItemBookBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ))
        } else {
            ListViewHolder(ItemBookListBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        val isDownloaded = item.ratingKey in downloadedKeys
        when (holder) {
            is GridViewHolder -> holder.bind(item, isDownloaded)
            is ListViewHolder -> holder.bind(item, isDownloaded)
        }
    }

    inner class GridViewHolder(private val b: ItemBookBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: BookDisplayItem, isDownloaded: Boolean) {
            b.tvBookTitle.text = item.title
            b.tvBookAuthor.text = item.author ?: ""
            b.progressBook.progress = (item.progressPercent * 100).toInt()
            b.tvOfflineBadge.visibility = if (isDownloaded) View.VISIBLE else View.GONE

            Glide.with(b.root.context)
                .load(thumbUrlBuilder(item.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(b.ivCover)

            b.root.setOnClickListener { onBookClick(item) }
            b.root.setOnLongClickListener { onMarkCompleted(item); true }
        }
    }

    inner class ListViewHolder(private val b: ItemBookListBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: BookDisplayItem, isDownloaded: Boolean) {
            b.tvBookTitle.text = item.title
            b.tvBookAuthor.text = item.author ?: ""
            b.progressBook.progress = (item.progressPercent * 100).toInt()
            b.tvOfflineBadge.visibility = if (isDownloaded) View.VISIBLE else View.GONE
            b.ivCompleted.visibility = View.GONE

            Glide.with(b.root.context)
                .load(thumbUrlBuilder(item.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(b.ivCover)

            b.root.setOnClickListener { onBookClick(item) }
            b.root.setOnLongClickListener { onMarkCompleted(item); true }
        }
    }

    object BookDiff : DiffUtil.ItemCallback<BookDisplayItem>() {
        override fun areItemsTheSame(a: BookDisplayItem, b: BookDisplayItem) =
            a.ratingKey == b.ratingKey
        override fun areContentsTheSame(a: BookDisplayItem, b: BookDisplayItem) = a == b
    }
}
