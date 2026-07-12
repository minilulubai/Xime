package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileConflictInfo(
    val fileName: String,
    val existingSha256: String,
    val newSha256: String,
    val claimedBy: List<String>,
) {
    val isRealConflict: Boolean get() = existingSha256 != newSha256
}

data class UninstallResult(
    val success: Boolean,
    val deletedFiles: Int = 0,
    val manifestExisted: Boolean = true,
    val message: String = "",
)

/**
 * 方案文件清单管理系统：
 * - 全局注册表 (.registry.json)：记录 rime/ 下每个文件被哪些方案声明拥有
 * - 方案清单 (.manifests/{schemeId}.json)：记录单个方案安装的全部文件
 *
 * 用于文件冲突检测、精准卸载、依赖共享追踪。
 */
object SchemaManifestManager {
    private const val TAG = "SchemaManifestManager"
    private const val REGISTRY_FILE = ".registry.json"
    private const val MANIFESTS_DIR = ".manifests"
    private const val REGISTRY_VERSION = 1

    fun getRegistryFile(context: Context): File =
        File(SchemaManager.getRimeDir(context), REGISTRY_FILE)

    fun getManifestsDir(context: Context): File =
        File(SchemaManager.getRimeDir(context), MANIFESTS_DIR)

    fun getManifestFile(context: Context, schemeId: String): File =
        File(getManifestsDir(context), "$schemeId.json")

    // ── Registry ──

