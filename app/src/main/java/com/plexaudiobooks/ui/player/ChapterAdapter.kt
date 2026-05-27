package com.plexaudiobooks.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.plexaudiobooks.R
import com.plexaudiobooks.data.model.Chapter
import com.plexaudiobooks.databinding.ItemChapterBinding

class ChapterAdapter(
    private val onChapterClick: (Chapter) -> Unit
) : ListAdapter<Chapter, ChapterAdapter.ViewHolder>(ChapterDiff) {

    private var currentChapterIndex: Int = -1

    fun setCurrentChapter(index: Int) {
        val old = currentChapterIndex
        currentChapterIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChapterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == currentChapterIndex)
    }

    inner class ViewHolder(private val binding: ItemChapterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chapter: Chapter, isCurrent: Boolean) {
            binding.tvChapterTitle.text = chapter.title
            binding.tvChapterTime.text = formatMs(chapter.startMs)

            val ctx = binding.root.context
            if (isCurrent) {
                binding.root.setBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.chapter_active_bg)
                )
                binding.tvChapterTitle.setTextColor(
                    ContextCompat.getColor(ctx, R.color.colorPrimary)
                )
                binding.ivChapterActive.visibility = android.view.View.VISIBLE
            } else {
                binding.root.setBackgroundColor(
                    ContextCompat.getColor(ctx, android.R.color.transparent)
                )
                binding.tvChapterTitle.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_primary)
                )
                binding.ivChapterActive.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener { onChapterClick(chapter) }
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    object ChapterDiff : DiffUtil.ItemCallback<Chapter>() {
        override fun areItemsTheSame(a: Chapter, b: Chapter) = a.id == b.id
        override fun areContentsTheSame(a: Chapter, b: Chapter) = a == b
    }
}
