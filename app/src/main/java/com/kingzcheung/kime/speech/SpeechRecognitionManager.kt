package com.kingzcheung.kime.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.kingzcheung.kime.MainActivity
import com.kingzcheung.kime.speech.RecognitionState
import com.kingzcheung.kime.settings.SettingsPreferences
import com.kingzcheung.kime.speech.funasr.FunAsrWebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SpeechRecognitionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 1
    }
    
    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR
    
    private var audioRecord: AudioRecord? = null
    private var webSocketManager: FunAsrWebSocketManager? = null
    private val isRecording = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    
    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null
    
    private var partialResultBuffer = StringBuilder()
    
    fun setCallbacks(
        onResult: (String) -> Unit,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null
    ) {
        resultCallback = onResult
        stateCallback = onStateChange
        errorCallback = onError
        amplitudeCallback = onAmplitude
    }
    
    fun startRecognition(): Boolean {
        val apiKey = SettingsPreferences.getFunAsrApiKey(context)
        
        if (apiKey.isEmpty()) {
            Log.e(TAG, "FunAsr API Key not configured")
            errorCallback?.invoke("请先配置阿里百炼 API Key")
            
            coroutineScope.launch(Dispatchers.Main) {
                val intent = Intent(context, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra("open_fragment", "speech_to_text")
                context.startActivity(intent)
            }
            return false
        }
        
        if (!startAudioRecording()) {
            Log.e(TAG, "Failed to start audio recording")
            errorCallback?.invoke("无法启动录音")
            return false
        }
        
        partialResultBuffer.clear()
        
        webSocketManager = FunAsrWebSocketManager(
            apiKey = apiKey,
            onResult = { text, isFinal ->
                handleRecognitionResult(text, isFinal)
            },
            onError = { error ->
                handleRecognitionError(error)
            },
            onStateChanged = { state ->
                handleStateChanged(state)
            }
        )
        
        val connected = webSocketManager?.connect() ?: false
        
        if (!connected) {
            Log.e(TAG, "Failed to connect WebSocket")
            stopAudioRecording()
            errorCallback?.invoke("连接失败")
            return false
        }
        
        isRecording.set(true)
        
        recordingJob = coroutineScope.launch {
            try {
                while (isRecording.get()) {
                    val buffer = ByteArray(bufferSize)
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                    
                    if (bytesRead > 0) {
                        val audioChunk = buffer.copyOf(bytesRead)
                        webSocketManager?.sendAudioChunk(audioChunk)
                        
                        val amplitude = calculateAmplitude(buffer, bytesRead)
                        amplitudeCallback?.invoke(amplitude)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Audio read error: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    errorCallback?.invoke("录音错误: ${e.message}")
                }
            }
        }
        
        Log.d(TAG, "Recognition started with FunAsr")
        return true
    }
    
    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        
        isRecording.set(false)
        
        recordingJob?.cancel()
        recordingJob = null
        
        webSocketManager?.sendFinishTask()
        
        coroutineScope.launch {
            kotlinx.coroutines.delay(500)
            webSocketManager?.disconnect()
            webSocketManager = null
        }
        
        stopAudioRecording()
        
        stateCallback?.invoke(RecognitionState.IDLE)
        
        Log.d(TAG, "Recognition stopped")
    }
    
    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        
        isRecording.set(false)
        
        recordingJob?.cancel()
        recordingJob = null
        
        webSocketManager?.cancel()
        webSocketManager = null
        
        stopAudioRecording()
        
        stateCallback?.invoke(RecognitionState.IDLE)
        
        Log.d(TAG, "Recognition canceled")
    }
    
    fun getState(): RecognitionState {
        return when (webSocketManager?.getState()) {
            FunAsrWebSocketManager.State.LISTENING -> RecognitionState.LISTENING
            FunAsrWebSocketManager.State.PROCESSING -> RecognitionState.PROCESSING
            FunAsrWebSocketManager.State.CONNECTING, 
            FunAsrWebSocketManager.State.CONNECTED -> RecognitionState.LISTENING
            FunAsrWebSocketManager.State.ERROR -> RecognitionState.ERROR
            else -> RecognitionState.IDLE
        }
    }
    
    private fun startAudioRecording(): Boolean {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return false
            }
            
            audioRecord?.startRecording()
            
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord not recording")
                return false
            }
            
            Log.d(TAG, "Audio recording started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            return false
        }
    }
    
    private fun stopAudioRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio recording", e)
        }
    }
    
    private fun handleRecognitionResult(text: String, isFinal: Boolean) {
        Log.d(TAG, "Recognition result: $text, isFinal: $isFinal")
        
        CoroutineScope(Dispatchers.Main).launch {
            if (text.isNotEmpty()) {
                resultCallback?.invoke(text)
            }
            
            if (isFinal) {
                stateCallback?.invoke(RecognitionState.IDLE)
            } else {
                stateCallback?.invoke(RecognitionState.PROCESSING)
            }
        }
    }
    
    private fun handleRecognitionError(error: String) {
        Log.e(TAG, "Recognition error: $error")
        
        CoroutineScope(Dispatchers.Main).launch {
            errorCallback?.invoke(error)
        }
    }
    
    private fun handleStateChanged(state: FunAsrWebSocketManager.State) {
        Log.d(TAG, "State changed: $state")
        
        CoroutineScope(Dispatchers.Main).launch {
            when (state) {
                FunAsrWebSocketManager.State.LISTENING -> 
                    stateCallback?.invoke(RecognitionState.LISTENING)
                FunAsrWebSocketManager.State.PROCESSING -> 
                    stateCallback?.invoke(RecognitionState.PROCESSING)
                FunAsrWebSocketManager.State.CONNECTING, 
                FunAsrWebSocketManager.State.CONNECTED -> 
                    stateCallback?.invoke(RecognitionState.LISTENING)
                FunAsrWebSocketManager.State.ERROR -> 
                    stateCallback?.invoke(RecognitionState.ERROR)
                else -> 
                    stateCallback?.invoke(RecognitionState.IDLE)
            }
        }
    }
    
    fun release() {
        cancelRecognition()
        coroutineScope.cancel()
        Log.d(TAG, "SpeechRecognitionManager released")
    }
    
    private fun calculateAmplitude(buffer: ByteArray, length: Int): Float {
        var maxAmplitude = 0
        val samples = length / 2
        
        for (i in 0 until samples) {
            val sample = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort()
            val absSample = kotlin.math.abs(sample.toInt())
            if (absSample > maxAmplitude) {
                maxAmplitude = absSample
            }
        }
        
        val normalizedAmplitude = (maxAmplitude / 15000.0).coerceIn(0.0, 1.0)
        
        return normalizedAmplitude.toFloat()
    }
}