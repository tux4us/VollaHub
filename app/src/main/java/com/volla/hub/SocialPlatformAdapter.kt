package com.volla.hub

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemSocialPlatformBinding

data class SocialPlatform(
    val name: String,
    val url: String,
    @DrawableRes val iconRes: Int,
)

class SocialPlatformAdapter(
    private val platforms: List<SocialPlatform>,
    private val onItemClick: (SocialPlatform) -> Unit,
) : RecyclerView.Adapter<SocialPlatformAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSocialPlatformBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(platforms[position])
    }

    override fun getItemCount(): Int = platforms.size

    inner class ViewHolder(
        private val binding: ItemSocialPlatformBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(platforms[position])
                }
            }
        }

        fun bind(platform: SocialPlatform) {
            binding.nameText.text = platform.name
            binding.iconImage.setImageResource(platform.iconRes)
        }
    }
}
