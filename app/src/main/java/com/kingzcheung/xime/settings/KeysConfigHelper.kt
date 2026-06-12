package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlList
import com.kingzcheung.xime.keyboard.GestureAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// ── 键盘手势配置 ──

data class GestureDef(
    val label: String = "",
    val action: GestureAction? = GestureAction.COMMIT,
    val value: String = "",
    val display: String = "key", // "key"（默认）显示在按键上, "bubble" 气泡显示
)

data class LongPressConfig(
    val display: String = "key", // "key"（默认）显示在按键上, "bubble" 气泡弹出
    val values: List<GestureDef> = emptyList(),
)

data class KeyGestureConfig(
    val tap: GestureDef? = null,
    val swipeUp: GestureDef? = null,
    val swipeDown: GestureDef? = null,
    val longPress: LongPressConfig? = null,
)

data class KeyboardConfig(
    val keys: Map<String, KeyGestureConfig> = emptyMap(),
)

/**
 * 键盘阴影配置，从 xime.yaml keyboard.shadow 加载。
 */
data class KeyboardShadowConfig(
    val enabled: Boolean = true,
    val elevation: Int = 1,
    val shapeRadius: Int = 8,
)

/**
 * 键盘颜色配置，从 xime.yaml keyboard.colors 加载。
 * 所有颜色值为 0xRRGGBB 格式（不含 alpha）。
 */
data class KeyboardColorsConfig(
    val keyboardBgColor: Long = 0xE3E4E8,
    val keyboardBgColorDark: Long = 0x202020,
    val keyBgColor: Long = 0xFFFFFF,
    val keyBgColorDark: Long = 0x4A4A4A,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val candidateBarBgColor: Long = 0xE3E4E8,
    val candidateBarBgColorDark: Long = 0x202020,
    val keyTextColor: Long = 0x202124,
    val keyTextColorDark: Long = 0xE8EAED,
    val candidateTextColor: Long = 0x1A73E8,
    val candidateTextColorDark: Long = 0x8AB4F8,
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
    var longPress: LongPressConfig? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        when (name) {
            "tap" -> tap = parseGestureNode(vNode)
            "swipe_up" -> swipeUp = parseGestureNode(vNode)
            "swipe_down" -> swipeDown = parseGestureNode(vNode)
            "long_press" -> longPress = parseLongPress(vNode)
        }
    }
    return KeyGestureConfig(tap, swipeUp, swipeDown, longPress)
}

/**
 * 解析 long_press，支持两种格式：
 *   新格式（推荐）：{ display: "bubble", values: ["q", "Q"] }
 *   旧格式（兼容）：["q", "Q"]
 */
private fun parseLongPress(node: com.charleskorn.kaml.YamlNode): LongPressConfig? {
    // 旧格式：纯数组 → 默认 display="key"
    if (node is YamlList) {
        val values = node.items.map { parseGestureNode(it) }.take(10)
        return LongPressConfig(display = "key", values = values)
    }
    // 新格式：对象 { display, values }
    if (node is com.charleskorn.kaml.YamlMap) {
        var display = "key"
        var values: List<GestureDef> = emptyList()
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "display" -> display = (v as? com.charleskorn.kaml.YamlScalar)?.content ?: "key"
                "values" -> if (v is YamlList) values = v.items.map { parseGestureNode(it) }.take(10)
            }
        }
        return LongPressConfig(display = display, values = values)
    }
    return null
}

private fun parseGestureNode(node: com.charleskorn.kaml.YamlNode): GestureDef {
    // 字符串 → commit
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        return GestureDef(label = text, action = GestureAction.COMMIT, value = text)
    }
    // 映射 → 完整定义
    if (node is com.charleskorn.kaml.YamlMap) {
        var label = ""
        var action: GestureAction? = GestureAction.COMMIT
        var value = ""
        var display = "key"
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            val vStr = (v as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> label = vStr
                "action" -> action = if (vStr == "null") null else GestureAction.fromValue(vStr)
                "value" -> value = vStr
                "display" -> display = vStr
            }
        }
        return GestureDef(label = label, action = action, value = value, display = display)
    }
    return GestureDef()
}

private fun parseGestureList(node: com.charleskorn.kaml.YamlNode): List<GestureDef>? {
    val list = node as? YamlList ?: return null
    return list.items.map { parseGestureNode(it) }
}

// ── 原有配置类 ──

