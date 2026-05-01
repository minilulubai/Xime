package com.kingzcheung.xime.service

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.speech.sherpa.SherpaAsrEngine
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
            onPartialResult = { text ->
                handlePartialResult(text)
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
            val sherpaEngine = SherpaAsrEngine(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: ${if (useLocal) "local" else "funasr"}")

        if (useLocal && SettingsPreferences.isSttEnabled(context)) {
            Thread {
                try {
                    speechRecognitionManager.preload()
                } catch (_: Exception) { }
            }.start()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length
        Log.d("VoiceButtons", "Saved text before voice: length=$textLengthBeforeVoiceInput")

        val useLocal = SettingsPreferences.isSttUseLocal(context)
        val providerName = if (useLocal) {
            val sherpaEngine = SherpaAsrEngine(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
        Log.d("VoiceButtons", "Speech recognition starting")
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

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")
        lastPartialText = ""

        if (text.isNotEmpty() && !text.startsWith("错误:")) {
            val ic = getInputConnection()
            if (ic != null) {
                val needsAutoPunctuation = getNeedsAutoPunctuation()
                val finalText = if (needsAutoPunctuation) "$text${heuristicPunctuation(text)}" else text
                ic.commitText(finalText, 1)
            }
            onStateChanged(getState().copy(voiceRecognizedText = ""))
        }
    }
    
    private fun getNeedsAutoPunctuation(): Boolean {
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        if (useLocal) {
            val sherpaEngine = SherpaAsrEngine(context)
            return sherpaEngine.getSelectedModelInfo()?.needsAutoPunctuation ?: true
        }
        return false
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun handlePartialResult(text: String) {
        if (text == lastPartialText) return
        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")
        val ic = getInputConnection()
        if (ic != null) {
            ic.setComposingText(text, 1)
        }
        onStateChanged(getState().copy(voiceRecognizedText = text))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
        }
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        lastPartialText = ""
        onStateChanged(getState().copy(
            isVoiceMode = false,
            voiceButtonState = VoiceButtonState(),
            voiceRecognitionState = RecognitionState.ERROR,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        ))
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        onStateChanged(getState().copy(voiceAmplitude = amplitude))
    }
}