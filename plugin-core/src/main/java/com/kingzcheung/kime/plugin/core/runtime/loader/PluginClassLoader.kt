package com.kingzcheung.kime.plugin.core.runtime.loader

import android.util.Log
import com.kingzcheung.kime.plugin.core.api.IPluginFinder
import com.kingzcheung.kime.plugin.core.exception.PluginDependencyException
import dalvik.system.DexClassLoader
import java.io.File

open class PluginClassLoader(
    internal val pluginId: String,
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader?,
    private val pluginFinder: IPluginFinder?
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    companion object {
        private const val TAG = "PluginClassLoader"
    }

    constructor(
        pluginId: String,
        pluginFile: File,
        parent: ClassLoader,
        optimizedDirectory: String?,
        librarySearchPath: String?,
        pluginFinder: IPluginFinder?
    ) : this(
        pluginId = pluginId,
        dexPath = pluginFile.absolutePath,
        parent = parent,
        optimizedDirectory = optimizedDirectory,
        librarySearchPath = librarySearchPath,
        pluginFinder = pluginFinder
    )

    @Throws(ClassNotFoundException::class)
    override fun findClass(name: String): Class<*> {
        try {
            return super.findClass(name)
        } catch (e: ClassNotFoundException) {
            val result = pluginFinder?.findClass(name, this)
            if (result != null) {
                return result
            }
            throw PluginDependencyException(
                culpritPluginId = this.pluginId,
                missingClassName = name,
                cause = e
            )
        }
    }

    @Throws(ClassNotFoundException::class)
    fun findClassLocally(name: String): Class<*> = super.findClass(name)

    fun <T> getInterface(interfaceClass: Class<T>, className: String): T? {
        return try {
            Log.d(TAG, "[$pluginId] Loading class: $className for interface: ${interfaceClass.simpleName}")
            val clazz = loadClass(className)
            Log.d(TAG, "[$pluginId] Class loaded: ${clazz.name}, classLoader: ${clazz.classLoader}")
            
            val instance = clazz.getDeclaredConstructor().newInstance()
            Log.d(TAG, "[$pluginId] Instance created: ${instance::class.simpleName}")
            
            val implementedInterfaces = instance::class.java.interfaces.map { it.simpleName }
            Log.d(TAG, "[$pluginId] Instance interfaces: $implementedInterfaces")
            
            val isInstance = interfaceClass.isInstance(instance)
            Log.d(TAG, "[$pluginId] isInstance check: $isInstance (interface: ${interfaceClass.simpleName}, instanceClassLoader: ${instance::class.java.classLoader}, interfaceClassLoader: ${interfaceClass.classLoader})")
            
            if (isInstance) {
                @Suppress("UNCHECKED_CAST")
                instance as T
            } else {
                Log.w(TAG, "[$pluginId] Instance is NOT of type ${interfaceClass.simpleName}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$pluginId] Failed to get interface", e)
            null
        }
    }
}