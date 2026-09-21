package com.volla.hub

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemAppCacheBinding

class AppCacheAdapter(
    private val onClick: (AppCacheInfo) -> Unit
) : RecyclerView.Adapter<AppCacheAdapter.ViewHolder>() {

    private var apps: List<AppCacheInfo> = emptyList()

    fun submitList(newApps: List<AppCacheInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemAppCacheBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppCacheBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.binding.ivAppIcon.setImageDrawable(app.icon)
        holder.binding.tvAppName.text = app.label
        holder.binding.tvAppPackage.text = app.packageName
        holder.binding.tvCacheSize.text = formatBytes(app.cacheBytes)
        holder.binding.root.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = apps.size
}
