package com.kingzcheung.xime.service

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?
) {
    companion object {
        private const val TAG = "VoiceRecognition"
    }
    
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    
    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0
    
    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = SpeechRecognitionManager(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error ->
                handleSpeechError(error)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            }
        )

        val useLocal = SettingsPreferences.isSttUseLocal(context)
        val providerName = if (useLocal) {
            "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: ${if (useLocal) "local" else "funasr"}")
    }
    
    fun startRecognition(): Boolean {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            return false
        }
        
        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length
        Log.d("VoiceButtons", "Saved text before voice: length=$textLengthBeforeVoiceInput")
        
        val started = speechRecognitionManager.startRecognition()
        Log.d("VoiceButtons", "Speech recognition started: $started")
        
        return started
    }
    
    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
    }
    
    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }
    
    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized
    
    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result: $text")
        
        if (text.isNotEmpty() && !text.startsWith("错误:")) {
            getInputConnection()?.commitText(text, 1)
            onStateChanged(getState().copy(voiceRecognizedText = text))
        }
    }
    
    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }
    
    private fun handleSpeechError(error: String) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        onStateChanged(getState().copy(
            voiceRecognitionState = RecognitionState.ERROR,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        ))
    }
    
    private fun handleAmplitudeUpdate(amplitude: Float) {
        onStateChanged(getState().copy(voiceAmplitude = amplitude))
    }
}