    suspend fun loadRegistry(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val file = getRegistryFile(context)
        if (!file.exists()) {
            JSONObject().apply {
                put("version", REGISTRY_VERSION)
                put("files", JSONObject())
            }
        } else {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "registry corrupted, resetting", e)
                JSONObject().apply {
                    put("version", REGISTRY_VERSION)
                    put("files", JSONObject())
                }
            }
        }
    }

    private suspend fun saveRegistry(context: Context, registry: JSONObject) {
        withContext(Dispatchers.IO) {
            val file = getRegistryFile(context)
            file.parentFile?.mkdirs()
            file.writeText(registry.toString(2))
        }
    }

    // ── Per-scheme Manifest ──

    suspend fun loadManifest(context: Context, schemeId: String): JSONObject? = withContext(Dispatchers.IO) {
        val file = getManifestFile(context, schemeId)
        if (!file.exists()) return@withContext null
        try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "manifest for $schemeId corrupted", e)
            null
        }
    }

    private suspend fun saveManifest(context: Context, schemeId: String, manifest: JSONObject) {
        withContext(Dispatchers.IO) {
            val file = getManifestFile(context, schemeId)
            file.parentFile?.mkdirs()
            file.writeText(manifest.toString(2))
        }
    }

    suspend fun deleteManifest(context: Context, schemeId: String) {
        withContext(Dispatchers.IO) {
            getManifestFile(context, schemeId).delete()
        }
    }

    // ── SHA256 helper ──

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fileSha256(file: File): String? {
        return try {
            sha256Hex(file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}", e)
            null
        }
    }

    // ── Conflict Detection ──

    /**
     * 检测待安装方案与已安装方案的文件冲突。
     * 仅报告真正冲突（同名不同内容）；同内容视为共享依赖自动放行。
     *
     * @param newFileSha256 待安装方案中各目标文件的 sha256 映射。
     *   从市场包的归档内容计算得到，而非从 rime/ 目录下已有文件计算。
     */
    suspend fun detectConflicts(
        context: Context,
        schemeId: String,
        targetFiles: List<String>,
        newFileSha256: Map<String, String> = emptyMap(),
    ): List<FileConflictInfo> = withContext(Dispatchers.IO) {
        val registry = loadRegistry(context)
        val files = registry.optJSONObject("files") ?: return@withContext emptyList()
        val conflicts = mutableListOf<FileConflictInfo>()

        for (fileName in targetFiles) {
            val entry = files.optJSONObject(fileName) ?: continue
            val claimants = entry.optJSONArray("claimedBy") ?: continue
            // 同一方案重新安装/升级：不视为冲突
            if (jsonArrayToList(claimants).any { it == schemeId }) continue

            val existingSha256 = entry.optString("sha256", "")
            val newSha256 = newFileSha256[fileName] ?: continue
            if (existingSha256 != newSha256) {
                conflicts.add(FileConflictInfo(
                    fileName = fileName,
                    existingSha256 = existingSha256,
                    newSha256 = newSha256,
                    claimedBy = jsonArrayToList(claimants),
                ))
            }
        }
        conflicts
    }

    // ── Create Manifest After Installation ──

    /**
     * 安装成功后，为方案创建文件清单并更新全局注册表。
     * @param extractedFiles 安装到 rime/ 的文件相对路径列表
     */
    suspend fun createManifest(
        context: Context,
        schemeId: String,
        displayName: String,
        version: String = "",
        fromMarket: Boolean = true,
        extractedFiles: List<String>,
        dependencyIds: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rimeDir = SchemaManager.getRimeDir(context)
            val fileEntries = JSONObject()

            for (fileName in extractedFiles) {
                val file = File(rimeDir, fileName)
                if (!file.exists()) continue
                val sha256 = fileSha256(file) ?: continue
                fileEntries.put(fileName, JSONObject().apply {
                    put("sha256", sha256)
                    put("size", file.length())
                })
            }

            val manifest = JSONObject().apply {
                put("schemeId", schemeId)
                put("displayName", displayName)
                put("version", version)
                put("installedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                put("fromMarket", fromMarket)
                put("legacy", false)
                put("files", fileEntries)
                if (dependencyIds.isNotEmpty()) {
                    put("dependencies", JSONArray(dependencyIds))
                }
            }
            saveManifest(context, schemeId, manifest)

            // 更新全局注册表
            val registry = loadRegistry(context)
            val allFiles = registry.optJSONObject("files") ?: JSONObject()
            val keysIt = fileEntries.keys()
            while (keysIt.hasNext()) {
                val fn = keysIt.next() as String
                val fe = fileEntries.getJSONObject(fn)
                val existing = allFiles.optJSONObject(fn)
                if (existing != null) {
                    val claimants = existing.optJSONArray("claimedBy") ?: JSONArray()
                    if (!jsonArrayToList(claimants).contains(schemeId)) {
                        claimants.put(schemeId)
                    }
                    existing.put("claimedBy", claimants)
                } else {
                    allFiles.put(fn, JSONObject().apply {
                        put("sha256", fe.getString("sha256"))
                        put("size", fe.getLong("size"))
                        put("claimedBy", JSONArray(listOf(schemeId)))
                    })
                }
            }
            registry.put("files", allFiles)
            saveRegistry(context, registry)

            Log.i(TAG, "manifest created for $schemeId: ${fileEntries.length()} files")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to create manifest for $schemeId", e)
            false
        }
    }

    /** 在安装依赖后，把依赖包 id 追加到主方案的清单中。 */
    suspend fun appendDependencies(
        context: Context,
        schemeId: String,
        depIds: List<String>,
    ) {
        if (depIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val manifest = loadManifest(context, schemeId) ?: return@withContext
                val existing = mutableListOf<String>()
                val depsArray = manifest.optJSONArray("dependencies")
                if (depsArray != null) {
                    for (i in 0 until depsArray.length()) {
                        existing.add(depsArray.getString(i))
                    }
                }
                val merged = (existing + depIds).distinct()
                manifest.put("dependencies", JSONArray(merged))
                saveManifest(context, schemeId, manifest)
            } catch (e: Exception) {
                Log.e(TAG, "appendDependencies failed for $schemeId", e)
            }
        }
    }

    // ── Uninstall Using Manifest ──

    /**
     * 基于清单卸载方案，整体删除方案所属的所有文件（不保留共享文件）。
     */
    suspend fun uninstallWithManifest(
        context: Context,
        schemeId: String,
    ): UninstallResult = withContext(Dispatchers.IO) {
        val manifest = loadManifest(context, schemeId)
        if (manifest == null) {
            return@withContext UninstallResult(
                success = false,
                manifestExisted = false,
                message = "清单不存在，请使用传统方式删除",
            )
        }

        try {
            val files = manifest.optJSONObject("files") ?: JSONObject()
            val rimeDir = SchemaManager.getRimeDir(context)
            val registry = loadRegistry(context)
            val allFiles = registry.optJSONObject("files") ?: JSONObject()

            var deletedCount = 0
            val keysIt = files.keys()
            while (keysIt.hasNext()) {
                val fn = keysIt.next() as String
                val file = File(rimeDir, fn)
                if (file.exists()) {
                    file.delete()
                }
                // 从注册表中移除该文件的所有记录
                allFiles.remove(fn)
                deletedCount++
            }

            registry.put("files", allFiles)
            saveRegistry(context, registry)
            getManifestFile(context, schemeId).delete()

            Log.i(TAG, "uninstalled $schemeId: $deletedCount files removed")
            UninstallResult(
                success = true,
                deletedFiles = deletedCount,
                message = "已删除 $deletedCount 个文件",
            )
        } catch (e: Exception) {
            Log.e(TAG, "uninstall failed for $schemeId", e)
            UninstallResult(success = false, message = "卸载失败: ${e.message}")
        }
    }

    /** 检查一个文件是否列入受保护列表（不被方案覆盖和追踪）。 */
    fun isProtectedSystemFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base == "default.yaml" ||
               base == "xime.yaml" ||
               name.startsWith(".registry") ||
               name.startsWith(".manifests") ||
               name.startsWith("build/")
    }

    // ── Migration ──

    private const val KEY_MIGRATION_VERSION = "manifest_migration_version"
    private const val MIGRATION_VERSION_CURRENT = 2
    private const val BUILTIN_PACKAGE_ID = "builtin"

    /**
     * 为旧版已安装方案创建/合并遗留清单。
     * v1 → v2：把旧版单条 per-schema 清单合并成一个 `builtin` 包。
     */
    suspend fun migrateLegacySchemas(context: Context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val prevVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0)
        if (prevVersion >= MIGRATION_VERSION_CURRENT) return

        withContext(Dispatchers.IO) {
            try {
                val rimeDir = SchemaManager.getRimeDir(context)
                getManifestsDir(context).mkdirs()
                var changed = false

                // 清理旧的 per-schema 清单（非 market、非 import、非 builtin）
                val manifestDir = getManifestsDir(context)
                if (manifestDir.exists()) {
                    manifestDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
                        try {
                            val m = JSONObject(file.readText())
                            val id = m.getString("schemeId")
                            if (m.optBoolean("fromMarket", false)) return@forEach
                            if (id == BUILTIN_PACKAGE_ID) return@forEach
                            if (id.startsWith("import_")) return@forEach
                            file.delete()
                            changed = true
                        } catch (_: Exception) { }
                    }
                }

                // 重新扫描所有 .schema.yaml，归入 builtin 包
                val schemaFiles = rimeDir.listFiles { f -> f.name.endsWith(".schema.yaml") }
                    ?: emptyArray()
                if (schemaFiles.isNotEmpty()) {
                    val fileEntries = JSONObject()
                    val allYamlFiles = mutableSetOf<String>()

                    for (sf in schemaFiles) {
                        val schemaId = sf.name.removeSuffix(".schema.yaml")
                        allYamlFiles.add(sf.name)
                        val dictName = SchemaManager.getReferencedDictName(context, schemaId) ?: schemaId
                        val dictFile = File(rimeDir, "$dictName.dict.yaml")
                        if (dictFile.exists()) allYamlFiles.add("$dictName.dict.yaml")
                    }

                    for (fn in allYamlFiles) {
                        val file = File(rimeDir, fn)
                        if (!file.exists()) continue
                        val sha256 = fileSha256(file) ?: continue
                        fileEntries.put(fn, JSONObject().apply {
                            put("sha256", sha256)
                            put("size", file.length())
                        })
                    }

                    val manifest = JSONObject().apply {
                        put("schemeId", BUILTIN_PACKAGE_ID)
                        put("displayName", "系统内置方案")
                        put("version", "")
                        put("installedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                        put("fromMarket", false)
                        put("legacy", true)
                        put("files", fileEntries)
                    }
                    saveManifest(context, BUILTIN_PACKAGE_ID, manifest)

                    val registry = loadRegistry(context)
                    val allFiles = registry.optJSONObject("files") ?: JSONObject()
                    val keysIt = fileEntries.keys()
                    while (keysIt.hasNext()) {
                        val fn = keysIt.next() as String
                        val fe = fileEntries.getJSONObject(fn)
                        allFiles.put(fn, JSONObject().apply {
                            put("sha256", fe.getString("sha256"))
                            put("size", fe.getLong("size"))
                            put("claimedBy", JSONArray(listOf(BUILTIN_PACKAGE_ID)))
                        })
                    }
                    registry.put("files", allFiles)
                    saveRegistry(context, registry)
                    changed = true
                }

                if (changed) {
                    Log.i(TAG, "migration v2: consolidated into '$BUILTIN_PACKAGE_ID'")
                }

                prefs.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION_CURRENT).apply()
            } catch (e: Exception) {
                Log.e(TAG, "legacy migration failed", e)
            }
        }
    }

    // ── Market Package Listing ──

    data class MarketPackageInfo(
        val packageId: String,
        val displayName: String,
        val version: String,
        val schemaCount: Int,
        val fileCount: Int,
    )

    /** 获取所有已安装方案的包信息（从清单目录读取）。 */
    suspend fun getInstalledPackages(context: Context): List<MarketPackageInfo> = withContext(Dispatchers.IO) {
        val manifestDir = getManifestsDir(context)
        if (!manifestDir.exists()) return@withContext emptyList()

        manifestDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    val manifest = JSONObject(file.readText())
                    val files = manifest.optJSONObject("files") ?: JSONObject()
                    val schemaCount = files.keys().asSequence().count { key ->
                        (key as String).endsWith(".schema.yaml")
                    }
                    val id = manifest.getString("schemeId")
                    MarketPackageInfo(
                        packageId = id,
                        displayName = manifest.optString("displayName", id),
                        version = manifest.optString("version", ""),
                        schemaCount = schemaCount,
                        fileCount = files.length(),
                    )
                } catch (_: Exception) { null }
            }?.sortedBy { it.displayName } ?: emptyList()
    }

    // ── Utilities ──

    private fun jsonArrayToList(arr: JSONArray): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            result.add(arr.getString(i))
        }
        return result
    }
}
