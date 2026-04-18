package com.kingzcheung.kime.plugin.core.exception

class PluginDependencyException(
    val culpritPluginId: String,
    val missingClassName: String,
    cause: Throwable
) : ClassNotFoundException(
    "Plugin '$culpritPluginId' cannot find class '$missingClassName'",
    cause
)