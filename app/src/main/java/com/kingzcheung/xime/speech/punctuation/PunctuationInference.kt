package com.kingzcheung.xime.speech.punctuation

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import org.json.JSONObject
import java.io.File
import java.io.InputStream

object PunctuationInference {
    private const val TAG = "PunctuationInference"
    private var nativeLoaded = false
    private var vocabLoaded = false
    private var charToId = mutableMapOf<String, Int>()
    private var idToPunctuation = mutableMapOf<Int, String>()
    private const val UNK_ID = 1
    
    fun loadVocabFromAssets(context: Context): Boolean {
        if (vocabLoaded) return true
        
        try {
            val inputStream: InputStream = context.assets.open("punctuation/vocab.json")
            val jsonStr = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonStr)
            
            val charToIdObj = json.getJSONObject("char_to_id")
            charToId.clear()
            val keys = charToIdObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                charToId[key] = charToIdObj.getInt(key)
            }
            
            val idToPuncObj = json.getJSONObject("id_to_punctuation")
            idToPunctuation.clear()
            val puncKeys = idToPuncObj.keys()
            while (puncKeys.hasNext()) {
                val key = puncKeys.next()
                idToPunctuation[key.toInt()] = idToPuncObj.getString(key)
            }
            
            vocabLoaded = true
            Log.d(TAG, "Vocab loaded: ${charToId.size} chars, ${idToPunctuation.size} punctuation labels")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab: ${e.message}")
            FileLogger.e(TAG, "Failed to load vocab: ${e.message}", e)
            return false
        }
    }
    
    fun loadNativeLibrary(context: Context): Boolean {
        val libsToLoad = listOf("libonnxruntime.so", "libpunctuation_jni.so")
        
        for (libName in libsToLoad) {
            if (!loadSingleLibrary(context, libName)) {
                Log.e(TAG, "Failed to load $libName")
                return false
            }
        }
        
        nativeLoaded = true
        Log.d(TAG, "All native libraries loaded successfully")
        return true
    }
    
    private fun loadSingleLibrary(context: Context, libName: String): Boolean {
        val simpleName = libName.removePrefix("lib").removeSuffix(".so")
        
        try {
            System.loadLibrary(simpleName)
            Log.d(TAG, "Loaded $libName via System.loadLibrary")
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                Log.d(TAG, "$libName already loaded, skipping")
                return true
            }
            Log.d(TAG, "System.loadLibrary failed for $libName: ${e.message}")
            
            val nativeLibDir = context.applicationInfo?.nativeLibraryDir
            if (nativeLibDir != null) {
                val libFile = File(nativeLibDir, libName)
                if (libFile.exists()) {
                    try {
                        System.load(libFile.absolutePath)
                        Log.d(TAG, "Loaded $libName from nativeLibraryDir")
                        return true
                    } catch (e2: UnsatisfiedLinkError) {
                        if (e2.message?.contains("already opened") == true || e2.message?.contains("already loaded") == true) {
                            return true
                        }
                        Log.e(TAG, "Failed to load from nativeLibraryDir: ${e2.message}")
                    }
                }
            }
            
            return false
        }
    }
    
    fun loadVocabFromFile(vocabPath: String): Boolean {
        if (vocabLoaded) return true
        
        try {
            val file = File(vocabPath)
            if (!file.exists()) {
                Log.e(TAG, "Vocab file not found: $vocabPath")
                return false
            }
            val jsonStr = file.readText()
            val json = JSONObject(jsonStr)
            
            charToId.clear()
            
            // Support both Android format {"char_to_id": {...}} and flat Python format {char: id}
            if (json.has("char_to_id")) {
                val charToIdObj = json.getJSONObject("char_to_id")
                val keys = charToIdObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    charToId[key] = charToIdObj.getInt(key)
                }
                
                val idToPuncObj = json.getJSONObject("id_to_punctuation")
                idToPunctuation.clear()
                val puncKeys = idToPuncObj.keys()
                while (puncKeys.hasNext()) {
                    val key = puncKeys.next()
                    idToPunctuation[key.toInt()] = idToPuncObj.getString(key)
                }
            } else {
                // Flat format: {char: id}
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    charToId[key] = json.getInt(key)
                }
                
                // Default punctuation mapping
                idToPunctuation.clear()
                idToPunctuation[0] = ""
                idToPunctuation[1] = "，"
                idToPunctuation[2] = "。"
                idToPunctuation[3] = "？"
                idToPunctuation[4] = "！"
            }
            
            vocabLoaded = true
            Log.d(TAG, "Vocab loaded from file: ${charToId.size} chars, ${idToPunctuation.size} punctuation labels")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab from file: ${e.message}")
            FileLogger.e(TAG, "Failed to load vocab from file: ${e.message}", e)
            return false
        }
    }

    fun initialize(context: Context, modelPath: String, vocabPath: String): Boolean {
        if (!loadVocabFromFile(vocabPath)) {
            FileLogger.e(TAG, "Failed to load vocab")
            return false
        }
        
        try {
            nativeInitialize(modelPath)
            Log.d(TAG, "Native method already available")
            return true
        } catch (e: UnsatisfiedLinkError) {
            Log.d(TAG, "Native method not available, loading libraries...")
        }
        
        if (!loadNativeLibrary(context)) {
            FileLogger.e(TAG, "Native libraries not loaded")
            return false
        }
        
        return try {
            nativeInitialize(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            FileLogger.e(TAG, "Native method still unavailable: ${e.message}")
            nativeLoaded = false
            false
        }
    }
    
    fun predict(text: String): String {
        if (!vocabLoaded || text.isEmpty()) {
            FileLogger.d(TAG, "Predict skipped: vocabLoaded=$vocabLoaded, text empty=${text.isEmpty()}")
            return text
        }
        
        val inputIds = tokenize(text)
        if (inputIds.isEmpty()) {
            FileLogger.d(TAG, "Predict skipped: inputIds empty after tokenization")
            return text
        }
        
        FileLogger.d(TAG, "Tokenized '$text' -> inputIds=$inputIds")
        
        try {
            val labelsStr = nativePredict(inputIds.toIntArray())
            val labels = labelsStr.split(",").mapNotNull { it.toIntOrNull() }
            
            FileLogger.d(TAG, "Punctuation prediction: text='$text', inputIds=$inputIds, labels=$labels")
            
            // Build result with punctuation for each position
            val result = StringBuilder()
            val textChars = text.toCharArray()
            
            for (i in textChars.indices) {
                result.append(textChars[i])
                
                // Get label for this position (if available)
                if (i < labels.size) {
                    val label = labels[i]
                    val punctuation = idToPunctuation[label] ?: ""
                    if (punctuation.isNotEmpty()) {
                        result.append(punctuation)
                    }
                }
            }
            
            val finalResult = result.toString()
            FileLogger.d(TAG, "Punctuation result: '$text' -> '$finalResult'")
            
            return finalResult
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed: ${e.message}")
            FileLogger.e(TAG, "Prediction failed for '$text': ${e.message}", e)
            return text
        }
    }
    
    private fun tokenize(text: String): List<Int> {
        return text.map { char ->
            charToId[char.toString()] ?: UNK_ID
        }
    }
    
    fun release() {
        try {
            nativeRelease()
        } catch (e: UnsatisfiedLinkError) {
            Log.d(TAG, "Native release not available")
        }
        nativeLoaded = false
    }
    
    fun isInitialized(): Boolean {
        return try {
            nativeIsInitialized()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
    
    private external fun nativeInitialize(modelPath: String): Boolean
    private external fun nativePredict(inputIds: IntArray): String
    private external fun nativeRelease()
    private external fun nativeIsInitialized(): Boolean
}