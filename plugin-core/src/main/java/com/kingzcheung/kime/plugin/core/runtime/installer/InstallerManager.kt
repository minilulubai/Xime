package com.kingzcheung.kime.plugin.core.runtime.installer

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import java.io.File
import java.util.zip.ZipFile

class InstallerManager(
    private val context: Application,
    private val xmlManager: XmlManager
) {
    companion object {
        private const val PLUGINS_DIR = "plugins"
        private const val PLUGIN_BASE_APK_NAME = "base.apk"
        private const val NATIVE_LIBS_DIR_NAME = "lib"
        private const val CLASS_INDEX_FILENAME = "class_index"
        private const val META_PLUGIN_ENTRY_CLASS = "plugin.entryClass"
        private const val META_PLUGIN_DESCRIPTION = "plugin.description"
        private const val META_PLUGIN_TYPE = "plugin.type"
    }

    sealed class InstallResult {
        data class Success(val pluginInfo: PluginInfo) : InstallResult()
        data class Failure(val reason: String, val exception: Throwable? = null) : InstallResult()
    }

    private val pluginsDir: File by lazy {
        File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }
    }

    suspend fun installPlugin(
        pluginApkFile: File,
        forceOverwrite: Boolean = false
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!pluginApkFile.exists()) {
            return@withContext InstallResult.Failure("插件文件不存在")
        }

        val pluginConfig = parsePluginConfig(pluginApkFile)
            ?: return@withContext InstallResult.Failure("插件配置解析失败")

        val pluginId = pluginConfig.id
        val pluginDir = getPluginDirectory(pluginId)

        val existingPlugin = xmlManager.getPluginById(pluginId)
        if (!forceOverwrite && existingPlugin != null) {
            if (pluginConfig.versionCode <= existingPlugin.versionCode) {
                fixExistingPluginPermissions(pluginDir)
                return@withContext InstallResult.Success(existingPlugin)
            }
        }

        if (forceOverwrite && pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }

        pluginDir.mkdirs()

        try {
            val targetApkFile = copyPluginApk(pluginApkFile, pluginDir)
            val nativeLibPath = extractNativeLibs(pluginApkFile, pluginDir)
            createClassIndex(targetApkFile, pluginDir)

            val pluginInfo = PluginInfo(
                id = pluginConfig.id,
                name = pluginConfig.name,
                iconResId = pluginConfig.iconResId,
                description = pluginConfig.description,
                versionCode = pluginConfig.versionCode,
                versionName = pluginConfig.versionName,
                path = targetApkFile.absolutePath,
                entryClass = pluginConfig.entryClass,
                type = pluginConfig.type,
                enabled = existingPlugin?.enabled ?: true,
                installTime = existingPlugin?.installTime ?: System.currentTimeMillis(),
                nativeLibPath = nativeLibPath
            )

            if (existingPlugin != null) {
                xmlManager.updatePlugin(pluginInfo)
            } else {
                xmlManager.addPlugin(pluginInfo)
            }
            xmlManager.flushToDisk()

            InstallResult.Success(pluginInfo)
        } catch (e: Exception) {
            pluginDir.deleteRecursively()
            InstallResult.Failure("插件安装失败: ${e.message}", e)
        }
    }

    suspend fun uninstallPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val pluginDir = getPluginDirectory(pluginId)
        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        xmlManager.removePlugin(pluginId)
        xmlManager.flushToDisk()
        true
    }

    internal fun getPluginDirectory(pluginId: String): File {
        return File(pluginsDir, pluginId)
    }

    internal fun getOptimizedDirectory(pluginId: String): File? {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            File(File(context.cacheDir, "dex_opt"), pluginId).apply { mkdirs() }
        } else {
            null
        }
    }

    private fun fixExistingPluginPermissions(pluginDir: File) {
        val apkFile = File(pluginDir, PLUGIN_BASE_APK_NAME)
        if (apkFile.exists() && apkFile.canWrite()) {
            apkFile.setReadOnly()
        }
        
        val libDir = File(pluginDir, NATIVE_LIBS_DIR_NAME)
        if (libDir.exists()) {
            libDir.walk().filter { it.isFile }.forEach { it.setReadOnly() }
        }
    }

    private data class PluginConfig(
        val id: String,
        val name: String,
        val iconResId: Int,
        val versionCode: Long,
        val versionName: String,
        val entryClass: String,
        val description: String,
        val type: String
    )

    @Suppress("DEPRECATION")
    private fun parsePluginConfig(pluginApkFile: File): PluginConfig? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                pluginApkFile.absolutePath,
                PackageManager.GET_META_DATA
            )

            if (packageInfo == null) return null
            val appInfo = packageInfo.applicationInfo ?: return null
            
            appInfo.publicSourceDir = pluginApkFile.absolutePath

            val metaData = appInfo.metaData ?: return null

            val pluginId = packageInfo.packageName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            val versionName = packageInfo.versionName ?: "0.0.0"
            val name = pm.getApplicationLabel(appInfo).toString()
            val iconResId = appInfo.icon
            val entryClass = metaData.getString(META_PLUGIN_ENTRY_CLASS) ?: return null
            val description = metaData.getString(META_PLUGIN_DESCRIPTION) ?: ""
            val type = metaData.getString(META_PLUGIN_TYPE) ?: "unknown"

            PluginConfig(
                id = pluginId,
                name = name,
                iconResId = iconResId,
                versionCode = versionCode,
                versionName = versionName,
                entryClass = entryClass,
                description = description,
                type = type
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun copyPluginApk(sourceFile: File, pluginDir: File): File {
        val targetFile = File(pluginDir, PLUGIN_BASE_APK_NAME)
        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        targetFile.setReadOnly()
        return targetFile
    }

    private fun extractNativeLibs(pluginApk: File, pluginDir: File): String? {
        val libDir = File(pluginDir, NATIVE_LIBS_DIR_NAME)
        libDir.mkdirs()

        var extractedPath: String? = null

        ZipFile(pluginApk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.startsWith("lib/") && !entry.isDirectory) {
                    val abi = entry.name.substringAfter("lib/").substringBefore('/')
                    if (Build.SUPPORTED_ABIS.contains(abi)) {
                        val abiDir = File(libDir, abi).apply { mkdirs() }
                        val outputFile = File(abiDir, entry.name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { input ->
                            outputFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        outputFile.setReadOnly()
                        if (extractedPath == null) {
                            extractedPath = abiDir.absolutePath
                        }
                    }
                }
            }
        }
        return extractedPath
    }

    private fun createClassIndex(pluginApkFile: File, pluginDir: File): Boolean {
        val indexFile = File(pluginDir, CLASS_INDEX_FILENAME)
        return try {
            val dexContainer = DexFileFactory.loadDexContainer(
                pluginApkFile,
                Opcodes.forApi(Build.VERSION.SDK_INT)
            )

            indexFile.bufferedWriter().use { writer ->
                for (dexEntryName in dexContainer.dexEntryNames) {
                    val dexEntry = dexContainer.getEntry(dexEntryName) ?: continue
                    val dexFile = dexEntry.dexFile
                    dexFile.classes.asSequence().forEach { classDef ->
                        val className = convertDexTypeToClassName(classDef.type)
                        writer.write(className)
                        writer.newLine()
                    }
                }
            }
            true
        } catch (e: Exception) {
            indexFile.delete()
            false
        }
    }

    private fun convertDexTypeToClassName(dexType: String): String {
        return if (dexType.startsWith("L") && dexType.endsWith(";")) {
            dexType.substring(1, dexType.length - 1).replace('/', '.')
        } else {
            dexType.replace('/', '.')
        }
    }
}