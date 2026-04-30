package com.kingzcheung.xime.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.funasr.FunAsrAsrBackend
import com.kingzcheung.xime.speech.sherpa.SherpaAsrBackend
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
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private var audioRecord: AudioRecord? = null
    private var backend: AsrBackend? = null
    private val isRecording = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null

    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null

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
        backend = createBackend()
        val currentBackend = backend ?: run {
            errorCallback?.invoke("无法创建 ASR 引擎")
            return false
        }

        currentBackend.setCallbacks(
            onResult = { text -> handleResult(text) },
            onStateChange = { state -> stateCallback?.invoke(state) },
            onError = { error -> handleError(error) }
        )

        if (!currentBackend.initialize()) {
            val msg = when {
                currentBackend is SherpaAsrBackend -> "本地模型未下载或引擎未编译"
                currentBackend is FunAsrAsrBackend -> {
                    if (SettingsPreferences.getFunAsrApiKey(context).isEmpty()) {
                        coroutineScope.launch(Dispatchers.Main) {
                            val intent = Intent(context, MainActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.putExtra("open_fragment", "speech_to_text")
                            context.startActivity(intent)
                        }
                        "请先配置阿里百炼 API Key"
                    } else {
                        "初始化在线引擎失败"
                    }
                }
                else -> "引擎初始化失败"
            }
            errorCallback?.invoke(msg)
            return false
        }

        if (!startAudioRecording()) {
            errorCallback?.invoke("无法启动录音")
            return false
        }

        if (!currentBackend.start()) {
            stopAudioRecording()
            errorCallback?.invoke("启动引擎失败")
            return false
        }

        isRecording.set(true)
        recordingJob = coroutineScope.launch {
            try {
                while (isRecording.get()) {
                    val buffer = ByteArray(bufferSize)
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        currentBackend.processAudioChunk(chunk)
                        amplitudeCallback?.invoke(calculateAmplitude(buffer, bytesRead))
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

        Log.d(TAG, "Recognition started with ${currentBackend.name}")
        return true
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
        backend?.stop()
        coroutineScope.launch {
            delay(500)
            backend?.release()
            backend = null
        }
        stopAudioRecording()
        stateCallback?.invoke(RecognitionState.IDLE)
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
        backend?.cancel()
        backend?.release()
        backend = null
        stopAudioRecording()
        stateCallback?.invoke(RecognitionState.IDLE)
    }

    fun getState(): RecognitionState {
        return backend?.getState() ?: RecognitionState.IDLE
    }

    private fun createBackend(): AsrBackend {
        return if (SettingsPreferences.isSttUseLocal(context)) {
            SherpaAsrBackend(context)
        } else {
            FunAsrAsrBackend(context)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
        }
    }

    private fun handleResult(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            if (text.isNotEmpty()) {
                resultCallback?.invoke(text)
            }
            if (text.isNotEmpty()) {
                stateCallback?.invoke(RecognitionState.PROCESSING)
            }
        }
    }

    private fun handleError(error: String) {
        Log.e(TAG, "Recognition error: $error")
        CoroutineScope(Dispatchers.Main).launch {
            errorCallback?.invoke(error)
        }
    }

    fun release() {
        cancelRecognition()
        coroutineScope.cancel()
    }

    private fun calculateAmplitude(buffer: ByteArray, length: Int): Float {
        var maxAmplitude = 0
        val samples = length / 2
        for (i in 0 until samples) {
            val sample = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort()
            val absSample = kotlin.math.abs(sample.toInt())
            if (absSample > maxAmplitude) maxAmplitude = absSample
        }
        return (maxAmplitude / 15000.0).coerceIn(0.0, 1.0).toFloat()
    }
}
