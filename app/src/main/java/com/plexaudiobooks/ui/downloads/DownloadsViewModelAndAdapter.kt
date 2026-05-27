package com.plexaudiobooks.ui.downloads

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.PlexRepository
import com.plexaudiobooks.data.local.DownloadedBookEntity
import com.plexaudiobooks.databinding.ItemDownloadBinding
import com.plexaudiobooks.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: PlexRepository,
    private val session: SessionManager
) : ViewModel() {

    val downloads = repository.observeDownloads().asLiveData()

    fun deleteDownload(ratingKey: String) {
        viewModelScope.launch {
            repository.deleteDownload(ratingKey)
        }
    }

    fun buildThumbUrl(thumbPath: String?): String? = session.buildThumbUrl(thumbPath)

    fun getStorageSummary(downloads: List<DownloadedBookEntity>): String {
        val totalBytes = downloads.sumOf { it.fileSizeBytes }
        val mb = totalBytes / (1024 * 1024)
        return "$mb MB used · ${downloads.size} book${if (downloads.size != 1) "s" else ""} downloaded"
    }
}

// ── Adapter ──────────────────────────────────────────────────────────────────

class DownloadsAdapter(
    private val onPlay: (DownloadedBookEntity) -> Unit,
    private val onDelete: (DownloadedBookEntity) -> Unit,
    private val thumbUrlBuilder: (String?) -> String?
) : ListAdapter<DownloadedBookEntity, DownloadsAdapter.ViewHolder>(DownloadDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadedBookEntity) {
            binding.tvTitle.text = item.title
            binding.tvAuthor.text = item.author ?: ""

            val cachedHours = item.downloadedUpToMs / 3_600_000
            val cachedMins = (item.downloadedUpToMs % 3_600_000) / 60_000
            binding.tvCachedDuration.text = "~${cachedHours}h ${cachedMins}m available offline"

            val mb = item.fileSizeBytes / (1024 * 1024)
            binding.tvFileSize.text = "$mb MB"

            Glide.with(binding.root.context)
                .load(thumbUrlBuilder(item.thumbPath))
                .placeholder(R.drawable.ic_book_placeholder)
                .centerCrop()
                .into(binding.ivCover)

            binding.btnPlay.setOnClickListener { onPlay(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    object DownloadDiff : DiffUtil.ItemCallback<DownloadedBookEntity>() {
        override fun areItemsTheSame(a: DownloadedBookEntity, b: DownloadedBookEntity) =
            a.ratingKey == b.ratingKey
        override fun areContentsTheSame(a: DownloadedBookEntity, b: DownloadedBookEntity) = a == b
    }
}
