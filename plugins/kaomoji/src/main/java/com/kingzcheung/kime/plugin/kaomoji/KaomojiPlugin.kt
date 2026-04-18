package com.kingzcheung.kime.plugin.kaomoji

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.kingzcheung.kime.plugin.core.api.EmojiItem
import com.kingzcheung.kime.plugin.core.api.EmojiPlugin
import com.kingzcheung.kime.plugin.core.model.PluginContext

class KaomojiPlugin : EmojiPlugin {
    
    private var kaomojiList: List<EmojiItem> = emptyList()
    
    companion object {
        private const val TAG = "KaomojiPlugin"
    }
    
    override fun onLoad(context: PluginContext) {
        Log.d(TAG, "Plugin loaded: ${context.pluginInfo.id}")
        
        kaomojiList = KaomojiData.kaomojis.mapIndexed { index, kaomoji ->
            EmojiItem(
                id = "kaomoji_$index",
                displayText = kaomoji,
                insertText = kaomoji,
                imageUrl = null,
                category = "颜文字"
            )
        }
        Log.d(TAG, "Loaded ${kaomojiList.size} kaomojis")
    }
    
    override fun onUnload() {
        kaomojiList = emptyList()
        Log.d(TAG, "Plugin unloaded")
    }
    
    override suspend fun getEmojis(category: String?, searchText: String?, topK: Int): List<EmojiItem> {
        val filtered = if (searchText.isNullOrEmpty()) kaomojiList
        else kaomojiList.filter { it.displayText.contains(searchText) }
        return filtered.take(topK)
    }
    
    override suspend fun getCategories(): List<String> = listOf("颜文字")
    
    override fun hasSettings(): Boolean = true
    
    override fun openSettings(context: Context) {
        try {
            val intent = android.content.Intent()
            intent.setClassName(
                "com.kingzcheung.kime.plugin.kaomoji",
                "com.kingzcheung.kime.plugin.kaomoji.PluginSettingsActivity"
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}