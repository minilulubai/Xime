package com.kingzcheung.kime.plugin.prediction

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.kingzcheung.kime.plugin.core.api.PredictionCandidate
import com.kingzcheung.kime.plugin.core.api.PredictionPlugin
import com.kingzcheung.kime.plugin.core.model.PluginContext
import com.kingzcheung.kime.association.AssociationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

class OnnxPredictionPlugin : PredictionPlugin {
    
    private var initialized = false
    
    companion object {
        private const val TAG = "OnnxPredictionPlugin"
    }
    
    override fun onLoad(context: PluginContext) {
        Log.d(TAG, "Plugin loaded: ${context.pluginInfo.id}")
        
        val filesDir = File("/data/data/com.kingzcheung.kime/files")
        if (!filesDir.exists()) filesDir.mkdirs()
        
        initialized = runBlocking(Dispatchers.IO) {
            AssociationManager.initialize(context.application, filesDir, context.pluginInfo.path)
        }
        
        Log.d(TAG, "Initialized: $initialized")
    }
    
    override fun onUnload() {
        if (initialized) {
            AssociationManager.release()
            initialized = false
        }
        Log.d(TAG, "Plugin unloaded")
    }
    
    override suspend fun predict(inputText: String, topK: Int): List<PredictionCandidate> {
        if (!initialized || inputText.isEmpty()) return emptyList()
        
        return try {
            AssociationManager.predict(inputText, topK).map { candidate -> 
                PredictionCandidate(candidate.text, candidate.score) 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            emptyList()
        }
    }
    
    override fun learn(text: String) {
        if (initialized) AssociationManager.recordInput(text)
    }
    
    override suspend fun saveLearnedData() {
        if (initialized) AssociationManager.saveUserData()
    }
    
    override fun hasSettings(): Boolean = true
    
    override fun openSettings(context: Context) {
        try {
            val intent = android.content.Intent()
            intent.setClassName(
                "com.kingzcheung.kime.plugin.prediction",
                "com.kingzcheung.kime.plugin.prediction.PluginSettingsActivity"
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}