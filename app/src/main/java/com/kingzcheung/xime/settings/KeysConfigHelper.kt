package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlList
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.BufferedReader
import java.io.InputStreamReader

// ── 键盘手势配置 ──

data class GestureDef(
    val label: String = "",
    val action: String? = "commit",
    val value: String = "",
)

data class KeyGestureConfig(
    val tap: GestureDef? = null,
    val swipeUp: GestureDef? = null,
    val swipeDown: GestureDef? = null,
    val longPress: List<GestureDef>? = null,
)

data class KeyboardConfig(
    val keys: Map<String, KeyGestureConfig> = emptyMap(),
)

/**
 * 从 YAML node 解析 KeyboardConfig。
 *
 * 支持两种格式：
 * - 字符串 `"q"` → 等价于 GestureDef(label="q", action="commit", value="q")
 * - 对象 `{ label: "复制", action: "copy" }` → 完整定义
 */
private fun parseKeyboardConfig(raw: com.charleskorn.kaml.YamlMap?): KeyboardConfig? {
    if (raw == null) return null
    val keysNode = raw["keys"] as? com.charleskorn.kaml.YamlMap ?: return KeyboardConfig()
    val keys = mutableMapOf<String, KeyGestureConfig>()
    for ((keyNode, valueNode) in keysNode.entries) {
        val key = (keyNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        val gestureMap = valueNode as? com.charleskorn.kaml.YamlMap ?: continue
        keys[key] = parseKeyGestureConfig(gestureMap)
    }
    return KeyboardConfig(keys)
}

private fun parseKeyGestureConfig(map: com.charleskorn.kaml.YamlMap): KeyGestureConfig {
    var tap: GestureDef? = null
    var swipeUp: GestureDef? = null
    var swipeDown: GestureDef? = null
    var longPress: List<GestureDef>? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        when (name) {
            "tap" -> tap = parseGestureNode(vNode)
            "swipe_up" -> swipeUp = parseGestureNode(vNode)
            "swipe_down" -> swipeDown = parseGestureNode(vNode)
            "long_press" -> longPress = parseGestureList(vNode)
        }
    }
    return KeyGestureConfig(tap, swipeUp, swipeDown, longPress)
}

private fun parseGestureNode(node: com.charleskorn.kaml.YamlNode): GestureDef {
    // 字符串 → commit
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        return GestureDef(label = text, action = "commit", value = text)
    }
    // 映射 → 完整定义
    if (node is com.charleskorn.kaml.YamlMap) {
        var label = ""
        var action: String? = "commit"
        var value = ""
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            val vStr = (v as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> label = vStr
                "action" -> action = if (vStr == "null") null else vStr
                "value" -> value = vStr
            }
        }
        return GestureDef(label = label, action = action, value = value)
    }
    return GestureDef()
}

private fun parseGestureList(node: com.charleskorn.kaml.YamlNode): List<GestureDef>? {
    val list = node as? YamlList ?: return null
    return list.items.map { parseGestureNode(it) }
}

// ── 原有配置类 ──

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
)

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = listOf("https://index.ximei.me/")
)

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap()
)

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"
    
    private val yaml = Yaml.default
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 新：手势配置缓存
    private var keyGestureConfig: Map<String, KeyGestureConfig> = emptyMap()
    
    fun loadConfig(context: Context): KeysConfig {
        loadXimeConfig(context)
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    private fun loadXimeConfig(context: Context) {
        try {
            // 键盘手势（从原始 YAML 手动解析）
            keyGestureConfig = parseKeyboardFromAssets(context) ?: emptyMap()
            Log.d(TAG, "Loaded config: ${keyGestureConfig.size} keys")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Map<String, KeyGestureConfig>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        val default = parseKeyboardYamlText(defaultText)
        val customText = readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = customText?.let { parseKeyboardYamlText(it) }
        return custom ?: default
    }

    /** 从 YAML 文本中提取 keyboard.keys 段。 */
    private fun parseKeyboardYamlText(yamlText: String): Map<String, KeyGestureConfig>? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val keysNode = keyboardNode["keys"] as? YamlMap ?: return null
        val result = mutableMapOf<String, KeyGestureConfig>()
        for ((kNode, vNode) in keysNode.entries) {
            val key = (kNode as? YamlScalar)?.content ?: continue
            val gestureMap = vNode as? YamlMap ?: continue
            result[key] = parseKeyGestureConfig(gestureMap)
        }
        return result
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = parseConfig(readAssetText(context, XIME_CUSTOM_CONFIG_FILE))
        return mergeConfig(default, custom)
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(
            ximeIndex = custom.ximeIndex ?: default.ximeIndex,
        )
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            null
        }
    }

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    // ── 新公开 API ──

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyGestureConfig? = keyGestureConfig[key.lowercase()]

    /** 获取某个按键指定手势的显示标签。 */
    fun getGestureLabel(key: String, gesture: String): String? {
        val kc = keyGestureConfig[key.lowercase()] ?: return null
        return when (gesture) {
            "tap" -> kc.tap?.label
            "swipe_up" -> kc.swipeUp?.label
            "swipe_down" -> kc.swipeDown?.label
            "long_press" -> kc.longPress?.firstOrNull()?.label
            else -> null
        }
    }

    // ── 旧公开 API（兼容） ──
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String): String? {
        val fromYaml = keyGestureConfig[key.lowercase()]?.swipeUp?.value
        if (fromYaml != null && fromYaml.isNotEmpty()) return fromYaml
        return config.swipeUp[key.lowercase()]
    }
    
    fun getSwipeDownEnglishText(key: String): String? {
        val fromYaml = keyGestureConfig[key.lowercase()]?.swipeDown?.label
        if (fromYaml != null && fromYaml.isNotEmpty()) return fromYaml
        return config.swipeDownEnglish[key.lowercase()]
    }

    /** 获取下滑动作类型：commit（默认上屏）或 none（仅显示） */
    fun getSwipeDownAction(key: String): String? {
        return keyGestureConfig[key.lowercase()]?.swipeDown?.action
    }
    

    
    private fun getDefaultSwipeUp(): Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
        "h" to "^", "j" to "&", "k" to "(", "l" to ")",
        "z" to "|", "x" to "*", "c" to "\\", "v" to "?", "b" to "_",
        "n" to "-", "m" to "+"
    )
    
    private fun getDefaultSwipeDownEnglish(): Map<String, String> = mapOf(
        "q" to "Q", "w" to "W", "e" to "E", "r" to "R", "t" to "T",
        "y" to "Y", "u" to "U", "i" to "I", "o" to "O", "p" to "P",
        "a" to "A", "s" to "S", "d" to "D", "f" to "F", "g" to "G",
        "h" to "H", "j" to "J", "k" to "K", "l" to "L",
        "z" to "Z", "x" to "X", "c" to "C", "v" to "V", "b" to "B",
        "n" to "N", "m" to "M"
    )
    

}