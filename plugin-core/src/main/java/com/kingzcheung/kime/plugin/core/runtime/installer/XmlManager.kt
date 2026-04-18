package com.kingzcheung.kime.plugin.core.runtime.installer

import android.app.Application
import com.kingzcheung.kime.plugin.core.model.PluginInfo
import com.kingzcheung.kime.plugin.core.model.ProviderInfo
import java.io.File

class XmlManager(private val context: Application) {
    companion object {
        private const val PLUGINS_XML = "plugins.xml"
    }

    private val pluginsFile: File by lazy {
        File(context.filesDir, PLUGINS_XML)
    }

    private val plugins = mutableMapOf<String, PluginInfo>()

    init {
        loadFromDisk()
    }

    fun getAllPlugins(): List<PluginInfo> = plugins.values.toList()

    fun getPluginById(id: String): PluginInfo? = plugins[id]

    fun addPlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun updatePlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun removePlugin(id: String) {
        plugins.remove(id)
    }

    fun flushToDisk() {
        try {
            pluginsFile.bufferedWriter().use { writer ->
                writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                writer.write("<plugins>\n")
                for (plugin in plugins.values) {
                    writer.write("  <plugin>\n")
                    writer.write("    <id>${escapeXml(plugin.id)}</id>\n")
                    writer.write("    <name>${escapeXml(plugin.name)}</name>\n")
                    writer.write("    <description>${escapeXml(plugin.description)}</description>\n")
                    writer.write("    <versionCode>${plugin.versionCode}</versionCode>\n")
                    writer.write("    <versionName>${escapeXml(plugin.versionName)}</versionName>\n")
                    writer.write("    <path>${escapeXml(plugin.path)}</path>\n")
                    writer.write("    <entryClass>${escapeXml(plugin.entryClass)}</entryClass>\n")
                    writer.write("    <type>${escapeXml(plugin.type)}</type>\n")
                    writer.write("    <enabled>${plugin.enabled}</enabled>\n")
                    writer.write("    <installTime>${plugin.installTime}</installTime>\n")
                    if (plugin.nativeLibPath != null) {
                        writer.write("    <nativeLibPath>${escapeXml(plugin.nativeLibPath)}</nativeLibPath>\n")
                    }
                    writer.write("    <iconResId>${plugin.iconResId}</iconResId>\n")
                    if (plugin.providers.isNotEmpty()) {
                        writer.write("    <providers>\n")
                        for (provider in plugin.providers) {
                            writer.write("      <provider>\n")
                            writer.write("        <className>${escapeXml(provider.className)}</className>\n")
                            writer.write("        <authorities>${escapeXml(provider.authorities.joinToString(";"))}</authorities>\n")
                            writer.write("        <exported>${provider.exported}</exported>\n")
                            writer.write("        <enabled>${provider.enabled}</enabled>\n")
                            writer.write("      </provider>\n")
                        }
                        writer.write("    </providers>\n")
                    }
                    writer.write("  </plugin>\n")
                }
                writer.write("</plugins>\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromDisk() {
        if (!pluginsFile.exists()) return

        try {
            val content = pluginsFile.readText()
            parsePluginsXml(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parsePluginsXml(content: String) {
        val pluginRegex = Regex("<plugin>(.*?)</plugin>", RegexOption.DOT_MATCHES_ALL)
        val matches = pluginRegex.findAll(content)

        for (match in matches) {
            val pluginContent = match.groupValues[1]
            val id = extractTag(pluginContent, "id")
            val name = extractTag(pluginContent, "name")
            val description = extractTag(pluginContent, "description")
            val versionCode = extractTag(pluginContent, "versionCode")?.toLongOrNull() ?: 0
            val versionName = extractTag(pluginContent, "versionName") ?: ""
            val path = extractTag(pluginContent, "path")
            val entryClass = extractTag(pluginContent, "entryClass")
            val type = extractTag(pluginContent, "type") ?: "unknown"
            val enabled = extractTag(pluginContent, "enabled")?.toBoolean() ?: true
            val installTime = extractTag(pluginContent, "installTime")?.toLongOrNull() ?: System.currentTimeMillis()
            val nativeLibPath = extractTag(pluginContent, "nativeLibPath")
            val iconResId = extractTag(pluginContent, "iconResId")?.toIntOrNull() ?: 0
            
            val providers = parseProviders(pluginContent)

            if (id != null && path != null && entryClass != null) {
                plugins[id] = PluginInfo(
                    id = id,
                    name = name ?: "",
                    iconResId = iconResId,
                    description = description ?: "",
                    versionCode = versionCode,
                    versionName = versionName,
                    path = path,
                    entryClass = entryClass,
                    type = type,
                    enabled = enabled,
                    installTime = installTime,
                    nativeLibPath = nativeLibPath,
                    providers = providers
                )
            }
        }
    }
    
    private fun parseProviders(pluginContent: String): List<ProviderInfo> {
        val providersRegex = Regex("<providers>(.*?)</providers>", RegexOption.DOT_MATCHES_ALL)
        val providersMatch = providersRegex.find(pluginContent)
        
        if (providersMatch == null) return emptyList()
        
        val providersContent = providersMatch.groupValues[1]
        val providerRegex = Regex("<provider>(.*?)</provider>", RegexOption.DOT_MATCHES_ALL)
        val providerMatches = providerRegex.findAll(providersContent)
        
        return providerMatches.map { providerMatch ->
            val providerContent = providerMatch.groupValues[1]
            val className = extractTag(providerContent, "className") ?: ""
            val authoritiesStr = extractTag(providerContent, "authorities") ?: ""
            val authorities = authoritiesStr.split(";").filter { it.isNotBlank() }
            val exported = extractTag(providerContent, "exported")?.toBoolean() ?: false
            val enabled = extractTag(providerContent, "enabled")?.toBoolean() ?: true
            
            ProviderInfo(
                className = className,
                authorities = authorities,
                exported = exported,
                enabled = enabled
            )
        }.toList()
    }

    private fun extractTag(content: String, tagName: String): String? {
        val regex = Regex("<$tagName>(.*?)</$tagName>")
        return regex.find(content)?.groupValues?.get(1)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}