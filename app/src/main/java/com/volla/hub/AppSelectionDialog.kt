package com.volla.hub

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.volla.hub.databinding.DialogAppSelectionBinding

class AppSelectionDialog : DialogFragment() {

    private var _binding: DialogAppSelectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AppSelectionAdapter
    private var onAppsSelected: ((List<AppInfo>) -> Unit)? = null
    private var initiallySelectedPackages = listOf<String>()

    fun setOnAppsSelectedListener(listener: (List<AppInfo>) -> Unit) {
        onAppsSelected = listener
    }

    fun setInitiallySelectedApps(apps: List<AppInfo>) {
        initiallySelectedPackages = apps.map { it.packageName }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAppSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        adapter = AppSelectionAdapter(requireContext().packageManager)
        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        loadApps()

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSelect.setOnClickListener {
            onAppsSelected?.invoke(adapter.getSelectedApps())
            dismiss()
        }
    }

    private fun loadApps() {
        val pm = requireContext().packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val selectableApps = packages
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .map { appInfo ->
                val name = pm.getApplicationLabel(appInfo).toString()
                val packageName = appInfo.packageName
                val version = try {
                    pm.getPackageInfo(packageName, 0).versionName ?: getString(R.string.app_version_unknown)
                } catch (e: Exception) { getString(R.string.app_version_unknown) }
                
                SelectableApp(
                    info = AppInfo(name, packageName, version),
                    isSelected = initiallySelectedPackages.contains(packageName)
                )
            }
            .sortedBy { it.info.name }
        
        adapter.setApps(selectableApps)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AppSelectionDialog"
        fun newInstance() = AppSelectionDialog()
    }
}
