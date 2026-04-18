package com.kingzcheung.kime.plugin.core.api

import android.content.Context
import com.kingzcheung.kime.plugin.core.model.PluginContext

interface SpeechPlugin : IPluginEntryClass {
    
    override fun onLoad(context: PluginContext)
    
    override fun onUnload()
    
    val supportsRealtime: Boolean get() = false
    val requiresNetwork: Boolean get() = false
    
    fun startRecognition(config: AudioConfig, onResult: (SpeechResult) -> Unit): Boolean
    
    fun sendAudioChunk(data: ByteArray)
    
    fun stopRecognition()
    
    fun cancelRecognition()
    
    suspend fun recognizeOnce(data: ByteArray, config: AudioConfig): String?
    
    fun getState(): RecognitionState
    
    fun openSettings(context: Context)
}

data class AudioConfig(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val encoding: String = "pcm16"
)

enum class AudioEncoding {
    PCM16
}

data class SpeechResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 1.0f
)

enum class RecognitionState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}