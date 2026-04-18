package com.kingzcheung.kime

import android.app.Application
import android.util.Log
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import com.kingzcheung.kime.plugin.ExtensionManager

class KimeApplication : Application() {
    
    companion object {
        private const val TAG = "KimeApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Initializing PluginManager...")
        PluginManager.initialize(this) {
            Log.d(TAG, "PluginManager onSetup callback executing...")
            
            if (BuildConfig.DEBUG) {
                val installed = PluginManager.installPluginsFromAssetsForDebug("plugins")
                Log.d(TAG, "Installed $installed plugins from assets (run ./gradlew copyPluginsToAssets first)")
            }
            
            Log.d(TAG, "Loading enabled plugins...")
            val loaded = PluginManager.loadEnabledPlugins()
            Log.d(TAG, "Loaded $loaded plugins")
            
            Log.d(TAG, "All installed plugins: ${PluginManager.getAllInstallPlugins().map { it.id }}")
            Log.d(TAG, "All plugin instances: ${PluginManager.getAllPluginInstances().keys}")
        }
        
        Log.d(TAG, "Initializing ExtensionManager...")
        ExtensionManager.initialize(this)
        Log.d(TAG, "Initialization complete")
    }
}