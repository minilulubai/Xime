package com.kingzcheung.kime.plugin.funasr

import android.content.Context
import android.util.Log
import java.io.File

class FunAsrPreferences(private val context: Context) {
    
    companion object {
        private const val TAG = "FunAsrPreferences"
        private const val PREFS_NAME = "funasr_plugin_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val CONFIG_FILE = "config.json"
        private const val HOST_PACKAGE_NAME = "com.kingzcheung.kime"
        
        fun getExternalConfigFile(): File {
            val externalDir = File("/sdcard/Android/data/$HOST_PACKAGE_NAME/files")
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            return File(externalDir, CONFIG_FILE)
        }
    }
    
    fun saveApiKey(apiKey: String) {
        // 同时保存到 SharedPreferences 和外部存储
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
        
        // 保存到外部存储（可被其他应用读取）
        saveToExternalStorage(apiKey)
        
        Log.d(TAG, "API key saved: length=${apiKey.length}")
    }
    
    fun getApiKey(): String {
        // 优先从外部存储读取（支持跨进程）
        val externalKey = readFromExternalStorage()
        if (externalKey.isNotEmpty()) {
            Log.d(TAG, "API key from external storage: length=${externalKey.length}")
            return externalKey
        }
        
        // fallback: 从 SharedPreferences 读取
        val prefsKey = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
        Log.d(TAG, "API key from SharedPreferences: length=${prefsKey.length}")
        return prefsKey
    }
    
    fun hasApiKey(): Boolean {
        return getApiKey().isNotEmpty()
    }
    
    private fun saveToExternalStorage(apiKey: String) {
        try {
            val configFile = getExternalConfigFile()
            configFile.writeText("{\"api_key\":\"$apiKey\"}")
            Log.d(TAG, "Saved to external storage: ${configFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to external storage", e)
        }
    }
    
    private fun readFromExternalStorage(): String {
        try {
            val configFile = getExternalConfigFile()
            if (!configFile.exists()) {
                Log.d(TAG, "External config file not exists: ${configFile.absolutePath}")
                return ""
            }
            
            val content = configFile.readText()
            Log.d(TAG, "Read from external storage: ${configFile.absolutePath}")
            
            val apiKeyMatch = Regex("\"api_key\":\"([^\"]+)\"").find(content)
            return apiKeyMatch?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read from external storage", e)
            return ""
        }
    }
}