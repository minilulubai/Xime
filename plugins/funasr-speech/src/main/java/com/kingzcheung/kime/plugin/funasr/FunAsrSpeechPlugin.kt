package com.kingzcheung.kime.plugin.funasr

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kingzcheung.kime.plugin.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FunAsrSpeechPlugin : SpeechPlugin {
    
    override val id = "funasr_speech_plugin"
    override val name = "Kime: Fun-ASR语音识别"
    override val description = "基于阿里云Fun-ASR的实时语音识别服务"
    override val version = "1.0.0"
    override val type = PluginType.SPEECH
    
    override val supportsRealtime = true
    override val requiresNetwork = true
    
    private var isInitialized = false
    private var context: Context? = null
    private var apiKey: String = ""
    private var wsManager: WebSocketManager? = null
    private var currentResultCallback: ((SpeechResult) -> Unit)? = null
    private var recognitionState: RecognitionState = RecognitionState.IDLE
    
    companion object {
        private const val TAG = "FunAsrSpeechPlugin"
        private const val API_KEY_ENV = "DASHSCOPE_API_KEY"
    }
    
    override fun initialize(context: Context): Boolean {
        return initialize(context, null)
    }
    
    override fun initialize(context: Context, apkPath: String?): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }
        
        Log.d(TAG, "Initializing Fun-ASR plugin")
        Log.d(TAG, "Context package: ${context.packageName}")
        
        this.context = context
        
        // 从插件的 SharedPreferences 读取（使用插件包名）
        try {
            val prefsApiKey = FunAsrPreferences(context).getApiKey()
            Log.d(TAG, "API key from prefs: length=${prefsApiKey.length}")
            
            apiKey = if (prefsApiKey.isNotEmpty()) {
                Log.d(TAG, "Using API key from preferences")
                prefsApiKey
            } else {
                Log.d(TAG, "Trying to get API key from environment")
                try {
                    val envKey = System.getenv(API_KEY_ENV) ?: ""
                    Log.d(TAG, "API key from env: length=${envKey.length}")
                    envKey
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get API key from env", e)
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read preferences", e)
        }
        
        Log.d(TAG, "Final API key length: ${apiKey.length}")
        
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API key not configured, please configure via settings")
        }
        
        isInitialized = true
        Log.d(TAG, "Plugin initialized successfully")
        return true
    }
    
    override fun startRecognition(config: AudioConfig, onResult: (SpeechResult) -> Unit): Boolean {
        Log.d(TAG, "startRecognition called")
        
        if (!isInitialized) {
            Log.e(TAG, "Plugin not initialized")
            onResult(SpeechResult("错误: 插件未初始化", true, 0f))
            return false
        }
        
        // 重新读取配置
        context?.let { ctx ->
            apiKey = FunAsrPreferences(ctx).getApiKey()
            Log.d(TAG, "Refreshed API key: length=${apiKey.length}")
        }
        
        if (apiKey.isEmpty()) {
            Log.e(TAG, "API key not configured")
            onResult(SpeechResult("错误: 未配置API Key，请在插件设置中配置", true, 0f))
            return false
        }
        
        Log.d(TAG, "API key ready: length=${apiKey.length}")
        
        if (recognitionState != RecognitionState.IDLE) {
            Log.w(TAG, "Recognition already in progress, state: $recognitionState")
            return false
        }
        
        currentResultCallback = onResult
        
        Log.d(TAG, "Creating WebSocketManager")
        wsManager = WebSocketManager(
            apiKey = apiKey,
            onResult = { text, isFinal ->
                Log.d(TAG, "WebSocket result: '$text', isFinal: $isFinal")
                currentResultCallback?.invoke(
                    SpeechResult(
                        text = text,
                        isFinal = isFinal,
                        confidence = 1.0f
                    )
                )
            },
            onError = { errorMsg ->
                Log.e(TAG, "WebSocket error: $errorMsg")
                recognitionState = RecognitionState.ERROR
                currentResultCallback?.invoke(
                    SpeechResult("错误: $errorMsg", true, 0f)
                )
            },
            onStateChanged = { wsState ->
                Log.d(TAG, "WebSocket state changed: $wsState")
                recognitionState = when (wsState) {
                    WebSocketManager.State.IDLE -> RecognitionState.IDLE
                    WebSocketManager.State.CONNECTING -> RecognitionState.PROCESSING
                    WebSocketManager.State.CONNECTED -> RecognitionState.PROCESSING
                    WebSocketManager.State.LISTENING -> RecognitionState.LISTENING
                    WebSocketManager.State.PROCESSING -> RecognitionState.PROCESSING
                    WebSocketManager.State.ERROR -> RecognitionState.ERROR
                }
            }
        )
        
        Log.d(TAG, "Connecting WebSocket...")
        val connected = wsManager?.connect() ?: false
        if (!connected) {
            Log.e(TAG, "Failed to connect WebSocket")
            onResult(SpeechResult("错误: WebSocket连接失败", true, 0f))
            return false
        }
        
        recognitionState = RecognitionState.PROCESSING
        Log.d(TAG, "Recognition started, waiting for task-started event")
        return true
    }
    
    override fun sendAudioChunk(data: ByteArray) {
        wsManager?.sendAudioChunk(data)
    }
    
    override fun stopRecognition() {
        wsManager?.sendFinishTask()
        recognitionState = RecognitionState.IDLE
        Log.d(TAG, "Recognition stopped")
    }
    
    override fun cancelRecognition() {
        wsManager?.cancel()
        wsManager = null
        currentResultCallback = null
        recognitionState = RecognitionState.IDLE
        Log.d(TAG, "Recognition cancelled")
    }
    
    override suspend fun recognizeOnce(data: ByteArray, config: AudioConfig): String? {
        return withContext(Dispatchers.IO) {
            if (!isInitialized || apiKey.isEmpty()) {
                return@withContext null
            }
            
            var result: String? = null
            
            val tempWsManager = WebSocketManager(
                apiKey = apiKey,
                onResult = { text, isFinal ->
                    if (isFinal) {
                        result = text
                    }
                },
                onError = { errorMsg ->
                    Log.e(TAG, "recognizeOnce error: $errorMsg")
                },
                onStateChanged = {}
            )
            
            if (!tempWsManager.connect()) {
                return@withContext null
            }
            
            tempWsManager.sendAudioChunk(data)
            tempWsManager.sendFinishTask()
            
            Thread.sleep(500)
            
            return@withContext result
        }
    }
    
    override fun getState(): RecognitionState = recognitionState
    
    override fun release() {
        cancelRecognition()
        context = null
        isInitialized = false
        Log.d(TAG, "Plugin released")
    }
    
    override fun hasSettings(): Boolean = true
    
    override fun createSettingsIntent(context: Context): Intent {
        val intent = Intent()
        intent.setClassName(
            "com.kingzcheung.kime.plugin.funasr",
            "com.kingzcheung.kime.plugin.funasr.PluginSettingsActivity"
        )
        return intent
    }
}