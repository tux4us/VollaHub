package com.volla.hub

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemSelectedAppBinding

class SelectedAppAdapter(
    private val packageManager: PackageManager,
    private val onRemove: (AppInfo) -> Unit
) : RecyclerView.Adapter<SelectedAppAdapter.ViewHolder>() {

    private var selectedApps = mutableListOf<AppInfo>()

    fun setApps(newApps: List<AppInfo>) {
        selectedApps = newApps.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = selectedApps[position]
        holder.binding.tvAppName.text = app.name
        holder.binding.tvAppVersion.text = app.version
        
        try {
            val icon = packageManager.getApplicationIcon(app.packageName)
            holder.binding.ivAppIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.binding.btnRemove.setOnClickListener { onRemove(app) }
    }

    override fun getItemCount() = selectedApps.size

    class ViewHolder(val binding: ItemSelectedAppBinding) : RecyclerView.ViewHolder(binding.root)
}
