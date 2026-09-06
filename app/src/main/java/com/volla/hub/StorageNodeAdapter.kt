package com.volla.hub

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemStorageNodeBinding

class StorageNodeAdapter(
    private val onClick: (StorageNode) -> Unit
) : RecyclerView.Adapter<StorageNodeAdapter.ViewHolder>() {

    private var nodes: List<StorageNode> = emptyList()
    private var totalBytes: Long = 1L

    fun submitList(newNodes: List<StorageNode>, total: Long) {
        nodes = newNodes.sortedByDescending { it.sizeBytes }
        totalBytes = total.coerceAtLeast(1L)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemStorageNodeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStorageNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = nodes[position]
        val percent = (node.sizeBytes.toFloat() / totalBytes.toFloat() * 100f)
        holder.binding.tvNodeName.text = if (node.isDirectory) node.name else node.name
        holder.binding.tvNodeSize.text = formatBytes(node.sizeBytes)
        holder.binding.tvNodePercent.text = String.format("%.1f%%", percent)
        holder.binding.progressBar.progress = percent.toInt().coerceIn(0, 100)
        holder.binding.progressBar.progressTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(node.category.colorHex))
        holder.binding.colorDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(node.category.colorHex))
        holder.binding.tvNodeCategory.text = holder.binding.root.context.getString(node.category.displayNameRes)
        holder.binding.root.setOnClickListener {
            if (node.isDirectory) onClick(node)
        }
        holder.binding.ivFolderIcon.visibility =
            if (node.isDirectory) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun getItemCount(): Int = nodes.size
}
