package com.volla.hub

import android.content.Context

/**
 * Speichert WebDAV-Zugangsdaten in SharedPreferences.
 */
class WebDavPreferences(private val context: Context) {

    companion object {
        private const val PREFS_FILE = "webdav_credentials"
        private const val KEY_SERVER_URL = "webdav_server_url"
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_ORS_KEY = "ors_api_key"
    }

    data class WebDavConfig(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    private fun prefs() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun saveCredentials(serverUrl: String, username: String, password: String) {
        prefs().edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun saveOrsKey(key: String) {
        prefs().edit().putString(KEY_ORS_KEY, key).apply()
    }

    fun getOrsKey(): String = prefs().getString(KEY_ORS_KEY, "") ?: ""

    fun loadConfig(): WebDavConfig? {
        val p = prefs()
        val url  = p.getString(KEY_SERVER_URL, null) ?: return null
        val user = p.getString(KEY_USERNAME,   null) ?: return null
        val pass = p.getString(KEY_PASSWORD,   "") ?: ""
        if (url.isBlank() || user.isBlank()) return null
        return WebDavConfig(url, user, pass)
    }

    fun clearCredentials() = prefs().edit().clear().apply()

    fun hasCredentials(): Boolean = loadConfig() != null
}
