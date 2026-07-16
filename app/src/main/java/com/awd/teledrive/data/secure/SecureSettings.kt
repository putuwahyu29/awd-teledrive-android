package com.awd.teledrive.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val TAG = "SecureSettings"
    private var sharedPreferences: SharedPreferences

    init {
        sharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "secure_settings_v2", // Gunakan nama baru untuk menghindari konflik data lama yang rusak
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to plain SharedPreferences", e)
            // Fallback ke SharedPreferences biasa agar tidak crash, meskipun kurang aman.
            // Di aplikasi produksi, sebaiknya beri peringatan ke user.
            context.getSharedPreferences("fallback_settings", Context.MODE_PRIVATE)
        }
    }

    fun saveString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    fun saveBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun saveInt(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun saveLong(key: String, value: Long) {
        sharedPreferences.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }

    fun saveStringList(key: String, list: List<String>) {
        val data = list.joinToString(";")
        saveString(key, data)
    }

    fun getStringList(key: String): List<String> {
        val data = getString(key, "") ?: ""
        return if (data.isEmpty()) emptyList() else data.split(";")
    }

    fun isApiConfigured(): Boolean {
        return getInt("api_id", 0) != 0 && !getString("api_hash", "").isNullOrEmpty()
    }

    fun isSecurityEnabled(): Boolean {
        return getBoolean("security_enabled", false)
    }

    fun setSecurityEnabled(enabled: Boolean) {
        saveBoolean("security_enabled", enabled)
    }

    fun saveDownloadUri(uri: String) {
        saveString("download_uri", uri)
    }

    fun getDownloadUri(): String? {
        return getString("download_uri")
    }
}
