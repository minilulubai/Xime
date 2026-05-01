package com.kingzcheung.xime.speech.sherpa

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val MODELS_DIR = "asr_models"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 120L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Error(val message: String) : DownloadState()
        object Complete : DownloadState()
    }

    data class DownloadProgress(
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = -1,
        val progress: Float = 0f
    )

    fun getModelsDir(): File {
        return File(context.filesDir, MODELS_DIR)
    }

    fun getModelDir(modelId: String): File {
        return File(getModelsDir(), modelId)
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        if (!dir.exists()) return false
        val files = dir.listFiles()
        return files != null && files.isNotEmpty()
    }

    fun getDownloadedModelIds(): List<String> {
        val dir = getModelsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            ?.map { it.name }
            ?: emptyList()
    }

    fun getModelSizeOnDisk(modelId: String): Long {
        val dir = getModelDir(modelId)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun downloadModel(
        downloadUrl: String,
        modelId: String,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Starting model download: $modelId from $downloadUrl")
        
        val modelDir = getModelDir(modelId)
        modelDir.mkdirs()

        val tmpFile = File(context.cacheDir, "${modelId}.tar.bz2")
        try {
            onProgress(DownloadState.Downloading(0f, 0, -1))

            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                FileLogger.e(TAG, "Model download failed: HTTP ${response.code}")
                onProgress(DownloadState.Error("下载失败: HTTP ${response.code}"))
                return@withContext
            }

            val totalBytes = response.body?.contentLength() ?: -1L
            FileLogger.i(TAG, "Download started: total size = ${if (totalBytes > 0) "$totalBytes bytes" else "unknown"}")
            var downloadedBytes = 0L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tmpFile).use { output ->
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

            FileLogger.i(TAG, "Download complete: $downloadedBytes bytes, extracting...")
            onProgress(DownloadState.Downloading(1f, downloadedBytes, totalBytes))

            extractTarBz2(tmpFile, modelDir, onProgress)

            tmpFile.delete()

            FileLogger.i(TAG, "Model $modelId downloaded and extracted successfully")
            onProgress(DownloadState.Complete)
            Log.d(TAG, "Model $modelId downloaded and extracted successfully")
        } catch (e: Exception) {
            tmpFile.delete()
            FileLogger.e(TAG, "Failed to download model $modelId: ${e.message}", e)
            Log.e(TAG, "Failed to download model $modelId", e)
            onProgress(DownloadState.Error("下载失败: ${e.message}"))
        }
    }

    private fun extractTarBz2(
        archiveFile: File,
        targetDir: File,
        onProgress: (DownloadState) -> Unit
    ) {
        try {
            FileInputStream(archiveFile).use { fis ->
                java.io.BufferedInputStream(fis, 65536).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextEntry
                            var count = 0
                            while (entry != null) {
                                val rawName = entry.name
                                val parts = rawName.split("/", limit = 2)
                                val entryName = if (parts.size > 1) parts[1] else rawName

                                Log.d(TAG, "Entry #$count: raw='$rawName' -> stripped='$entryName' isDir=${entry.isDirectory}")

                                if (entryName.isEmpty() || entry.isDirectory) {
                                    entry = tarIn.nextEntry
                                    count++
                                    continue
                                }

                                val outputFile = File(targetDir, entryName)
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { out ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                                Log.d(TAG, "Extracted: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                                entry = tarIn.nextEntry
                                count++
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "Extraction complete to ${targetDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            throw IOException("Failed to extract archive", e)
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun getDownloadSize(downloadUrl: String): Long {
        return try {
            val request = Request.Builder().head().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            response.body?.contentLength() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
