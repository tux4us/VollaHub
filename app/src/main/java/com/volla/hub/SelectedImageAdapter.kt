package com.volla.hub

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemSelectedImageBinding

class SelectedImageAdapter(
    private val onRemove: (Uri) -> Unit
) : RecyclerView.Adapter<SelectedImageAdapter.ViewHolder>() {

    private var images = mutableListOf<Uri>()

    fun setImages(newImages: List<Uri>) {
        images = newImages.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = images[position]
        holder.binding.ivThumbnail.setImageURI(uri)
        holder.binding.btnRemove.setOnClickListener { onRemove(uri) }
    }

    override fun getItemCount() = images.size

    class ViewHolder(val binding: ItemSelectedImageBinding) : RecyclerView.ViewHolder(binding.root)
}