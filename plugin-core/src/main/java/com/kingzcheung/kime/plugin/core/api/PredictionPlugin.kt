package com.kingzcheung.kime.plugin.core.api

import android.content.Context
import com.kingzcheung.kime.plugin.core.model.PluginContext

interface PredictionPlugin : IPluginEntryClass {
    
    override fun onLoad(context: PluginContext)
    
    override fun onUnload()
    
    suspend fun predict(inputText: String, topK: Int): List<PredictionCandidate>
    
    fun learn(text: String)
    
    suspend fun saveLearnedData()
    
    fun openSettings(context: Context)
}

data class PredictionCandidate(
    val text: String,
    val score: Float
)