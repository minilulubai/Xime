package com.kingzcheung.xime.model

import android.content.Context
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

object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val CONNECT_TIMEOUT = 30L
    private const val READ_TIMEOUT = 120L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun downloadModel(
        context: Context,
        modelInfo: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Starting download: ${modelInfo.id}")

        val storageDir = getStorageDir(context, modelInfo)
        storageDir.mkdirs()

        try {
            if (modelInfo.archiveUrl != null) {
                downloadAndExtractArchive(context, modelInfo, storageDir, onProgress)
            } else {
                downloadFiles(modelInfo, storageDir, onProgress)
            }
            FileLogger.i(TAG, "Download complete: ${modelInfo.id}")
            onProgress(ModelDownloadState.Complete)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Download failed: ${modelInfo.id}: ${e.message}", e)
            onProgress(ModelDownloadState.Error("下载失败: ${e.message}"))
        }
    }

    private fun getStorageDir(context: Context, modelInfo: ModelInfo): File {
        return if (modelInfo.storageDir.isEmpty()) {
            context.filesDir
        } else {
            File(context.filesDir, modelInfo.storageDir)
        }
    }

    private suspend fun downloadFiles(
        modelInfo: ModelInfo,
        targetDir: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val totalFiles = modelInfo.files.size

        modelInfo.files.forEachIndexed { index, file ->
            FileLogger.i(TAG, "Downloading ${file.name} (${index + 1}/$totalFiles)")
            downloadSingleFile(file.downloadUrl, File(targetDir, file.name)) { fileProgress ->
                val overall = (index.toFloat() + fileProgress) / totalFiles
                onProgress(ModelDownloadState.Downloading(overall, 0, -1))
            }
        }
    }

    private suspend fun downloadSingleFile(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
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
                        onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                    }
                }
            }
        }

        FileLogger.i(TAG, "Downloaded ${targetFile.name}: $downloadedBytes bytes")
    }

    private suspend fun downloadAndExtractArchive(
        context: Context,
        modelInfo: ModelInfo,
        targetDir: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val archiveUrl = modelInfo.archiveUrl ?: return
        val tmpFile = File(context.cacheDir, "${modelInfo.id}.tar.bz2")

        try {
            onProgress(ModelDownloadState.Downloading(0f, 0, -1))

            val request = Request.Builder().url(archiveUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val totalBytes = response.body?.contentLength() ?: -1L
            var downloadedBytes = 0L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()) * 0.85f
                            onProgress(ModelDownloadState.Downloading(progress, downloadedBytes, totalBytes))
                        }
                    }
                }
            }

            onProgress(ModelDownloadState.Downloading(0.85f, downloadedBytes, totalBytes))

            extractTarBz2(tmpFile, targetDir)

            tmpFile.delete()
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, 65536).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            val rawName = entry.name
                            val parts = rawName.split("/", limit = 2)
                            val entryName = if (parts.size > 1) parts[1] else rawName

                            if (entryName.isNotEmpty() && !entry.isDirectory) {
                                val outputFile = File(targetDir, entryName)
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { out ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    suspend fun getDownloadSize(modelInfo: ModelInfo): Long {
        val url = modelInfo.archiveUrl ?: modelInfo.files.firstOrNull()?.downloadUrl ?: return -1
        return try {
            val request = Request.Builder().head().url(url).build()
            val response = client.newCall(request).execute()
            response.body?.contentLength() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
