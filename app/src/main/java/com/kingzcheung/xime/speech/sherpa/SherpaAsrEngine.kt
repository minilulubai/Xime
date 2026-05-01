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
                id = "ctc-multi-zh-hans-int8",
                name = "中文多方言 CTC int8",
                description = "CTC 架构，支持多种中文方言，int8 量化",
                language = "zh",
                size = "13 MB",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-ctc-multi-zh-hans-int8-2023-12-13.tar.bz2",
                modelType = "ctc",
                files = listOf("ctc-epoch-20-avg-1-chunk-16-left-128.int8.onnx", "tokens.txt"),
                ctcModelFile = "ctc-epoch-20-avg-1-chunk-16-left-128.int8.onnx"
            ),
            AsrModelInfo(
                id = "zipformer-multilingual-int8",
                name = "多语言 Zipformer int8",
                description = "支持阿拉伯语、英语、印尼语、日语、俄语、泰语、越南语、中文",
                language = "multi",
                size = "259 MB",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
                modelType = "transducer",
                files = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                encoderFile = "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                decoderFile = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
                joinerFile = "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx"
            ),
            AsrModelInfo(
                id = "ctc-zh-xlarge-int8",
                name = "中文大模型 CTC int8",
                description = "中文大模型，int8 量化，更高精度",
                language = "zh",
                size = "590 MB",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-ctc-zh-xlarge-int8-2025-06-30.tar.bz2",
                modelType = "ctc",
                files = listOf("model.int8.onnx", "tokens.txt"),
                ctcModelFile = "model.int8.onnx"
            )
        )
    }
    
    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val ctcModelFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )
    
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    
    private val accumulatedText = StringBuilder()
    
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
        val modelId = sharedPrefs.getString("selected_model", "ctc-multi-zh-hans-int8") ?: "ctc-multi-zh-hans-int8"
        return File(context.filesDir, "asr_models/$modelId")
    }

    fun getSelectedModelInfo(): AsrModelInfo? {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        val modelId = sharedPrefs.getString("selected_model", "ctc-multi-zh-hans-int8") ?: "ctc-multi-zh-hans-int8"
        return AVAILABLE_MODELS.find { it.id == modelId }
    }
    
    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences("sherpa_asr", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_model", modelId).apply()
    }
    
    private fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
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

        val tokensFile = findFile(modelDir, "tokens.txt")
        if (tokensFile == null) {
            Log.e(TAG, "tokens.txt not found in ${modelDir.absolutePath}")
            errorCallback?.invoke("模型文件不完整，缺少 tokens.txt")
            return false
        }

        if (modelInfo.modelType == "ctc") {
            if (findFile(modelDir, modelInfo.ctcModelFile) == null &&
                modelInfo.files.none { f -> f.endsWith(".onnx") && findFile(modelDir, f) != null }) {
                Log.e(TAG, "CTC model file not found in ${modelDir.absolutePath}")
                errorCallback?.invoke("模型文件不完整，请重新下载")
                return false
            }
        } else {
            if (findFile(modelDir, modelInfo.encoderFile) == null) {
                Log.e(TAG, "Encoder file not found in ${modelDir.absolutePath}")
                errorCallback?.invoke("模型文件不完整，请重新下载")
                return false
            }
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
        val tokens = findFile(modelDir, "tokens.txt")?.absolutePath
            ?: File(modelDir, "tokens.txt").absolutePath

        val modelConfig = if (modelInfo.modelType == "ctc") {
            val modelFile = findFile(modelDir, modelInfo.ctcModelFile)?.absolutePath
                ?: modelInfo.files.firstOrNull { f -> f.endsWith(".onnx") }
                    ?.let { findFile(modelDir, it)?.absolutePath }
                ?: File(modelDir, modelInfo.ctcModelFile).absolutePath
            OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = modelFile),
                tokens = tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2"
            )
        } else {
            val encoder = (findFile(modelDir, modelInfo.encoderFile) ?: modelInfo.files.firstOrNull { f -> f.startsWith("encoder") }
                ?.let { findFile(modelDir, it) } ?: File(modelDir, modelInfo.encoderFile)).absolutePath
            val decoder = (findFile(modelDir, modelInfo.decoderFile) ?: modelInfo.files.firstOrNull { f -> f.startsWith("decoder") }
                ?.let { findFile(modelDir, it) } ?: File(modelDir, modelInfo.decoderFile)).absolutePath
            val joiner = (findFile(modelDir, modelInfo.joinerFile) ?: modelInfo.files.firstOrNull { f -> f.startsWith("joiner") }
                ?.let { findFile(modelDir, it) } ?: File(modelDir, modelInfo.joinerFile)).absolutePath
            OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder, decoder = decoder, joiner = joiner
                ),
                tokens = tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2"
            )
        }

        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
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
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
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
        
        val isEndpoint = currentRecognizer.isEndpoint(currentStream)
        val text = currentRecognizer.getResult(currentStream).text
        
        if (isEndpoint) {
            val tailPaddings = FloatArray((0.8f * SAMPLE_RATE).toInt())
            currentStream.acceptWaveform(tailPaddings, SAMPLE_RATE)
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
            }
            val finalText = currentRecognizer.getResult(currentStream).text
            
            if (finalText.isNotEmpty()) {
                coroutineScope.launch(Dispatchers.Main) {
                    resultCallback?.invoke(finalText)
                }
            }
            
            currentRecognizer.reset(currentStream)
            coroutineScope.launch(Dispatchers.Main) {
                stateCallback?.invoke(RecognitionState.LISTENING)
            }
        } else if (text.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.Main) {
                partialResultCallback?.invoke(text)
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
        
        var resultText = ""
        if (currentStream != null && currentRecognizer != null) {
            val tailPaddings = FloatArray((0.6f * SAMPLE_RATE).toInt())
            currentStream.acceptWaveform(tailPaddings, SAMPLE_RATE)
            currentStream.inputFinished()
            
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
            }
            
            resultText = currentRecognizer.getResult(currentStream).text
            
            currentStream.release()
            stream = null
        }
        
        if (resultText.isNotEmpty()) {
            val finalText = resultText
            coroutineScope.launch(Dispatchers.Main) {
                resultCallback?.invoke(finalText)
            }
        }
        
        accumulatedText.clear()
        stateCallback?.invoke(RecognitionState.IDLE)
        Log.d(TAG, "Recognition stopped")
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