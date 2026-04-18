package com.kingzcheung.kime.plugin.core.runtime.loader

import com.kingzcheung.kime.plugin.core.api.IPluginFinder
import com.kingzcheung.kime.plugin.core.model.PluginFrameworkContext
import java.util.concurrent.ConcurrentHashMap

class DependencyManager internal constructor(private val context: PluginFrameworkContext) : IPluginFinder {

    private val dependencyGraph = ConcurrentHashMap<String, MutableSet<String>>()
    private val dependentGraph = ConcurrentHashMap<String, MutableSet<String>>()

    override fun findClass(className: String, requester: PluginClassLoader): Class<*>? {
        val targetPluginId = context.classIndex[className]
        if (targetPluginId == null || targetPluginId == requester.pluginId) {
            return null
        }

        recordDependency(requester.pluginId, targetPluginId)

        val targetLoader = context.loadedPlugins[targetPluginId]?.classLoader
        if (targetLoader != null) {
            return try {
                targetLoader.findClassLocally(className)
            } catch (e: ClassNotFoundException) {
                null
            }
        }
        return null
    }

    private fun recordDependency(from: String, to: String) {
        dependencyGraph.getOrPut(from) { mutableSetOf() }.add(to)
        dependentGraph.getOrPut(to) { mutableSetOf() }.add(from)
    }

    fun findDependentsRecursive(pluginId: String): List<String> {
        val result = mutableSetOf<String>()
        findDependentsRecursiveInternal(pluginId, result)
        return result.toList()
    }

    private fun findDependentsRecursiveInternal(pluginId: String, visited: MutableSet<String>) {
        val dependents = dependentGraph[pluginId] ?: return
        for (dependent in dependents) {
            if (!visited.contains(dependent)) {
                visited.add(dependent)
                findDependentsRecursiveInternal(dependent, visited)
            }
        }
    }

    fun findDependenciesRecursive(pluginId: String): List<String> {
        val result = mutableSetOf<String>()
        findDependenciesRecursiveInternal(pluginId, result)
        return result.toList()
    }

    private fun findDependenciesRecursiveInternal(pluginId: String, visited: MutableSet<String>) {
        val dependencies = dependencyGraph[pluginId] ?: return
        for (dependency in dependencies) {
            if (!visited.contains(dependency)) {
                visited.add(dependency)
                findDependenciesRecursiveInternal(dependency, visited)
            }
        }
    }

    fun clearDependenciesFor(pluginId: String) {
        dependencyGraph.remove(pluginId)
        dependentGraph.remove(pluginId)
        dependentGraph.values.forEach { it.remove(pluginId) }
        dependencyGraph.values.forEach { it.remove(pluginId) }
    }
}