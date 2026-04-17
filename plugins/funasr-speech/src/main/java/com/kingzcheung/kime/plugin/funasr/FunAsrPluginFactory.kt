package com.kingzcheung.kime.plugin.funasr

import com.kingzcheung.kime.plugin.api.EmojiPlugin
import com.kingzcheung.kime.plugin.api.PluginFactory
import com.kingzcheung.kime.plugin.api.PredictionPlugin
import com.kingzcheung.kime.plugin.api.SpeechPlugin

class FunAsrPluginFactory : PluginFactory {
    
    private val speechPlugin by lazy { FunAsrSpeechPlugin() }
    
    override fun createPredictionPlugin(): PredictionPlugin? = null
    
    override fun createEmojiPlugin(): EmojiPlugin? = null
    
    override fun createSpeechPlugin(): SpeechPlugin {
        return speechPlugin
    }
}