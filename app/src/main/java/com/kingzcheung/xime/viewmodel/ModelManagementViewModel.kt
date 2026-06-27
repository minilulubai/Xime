package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelItemState(
    val model: ModelInfo,
    val isDownloaded: Boolean = false,
    val diskSize: Long = 0,
    val downloadState: ModelDownloadState = ModelDownloadState.Idle
)

data class ModelManagementUiState(
    val models: List<ModelItemState> = emptyList(),
    val isLoading: Boolean = true,
    val toastMessage: String? = null
)

class ModelManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(ModelManagementUiState())
    val uiState: StateFlow<ModelManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadModels()
            refreshFromRemote()
        }
    }

    private suspend fun loadModels() {
        val allModels = ModelManager.getAllModels()
        val items = allModels.map { model ->
            val downloaded = withContext(Dispatchers.IO) {
                ModelManager.isModelDownloaded(context, model)
            }
            val size = if (downloaded) {
                withContext(Dispatchers.IO) {
                    ModelManager.getModelSizeOnDisk(context, model.id)
                }
            } else 0L
            ModelItemState(
                model = model,
                isDownloaded = downloaded,
                diskSize = size
            )
        }
        _uiState.update { it.copy(models = items, isLoading = false) }
    }

    private suspend fun refreshFromRemote() {
        ModelManager.loadFromRemote(context)
        loadModels()
    }

    fun downloadModel(modelId: String) {
        val index = _uiState.value.models.indexOfFirst { it.model.id == modelId }
        if (index < 0) return

        viewModelScope.launch {
            updateModelState(index) {
                it.copy(downloadState = ModelDownloadState.Downloading(0f, 0, -1))
            }

            var downloadError: String? = null
            ModelManager.downloadModel(context, modelId) { state ->
                when (state) {
                    is ModelDownloadState.Downloading -> {
                        updateModelState(index) { it.copy(downloadState = state) }
                    }
                    is ModelDownloadState.Error -> {
                        downloadError = state.message
                        updateModelState(index) { it.copy(downloadState = state) }
                    }
                    else -> {}
                }
            }

            if (downloadError != null) {
                updateModelState(index) { it.copy(downloadState = ModelDownloadState.Idle) }
            } else {
                val downloaded = withContext(Dispatchers.IO) {
                    ModelManager.isModelDownloaded(context, modelId)
                }
                val size = if (downloaded) {
                    withContext(Dispatchers.IO) {
                        ModelManager.getModelSizeOnDisk(context, modelId)
                    }
                } else 0L
                updateModelState(index) {
                    it.copy(
                        isDownloaded = downloaded,
                        diskSize = size,
                        downloadState = ModelDownloadState.Idle
                    )
                }
                if (downloaded) {
                    _uiState.update { s -> s.copy(toastMessage = "模型下载完成") }
                }
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                ModelManager.deleteModel(context, modelId)
            }
            if (success) {
                val index = _uiState.value.models.indexOfFirst { it.model.id == modelId }
                if (index >= 0) {
                    updateModelState(index) { it.copy(isDownloaded = false, diskSize = 0) }
                }
                _uiState.update { s -> s.copy(toastMessage = "模型已删除") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun updateModelState(index: Int, transform: (ModelItemState) -> ModelItemState) {
        _uiState.update { state ->
            val updated = state.models.toMutableList()
            updated[index] = transform(updated[index])
            state.copy(models = updated)
        }
    }
}
