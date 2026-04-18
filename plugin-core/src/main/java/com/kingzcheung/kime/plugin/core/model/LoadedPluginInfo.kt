package com.kingzcheung.kime.plugin.core.runtime.loader

import com.kingzcheung.kime.plugin.core.model.PluginInfo

data class LoadedPluginInfo(
    val pluginInfo: PluginInfo,
    val classLoader: PluginClassLoader
)