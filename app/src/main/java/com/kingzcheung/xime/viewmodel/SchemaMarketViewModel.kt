package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.MarketSchemeItem
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.XimeIndexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaMarketUiState(
    val schemes: List<MarketSchemeItem> = emptyList(),
    val isLoading: Boolean = false,
    /** 正在下载的方案 id */
    val downloadingId: String? = null,
    /** 下载进度 0f~1f */
    val downloadProgress: Float = 0f,
    /** 已下载到 market 目录的方案 id（压缩包文件存在） */
    val downloadedIds: Set<String> = emptySet(),
    /** sha256 校验状态：null=未提供sha256, true=校验通过, false=校验不通过 */
    val sha256Status: Map<String, Boolean?> = emptyMap(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val searchQuery: String = "",
    // 本次方案列表实际命中的来源端点主机名（如 index.ximei.me），用于在界面上显示「从哪个端点拉的」
    val source: String = "",
    /** 用户选择的版本：schemeId → version 字符串 */
    val selectedVersions: Map<String, String> = emptyMap(),
    /** 索引文件最后更新时间 */
    val updatedAt: String = "",
) {
    val filteredSchemes: List<MarketSchemeItem>
        get() = if (searchQuery.isBlank()) schemes else schemes.filter {
            val q = searchQuery.trim()
            it.scheme.name.contains(q, true) ||
                it.scheme.description.contains(q, true) ||
                it.scheme.tags.any { t -> t.contains(q, true) }
        }
}

class SchemaMarketViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaMarketUiState())
    val uiState: StateFlow<SchemaMarketUiState> = _uiState.asStateFlow()

    init {
        loadSchemes()
    }

    /** 加载/刷新方案列表。[manual] 为 true 时（用户点刷新）成功也弹 toast 并报告来源端点。 */
    fun loadSchemes(manual: Boolean = false) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = XimeIndexSource.fetchSchemes(context, BuildConfig.VERSION_NAME)
            // 「已下载」靠 market/{schemeId}/ 子目录存在性判断
            val downloadedIds = withContext(Dispatchers.IO) {
                val marketDir = SchemaManager.getMarketDir(context)
                if (!marketDir.exists()) emptySet()
                else marketDir.listFiles()?.mapNotNull { sub ->
                    if (sub.isDirectory && sub.listFiles()?.any { it.isFile } == true) sub.name
                    else null
                }?.toSet() ?: emptySet()
            }
            result.onSuccess { fetch ->
                if (fetch.schemes.isEmpty()) {
                    // 索引可达但没取到任何方案：当作软失败处理，不要用空列表覆盖已有数据
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            source = fetch.source,
                            errorMessage = if (it.schemes.isEmpty())
                                "未获取到方案（来源：${fetch.source}），请检查网络后刷新" else it.errorMessage,
                            toastMessage = if (it.schemes.isEmpty()) "未获取到方案（来源：${fetch.source}）"
                                else "刷新未获取到方案，已保留当前列表",
                        )
                    }
                } else {
                    val existingSel = _uiState.value.selectedVersions
                    val mergedSel = fetch.schemes.associate { item ->
                        val id = item.scheme.id
                        val keep = existingSel[id]
                        if (keep != null && item.scheme.versions.any { it.version == keep }) {
                            id to keep
                        } else {
                            val v = item.scheme.resolvedVersion()
                            id to (v?.version ?: item.scheme.currentVersion)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            schemes = fetch.schemes,
                            isLoading = false,
                            source = fetch.source,
                            updatedAt = fetch.updatedAt,
                            errorMessage = null,
                            downloadedIds = downloadedIds,
                            selectedVersions = mergedSel,
                            toastMessage = if (manual) "已刷新 · 来源：${fetch.source}" else it.toastMessage,
                        )
                    }
                }
            }.onFailure { e ->
                val msg = e.message ?: "加载方案市场失败"
                // 失败一律弹 toast；已有列表时还保留旧数据，空列表时另外保留整页错误+重试
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.schemes.isEmpty()) msg else it.errorMessage,
                        toastMessage = if (it.schemes.isEmpty()) msg else "刷新失败：$msg",
                    )
                }
            }
        }
    }

    fun setSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }

    /** 选择指定方案的版本。 */
    fun selectVersion(schemeId: String, version: String) {
        _uiState.update { it.copy(selectedVersions = it.selectedVersions + (schemeId to version)) }
    }

    /** 下载方案到 market 目录（仅下载，不解压）。 */
    fun downloadScheme(item: MarketSchemeItem) {
        if (_uiState.value.downloadingId != null) {
            showToast("有其他方案正在下载，请稍候")
            return
        }
        if (!item.compatible) {
            showToast("需 App ≥ ${item.minAppVersion}")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingId = item.scheme.id, downloadProgress = 0f) }
            val selectedVersion = _uiState.value.selectedVersions[item.scheme.id]
            val result = withContext(Dispatchers.IO) {
                XimeIndexSource.downloadScheme(
                    context, item.scheme,
                    version = selectedVersion,
                    onDownloadProgress = { downloaded, total ->
                        val progress = if (total > 0) (downloaded.toFloat() / total) else 0f
                        _uiState.update { it.copy(downloadProgress = progress) }
                    },
                )
            }
            val nowDownloaded = withContext(Dispatchers.IO) {
                SchemaManager.isSchemeDownloaded(context, item.scheme.id)
            }
            _uiState.update { st ->
                st.copy(
                    downloadingId = null,
                    downloadProgress = 0f,
                    downloadedIds = if (nowDownloaded) st.downloadedIds + item.scheme.id else st.downloadedIds,
                    sha256Status = if (result.success || result.sha256Status == false)
                        st.sha256Status + (item.scheme.id to result.sha256Status)
                    else st.sha256Status,
                )
            }
            val toast = when {
                !result.success && result.sha256Status == false ->
                    "sha256 校验不通过，文件可能不完整，请重新下载"
                !result.success -> result.failureReason ?: "下载失败"
                result.sha256Status == true -> "已下载「${item.scheme.name}」（已校验）"
                result.sha256Status == null -> "已下载「${item.scheme.name}」（未验证）"
                else -> "已下载「${item.scheme.name}」"
            }
            showToast(toast)
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}
