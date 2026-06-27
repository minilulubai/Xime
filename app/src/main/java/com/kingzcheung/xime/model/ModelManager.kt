package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File

object ModelManager {

    private const val TAG = "ModelManager"

    private var initialized = false
    private val remoteModels = mutableListOf<ModelInfo>()

    fun initialize() {
        if (initialized) return
        initialized = true
        FileLogger.i(TAG, "ModelManager initialized, ${Models.ALL.size} built-in models")
    }

    private fun allModels(): List<ModelInfo> {
        return if (remoteModels.isNotEmpty()) remoteModels else Models.ALL
    }

    fun getAllModels(): List<ModelInfo> = allModels()

    fun getModel(id: String): ModelInfo? = allModels().find { it.id == id }

    fun getModelsByCategory(category: ModelCategory): List<ModelInfo> =
        allModels().filter { it.category == category }

    suspend fun loadFromRemote(context: Context) {
        val remote = ModelIndexLoader.loadFromRemote(context)
        if (remote.isNotEmpty()) {
            remoteModels.clear()
            remoteModels.addAll(remote)
            FileLogger.i(TAG, "Loaded ${remote.size} models from remote index")
        } else {
            FileLogger.w(TAG, "Remote index returned empty, keeping built-in models")
        }
    }

    fun isUsingRemoteIndex(): Boolean = remoteModels.isNotEmpty()

    fun isModelDownloaded(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return isModelDownloaded(context, model)
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        return model.files.all { fileInfo ->
            val file = if (model.archiveUrl != null) {
                findFileInDir(dir, fileInfo.name)
            } else {
                File(dir, fileInfo.name)
            }
            file.exists() && file.length() > 0
        }
    }

    fun getModelStorageDir(context: Context, model: ModelInfo): File? {
        return if (model.storageDir.isEmpty()) {
            context.filesDir
        } else {
            File(context.filesDir, model.storageDir)
        }
    }

    fun getModelStorageDir(context: Context, id: String): File? {
        val model = getModel(id) ?: return null
        return getModelStorageDir(context, model)
    }

    fun getModelSizeOnDisk(context: Context, id: String): Long {
        val model = getModel(id) ?: return 0
        val dir = getModelStorageDir(context, model) ?: return 0
        if (!dir.exists()) return 0

        return model.files.sumOf { fileInfo ->
            val file = if (model.archiveUrl != null) {
                findFileInDir(dir, fileInfo.name)
            } else {
                File(dir, fileInfo.name)
            }
            if (file.exists()) file.length() else 0
        }
    }

    fun getDownloadedModels(context: Context): List<ModelInfo> {
        return allModels().filter { isModelDownloaded(context, it) }
    }

    suspend fun downloadModel(
        context: Context,
        id: String,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val model = getModel(id)
        if (model == null) {
            onProgress(ModelDownloadState.Error("未知模型: $id"))
            return
        }
        downloadModel(context, model, onProgress)
    }

    suspend fun downloadModel(
        context: Context,
        model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        ModelDownloader.downloadModel(context, model, onProgress)
    }

    fun deleteModel(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return deleteModel(context, model)
    }

    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        var success = true
        for (fileInfo in model.files) {
            val file = if (model.archiveUrl != null) {
                findFileInDir(dir, fileInfo.name)
            } else {
                File(dir, fileInfo.name)
            }
            if (file.exists() && !file.delete()) {
                success = false
            }
        }

        return success
    }

    private fun findFileInDir(dir: File, fileName: String): File {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct

        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFileInDir(child, fileName)
                if (found.exists()) return found
            }
        }

        return direct
    }
}
