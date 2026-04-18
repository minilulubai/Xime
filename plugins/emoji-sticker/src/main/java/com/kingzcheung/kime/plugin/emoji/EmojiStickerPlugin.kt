package com.kingzcheung.kime.plugin.emoji

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.kingzcheung.kime.plugin.core.api.EmojiItem
import com.kingzcheung.kime.plugin.core.api.EmojiPlugin
import com.kingzcheung.kime.plugin.core.model.PluginContext
import java.io.File
import java.util.zip.ZipFile

class EmojiStickerPlugin : EmojiPlugin {
    
    private var pluginContext: PluginContext? = null
    private var emojiList: List<EmojiItem> = emptyList()
    
    companion object {
        private const val TAG = "EmojiStickerPlugin"
    }
    
    override fun onLoad(context: PluginContext) {
        this.pluginContext = context
        Log.d(TAG, "Plugin loaded: ${context.pluginInfo.id}")
        
        val filesDir = File("/data/data/com.kingzcheung.kime/files")
        if (!filesDir.exists()) filesDir.mkdirs()
        
        loadEmojis(filesDir, context.pluginInfo.path)
        Log.d(TAG, "Loaded ${emojiList.size} emojis")
    }
    
    override fun onUnload() {
        emojiList = emptyList()
        pluginContext = null
        Log.d(TAG, "Plugin unloaded")
    }
    
    private fun loadEmojis(filesDir: File, apkPath: String?) {
        val emojisDir = File(filesDir, "emojis")
        
        if (!emojisDir.exists()) {
            emojisDir.mkdirs()
            copyEmojisFromAssets(emojisDir, apkPath)
        }
        
        emojiList = emojisDir.listFiles()
            ?.filter { it.extension in listOf("jpg", "png", "gif") }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
            ?.mapIndexed { index, file ->
                EmojiItem(
                    id = "emoji_$index",
                    displayText = file.nameWithoutExtension,
                    insertText = "[表情${file.nameWithoutExtension}]",
                    imageUrl = file.absolutePath,
                    category = "恶搞兔"
                )
            } ?: emptyList()
    }
    
    private fun copyEmojisFromAssets(emojisDir: File, apkPath: String?) {
        val actualApkPath = apkPath ?: pluginContext?.application?.applicationInfo?.sourceDir
        
        if (actualApkPath != null) {
            try {
                ZipFile(File(actualApkPath)).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("assets/emojis/") && !it.isDirectory }
                        .forEach { entry ->
                            val fileName = entry.name.substringAfter("assets/emojis/")
                            zip.getInputStream(entry).use { input ->
                                File(emojisDir, fileName).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                }
                Log.d(TAG, "Copied emojis from APK")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy from APK", e)
            }
        }
        
        pluginContext?.application?.let { app ->
            try {
                val assets = app.assets
                assets.list("emojis")?.forEach { fileName ->
                    assets.open("emojis/$fileName").use { input ->
                        File(emojisDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                Log.d(TAG, "Copied emojis from assets")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy from assets", e)
            }
        }
    }
    
    override suspend fun getEmojis(category: String?, searchText: String?, topK: Int): List<EmojiItem> {
        val filtered = if (searchText.isNullOrEmpty()) emojiList
        else emojiList.filter { it.displayText.contains(searchText) || it.insertText.contains(searchText) }
        return filtered.take(topK)
    }
    
    override suspend fun getCategories(): List<String> = listOf("恶搞兔")
    
    override fun hasSettings(): Boolean = true
    
    override fun openSettings(context: Context) {
        try {
            val intent = android.content.Intent()
            intent.setClassName(
                "com.kingzcheung.kime.plugin.emoji",
                "com.kingzcheung.kime.plugin.emoji.PluginSettingsActivity"
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}