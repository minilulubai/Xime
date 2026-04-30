// Source: https://github.com/k2-fsa/sherpa-onnx
// License: Apache License 2.0
package com.kingzcheung.xime.speech.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.kingzcheung.xime.speech.RecognitionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class SherpaAsrEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16000
        
        val AVAILABLE_MODELS = listOf(
            AsrModelInfo(
                id = "zipformer-zh-14m",
                name = "中文小模型 (14MB)",
                language = "zh",
                size = "14MB",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
                files = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
                int8Encoder = "encoder-epoch-99-avg-1.int8.onnx",
                int8Joiner = "joiner-epoch-99-avg-1.int8.onnx"
            ),
            AsrModelInfo(
                id = "zipformer-small-zh-en",
                name = "中英双语小模型 (30MB)",
                language = "zh-en",
                size = "30MB",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2",
                files = listOf("encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt", "bpe.model"),
                int8Encoder = null,
                int8Joiner = null
            ),
            AsrModelInfo(
                id = "zipformer-zh-int8",
                name = "中文大模型 int8 (160MB)",
                language = "zh",
                size = "160MB",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
                files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.model"),
                int8Encoder = "encoder.int8.onnx",
                int8Joiner = "joiner.int8.onnx"
            )
        )
    }
    
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var resultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    
    private var accumulatedText = StringBuilder()
    private var isProcessingEndpoint = false
    
    data class AsrModelInfo(
        val id: String,
        val name: String,
        val language: String,
        val size: String,
        val downloadUrl: String,
        val files: List<String>,
        val int8Encoder: String?,
        val int8Joiner: String?
    )
    
    fun isAvailable(): Boolean {
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            Log.d(TAG, "sherpa-onnx-jni loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sherpa-onnx-jni not loaded: ${e.message}")
            false
        }
    }
    
    fun isModelReady(): Boolean {
        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) return false
        val files = modelDir.listFiles()
        return files != null && files.isNotEmpty()
    }
    
    fun getSelectedModelDir(): File {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        val modelId = sharedPrefs.getString("selected_model", "zipformer-zh-14m") ?: "zipformer-zh-14m"
        return File(context.filesDir, "asr_models/$modelId")
    }
    
    fun getSelectedModelInfo(): AsrModelInfo? {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        val modelId = sharedPrefs.getString("selected_model", "zipformer-zh-14m") ?: "zipformer-zh-14m"
        return AVAILABLE_MODELS.find { it.id == modelId }
    }
    
    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_model", modelId).apply()
    }
    
    fun initialize(): Boolean {
        if (!isAvailable()) {
            Log.e(TAG, "sherpa-onnx JNI not available")
            return false
        }
        
        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) {
            Log.e(TAG, "Model directory not found: ${modelDir.absolutePath}")
            return false
        }
        
        val modelInfo = getSelectedModelInfo()
        if (modelInfo == null) {
            Log.e(TAG, "Model info not found")
            return false
        }
        
        try {
            val config = createConfig(modelDir, modelInfo)
            recognizer = OnlineRecognizer(config = config)
            Log.d(TAG, "Recognizer initialized from ${modelDir.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer", e)
            errorCallback?.invoke("模型初始化失败: ${e.message}")
            return false
        }
    }
    
    private fun createConfig(modelDir: File, modelInfo: AsrModelInfo): OnlineRecognizerConfig {
        val encoder = if (modelInfo.int8Encoder != null && File(modelDir, modelInfo.int8Encoder).exists()) {
            File(modelDir, modelInfo.int8Encoder).absolutePath
        } else {
            val defaultEncoder = modelInfo.files.first { it.startsWith("encoder") }
            File(modelDir, defaultEncoder).absolutePath
        }
        
        val decoder = File(modelDir, modelInfo.files.first { it.startsWith("decoder") }).absolutePath
        
        val joiner = if (modelInfo.int8Joiner != null && File(modelDir, modelInfo.int8Joiner).exists()) {
            File(modelDir, modelInfo.int8Joiner).absolutePath
        } else {
            val defaultJoiner = modelInfo.files.first { it.startsWith("joiner") }
            File(modelDir, defaultJoiner).absolutePath
        }
        
        val tokens = File(modelDir, "tokens.txt").absolutePath
        
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder,
                    decoder = decoder,
                    joiner = joiner
                ),
                tokens = tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2"
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f)
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )
    }
    
    fun setCallbacks(
        onResult: (String) -> Unit,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        stateCallback = onStateChange
        errorCallback = onError
    }
    
    fun startRecognition(): Boolean {
        if (recognizer == null) {
            if (!initialize()) {
                return false
            }
        }
        
        stream = recognizer?.createStream()
        accumulatedText.clear()
        isProcessingEndpoint = false
        
        stateCallback?.invoke(RecognitionState.LISTENING)
        Log.d(TAG, "Recognition started")
        return true
    }
    
    fun processAudio(samples: FloatArray) {
        val currentStream = stream
        val currentRecognizer = recognizer
        if (currentStream == null || currentRecognizer == null) {
            return
        }
        
        currentStream.acceptWaveform(samples, SAMPLE_RATE)
        
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }
        
        val result = currentRecognizer.getResult(currentStream)
        if (result.text.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.Main) {
                resultCallback?.invoke(accumulatedText.toString() + result.text)
                stateCallback?.invoke(RecognitionState.PROCESSING)
            }
        }
        
        if (currentRecognizer.isEndpoint(currentStream) && !isProcessingEndpoint) {
            isProcessingEndpoint = true
            
            val finalResult = currentRecognizer.getResult(currentStream)
            if (finalResult.text.isNotEmpty()) {
                accumulatedText.append(finalResult.text)
                coroutineScope.launch(Dispatchers.Main) {
                    resultCallback?.invoke(accumulatedText.toString())
                }
            }
            
            currentRecognizer.reset(currentStream)
            isProcessingEndpoint = false
            
            coroutineScope.launch(Dispatchers.Main) {
                stateCallback?.invoke(RecognitionState.LISTENING)
            }
        }
    }
    
    fun processAudioBytes(buffer: ByteArray) {
        val samples = FloatArray(buffer.size / 2)
        for (i in samples.indices) {
            val low = buffer[i * 2].toInt() and 0xFF
            val high = buffer[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            samples[i] = sample.toFloat() / 32768.0f
        }
        processAudio(samples)
    }
    
    fun stopRecognition() {
        val currentStream = stream
        val currentRecognizer = recognizer
        
        if (currentStream != null && currentRecognizer != null) {
            currentStream.inputFinished()
            
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
            }
            
            val result = currentRecognizer.getResult(currentStream)
            if (result.text.isNotEmpty()) {
                accumulatedText.append(result.text)
                coroutineScope.launch(Dispatchers.Main) {
                    resultCallback?.invoke(accumulatedText.toString())
                }
            }
            
            currentStream.release()
            stream = null
        }
        
        stateCallback?.invoke(RecognitionState.IDLE)
        Log.d(TAG, "Recognition stopped, final text: ${accumulatedText}")
    }
    
    fun cancelRecognition() {
        stream?.release()
        stream = null
        accumulatedText.clear()
        stateCallback?.invoke(RecognitionState.IDLE)
        Log.d(TAG, "Recognition canceled")
    }
    
    fun release() {
        cancelRecognition()
        recognizer?.release()
        recognizer = null
        coroutineScope.cancel()
        Log.d(TAG, "SherpaAsrEngine released")
    }
    
    fun getState(): RecognitionState {
        return if (stream != null) RecognitionState.LISTENING else RecognitionState.IDLE
    }
}