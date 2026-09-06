package com.volla.hub

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volla.hub.databinding.ItemAppSelectionBinding

data class SelectableApp(
    val info: AppInfo,
    var isSelected: Boolean = false
)

class AppSelectionAdapter(
    private val packageManager: PackageManager
) : RecyclerView.Adapter<AppSelectionAdapter.ViewHolder>() {

    private var allApps = listOf<SelectableApp>()
    private var filteredApps = listOf<SelectableApp>()

    fun setApps(apps: List<SelectableApp>) {
        allApps = apps
        filteredApps = apps
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredApps = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { 
                it.info.name.contains(query, ignoreCase = true) || 
                it.info.packageName.contains(query, ignoreCase = true) 
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedApps(): List<AppInfo> {
        return allApps.filter { it.isSelected }.map { it.info }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val selectableApp = filteredApps[position]
        val app = selectableApp.info
        
        holder.binding.tvAppName.text = app.name
        holder.binding.tvAppPackage.text = app.packageName
        
        try {
            val icon = packageManager.getApplicationIcon(app.packageName)
            holder.binding.ivAppIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.binding.cbSelected.setOnCheckedChangeListener(null)
        holder.binding.cbSelected.isChecked = selectableApp.isSelected
        holder.binding.cbSelected.setOnCheckedChangeListener { _, isChecked ->
            selectableApp.isSelected = isChecked
        }

        holder.binding.root.setOnClickListener {
            holder.binding.cbSelected.isChecked = !holder.binding.cbSelected.isChecked
        }
    }

    override fun getItemCount() = filteredApps.size

    class ViewHolder(val binding: ItemAppSelectionBinding) : RecyclerView.ViewHolder(binding.root)
}
