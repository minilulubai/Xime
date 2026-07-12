package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.FileConflictInfo
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.SchemaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalPackageItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    val downloaded: Boolean,
    val installed: Boolean,
    val schemaCount: Int = 0,
    val fileCount: Int = 0,
) {
    val isImport: Boolean get() = packageId.startsWith("import_")
    val statusLabel: String
        get() = when {
            installed && downloaded -> "已安装"
            installed -> "已安装"
            downloaded -> "已下载"
            else -> ""
        }
}

data class SchemaLocalUiState(
    val packages: List<LocalPackageItem> = emptyList(),
    val isLoading: Boolean = false,
    val installingId: String? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val conflictPackageId: String? = null,
    val conflictingSchemeIds: List<String> = emptyList(),
)

class SchemaLocalViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaLocalUiState())
    val uiState: StateFlow<SchemaLocalUiState> = _uiState.asStateFlow()

    init {
        loadLocalPackages()
    }

    fun loadLocalPackages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val installedPkgs = withContext(Dispatchers.IO) {
                SchemaManifestManager.getInstalledPackages(context)
            }
            val downloadedIds = withContext(Dispatchers.IO) {
                val marketDir = SchemaManager.getMarketDir(context)
                if (!marketDir.exists()) emptySet()
                else marketDir.listFiles()?.mapNotNull { sub ->
                    if (sub.isDirectory && sub.listFiles()?.any { it.isFile } == true) sub.name
                    else null
                }?.toSet() ?: emptySet()
            }
            val installedMap = installedPkgs.associateBy { it.packageId }
            val allIds = (downloadedIds + installedMap.keys).sorted()
            val packages = allIds.map { id ->
                val info = installedMap[id]
                LocalPackageItem(
                    packageId = id,
                    displayName = info?.displayName ?: id,
                    version = info?.version ?: "",
                    downloaded = id in downloadedIds,
                    installed = info != null,
                    schemaCount = info?.schemaCount ?: 0,
                    fileCount = info?.fileCount ?: 0,
                )
            }
            _uiState.update { it.copy(packages = packages, isLoading = false) }
        }
    }

    fun installPackage(item: LocalPackageItem) {
        if (_uiState.value.installingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(installingId = item.packageId) }
            val result = withContext(Dispatchers.IO) {
                SchemaManager.installPackageFromMarketDir(
                    context = context,
                    packageId = item.packageId,
                    displayName = item.displayName,
                    version = item.version,
                    fromMarket = !item.isImport,
                )
            }
            _uiState.update { st -> st.copy(installingId = null) }
            if (!result.success && result.conflicts.isNotEmpty()) {
                val schemeIds = result.conflicts.flatMap { c: FileConflictInfo -> c.claimedBy }.distinct()
                _uiState.update {
                    it.copy(
                        conflictPackageId = item.packageId,
                        conflictingSchemeIds = schemeIds,
                    )
                }
                return@launch
            }
            if (result.success) {
                showToast("已安装「${item.displayName}」，点「部署」生效")
            } else {
                showToast(result.failureReason ?: "安装失败")
            }
            loadLocalPackages()
        }
    }

    fun confirmInstallWithUninstall() {
        if (_uiState.value.installingId != null) return
        val pkgId = _uiState.value.conflictPackageId ?: return
        val item = _uiState.value.packages.firstOrNull { it.packageId == pkgId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(conflictPackageId = null, installingId = pkgId) }
            for (sid in _uiState.value.conflictingSchemeIds) {
                withContext(Dispatchers.IO) {
                    SchemaManifestManager.uninstallWithManifest(context, sid)
                }
            }
            val result = withContext(Dispatchers.IO) {
                SchemaManager.installPackageFromMarketDir(
                    context = context,
                    packageId = pkgId,
                    displayName = item.displayName,
                    version = item.version,
                    fromMarket = !item.isImport,
                )
            }
            _uiState.update { st -> st.copy(installingId = null, conflictingSchemeIds = emptyList()) }
            if (result.success) {
                showToast("已安装「${item.displayName}」，点「部署」生效")
            } else {
                showToast(result.failureReason ?: "安装失败")
            }
            loadLocalPackages()
        }
    }

    fun cancelConflictInstall() {
        _uiState.update { it.copy(conflictPackageId = null, conflictingSchemeIds = emptyList()) }
    }

    fun deleteDownloaded(item: LocalPackageItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val dir = SchemaManager.getMarketDir(context, item.packageId)
                if (dir.exists()) { dir.deleteRecursively(); true } else false
            }
            showToast(if (ok) "已删除" else "删除失败")
            loadLocalPackages()
        }
    }

    fun uninstall(item: LocalPackageItem) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                SchemaManifestManager.uninstallWithManifest(context, item.packageId)
            }
            showToast(result.message)
            loadLocalPackages()
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}