@Serializable
data class ColorSchemeEntry(
    val name: String = "",
    @SerialName("primary_color")
    val primaryColor: Long = 0,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
    @SerialName("candidate_bar_bg_color")
    val candidateBarBgColor: Long? = null,
    @SerialName("key_text_color")
    val keyTextColor: Long? = null,
    @SerialName("candidate_text_color")
    val candidateTextColor: Long? = null,
)

@Serializable
data class StyleConfig(
    @SerialName("color_scheme")
    val colorScheme: String? = null,
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
    @SerialName("color_schemes")
    val colorSchemes: Map<String, ColorSchemeEntry>? = null,
    @SerialName("style")
    val style: StyleConfig? = null,
    @SerialName("customization")
    val customization: Map<String, String>? = null,
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
    
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 新：手势配置缓存
    private var keyGestureConfig: Map<String, KeyGestureConfig> = emptyMap()
    
    // 键盘颜色配置缓存
    private var keyboardColorsConfig: KeyboardColorsConfig = KeyboardColorsConfig()
    
    // 键盘阴影配置缓存
    private var keyboardShadowConfig: KeyboardShadowConfig = KeyboardShadowConfig()
    
    /** 配置版本号，每次 loadConfig 时递增，用于 Compose 感知配置变更。 */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()
    
    private var mergedConfigCache: XimeConfig? = null
    private var mergedConfigVersion = 0
    
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
            // 键盘颜色（从原始 YAML 手动解析）
            keyboardColorsConfig = parseKeyboardColorsFromAssets(context)
            // 键盘阴影（从原始 YAML 手动解析）
            keyboardShadowConfig = parseKeyboardShadowFromAssets(context)
            _configVersion.value++
            Log.d(TAG, "Loaded config: ${keyGestureConfig.size} keys (v${_configVersion.value})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Map<String, KeyGestureConfig>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        val default = parseKeyboardYamlText(defaultText) ?: return null
        // 优先从用户数据目录读取自定义覆盖
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = customText?.let { parseKeyboardYamlText(it) } ?: return default
        // 合并：自定义覆盖同名键，默认填充其余键
        return default + custom
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘颜色配置。 */
    private fun parseKeyboardColorsFromAssets(context: Context): KeyboardColorsConfig {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return KeyboardColorsConfig()
        val default = parseKeyboardColorsYamlText(defaultText) ?: return KeyboardColorsConfig()
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE) ?: return default
        val custom = parseKeyboardColorsYamlText(customText)
        return custom ?: default
    }

    /** 从 YAML 文本中提取 keyboard.colors 段。 */
    private fun parseKeyboardColorsYamlText(yamlText: String): KeyboardColorsConfig? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val colorsNode = keyboardNode["colors"] as? YamlMap ?: return null
        var kbBg = 0xE3E4E8L
        var kbBgDark = 0x202020L
        var kBg = 0xFFFFFFL
        var kBgDark = 0x4A4A4AL
        var spKeyBg: Long? = null
        var spKeyBgDark: Long? = null
        var candBg = 0xE3E4E8L
        var candBgDark = 0x202020L
        var kTxt = 0x202124L
        var kTxtDark = 0xE8EAEDL
        var candTxt = 0x1A73E8L
        var candTxtDark = 0x8AB4F8L
        for ((kNode, vNode) in colorsNode.entries) {
            val key = (kNode as? YamlScalar)?.content ?: continue
            val value = (vNode as? YamlScalar)?.content ?: continue
            val hex = value.removePrefix("0x").toLongOrNull(16) ?: continue
            when (key) {
                "keyboard_bg_color" -> kbBg = hex
                "keyboard_bg_color_dark" -> kbBgDark = hex
                "key_bg_color" -> kBg = hex
                "key_bg_color_dark" -> kBgDark = hex
                "special_key_bg_color" -> spKeyBg = hex
                "special_key_bg_color_dark" -> spKeyBgDark = hex
                "candidate_bar_bg_color" -> candBg = hex
                "candidate_bar_bg_color_dark" -> candBgDark = hex
                "key_text_color" -> kTxt = hex
                "key_text_color_dark" -> kTxtDark = hex
                "candidate_text_color" -> candTxt = hex
                "candidate_text_color_dark" -> candTxtDark = hex
            }
        }
        return KeyboardColorsConfig(
            keyboardBgColor = kbBg,
            keyboardBgColorDark = kbBgDark,
            keyBgColor = kBg,
            keyBgColorDark = kBgDark,
            specialKeyBgColor = spKeyBg,
            specialKeyBgColorDark = spKeyBgDark,
            candidateBarBgColor = candBg,
            candidateBarBgColorDark = candBgDark,
            keyTextColor = kTxt,
            keyTextColorDark = kTxtDark,
            candidateTextColor = candTxt,
            candidateTextColorDark = candTxtDark,
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘阴影配置。 */
    private fun parseKeyboardShadowFromAssets(context: Context): KeyboardShadowConfig {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return KeyboardShadowConfig()
        val default = parseKeyboardShadowYamlText(defaultText) ?: return KeyboardShadowConfig()
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE) ?: return default
        val custom = parseKeyboardShadowYamlText(customText)
        return custom ?: default
    }

    /** 从 YAML 文本中提取 keyboard.shadow 段。 */
    private fun parseKeyboardShadowYamlText(yamlText: String): KeyboardShadowConfig? {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root["keyboard"] as? YamlMap ?: return null
        val shadowNode = keyboardNode["shadow"] as? YamlMap ?: return null
        var enabled = true
        var elevation = 1
        var shapeRadius = 8
        for ((kNode, vNode) in shadowNode.entries) {
            val key = (kNode as? YamlScalar)?.content ?: continue
            val value = (vNode as? YamlScalar)?.content ?: continue
            when (key) {
                "enabled" -> enabled = value.toBooleanStrictOrNull() ?: true
                "elevation" -> elevation = value.toIntOrNull() ?: 1
                "shape_radius" -> shapeRadius = value.toIntOrNull() ?: 8
            }
        }
        return KeyboardShadowConfig(enabled = enabled, elevation = elevation, shapeRadius = shapeRadius)
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
        val currentVersion = _configVersion.value
        if (mergedConfigCache != null && mergedConfigVersion == currentVersion) {
            return mergedConfigCache!!
        }
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        Log.d(TAG, "loadMergedConfig: customText=${customText != null}, len=${customText?.length}")
        val custom = parseConfig(customText)
        val config = mergeConfig(default, custom)
        mergedConfigCache = config
        mergedConfigVersion = currentVersion
        return config
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
            colorSchemes = custom.colorSchemes ?: default.colorSchemes,
            style = custom.style ?: default.style,
            customization = custom.customization ?: default.customization,
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

    /** 从用户数据目录 (context.filesDir/rime/) 读取文件。 */
    private fun readUserDataText(context: Context, fileName: String): String? {
        val file = File(context.filesDir, "rime/$fileName")
        Log.d(TAG, "readUserDataText: path=${file.absolutePath}, exists=${file.exists()}")
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            Log.d(TAG, "readUserDataText: read ${text.length} chars, first 80=${text.take(80)}")
            text
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    /** 从 xime.yaml 加载 color_schemes 配置。 */
    fun loadColorSchemes(context: Context): Map<String, ColorSchemeEntry> {
        val merged = loadMergedConfig(context)
        return merged.colorSchemes ?: emptyMap()
    }

    /** 从 xime.yaml 加载默认主题 ID（style.color_scheme）。 */
    fun loadDefaultThemeId(context: Context): String {
        val merged = loadMergedConfig(context)
        return merged.style?.colorScheme ?: "lavender_purple"
    }

    // ── 新公开 API ──

    /** 获取键盘颜色配置（从 xime.yaml keyboard.colors 加载）。 */
    fun getKeyboardColors(): KeyboardColorsConfig = keyboardColorsConfig

    /** 获取键盘阴影配置（从 xime.yaml keyboard.shadow 加载）。 */
    fun getKeyboardShadow(): KeyboardShadowConfig = keyboardShadowConfig

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyGestureConfig? = keyGestureConfig[key.lowercase()]

    /** 获取某个按键指定手势的显示标签。 */
    fun getGestureLabel(key: String, gesture: String): String? {
        val kc = keyGestureConfig[key.lowercase()] ?: return null
        return when (gesture) {
            "tap" -> kc.tap?.label
            "swipe_up" -> kc.swipeUp?.label
            "swipe_down" -> kc.swipeDown?.label
            "long_press" -> kc.longPress?.values?.firstOrNull()?.label
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

    /** 获取下滑动作类型 */
    fun getSwipeDownAction(key: String): GestureAction? {
        return keyGestureConfig[key.lowercase()]?.swipeDown?.action
    }

    /** 获取下滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeDownDisplay(key: String): String {
        return keyGestureConfig[key.lowercase()]?.swipeDown?.display ?: "key"
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