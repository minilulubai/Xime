package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardGestureConfigTest {

    @Test
    fun `gestureDef 字符串简写解析为 commit 动作`() {
        val keys = parseKeys("""
            q: { tap: "q" }
        """.trimIndent())
        val kc = keys["q"]!!
        assertEquals("q", kc.tap!!.label)
        assertEquals("commit", kc.tap!!.action)
        assertEquals("q", kc.tap!!.value)
    }

    @Test
    fun `gestureDef 字符串简写用于 swipe_up 和 swipe_down`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_up: "!", swipe_down: "A" }
        """.trimIndent())
        val kc = keys["a"]!!
        assertEquals("!", kc.swipeUp!!.label)
        assertEquals("commit", kc.swipeUp!!.action)
        assertEquals("!", kc.swipeUp!!.value)
        assertEquals("A", kc.swipeDown!!.label)
    }

    @Test
    fun `gestureDef 对象格式指定 action 为 copy`() {
        val keys = parseKeys("""
            c:
              swipe_up: { label: "复制", action: "copy" }
        """.trimIndent())
        val su = keys["c"]!!.swipeUp!!
        assertEquals("复制", su.label)
        assertEquals("copy", su.action)
    }

    @Test
    fun `gestureDef 对象格式指定 value 与 label 不同`() {
        val keys = parseKeys("""
            x:
              swipe_up: { label: "剪切", action: "commit", value: "x_cut" }
        """.trimIndent())
        val su = keys["x"]!!.swipeUp!!
        assertEquals("剪切", su.label)
        assertEquals("x_cut", su.value)
    }

    @Test
    fun `long_press 支持多值数组`() {
        val keys = parseKeys("""
            a:
              long_press:
                - { label: "大写", action: "commit", value: "A" }
                - { label: "Ä",    action: "commit", value: "ä" }
        """.trimIndent())
        val lp = keys["a"]!!.longPress!!
        assertEquals(2, lp.size)
        assertEquals("大写", lp[0].label)
        assertEquals("A", lp[0].value)
        assertEquals("Ä", lp[1].label)
    }

    @Test
    fun `long_press 单值数组`() {
        val keys = parseKeys("""
            backspace:
              long_press:
                - { label: "清空", action: "command", value: "clear_composition" }
        """.trimIndent())
        val lp = keys["backspace"]!!.longPress!!
        assertEquals(1, lp.size)
        assertEquals("command", lp[0].action)
    }

    @Test
    fun `action 为 null 表示无动作`() {
        val keys = parseKeys("""
            space:
              swipe_down: { label: "", action: null }
        """.trimIndent())
        assertNull(keys["space"]!!.swipeDown!!.action)
    }

    @Test
    fun `完整多键配置解析`() {
        val keys = parseKeys("""
            q: { tap: "q", swipe_up: "1", swipe_down: "Q" }
            a: { tap: "a", swipe_up: "!", swipe_down: "A" }
            z: { tap: "z", swipe_up: "|", swipe_down: "Z" }
            m: { tap: "m", swipe_up: "+", swipe_down: "M" }
        """.trimIndent())
        assertEquals(4, keys.size)
        assertEquals("1", keys["q"]!!.swipeUp!!.label)
        assertEquals("|", keys["z"]!!.swipeUp!!.label)
        assertEquals("+", keys["m"]!!.swipeUp!!.label)
    }

    @Test
    fun `空的 keys 不报错`() {
        assertEquals(0, parseKeys("{}").size)
    }

    @Test
    fun `部分手势缺失不报错`() {
        val a = parseKeys("""a: { tap: "a" }""".trimIndent())["a"]!!
        assertEquals("a", a.tap!!.label)
        assertNull(a.swipeUp)
        assertNull(a.swipeDown)
        assertNull(a.longPress)
    }

    // ── long_press flow-style 数组 ──

    @Test
    fun `long_press flow-style 字符串数组解析`() {
        val keys = parseKeys("""
            q: { tap: "q", swipe_up: "1", swipe_down: "Q", long_press: ["q", "Q"] }
        """.trimIndent())
        val lp = keys["q"]!!.longPress!!
        assertEquals(2, lp.size)
        assertEquals("q", lp[0].label)
        assertEquals("commit", lp[0].action)
        assertEquals("q", lp[0].value)
        assertEquals("Q", lp[1].label)
        assertEquals("commit", lp[1].action)
        assertEquals("Q", lp[1].value)
    }

    @Test
    fun `long_press flow-style 混合字符串和对象`() {
        val keys = parseKeys("""
            a: { tap: "a", swipe_up: "!", swipe_down: "A", long_press: [{ label: "全选", action: "select_all" }, "a", "A"] }
        """.trimIndent())
        val lp = keys["a"]!!.longPress!!
        assertEquals(3, lp.size)
        // 对象格式
        assertEquals("全选", lp[0].label)
        assertEquals("select_all", lp[0].action)
        assertEquals("", lp[0].value) // 未指定 value 时默认为空字符串
        // 字符串简写
        assertEquals("a", lp[1].label)
        assertEquals("commit", lp[1].action)
        assertEquals("a", lp[1].value)
        assertEquals("A", lp[2].label)
        assertEquals("commit", lp[2].action)
        assertEquals("A", lp[2].value)
    }

    @Test
    fun `long_press flow-style 带变音符号`() {
        val keys = parseKeys("""
            u: { tap: "u", swipe_up: "7", swipe_down: "U", long_press: ["u", "U", "ù", "ú", "û", "ü"] }
        """.trimIndent())
        val lp = keys["u"]!!.longPress!!
        assertEquals(6, lp.size)
        assertEquals("u", lp[0].value)
        assertEquals("U", lp[1].value)
        assertEquals("ù", lp[2].value)
        assertEquals("ú", lp[3].value)
        assertEquals("û", lp[4].value)
        assertEquals("ü", lp[5].value)
    }

    @Test
    fun `long_press flow-style 单个元素`() {
        val keys = parseKeys("""
            p: { tap: "p", swipe_up: "0", swipe_down: "P", long_press: ["p", "P"] }
        """.trimIndent())
        val lp = keys["p"]!!.longPress!!
        assertEquals(2, lp.size)
    }

    @Test
    fun `long_press flow-style 带有特殊字符的反斜杠`() {
        val keys = parseKeys("""
            c: { tap: "c", swipe_up: "\\", swipe_down: "C", long_press: ["c", "C", "ç"] }
        """.trimIndent())
        val lp = keys["c"]!!.longPress!!
        assertEquals(3, lp.size)
        assertEquals("c", lp[0].value)
        assertEquals("C", lp[1].value)
        assertEquals("ç", lp[2].value)
        assertEquals("\\", keys["c"]!!.swipeUp!!.value)
    }

    // ── 完整 26 键配置 ──

    @Test
    fun `完整 26 键全键盘配置解析`() {
        val yaml = """
            q: { tap: "q", swipe_up: "1", swipe_down: "Q", long_press: ["q", "Q"] }
            w: { tap: "w", swipe_up: "2", swipe_down: "W", long_press: ["w", "W"] }
            e: { tap: "e", swipe_up: "3", swipe_down: "E", long_press: ["e", "E", "è", "é", "ê", "ë"] }
            r: { tap: "r", swipe_up: "4", swipe_down: "R", long_press: ["r", "R"] }
            t: { tap: "t", swipe_up: "5", swipe_down: "T", long_press: ["t", "T"] }
            y: { tap: "y", swipe_up: "6", swipe_down: "Y", long_press: ["y", "Y", "ÿ"] }
            u: { tap: "u", swipe_up: "7", swipe_down: "U", long_press: ["u", "U", "ù", "ú", "û", "ü"] }
            i: { tap: "i", swipe_up: "8", swipe_down: "I", long_press: ["i", "I", "ì", "í", "î", "ï"] }
            o: { tap: "o", swipe_up: "9", swipe_down: "O", long_press: ["o", "O", "ò", "ó", "ô", "õ", "ö", "ø"] }
            p: { tap: "p", swipe_up: "0", swipe_down: "P", long_press: ["p", "P"] }
            a: { tap: "a", swipe_up: "!", swipe_down: "A", long_press: ["a", "A", "à", "á", "â", "ã", "ä", "å", "æ"] }
            s: { tap: "s", swipe_up: "@", swipe_down: "S", long_press: ["s", "S", "ß"] }
            d: { tap: "d", swipe_up: "#", swipe_down: "D", long_press: ["d", "D"] }
            f: { tap: "f", swipe_up: "$", swipe_down: "F", long_press: ["f", "F"] }
            g: { tap: "g", swipe_up: "%", swipe_down: "G", long_press: ["g", "G"] }
            h: { tap: "h", swipe_up: "^", swipe_down: "H", long_press: ["h", "H"] }
            j: { tap: "j", swipe_up: "&", swipe_down: "J", long_press: ["j", "J"] }
            k: { tap: "k", swipe_up: "(", swipe_down: "K", long_press: ["k", "K"] }
            l: { tap: "l", swipe_up: ")", swipe_down: "L", long_press: ["l", "L"] }
            z: { tap: "z", swipe_up: "|", swipe_down: "Z", long_press: ["z", "Z"] }
            x: { tap: "x", swipe_up: "*", swipe_down: "X", long_press: ["x", "X"] }
            c: { tap: "c", swipe_up: "\\", swipe_down: "C", long_press: ["c", "C", "ç"] }
            v: { tap: "v", swipe_up: "?", swipe_down: "V", long_press: ["v", "V"] }
            b: { tap: "b", swipe_up: "_", swipe_down: "B", long_press: ["b", "B"] }
            n: { tap: "n", swipe_up: "-", swipe_down: "N", long_press: ["n", "N", "ñ"] }
            m: { tap: "m", swipe_up: "+", swipe_down: "M", long_press: ["m", "M"] }
        """.trimIndent()
        val keys = parseKeys(yaml)
        assertEquals("应有 26 个字母键", 26, keys.size)

        // 验证所有字母键都存在
        val allLetters = ('a'..'z').map { it.toString() }
        for (letter in allLetters) {
            assertNotNull("键 $letter 应该存在", keys[letter])
        }

        // 验证每个键的 tap / swipe_up / swipe_down
        for ((key, kc) in keys) {
            assertNotNull("$key.tap 不能为空", kc.tap)
            assertNotNull("$key.swipe_up 不能为空", kc.swipeUp)
            assertNotNull("$key.swipe_down 不能为空", kc.swipeDown)
            assertNotNull("$key.long_press 不能为空", kc.longPress)
        }
    }

    @Test
    fun `完整 26 键 long_press 顺序正确`() {
        val yaml = """
            q: { tap: "q", swipe_up: "1", swipe_down: "Q", long_press: ["q", "Q"] }
            w: { tap: "w", swipe_up: "2", swipe_down: "W", long_press: ["w", "W"] }
            e: { tap: "e", swipe_up: "3", swipe_down: "E", long_press: ["e", "E", "è", "é", "ê", "ë"] }
            r: { tap: "r", swipe_up: "4", swipe_down: "R", long_press: ["r", "R"] }
            t: { tap: "t", swipe_up: "5", swipe_down: "T", long_press: ["t", "T"] }
            y: { tap: "y", swipe_up: "6", swipe_down: "Y", long_press: ["y", "Y", "ÿ"] }
            u: { tap: "u", swipe_up: "7", swipe_down: "U", long_press: ["u", "U", "ù", "ú", "û", "ü"] }
            i: { tap: "i", swipe_up: "8", swipe_down: "I", long_press: ["i", "I", "ì", "í", "î", "ï"] }
            o: { tap: "o", swipe_up: "9", swipe_down: "O", long_press: ["o", "O", "ò", "ó", "ô", "õ", "ö", "ø"] }
            p: { tap: "p", swipe_up: "0", swipe_down: "P", long_press: ["p", "P"] }
            a: { tap: "a", swipe_up: "!", swipe_down: "A", long_press: ["a", "A", "à", "á", "â", "ã", "ä", "å", "æ"] }
            s: { tap: "s", swipe_up: "@", swipe_down: "S", long_press: ["s", "S", "ß"] }
            d: { tap: "d", swipe_up: "#", swipe_down: "D", long_press: ["d", "D"] }
            f: { tap: "f", swipe_up: "$", swipe_down: "F", long_press: ["f", "F"] }
            g: { tap: "g", swipe_up: "%", swipe_down: "G", long_press: ["g", "G"] }
            h: { tap: "h", swipe_up: "^", swipe_down: "H", long_press: ["h", "H"] }
            j: { tap: "j", swipe_up: "&", swipe_down: "J", long_press: ["j", "J"] }
            k: { tap: "k", swipe_up: "(", swipe_down: "K", long_press: ["k", "K"] }
            l: { tap: "l", swipe_up: ")", swipe_down: "L", long_press: ["l", "L"] }
            z: { tap: "z", swipe_up: "|", swipe_down: "Z", long_press: ["z", "Z"] }
            x: { tap: "x", swipe_up: "*", swipe_down: "X", long_press: ["x", "X"] }
            c: { tap: "c", swipe_up: "\\", swipe_down: "C", long_press: ["c", "C", "ç"] }
            v: { tap: "v", swipe_up: "?", swipe_down: "V", long_press: ["v", "V"] }
            b: { tap: "b", swipe_up: "_", swipe_down: "B", long_press: ["b", "B"] }
            n: { tap: "n", swipe_up: "-", swipe_down: "N", long_press: ["n", "N", "ñ"] }
            m: { tap: "m", swipe_up: "+", swipe_down: "M", long_press: ["m", "M"] }
        """.trimIndent()
        val keys = parseKeys(yaml)

        // 验证带变音符号的键
        assertLongPressValues(keys["e"]!!, listOf("e", "E", "è", "é", "ê", "ë"))
        assertLongPressValues(keys["y"]!!, listOf("y", "Y", "ÿ"))
        assertLongPressValues(keys["u"]!!, listOf("u", "U", "ù", "ú", "û", "ü"))
        assertLongPressValues(keys["i"]!!, listOf("i", "I", "ì", "í", "î", "ï"))
        assertLongPressValues(keys["o"]!!, listOf("o", "O", "ò", "ó", "ô", "õ", "ö", "ø"))
        assertLongPressValues(keys["a"]!!, listOf("a", "A", "à", "á", "â", "ã", "ä", "å", "æ"))
        assertLongPressValues(keys["s"]!!, listOf("s", "S", "ß"))
        assertLongPressValues(keys["c"]!!, listOf("c", "C", "ç"))
        assertLongPressValues(keys["n"]!!, listOf("n", "N", "ñ"))

        // 验证无变音符号的键（仅小写+大写）
        val noAccentKeys = listOf("q", "w", "r", "t", "p", "d", "f", "g", "h", "j", "k", "l", "z", "x", "v", "b", "m")
        for (key in noAccentKeys) {
            val upper = key.uppercase()
            assertLongPressValues(keys[key]!!, listOf(key, upper))
        }
    }

    private fun assertLongPressValues(kc: KeyGestureConfig, expectedValues: List<String>) {
        val lp = kc.longPress!!
        assertEquals("long_press 数量不匹配: 期望 $expectedValues 实际 ${lp.map { it.value }}",
            expectedValues.size, lp.size)
        for (i in expectedValues.indices) {
            assertEquals("索引 $i 的值不匹配", expectedValues[i], lp[i].value)
            assertEquals("索引 $i 的动作应为 commit", "commit", lp[i].action)
        }
    }

    // ── 辅助 ──

    private fun parseKeys(yamlFragment: String): Map<String, KeyGestureConfig> {
        val fullYaml = "keyboard:\n  keys:\n    " + yamlFragment.replace("\n", "\n    ")
        val root = Yaml.default.parseToYamlNode(fullYaml) as com.charleskorn.kaml.YamlMap
        val keyboardNode = root["keyboard"] as? com.charleskorn.kaml.YamlMap ?: return emptyMap()
        val keysNode = keyboardNode["keys"] as? com.charleskorn.kaml.YamlMap ?: return emptyMap()
        val result = mutableMapOf<String, KeyGestureConfig>()
        for ((kNode, vNode) in keysNode.entries) {
            val key = (kNode as com.charleskorn.kaml.YamlScalar).content
            val gestureMap = vNode as com.charleskorn.kaml.YamlMap
            result[key] = parseKeyGestureConfig(gestureMap)
        }
        return result
    }

    private fun parseKeyGestureConfig(map: com.charleskorn.kaml.YamlMap): KeyGestureConfig {
        var tap: GestureDef? = null
        var swipeUp: GestureDef? = null
        var swipeDown: GestureDef? = null
        var longPress: List<GestureDef>? = null
        for ((kNode, vNode) in map.entries) {
            val name = (kNode as com.charleskorn.kaml.YamlScalar).content
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
        if (node is com.charleskorn.kaml.YamlScalar) {
            val text = node.content
            return GestureDef(label = text, action = "commit", value = text)
        }
        if (node is com.charleskorn.kaml.YamlMap) {
            var label = ""
            var action: String? = "commit"
            var value = ""
            for ((k, v) in node.entries) {
                val key = (k as com.charleskorn.kaml.YamlScalar).content
                val vStr = (v as? com.charleskorn.kaml.YamlScalar)?.content
                when (key) {
                    "label" -> if (vStr != null) label = vStr
                    "action" -> action = vStr // YAML null → null
                    "value" -> if (vStr != null) value = vStr
                }
            }
            return GestureDef(label = label, action = action, value = value)
        }
        return GestureDef()
    }

    private fun parseGestureList(node: com.charleskorn.kaml.YamlNode): List<GestureDef>? {
        val list = node as? com.charleskorn.kaml.YamlList ?: return null
        return list.items.map { parseGestureNode(it) }
    }
}
