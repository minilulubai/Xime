package com.kingzcheung.kime.plugin.core.api

import androidx.compose.runtime.Composable
import com.kingzcheung.kime.plugin.core.model.PluginContext

interface IPluginEntryClass {

    fun onLoad(context: PluginContext)

    fun onUnload()

    @Composable
    fun Content() {}

    fun hasSettings(): Boolean = false

    fun providesService(): List<Class<out Any>> = emptyList()

    fun <T : Any> getService(serviceClass: Class<T>): T? = null
}