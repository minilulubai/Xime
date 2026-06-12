package com.kingzcheung.xime.rime

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"
    
    suspend fun initializeRimeDataAsync(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // F1: assets 会用内置 default.yaml 覆盖，这里把启用方案重新写回 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        
        Log.d(TAG, "Checking for missing schema files...")
        try {
            withTimeout(60_000L) {
                val downloaded = SchemaConfigHelper.downloadMissingSchemas(context)
                if (downloaded.isNotEmpty()) {
                    Log.i(TAG, "Downloaded schemas: $downloaded")
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Schema download timed out, continuing with existing files")
        }
        
        checkAndCleanBuildDir(rimeDir)
        listFilesRecursively(rimeDir, TAG)
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }
    
    fun initializeRimeData(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // F1: 同步初始化路径也写回 default.yaml 的 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        checkAndCleanBuildDir(rimeDir)
        listFilesRecursively(rimeDir, TAG)
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }
    
    fun storeDeploymentHash(context: Context) {
        val hash = computeDeploymentHash(context)
        if (hash.isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, hash)
            Log.d(TAG, "Deployment hash stored: $hash")
        }
    }

    fun isDeploymentComplete(context: Context): Boolean {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return false

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        if (enabledSchemas.isEmpty()) return false

        for (schemaId in enabledSchemas) {
            if (!File(buildDir, "$schemaId.prism.bin").exists() &&
                !File(buildDir, "$schemaId.schema.yaml").exists()) {
                return false
            }
        }

        val currentHash = computeDeploymentHash(context)
        if (currentHash.isEmpty()) return false

        val storedHash = SettingsPreferences.getDeploymentHash(context)
        if (storedHash.isEmpty()) {
            SettingsPreferences.setDeploymentHash(context, currentHash)
            return true
        }

        if (currentHash != storedHash) {
            Log.d(TAG, "Schema files changed, re-deploy needed")
            return false
        }

        return true
    }

    private fun computeDeploymentHash(context: Context): String {
        val rimeDir = File(context.filesDir, "rime")
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        for (schemaId in enabledSchemas.sorted()) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) {
                digest.update(schemaId.toByteArray())
                digest.update(schemaFile.readBytes())
            }
        }

        val defaultYaml = File(rimeDir, "default.yaml")
        if (defaultYaml.exists()) {
            digest.update("default".toByteArray())
            digest.update(defaultYaml.readBytes())
        }

        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun checkAndCleanBuildDir(rimeDir: File) {
        val buildDir = File(rimeDir, "build")
        val defaultYaml = File(rimeDir, "default.yaml")
        
        if (!defaultYaml.exists() || !buildDir.exists()) {
            Log.d(TAG, "default.yaml or build directory not found, skipping check")
            return
        }
        
        try {
            val content = defaultYaml.readText()
            val schemaListRegex = Regex("""schema:\s*(\S+)""")
            val schemas = schemaListRegex.findAll(content).map { it.groupValues[1] }.toList()
            Log.d(TAG, "Schemas in default.yaml: $schemas")
            
            for (schema in schemas) {
                val schemaFile = File(rimeDir, "$schema.schema.yaml")
                val prismFile = File(buildDir, "$schema.prism.bin")
                
                if (schemaFile.exists()) {
                    if (prismFile.exists()) {
                        Log.d(TAG, "Schema $schema already deployed")
                    } else {
                        Log.d(TAG, "Schema $schema needs deployment (missing prism.bin)")
                    }
                } else {
                    Log.d(TAG, "Schema $schema schema file not found, skipping")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse default.yaml", e)
        }
    }
    
    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        try {
            return copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy assets", e)
            return false
        }
    }
    
    private fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "No files found in assets/$assetPath")
            return false
        }
        
        var copiedAny = false
        
        for (fileName in files) {
            val fullAssetPath = "$assetPath/$fileName"
            val targetFile = File(targetDir, fileName)
            
            try {
                val subFiles = context.assets.list(fullAssetPath)
                if (!subFiles.isNullOrEmpty()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                    Log.d(TAG, "Processing subdirectory: $fullAssetPath")
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    val needsCopy = try {
                        if (targetFile.exists()) {
                            val fd = context.assets.openFd(fullAssetPath)
                            val sameSize = targetFile.length() == fd.length
                            fd.close()
                            !sameSize
                        } else true
                    } catch (_: Exception) {
                        true
                    }
                    if (needsCopy) {
                        copyAssetFile(context, fullAssetPath, targetFile)
                        copiedAny = true
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists() && targetFile.name.contains("custom")) {
                return
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied: $assetPath -> ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(context.filesDir, "rime/shared")
        val oldUserDir = File(context.filesDir, "rime/user")
        
        if (!oldSharedDir.exists() && !oldUserDir.exists()) return
        
        Log.i(TAG, "Migrating old rime directory structure to single rime/ dir...")
        
        if (!rimeDir.exists()) rimeDir.mkdirs()
        
        // 迁移 user 数据（用户配置、build 产物、userdb）
        if (oldUserDir.exists()) {
            oldUserDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 迁移 shared 数据（方案文件）
        if (oldSharedDir.exists()) {
            oldSharedDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 删除旧目录
        oldSharedDir.deleteRecursively()
        oldUserDir.deleteRecursively()
        
        Log.i(TAG, "Migration complete")
    }

    private fun listFilesRecursively(dir: File, tag: String, prefix: String = "") {
        val files = dir.listFiles()
        if (files == null) {
            Log.e(tag, "$prefix${dir.name} is empty or not a directory!")
            return
        }
        Log.d(tag, "$prefix${dir.name}/ (${files.size} items)")
        for (file in files) {
            if (file.isDirectory) {
                listFilesRecursively(file, tag, "$prefix  ")
            } else {
                Log.d(tag, "$prefix  ${file.name} (${file.length()} bytes)")
            }
        }
    }
}