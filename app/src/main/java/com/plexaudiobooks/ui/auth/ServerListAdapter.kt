package com.plexaudiobooks.ui.auth

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.plexaudiobooks.data.model.PlexResource
import com.plexaudiobooks.databinding.ItemServerBinding

class ServerListAdapter(
    private var onServerClick: (Any) -> Unit = {}
) : ListAdapter<PlexResource, ServerListAdapter.ViewHolder>(ServerDiff) {

    fun setOnClickListener(listener: (Any) -> Unit) {
        onServerClick = listener
    }

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

        fun bind(server: PlexResource) {
            binding.tvServerName.text = server.name

            // Show best available connection info
            val bestConnection = server.connections.minByOrNull { it.priority }
            binding.tvServerAddress.text = bestConnection?.uri ?: "Unknown address"

            // Connection type badge
            val isLocal = bestConnection?.local == true
            val isRelay = bestConnection?.relay == true
            binding.tvConnectionType.text = when {
                isLocal -> "Local"
                isRelay -> "Relay"
                else -> "Remote"
            }

            binding.root.setOnClickListener { onServerClick(server) }
        }
    }

    object ServerDiff : DiffUtil.ItemCallback<PlexResource>() {
        override fun areItemsTheSame(a: PlexResource, b: PlexResource) =
            a.clientIdentifier == b.clientIdentifier
        override fun areContentsTheSame(a: PlexResource, b: PlexResource) = a == b
    }
}
