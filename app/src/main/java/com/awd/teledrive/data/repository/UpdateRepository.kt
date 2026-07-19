package com.awd.teledrive.data.repository

import android.content.Context
import android.util.Log
import com.awd.teledrive.core.utils.VersionUtils
import com.awd.teledrive.data.model.GitHubRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class NewVersionAvailable(val release: GitHubRelease) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class UpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    suspend fun checkForUpdates(manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateState.Checking) return@withContext
        
        _updateState.value = UpdateState.Checking
        
        try {
            val url = URL("https://api.github.com/repos/putuwahyu29/awd-teledrive-android/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (connection.responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                val latestRelease = json.decodeFromString<GitHubRelease>(responseBody)
                
                val currentVersion = VersionUtils.getVersionName(context)
                if (isNewerVersion(latestRelease.tag_name, currentVersion)) {
                    _updateState.value = UpdateState.NewVersionAvailable(latestRelease)
                } else {
                    _updateState.value = UpdateState.UpToDate
                }
            } else {
                _updateState.value = UpdateState.Error("HTTP Error: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Failed to check for updates", e)
            _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
        } finally {
            if (!manual && _updateState.value is UpdateState.UpToDate) {
                // If automatic check and up to date, reset to idle to avoid showing "Up to date" toast if not requested
                _updateState.value = UpdateState.Idle
            }
        }
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    private fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val latest = latestTag.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val current = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until minOf(latest.size, current.size)) {
            if (latest[i] > current[i]) return true
            if (latest[i] < current[i]) return false
        }
        return latest.size > current.size
    }
}
