package com.kingzcheung.xime.association

import android.content.Context
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object OnnxAssociationEngine {
    private const val TAG = "OnnxAssociationEngine"
    
    private var vocab: Map<String, Int> = emptyMap()
    private var id2word: Map<Int, String> = emptyMap()
    private var isInitialized = false
    private var warmupStarted = false
    private val warmupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            FileLogger.d(TAG, "Already initialized")
            return true
        }

        ModelRuntime.register(
            id = "predictive_text",
            loader = { initialize(context) },
            releaser = { release() },
            label = "智能联想模型"
        )

        try {
            val modelDir = context.filesDir
            
            modelDir.mkdirs()
            
            val filesToCheck = listOf("vocab.json", "model_int8_dynamic.onnx")
            for (fileName in filesToCheck) {
                val file = File(modelDir, fileName)
                if (!file.exists()) {
                    FileLogger.e(TAG, "$fileName not found at ${file.absolutePath}")
                    return false
                }
                FileLogger.d(TAG, "$fileName exists: ${file.length()} bytes")
            }

            val vocabFile = File(modelDir, "vocab.json")
            val vocabText = vocabFile.readText()

            val vocabJson = JSONObject(vocabText)
            val vocabMap = when {
                vocabJson.has("model") -> {
                    vocabJson.getJSONObject("model").getJSONObject("vocab")
                }
                vocabJson.has("vocab") -> {
                    vocabJson.getJSONObject("vocab")
                }
                else -> {
                    vocabJson
                }
            }
            vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
            id2word = vocab.entries.associate { it.value to it.key }
            FileLogger.i(TAG, "Vocabulary loaded: ${vocab.size} words")


            val modelFile = File(modelDir, "model_int8_dynamic.onnx")
            FileLogger.d(TAG, "Using model: ${modelFile.name} (${modelFile.length()} bytes)")

            val success = NativeOnnxEngine.initialize(context, modelFile.absolutePath)
            if (success) {
                isInitialized = true
                FileLogger.i(TAG, "ONNX Runtime initialized successfully")
                ModelRuntime.markLoaded("predictive_text")
                return true
            } else {
                FileLogger.e(TAG, "Failed to initialize ONNX Runtime - NativeOnnxEngine.initialize returned false")
                return false
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize ONNX Runtime: ${e.message}", e)
            return false
        }
    }

    suspend fun predict(inputText: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            FileLogger.e(TAG, "Engine not initialized")
            return@withContext emptyList()
        }

        try {
            val inputIds = encodeText(inputText)
            if (inputIds.isEmpty()) {
                return@withContext emptyList()
            }

            val inputIdsLong = inputIds.map { it.toLong() }.toLongArray()
            val scores = NativeOnnxEngine.predict(inputIdsLong, topK)

            val candidates = scores.mapNotNull { (id, score) ->
                id2word[id]?.let { word ->
                    AssociationCandidate(word, score)
                }
            }

            candidates

        } catch (e: Exception) {
            FileLogger.e(TAG, "Prediction failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun encodeText(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        ids.add(vocab["[BOS]"] ?: 1)
        var i = 0
        while (i < text.length) {
            val char = text[i].toString()
            val id = vocab[char] ?: 3
            ids.add(id)
            i++
        }
        return ids
    }

    fun startWarmup() {
        if (!isInitialized || warmupStarted) return
        warmupStarted = true
        warmupScope.launch {
            val dummyIds = longArrayOf(1L, 9L)
            try {
                NativeOnnxEngine.predict(dummyIds, 5)
            } catch (e: Exception) {
                FileLogger.w(TAG, "Warmup prediction failed (non-fatal): ${e.message}")
            }
        }
    }

    fun release() {
        NativeOnnxEngine.release()
        isInitialized = false
        ModelRuntime.markUnloaded("predictive_text")
        FileLogger.d(TAG, "ONNX Runtime released")
    }

    fun isInitialized(): Boolean = isInitialized
}