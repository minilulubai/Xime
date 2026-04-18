package com.kingzcheung.kime.plugin.core.api

import android.content.Context
import com.kingzcheung.kime.plugin.core.model.PluginContext

interface EmojiPlugin : IPluginEntryClass {
    
    override fun onLoad(context: PluginContext)
    
    override fun onUnload()
    
    suspend fun getEmojis(category: String?, searchText: String?, topK: Int): List<EmojiItem>
    
    suspend fun getCategories(): List<String>
    
    fun openSettings(context: Context)
}

data class EmojiItem(
    val id: String,
    val displayText: String,
    val insertText: String,
    val imageUrl: String?,
    val category: String
)