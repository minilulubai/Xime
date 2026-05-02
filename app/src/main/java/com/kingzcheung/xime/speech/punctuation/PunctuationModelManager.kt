package com.kingzcheung.xime.speech.punctuation

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class PunctuationModelManager(private val context: Context) {

    companion object {
        private const val TAG = "PunctuationModelManager"
        private const val MODEL_DIR = "punctuation_models"
        private const val MODEL_ID = "punctuation_int8"
        private const val MODEL_FILE = "punctuation_int8.onnx"
        private const val VOCAB_FILE = "vocab.json"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 120L
        
        val PUNCTUATION_MODEL = PunctuationModelInfo(
            id = MODEL_ID,
            name = "标点预测模型 int8",
            description = "基于 Transformer 的中文标点预测模型，int8 量化",
            size = "2.2 MB",
            modelDownloadUrl = "https://www.modelscope.cn/models/bikeand/srf-punctuation/resolve/master/punctuation_int8.onnx",
            vocabDownloadUrl = "https://www.modelscope.cn/models/bikeand/srf-punctuation/resolve/master/vocab.json"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class PunctuationModelInfo(
        val id: String,
        val name: String,
        val description: String,
        val size: String,
        val modelDownloadUrl: String,
        val vocabDownloadUrl: String
    )

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Error(val message: String) : DownloadState()
        object Complete : DownloadState()
    }

    fun getModelDir(): File {
        return File(context.filesDir, MODEL_DIR)
    }

    fun getModelFile(): File {
        return File(getModelDir(), MODEL_FILE)
    }

    fun getVocabFile(): File {
        return File(getModelDir(), VOCAB_FILE)
    }

    fun isModelDownloaded(): Boolean {
        return getModelFile().exists() && getModelFile().length() > 0 &&
               getVocabFile().exists() && getVocabFile().length() > 0
    }

    fun getModelSizeOnDisk(): Long {
        val file = getModelFile()
        return if (file.exists()) file.length() else 0
    }

    suspend fun downloadModel(
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Starting punctuation model download")
        
        val modelDir = getModelDir()
        modelDir.mkdirs()

        val modelFile = getModelFile()
        val vocabFile = getVocabFile()
        
        try {
            // Download model file
            onProgress(DownloadState.Downloading(0f, 0, -1))
            downloadFile(PUNCTUATION_MODEL.modelDownloadUrl, modelFile, onProgress)

            // Download vocab file
            downloadFile(PUNCTUATION_MODEL.vocabDownloadUrl, vocabFile, onProgress)

            FileLogger.i(TAG, "Punctuation model downloaded successfully")
            onProgress(DownloadState.Complete)
            Log.d(TAG, "Punctuation model downloaded to ${modelFile.absolutePath}")
        } catch (e: Exception) {
            modelFile.delete()
            vocabFile.delete()
            FileLogger.e(TAG, "Failed to download punctuation model: ${e.message}", e)
            Log.e(TAG, "Failed to download punctuation model", e)
            onProgress(DownloadState.Error("下载失败: ${e.message}"))
        }
    }

    private suspend fun downloadFile(
        url: String,
        targetFile: File,
        onProgress: (DownloadState) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}")
        }

        val totalBytes = response.body?.contentLength() ?: -1L
        var downloadedBytes = 0L

        response.body?.byteStream()?.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                        onProgress(DownloadState.Downloading(progress, downloadedBytes, totalBytes))
                    }
                }
            }
        }

        FileLogger.i(TAG, "Downloaded ${targetFile.name}: $downloadedBytes bytes")
    }

    fun deleteModel(): Boolean {
        val modelFile = getModelFile()
        val vocabFile = getVocabFile()
        val deletedModel = if (modelFile.exists()) modelFile.delete() else true
        val deletedVocab = if (vocabFile.exists()) vocabFile.delete() else true
        return deletedModel && deletedVocab
    }

    fun getDownloadSize(): Long {
        return try {
            val request = Request.Builder().head().url(PUNCTUATION_MODEL.modelDownloadUrl).build()
            val response = client.newCall(request).execute()
            response.body?.contentLength() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}