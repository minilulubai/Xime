package com.kingzcheung.xime.speech.funasr

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.time.Duration

class FunAsrWebSocketManager(
    private val apiKey: String,
    private val onResult: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onStateChanged: (State) -> Unit
) {
    
    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        LISTENING,
        PROCESSING,
        ERROR
    }
    
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var taskId: String = ""
    private var state: State = State.IDLE
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastResultText: String = ""
    
    companion object {
        private const val TAG = "FunAsrWebSocket"
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
        private const val MODEL = "fun-asr-realtime"
        private const val SAMPLE_RATE = 16000
        private const val FORMAT = "pcm"
    }
    
    fun getState(): State = state
    
    fun connect(): Boolean {
        lastResultText = ""
        
        if (state != State.IDLE) {
            Log.w(TAG, "Already connected or connecting, state: $state")
            return false
        }
        
        Log.d(TAG, "Starting connection, API key: '${apiKey.take(10)}...' (length: ${apiKey.length})")
        
        try {
            state = State.CONNECTING
            onStateChanged(state)
            
            taskId = UUID.randomUUID().toString().replace("-", "").take(32)
            Log.d(TAG, "Generated taskId: $taskId")
            
            client = OkHttpClient.Builder()
                .pingInterval(Duration.ofSeconds(30))
                .build()
            
            val request = Request.Builder()
                .url(WS_URL)
                .header("Authorization", "bearer $apiKey")
                .header("user-agent", "Xime-FunAsr/1.0")
                .build()
            
            Log.d(TAG, "Request headers: Authorization=bearer ${apiKey.take(10)}...")
            Log.d(TAG, "Connecting to: $WS_URL")
            
            webSocket = client?.newWebSocket(request, WebSocketListenerImpl())
            
            Log.d(TAG, "WebSocket connection initiated, waiting for onOpen...")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            state = State.ERROR
            onStateChanged(state)
            onError("连接失败: ${e.message}")
            return false
        }
    }
    
    fun sendRunTask(): Boolean {
        if (webSocket == null || state != State.CONNECTED) {
            Log.w(TAG, "WebSocket not ready, state: $state")
            return false
        }
        
        val runTaskMessage = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", MODEL)
                put("parameters", JSONObject().apply {
                    put("format", FORMAT)
                    put("sample_rate", SAMPLE_RATE)
                })
                put("input", JSONObject())
            })
        }
        
        Log.d(TAG, "Sending run-task: $runTaskMessage")
        webSocket?.send(runTaskMessage.toString())
        return true
    }
    
    fun sendAudioChunk(data: ByteArray) {
        if (state != State.LISTENING && state != State.PROCESSING) {
            Log.w(TAG, "Not in listening state, ignoring audio chunk")
            return
        }
        
        webSocket?.send(data.toByteString())
        Log.d(TAG, "Sent audio chunk: ${data.size} bytes")
    }
    
    fun sendFinishTask() {
        if (webSocket == null) return
        
        val finishTaskMessage = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject())
            })
        }
        
        Log.d(TAG, "Sending finish-task")
        webSocket?.send(finishTaskMessage.toString())
    }
    
    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        state = State.IDLE
        onStateChanged(state)
        Log.d(TAG, "WebSocket disconnected")
    }
    
    fun cancel() {
        disconnect()
    }
    
    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened, response code: ${response.code}")
            Log.d(TAG, "Response headers: ${response.headers}")
            state = State.CONNECTED
            onStateChanged(state)
            Log.d(TAG, "Sending run-task...")
            sendRunTask()
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            
            try {
                val message = JSONObject(text)
                val header = message.getJSONObject("header")
                val event = header.getString("event")
                val taskId = header.optString("task_id", "")
                
                Log.d(TAG, "Event: $event, taskId: $taskId")
                
                when (event) {
                    "task-started" -> {
                        Log.d(TAG, "Task started! Ready to send audio")
                        state = State.LISTENING
                        onStateChanged(state)
                    }
                    
                    "result-generated" -> {
                        Log.d(TAG, "Full result message: $text")
                        
                        val payload = message.getJSONObject("payload")
                        val output = payload.optJSONObject("output")
                        if (output != null) {
                            val sentence = output.optJSONObject("sentence")
                            if (sentence != null) {
                                val heartbeat = sentence.optBoolean("heartbeat", false)
                                if (heartbeat) {
                                    Log.d(TAG, "Skipping heartbeat message")
                                    return
                                }
                                
                                val resultText = sentence.optString("text", "")
                                val isFinal = sentence.optBoolean("sentence_end", false)
                                val beginTime = sentence.optInt("begin_time", 0)
                                val endTime = sentence.optInt("end_time", 0)
                                
                                Log.d(TAG, "Recognition result: '$resultText', isFinal: $isFinal, begin: $beginTime, end: $endTime")
                                Log.d(TAG, "lastResultText: '$lastResultText', resultText.startsWith(lastResultText): ${resultText.startsWith(lastResultText)}")
                                
                                state = State.PROCESSING
                                onStateChanged(state)
                                
                                if (resultText.isNotEmpty()) {
                                    val incrementalText = if (lastResultText.isNotEmpty() && resultText.startsWith(lastResultText)) {
                                        val delta = resultText.substring(lastResultText.length)
                                        Log.d(TAG, "Incremental text: '$delta'")
                                        delta
                                    } else {
                                        Log.d(TAG, "Result changed, returning full text")
                                        resultText
                                    }
                                    
                                    if (incrementalText.isNotEmpty()) {
                                        onResult(incrementalText, isFinal)
                                    }
                                    
                                    if (isFinal) {
                                        Log.d(TAG, "Sentence finalized, resetting lastResultText")
                                        lastResultText = ""
                                    } else {
                                        lastResultText = resultText
                                    }
                                }
                            } else {
                                Log.w(TAG, "No sentence in output")
                            }
                            
                            val usage = payload.optJSONObject("usage")
                            if (usage != null) {
                                Log.d(TAG, "Usage: duration=${usage.optInt("duration", 0)}s")
                            }
                        } else {
                            Log.w(TAG, "No output in payload")
                        }
                    }
                    
                    "task-finished" -> {
                        Log.d(TAG, "Task finished successfully")
                        state = State.IDLE
                        onStateChanged(state)
                        disconnect()
                    }
                    
                    "task-failed" -> {
                        val errorCode = header.optString("error_code", "UNKNOWN")
                        val errorMsg = header.optString("error_message", "Unknown error")
                        Log.e(TAG, "Task failed: code=$errorCode, message=$errorMsg")
                        state = State.ERROR
                        onStateChanged(state)
                        onError("识别失败 [$errorCode]: $errorMsg")
                        disconnect()
                    }
                    
                    else -> {
                        Log.w(TAG, "Unknown event: $event")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message: $text", e)
                onError("解析消息失败: ${e.message}")
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            Log.e(TAG, "Response: ${response?.code ?: "null"}, ${response?.message ?: "null"}")
            
            val errorCode = response?.code ?: 0
            val errorMsg = when (errorCode) {
                401 -> "API Key 无效或未配置，请检查设置"
                403 -> "访问被拒绝，请检查 API Key 权限"
                429 -> "请求过于频繁，请稍后再试"
                500 -> "服务器错误，请稍后再试"
                502 -> "服务器网关错误，请稍后再试"
                503 -> "服务暂时不可用，请稍后再试"
                else -> "连接失败: ${t.message ?: "未知错误"}"
            }
            
            webSocket?.close(1000, "Error cleanup")
            state = State.IDLE
            onStateChanged(state)
            onError(errorMsg)
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
            state = State.IDLE
            onStateChanged(state)
        }
    }
}