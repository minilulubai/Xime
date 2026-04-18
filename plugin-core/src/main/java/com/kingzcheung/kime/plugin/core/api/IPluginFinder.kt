package com.kingzcheung.kime.plugin.core.api

import com.kingzcheung.kime.plugin.core.runtime.loader.PluginClassLoader

interface IPluginFinder {
    fun findClass(className: String, requester: PluginClassLoader): Class<*>?
}