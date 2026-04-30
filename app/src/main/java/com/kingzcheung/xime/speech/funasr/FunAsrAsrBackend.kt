package com.kingzcheung.xime.speech.funasr

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState

class FunAsrAsrBackend(private val context: Context) : AsrBackend {

    override val name: String = "阿里百炼 FunAsr"

    private var wsManager: FunAsrWebSocketManager? = null
    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        stateCallback = onStateChange
        errorCallback = onError
    }

    override fun initialize(): Boolean {
        val apiKey = SettingsPreferences.getFunAsrApiKey(context)
        if (apiKey.isEmpty()) {
            Log.e("FunAsrBackend", "API Key not configured")
            return false
        }
        wsManager = FunAsrWebSocketManager(
            apiKey = apiKey,
            onResult = { text, _ ->
                if (text.isNotEmpty()) {
                    resultCallback?.invoke(text)
                }
            },
            onError = { error ->
                Log.e("FunAsrBackend", "Error: $error")
                errorCallback?.invoke(error)
            },
            onStateChanged = { wsState ->
                stateCallback?.invoke(wsState.toRecognitionState())
            }
        )
        return true
    }

    override fun start(): Boolean {
        return wsManager?.connect() ?: false
    }

    override fun processAudioChunk(buffer: ByteArray) {
        wsManager?.sendAudioChunk(buffer)
    }

    override fun stop() {
        wsManager?.sendFinishTask()
    }

    override fun cancel() {
        wsManager?.cancel()
    }

    override fun release() {
        wsManager?.disconnect()
        wsManager = null
    }

    override fun getState(): RecognitionState {
        return wsManager?.getState()?.toRecognitionState() ?: RecognitionState.IDLE
    }

    override fun isAvailable(): Boolean {
        return SettingsPreferences.getFunAsrApiKey(context).isNotEmpty()
    }

    private fun FunAsrWebSocketManager.State.toRecognitionState(): RecognitionState {
        return when (this) {
            FunAsrWebSocketManager.State.IDLE -> RecognitionState.IDLE
            FunAsrWebSocketManager.State.CONNECTING,
            FunAsrWebSocketManager.State.CONNECTED -> RecognitionState.LISTENING
            FunAsrWebSocketManager.State.LISTENING -> RecognitionState.LISTENING
            FunAsrWebSocketManager.State.PROCESSING -> RecognitionState.PROCESSING
            FunAsrWebSocketManager.State.ERROR -> RecognitionState.ERROR
        }
    }
}
