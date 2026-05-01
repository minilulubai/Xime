package com.kingzcheung.xime.speech.funasr

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.util.FileLogger

class FunAsrAsrBackend(private val context: Context) : AsrBackend {

    override val name: String = "阿里百炼 FunAsr"
    
    companion object {
        private const val TAG = "FunAsrBackend"
    }

    private var wsManager: FunAsrWebSocketManager? = null
    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
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
            FileLogger.e(TAG, "FunAsr API Key not configured")
            Log.e(TAG, "API Key not configured")
            return false
        }
        FileLogger.i(TAG, "Initializing FunAsr backend with API key (length: ${apiKey.length})")
        wsManager = FunAsrWebSocketManager(
            apiKey = apiKey,
            onResult = { text, _ ->
                if (text.isNotEmpty()) {
                    resultCallback?.invoke(text)
                }
            },
            onError = { error ->
                FileLogger.e(TAG, "FunAsr error: $error")
                Log.e(TAG, "Error: $error")
                errorCallback?.invoke(error)
            },
            onStateChanged = { wsState ->
                stateCallback?.invoke(wsState.toRecognitionState())
            }
        )
        return true
    }

    override fun start(): Boolean {
        FileLogger.i(TAG, "Starting FunAsr connection")
        return wsManager?.connect() ?: false
    }

    override fun processAudioChunk(buffer: ByteArray) {
        wsManager?.sendAudioChunk(buffer)
    }

    override fun stop() {
        FileLogger.i(TAG, "Stopping FunAsr recognition")
        wsManager?.sendFinishTask()
    }

    override fun cancel() {
        FileLogger.i(TAG, "Canceling FunAsr recognition")
        wsManager?.cancel()
    }

    override fun release() {
        FileLogger.i(TAG, "Releasing FunAsr backend")
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
