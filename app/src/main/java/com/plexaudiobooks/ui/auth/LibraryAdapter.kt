package com.plexaudiobooks.ui.auth

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.plexaudiobooks.data.model.PlexDirectory
import com.plexaudiobooks.databinding.ItemServerBinding

class LibraryAdapter(
    private val onLibraryClick: (PlexDirectory) -> Unit
) : ListAdapter<PlexDirectory, LibraryAdapter.ViewHolder>(LibraryDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(library: PlexDirectory) {
            binding.tvServerName.text = library.title
            binding.tvServerAddress.text = when (library.type) {
                "artist" -> "Music library"
                "album" -> "Album library"
                else -> library.type
            }
            binding.tvConnectionType.text = "Library"
            binding.root.setOnClickListener { onLibraryClick(library) }
        }
    }

    object LibraryDiff : DiffUtil.ItemCallback<PlexDirectory>() {
        override fun areItemsTheSame(a: PlexDirectory, b: PlexDirectory) = a.key == b.key
        override fun areContentsTheSame(a: PlexDirectory, b: PlexDirectory) = a == b
    }
}
