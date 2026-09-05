package com.volla.hub

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV-Client basierend auf OkHttp für zuverlässige Unterstützung von PROPFIND, MKCOL etc.
 * Behebt das Problem, dass HttpURLConnection keine benutzerdefinierten Methoden wie PROPFIND erlaubt.
 */
class WebDavClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    companion object {
        private const val TAG = "WebDavClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val authHeader: String
        get() {
            val raw = "$username:$password"
            return "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }

    fun uploadFile(remotePath: String, file: File): Result<Unit> {
        return try {
            val url = buildUrl(remotePath)
            val request = Request.Builder()
                .url(url)
                .put(file.asRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", authHeader)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun downloadFile(remotePath: String, dest: File): Result<Unit> {
        return try {
            val url = buildUrl(remotePath)
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", authHeader)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Result.failure(IOException("HTTP ${response.code}"))
                response.body!!.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun deleteFile(remotePath: String): Result<Unit> {
        return try {
            val url = buildUrl(remotePath)
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("Authorization", authHeader)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404) Result.success(Unit)
                else Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFile failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun listFiles(remoteDir: String): Result<List<String>> {
        return try {
            val url = buildUrl("$remoteDir/")
            val xml = "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>"
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", xml.toRequestBody("application/xml".toMediaType()))
                .addHeader("Authorization", authHeader)
                .addHeader("Depth", "1")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    createDirectory(remoteDir)
                    return Result.success(emptyList())
                }
                if (!response.isSuccessful) return Result.failure(IOException("HTTP ${response.code}"))

                val body = response.body!!.string()
                val regex = Regex("<[^>]*href>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE)
                val files = regex.findAll(body)
                    .map { it.groupValues[1] }
                    .map { href ->
                        val decoded = try { java.net.URLDecoder.decode(href, "UTF-8") } catch (e: Exception) { href }
                        decoded.trimEnd('/').substringAfterLast('/')
                    }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
                Result.success(files)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun createDirectory(remoteDir: String): Result<Unit> {
        return try {
            val url = buildUrl("$remoteDir/")
            val request = Request.Builder()
                .url(url)
                .method("MKCOL", null)
                .addHeader("Authorization", authHeader)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 405) Result.success(Unit)
                else Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildUrl(path: String): String {
        val base = serverUrl.trimEnd('/')
        val p = path.trimStart('/')
        return "$base/$p"
    }
}
