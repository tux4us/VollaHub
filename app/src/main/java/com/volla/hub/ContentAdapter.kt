package com.volla.hub

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemContentBinding

class ContentAdapter(
    private val onItemClick: (ContentItem) -> Unit
) : ListAdapter<ContentItem, ContentAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemContentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(item: ContentItem) {
            val paddingStart = (item.level * 24).coerceAtMost(72)
            binding.root.setPadding(paddingStart, 16, 16, 16)

            binding.titleText.text = item.title
            binding.excerptText.text = if (item.excerpt.isNotEmpty()) item.excerpt
            else if (item.date.isNotEmpty()) item.date
            else ""

            binding.excerptText.visibility = if (binding.excerptText.text.isEmpty())
                android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ContentItem>() {
        override fun areItemsTheSame(oldItem: ContentItem, newItem: ContentItem) =
            oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: ContentItem, newItem: ContentItem) =
            oldItem == newItem
    }
}