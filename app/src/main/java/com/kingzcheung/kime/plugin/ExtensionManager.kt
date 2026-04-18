package com.kingzcheung.kime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.kime.plugin.core.api.EmojiPlugin
import com.kingzcheung.kime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.kime.plugin.core.api.PredictionPlugin
import com.kingzcheung.kime.plugin.core.api.SpeechPlugin
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import com.kingzcheung.kime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ExtensionManager {
    private const val TAG = "ExtensionManager"
    
    private var initialized = false
    
    fun initialize(context: Context) {
        if (initialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        Log.d(TAG, "Initialized")
        initialized = true
    }
    
    fun reload(context: Context): Boolean = PluginManager.isInitialized
    
    fun getSpeechPlugins(): List<SpeechPlugin> {
        val all = PluginManager.getAllPluginInstances()
        Log.d(TAG, "All plugin instances: ${all.keys}")
        val speech = all.values.mapNotNull { instance ->
            Log.d(TAG, "Checking instance: ${instance::class.simpleName}, interfaces: ${instance::class.java.interfaces.map { it.simpleName }}")
            if (instance is SpeechPlugin) instance else null
        }
        Log.d(TAG, "Speech plugins found: ${speech.size}")
        return speech
    }
    
    fun getEmojiPlugins(): List<EmojiPlugin> {
        val all = PluginManager.getAllPluginInstances()
        Log.d(TAG, "All plugin instances: ${all.keys}")
        val emoji = all.values.mapNotNull { instance ->
            Log.d(TAG, "Checking instance: ${instance::class.simpleName}, interfaces: ${instance::class.java.interfaces.map { it.simpleName }}")
            if (instance is EmojiPlugin) instance else null
        }
        Log.d(TAG, "Emoji plugins found: ${emoji.size}")
        return emoji
    }
    
    fun getPredictionPlugins(): List<PredictionPlugin> {
        val all = PluginManager.getAllPluginInstances()
        Log.d(TAG, "All plugin instances: ${all.keys}")
        val prediction = all.values.mapNotNull { instance ->
            Log.d(TAG, "Checking instance: ${instance::class.simpleName}, interfaces: ${instance::class.java.interfaces.map { it.simpleName }}")
            if (instance is PredictionPlugin) instance else null
        }
        Log.d(TAG, "Prediction plugins found: ${prediction.size}")
        return prediction
    }
    
    fun getEnabledEmojiPlugins(context: Context): List<Pair<String, EmojiPlugin>> {
        return getEmojiPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    fun getEnabledPredictionPlugins(context: Context): List<Pair<String, PredictionPlugin>> {
        return getPredictionPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    fun getEnabledSpeechPlugins(context: Context): List<Pair<String, SpeechPlugin>> {
        return getSpeechPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    private fun getPluginId(plugin: Any): String {
        return PluginManager.getAllPluginInstances().entries
            .firstOrNull { it.value == plugin }?.key ?: ""
    }
    
    suspend fun predict(context: Context, inputText: String, topK: Int = 5): List<String> =
        withContext(Dispatchers.Default) {
            getEnabledPredictionPlugins(context).flatMap { (_, plugin) ->
                try { plugin.predict(inputText, topK).map { it.text } }
                catch (e: Exception) { Log.e(TAG, "Prediction failed", e); emptyList() }
            }.distinct().take(topK)
        }
    
    suspend fun getEmojis(context: Context, category: String? = null, searchText: String? = null, topK: Int = 100) =
        withContext(Dispatchers.Default) {
            getEnabledEmojiPlugins(context).flatMap { (_, plugin) ->
                try { plugin.getEmojis(category, searchText, topK) }
                catch (e: Exception) { Log.e(TAG, "Get emojis failed", e); emptyList() }
            }.take(topK)
        }
    
    fun getAllInstalledPlugins(): List<PluginInfo> = PluginManager.getAllInstallPlugins()
    
    fun getPluginById(id: String): Any? = PluginManager.getPluginInstance(id)
    
    fun isInitialized(): Boolean = initialized && PluginManager.isInitialized
    
    fun hasSpeechPlugins(context: Context): Boolean = getEnabledSpeechPlugins(context).isNotEmpty()
    fun hasEmojiPlugins(context: Context): Boolean = getEnabledEmojiPlugins(context).isNotEmpty()
    fun hasPredictionPlugins(context: Context): Boolean = getEnabledPredictionPlugins(context).isNotEmpty()
    
    fun release() { initialized = false }
}