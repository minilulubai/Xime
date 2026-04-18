package com.kingzcheung.kime.plugin.core.runtime.resource

import android.annotation.SuppressLint
import android.app.Application
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginResourcesManager(private val context: Application) {

    companion object {
        private const val TAG = "PluginResourcesManager"
    }

    private val loadedPluginFiles = ConcurrentHashMap<String, File>()
    private val _mResources: Resources = context.resources

    fun getResources(): Resources = _mResources

    fun loadPluginResources(pluginId: String, pluginFile: File): Boolean {
        return try {
            if (!pluginFile.exists()) return false

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                loadResourcesWithResourcesLoader(pluginFile)
            } else {
                loadResourcesWithAddAssetPath(pluginFile)
            }

            if (success) {
                loadedPluginFiles[pluginId] = pluginFile
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin resources: $pluginId", e)
            false
        }
    }

    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    private fun loadResourcesWithAddAssetPath(pluginFile: File): Boolean {
        return try {
            val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath",
                String::class.java
            )
            addAssetPathMethod.isAccessible = true
            val result = addAssetPathMethod.invoke(assetManager, pluginFile.absolutePath) as Int
            result != 0
        } catch (e: Exception) {
            Log.e(TAG, "addAssetPath failed", e)
            false
        }
    }

    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private fun loadResourcesWithResourcesLoader(pluginFile: File): Boolean {
        return try {
            val resourcesLoaderClass = Class.forName("android.content.res.ResourcesLoader")
            val resourcesProviderClass = Class.forName("android.content.res.ResourcesProvider")
            
            val parcelFileDescriptor = android.os.ParcelFileDescriptor.open(
                pluginFile,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            
            val loadFromApkMethod = resourcesProviderClass.getDeclaredMethod(
                "loadFromApk",
                android.os.ParcelFileDescriptor::class.java,
                AssetManager::class.java
            )
            loadFromApkMethod.isAccessible = true
            
            val resourcesProvider = loadFromApkMethod.invoke(null, parcelFileDescriptor, null)
            
            val resourcesLoaderConstructor = resourcesLoaderClass.getDeclaredConstructor()
            resourcesLoaderConstructor.isAccessible = true
            val resourcesLoader = resourcesLoaderConstructor.newInstance()
            
            val addProviderMethod = resourcesLoaderClass.getDeclaredMethod(
                "addProvider",
                resourcesProviderClass
            )
            addProviderMethod.isAccessible = true
            addProviderMethod.invoke(resourcesLoader, resourcesProvider)
            
            val addLoadersMethod = Resources::class.java.getDeclaredMethod(
                "addLoaders",
                resourcesLoaderClass
            )
            addLoadersMethod.isAccessible = true
            addLoadersMethod.invoke(_mResources, resourcesLoader)
            
            parcelFileDescriptor.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "ResourcesLoader failed", e)
            tryFallbackResourceLoading(pluginFile)
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun tryFallbackResourceLoading(pluginFile: File): Boolean {
        return try {
            val assetManagerClass = AssetManager::class.java
            val assetManager = assetManagerClass.getDeclaredConstructor().newInstance()
            
            val addAssetPathMethod = assetManagerClass.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPathMethod.isAccessible = true
            val result = addAssetPathMethod.invoke(assetManager, pluginFile.absolutePath) as Int
            result != 0
        } catch (e: Exception) {
            Log.e(TAG, "Fallback resource loading failed", e)
            false
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    fun removePluginResources(pluginId: String) {
        loadedPluginFiles.remove(pluginId)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val resourcesLoaderClass = Class.forName("android.content.res.ResourcesLoader")
                val getLoadersMethod = Resources::class.java.getDeclaredMethod("getLoaders")
                getLoadersMethod.isAccessible = true
                val loaders = getLoadersMethod.invoke(_mResources) as List<*>
                
                val removeLoaderMethod = Resources::class.java.getDeclaredMethod(
                    "removeLoaders",
                    resourcesLoaderClass
                )
                removeLoaderMethod.isAccessible = true
                
                loaders.forEach { loader ->
                    if (loader != null) {
                        removeLoaderMethod.invoke(_mResources, loader)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove loaders", e)
            }
        }
    }

    fun getLoadedPluginIds(): Set<String> = loadedPluginFiles.keys.toSet()

    fun clearAllResources() {
        loadedPluginFiles.clear()
    }
}