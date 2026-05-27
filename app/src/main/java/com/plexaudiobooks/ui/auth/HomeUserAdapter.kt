package com.plexaudiobooks.ui.auth

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.plexaudiobooks.R
import com.plexaudiobooks.data.model.PlexHomeUser
import com.plexaudiobooks.databinding.ItemHomeUserBinding

class HomeUserAdapter(
    private val onUserClick: (PlexHomeUser) -> Unit
) : ListAdapter<PlexHomeUser, HomeUserAdapter.ViewHolder>(UserDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemHomeUserBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(user: PlexHomeUser) {
            binding.tvUserName.text = user.title
            binding.tvUserType.text = when {
                user.admin -> "Admin"
                user.hasPassword || user.protected -> "PIN protected"
                user.restricted -> "Managed"
                else -> "Member"
            }

            Glide.with(binding.root.context)
                .load(user.avatarUrl)
                .placeholder(R.drawable.ic_book_placeholder)
                .circleCrop()
                .into(binding.ivAvatar)

            binding.root.setOnClickListener { onUserClick(user) }
        }
    }

    object UserDiff : DiffUtil.ItemCallback<PlexHomeUser>() {
        override fun areItemsTheSame(a: PlexHomeUser, b: PlexHomeUser) = a.id == b.id
        override fun areContentsTheSame(a: PlexHomeUser, b: PlexHomeUser) = a == b
    }
}
