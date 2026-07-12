package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * 分词键（1键）行为专项测试。
 *
 * 覆盖：
 * - 分词键后 RIME 输入正确性
 * - 分词键+左选+退格完整序列
 */
class T9SeparatorTest {

    private fun createController(): T9InputController {
        return T9InputController(onReplaceFullPinyin = { /* no-op */ })
    }

    private fun T9InputController.delete(): T9InputController.DeleteResult = onDeleted()

    private fun assertPinyins(controller: T9InputController, vararg expected: String) {
        assertEquals(expected.toList(), controller.firstOptions.map { it.pinyin })
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4：简拼和全拼混合输入，分词键+左选后退格完整序列
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION
    //   3. 左选 ge → buffer="kge", SELECTION(ge, "43")
    //   4. 退格6次清空
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4 separator - backspace sequence after separator and choices takes 6 steps`() {
        val ctrl = createController()

        // 步骤1: 输入5 → 按分词键(1) → 4 → 3
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.bufferString)
        ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.bufferString)
        assertTrue(ctrl.leftColumnLocked)
        ctrl.onDigitPressed("4")
        assertEquals("j'4", ctrl.bufferString)
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)

        // 步骤3: 左选ge
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ge", 2))
        assertEquals("kge", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("ge", 2), ctrl.selectedOption)
        assertEquals("43", ctrl.selectionCandidateDigits)

        // 步骤4: 退格6次清空
        // 预期回退顺序: ge点击, 3, 4, k点击, 1(分词键), 5

        // bs1: 撤销ge → buffer="k'43", SELECTION(k)
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("k'43", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)

        // bs2: 删除3 → buffer="k'4"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("k'4", ctrl.bufferString)

        // bs3: 删除4 → buffer="k'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("k'", ctrl.bufferString)

        // bs4: 撤销k → buffer="5", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs5: 撤销分词键(1) → buffer="5", Separator弹出
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)

        // bs6: 删除5 → buffer="", IDLE
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 验证：分词键后 sendToRime 发送正确输入
    // 按5 → 按1(分词键)后，RIME应收到"j'"而非"5"（保留音节边界信息）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4 separator - sendToRime sends pinyin after separator`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        ctrl.onDigitPressed("5")
        rimeCalls.clear() // 清除按5时的RIME调用

        ctrl.onDigitPressed("1")
        // 分词键后 RIME 应收到拼音（而非纯数字）
        // 注：toPreeditString 在全拼选择后不加 '，RIME syllabifier 会消费尾随分隔符，候选相同
        val separatorCall = rimeCalls.firstOrNull()
        assertNotNull("分词键后应有RIME调用", separatorCall)
        assertTrue("分词键后RIME应收到'j'而非纯数字'5'，实际=$separatorCall",
            separatorCall != null && separatorCall!!.contains("j"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景2：简拼和全拼混合输入并执行退格流程
    //
    // 操作流程：
    //   1. 输入序列：5143 → 预编辑文本："j'43"
    //   2. 执行backspace清空：按4次
    // 预期回退顺序：【3, 4, 1(分词键), 5】
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario2 separator - simple backspace after separator takes 4 steps`() {
        val ctrl = createController()

        // 步骤1: 输入 5 → 按分词键(1) → 4 → 3
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.bufferString)
        ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.bufferString)
        assertTrue(ctrl.leftColumnLocked)
        ctrl.onDigitPressed("4")
        assertEquals("j'4", ctrl.bufferString)
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 退格4次清空
        // 预期回退顺序: 3, 4, 1(分词键), 5

        // bs1: 删除3 → buffer="j'4"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("j'4", ctrl.bufferString)

        // bs2: 删除4 → buffer="j'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("j'", ctrl.bufferString)

        // bs3: 撤销分词键(1) → buffer="5", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs4: 删除5 → buffer="", IDLE
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.3：简拼+全拼混合输入，筛选层切换+同位数替换后退格序列
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 ge → buffer="kge", SELECTION(ge, "43")
    //   4. 左选 g（筛选层切换）→ buffer="kg'3", SELECTION(g, "4")
    //   5. 左选 d（同位数替换）→ buffer="kgd", SELECTION(d, "3")
    //   6. 退格7次清空
    // 预期回退顺序: d(撤销), 3, g(部分撤销 → k'4), 4, k(撤销), 1(分词键), 5
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario43 separator - backspace after selection layer switch and same digit replacement`() {
        val ctrl = createController()

        // 步骤1: 输入 5 → 按分词键(1) → 4 → 3
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.bufferString)
        ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.bufferString)
        ctrl.onDigitPressed("4")
        assertEquals("j'4", ctrl.bufferString)
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)

        // 步骤3: 左选 ge
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ge", 2))
        assertEquals("kge", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("ge", 2), ctrl.selectedOption)
        assertEquals("43", ctrl.selectionCandidateDigits)

        // 步骤4: 左选 g（筛选层切换，替换ge）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // 步骤5: 左选 d（同位数替换，替换g）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("kgd", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("d", 1), ctrl.selectedOption)
        assertEquals("3", ctrl.selectionCandidateDigits)

        // 步骤6: 退格7次清空
        // 预期回退顺序: d撤销, 3删除, g部分撤销(→k'4), 4删除, k撤销, 1分词键, 5删除

        // bs1: 撤销d → buffer="kg'3", SELECTION(g, "4")
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // bs2: 删除3 → buffer="kg'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("kg'", ctrl.bufferString)

        // bs3: 部分撤销g → buffer="k'4", SELECTION(k, "5")
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("k'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)

        // bs4: 删除4 → buffer="k'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("k'", ctrl.bufferString)

        // bs5: 撤销k → buffer="5", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs6: 撤销分词键(1) → buffer="5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)

        // bs7: 删除5 → buffer="", IDLE
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.3b：简拼+全拼混合输入，直接左选（无ge中介）+同位数替换后退格序列
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 g（直接，跳过ge）→ buffer="kg'3", SELECTION(g, "4")
    //   4. 左选 d（同位数替换）→ buffer="kgd", SELECTION(d, "3")
    //   5. 退格7次清空
    // 预期回退顺序: d(撤销), 3, g(部分撤销 → k'4), 4, k(撤销), 1(分词键), 5
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario43b separator - backspace after direct g selection and same digit replacement`() {
        val ctrl = createController()

        // 步骤1: 输入 5 → 按分词键(1) → 4 → 3
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.bufferString)
        ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.bufferString)
        ctrl.onDigitPressed("4")
        assertEquals("j'4", ctrl.bufferString)
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)

        // 步骤3: 左选 g（直接，跳过ge）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // 步骤4: 左选 d（同位数替换）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("kgd", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("d", 1), ctrl.selectedOption)
        assertEquals("3", ctrl.selectionCandidateDigits)

        // 步骤5: 退格7次清空
        // 预期回退顺序: d撤销, 3删除, g部分撤销(→k'4), 4删除, k撤销, 1分词键, 5删除

        // bs1: 撤销d → buffer="kg'3", SELECTION(g, "4")
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // bs2: 删除3 → buffer="kg'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("kg'", ctrl.bufferString)

        // bs3: 部分撤销g → buffer="k'4", SELECTION(k, "5")
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("k'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)

        // bs4: 删除4 → buffer="k'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("k'", ctrl.bufferString)

        // bs5: 撤销k → buffer="5", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs6: 撤销分词键(1) → buffer="5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.bufferString)

        // bs7: 删除5 → buffer="", IDLE
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.1：验证选择 ge 后 RIME 收到带分隔符的拼音
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", RIME收到"k'43"
    //   3. 左选 ge → buffer="kge", SELECTION(ge, "43")
    //   4. 验证 RIME 收到的是"k'ge"（带分隔符），而非"kge"
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario41 separator - sendToRime sends k'ge with apostrophe after selecting ge`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 步骤1: 输入 5 → 按分词键(1) → 4 → 3
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k，RIME 应收到"k'43"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)
        rimeCalls.clear()

        // 步骤3: 左选 ge，RIME 应收到"k'ge"（带分隔符）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ge", 2))
        assertEquals("kge", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 验证 RIME 最后收到的是 "k'ge"（带分隔符），而非 "kge"
        val lastCall = rimeCalls.lastOrNull()
        assertNotNull("选择ge后应有RIME调用", lastCall)
        assertEquals("选择ge后RIME应收到'k'ge'，实际=$lastCall",
            "k'ge", lastCall)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4扩展：验证简拼 i 选择后 RIME 收到带分隔符的连续简拼
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 l → buffer="l'43", RIME收到"l'43"
    //   3. 左选 i（简拼）→ buffer="li'3", RIME 应收到"l'i'3"（连续简拼需分隔）
    //   4. 验证 RIME 收到的是"l'i'3"，而非"li'3"
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4 li - sendToRime sends l'i'3 with apostrophe between abbrevs`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 步骤1: 输入 5 → 按分词键(1) → 4 → 3
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 l，RIME 应收到"l'43"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("l", 1))
        assertEquals("l'43", ctrl.bufferString)
        rimeCalls.clear()

        // 步骤3: 左选 i（简拼），RIME 应收到"l'i'3"（连续简拼需分隔）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("i", 1))
        assertEquals("li'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("i", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // 验证 RIME 最后收到的是 "l'i'3"（简拼间分隔），而非 "li'3"
        val lastCall = rimeCalls.lastOrNull()
        assertNotNull("选择i后应有RIME调用", lastCall)
        assertEquals("选择i后RIME应收到'l'i'3'，实际=$lastCall",
            "l'i'3", lastCall)
    }
}