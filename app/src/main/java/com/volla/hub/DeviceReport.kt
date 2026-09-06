package com.volla.hub

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.*

data class AppInfo(
    val name: String,
    val packageName: String,
    val version: String
)

data class DeviceReport(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val note: String,
    val specs: String,
    val shelterActive: Boolean,
    val securityModeActive: Boolean,
    val vpnActive: Boolean,
    val imagePaths: List<String> = emptyList(),
    val selectedApps: List<AppInfo> = emptyList()
)

class ReportStorage(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "reports.json")

    fun saveReport(report: DeviceReport) {
        val reports = getAllReports().toMutableList()
        reports.add(0, report)
        file.writeText(gson.toJson(reports))
    }

    fun getAllReports(): List<DeviceReport> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<DeviceReport>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun deleteReport(reportId: String) {
        val reports = getAllReports().filter { it.id != reportId }
        file.writeText(gson.toJson(reports))
    }
}