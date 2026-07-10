package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * T9InputController 单元测试 — 命令模式（Phase 3）。
 *
 * 覆盖基础输入场景、分词键、选区操作、右侧候选选词、退格撤销。
 */
class T9InputControllerTest {

    private fun createController(): T9InputController {
        return T9InputController(onReplaceFullPinyin = { /* no-op */ })
    }

    private fun createControllerWithRime(composition: RimeComposition): T9InputController {
        return T9InputController(
            onReplaceFullPinyin = { /* no-op */ },
            onQueryRimeComposition = { composition }
        )
    }

    private fun T9InputController.delete(): T9InputController.DeleteResult = onDeleted()

    private fun assertPinyins(controller: T9InputController, vararg expected: String) {
        assertEquals(expected.toList(), controller.firstOptions.map { it.pinyin })
    }

    // ═══════════════════════════════════════════════════════════
    // 场景3：全拼左选li+gua后空格选"离卦"（full commit）
    // 操作：54482 → 左选li → 左选gua → 空格(选择首位候选"离卦")
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `scenario 3 - select li then gua then space with space-separated comment should full commit`() {
        val ctrl = createController()
        // 1. 输入54482
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        assertEquals("54482", ctrl.bufferString)

        // 2. 左选li
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        assertEquals("li'482", ctrl.bufferString)

        // 3. 左选gua
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        assertEquals("ligua", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("gua", 3), ctrl.selectedOption)

        // 4. 空格选"离卦"(comment="li gua", 空格分隔)
        val result = ctrl.onRightCandidateSelected("li gua", 2)
        assertTrue("右选'离卦'(li gua)应完整消费, buffer=${ctrl.bufferString}", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
        assertNull(ctrl.selectionCandidateDigits)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `scenario 3 - select li then gua then space with no-space comment should full commit`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        assertEquals("ligua", ctrl.bufferString)

        // RIME可能返回无音节分隔的注释(如"ligua"而非"li gua")
        // 当selectionHistory完全覆盖buffer且候选字数>=选择数时，应判定为full commit
        val result = ctrl.onRightCandidateSelected("ligua", 2)
        assertTrue("右选'离卦'(ligua无分隔)应完整消费, buffer=${ctrl.bufferString}", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
    }

    @Test
    fun `scenario 3 - select li then gua then space with null comment should full commit`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        assertEquals("ligua", ctrl.bufferString)

        // 实机场景：RIME不返回spelling_hints注释时candidatePinyin=null
        // selectionHistory=[li, gua]完全覆盖buffer="ligua"，应判定为full commit
        val result = ctrl.onRightCandidateSelected(null, 2)
        assertTrue("右选'离卦'(null注释)应完整消费, buffer=${ctrl.bufferString}", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
        assertNull(ctrl.selectionCandidateDigits)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `scenario 3 - backspace sequence after selecting li and gua takes 7 steps`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        assertEquals("li'482", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        assertEquals("ligua", ctrl.bufferString)

        // 回退顺序：【gua点击, 2, 8, 4, li点击, 4, 5】= 7次
        // 1. 撤销gua选中
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("li'482", ctrl.bufferString)

        // 2. 删除2
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'48", ctrl.bufferString)

        // 3. 删除8
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'4", ctrl.bufferString)

        // 4. 删除4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'", ctrl.bufferString)

        // 5. 撤销li选中
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("54", ctrl.bufferString)

        // 6. 删除4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("5", ctrl.bufferString)

        // 7. 删除5
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景4：左选全拼后上屏（基础输入场景4）
    // 操作：54482 → 左选ji → 右选"计划"(full commit)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `scenario 4 - input 54482 select ji then right candidate plan full commit`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ji", 2))
        assertEquals("ji'482", ctrl.bufferString)
        assertTrue(ctrl.firstOptions.any { it.pinyin == "gua" })
        assertTrue(ctrl.firstOptions.any { it.pinyin == "hua" })

        val result = ctrl.onRightCandidateSelected("ji hua", 2)
        assertTrue("右选'计划'应完整消费所有数字", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
        assertNull(ctrl.selectionCandidateDigits)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `scenario 4 - verify sendToRime sends ji-apostrophe-482 after selecting ji`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        assertTrue("sendToRime should send '54482'",
            rimeCalls.any { it == "54482" })

        rimeCalls.clear()
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ji", 2))
        assertEquals("ji'482", ctrl.bufferString)

        // 关键验证：左选ji后sendToRime必须发送"ji'482"而非"ji"
        assertTrue("should send 'ji'482' to RIME, got: $rimeCalls",
            rimeCalls.any { it == "ji'482" })
        assertFalse("should NOT send 'ji' alone",
            rimeCalls.any { it == "ji" })

        rimeCalls.clear()
        ctrl.onRightCandidateSelected("ji hua", 2)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景5：左选全拼后空格（基础输入场景5）
    // 操作：54482 → 左选ji → 空格(选择首位候选"计划")
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `scenario 5 - input 54482 select ji then space full commit`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ji", 2))
        assertEquals("ji'482", ctrl.bufferString)

        val result = ctrl.onRightCandidateSelected("ji hua", 2)
        assertTrue("右选'计划'应完整消费数字", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景6：左选简拼后上屏（基础输入场景6）
    // 操作：5 → 左选j → 4 → 左选g → 空格
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `scenario 6 - input 5 select j 4 select g then space full commit`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))
        assertEquals("j", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("j", 1), ctrl.selectedOption)

        ctrl.onDigitPressed("4")
        assertEquals("j'4", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("jg", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        val result = ctrl.onRightCandidateSelected("jia ge", 2)
        assertTrue("左选j+g后右选'价格'应完整消费, 当前buffer=${ctrl.bufferString}", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
        assertNull(ctrl.selectionCandidateDigits)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 基础按键
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `press 5 updates buffer and candidates`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.bufferString)
        assertTrue(ctrl.firstOptions.isNotEmpty())
        assertEquals("j", ctrl.firstOptions.first().pinyin)
    }

    @Test
    fun `press 5 then 4 updates buffer to 54`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("4")
        assertEquals("54", ctrl.bufferString)
    }

    // ═══════════════════════════════════════════════════════════
    // 分词键
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `separator key converts digit 5 to j-apostrophe and locks`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.bufferString)
        assertTrue(ctrl.leftColumnLocked)
    }

    @Test
    fun `after separator pressing 43 appends digits and keeps panel locked`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)
        assertTrue(ctrl.leftColumnLocked)
    }

    @Test
    fun `separator uses rime comment to choose li over ji`() {
        val composition = RimeComposition(
            input = "54482", preedit = "li hua", committedText = "",
            candidates = arrayOf(RimeCandidate("梨花", "li hua")),
            hasNextPage = false, hasPrevPage = false, isAsciiMode = false
        )
        val ctrl = createControllerWithRime(composition)
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onDigitPressed("1")
        assertEquals("li'482", ctrl.bufferString)
        assertTrue(ctrl.leftColumnLocked)
    }

    // ═══════════════════════════════════════════════════════════
    // 从锁定候选区选词 / 从剩余数字选词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `select k from locked panel replaces j with k`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)
        assertFalse(ctrl.leftColumnLocked)
        assertTrue(ctrl.firstOptions.any { it.pinyin == "he" })
    }

    @Test
    fun `select ji from 54482 produces ji-apostrophe-482`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ji", 2))
        assertEquals("ji'482", ctrl.bufferString)
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `select j from 54482 produces j-apostrophe-4482`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))
        assertEquals("j'4482", ctrl.bufferString)
    }

    @Test
    fun `selection state - clicking another left option replaces selected pinyin`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        assertEquals("li'482", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        assertEquals("ligua", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("hua", 3))
        assertEquals("lihua", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════
    // 右侧候选选词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `rightCandidate on 23744 consumes first 2 digits to 744`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.bufferString)
        assertTrue("left options should contain shi", ctrl.firstOptions.any { it.pinyin == "shi" })
    }

    @Test
    fun `rightCandidate on empty buffer does nothing`() {
        val ctrl = createController()
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.bufferString)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `rightCandidate on 5 consumes all to empty`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.bufferString)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `bug1 - consecutive right candidate selections consume buffer correctly`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.bufferString)
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.bufferString)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `scenario 10 - right candidate full commit clears all state`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        val fullyConsumed = ctrl.onRightCandidateSelected("ji hua", 2)
        assertTrue(fullyConsumed)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 退格与撤销
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `backspace on empty buffer returns NOT_CONSUMED`() {
        val ctrl = createController()
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `backspace unlocks panel and undoes separator`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.bufferString)
        assertTrue(ctrl.leftColumnLocked)
        ctrl.delete()
        assertEquals("5", ctrl.bufferString)
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `first backspace after rightCandidate returns UNDO_COMMIT`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.bufferString)
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23744", ctrl.bufferString)
    }

    @Test
    fun `clearAll resets everything`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.clearAll()
        assertEquals("", ctrl.bufferString)
        assertTrue(ctrl.firstOptions.isEmpty())
        assertFalse(ctrl.leftColumnLocked)
    }

    // ═══════════════════════════════════════════════════════════
    // 候选词过滤（filterCandidatesBySelectionHistory）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `filter - selecting ji should keep ji-hua candidates like plan`() {
        // 场景4：输入54482 → 左选ji → RIME返回"计划(ji hua)"等候选
        // selectionHistory = [SyllableOption("ji", 2)]
        // "计划" 的 comment = "ji hua"，应通过过滤
        val candidates = listOf("计划", "即", "及")
        val comments = listOf("ji hua", "ji", "ji")
        val selectionHistory = listOf(T9PinyinMap.SyllableOption("ji", 2))

        val (filtered, _) = filterCandidatesBySelectionHistory(
            candidates, comments, selectionHistory
        )

        assertTrue("过滤后应保留'计划(ji hua)'，实际: $filtered",
            filtered.contains("计划"))
    }

    @Test
    fun `filter - selecting ji then hua keeps all digit-code-matching candidates`() {
        // 场景4完整：54482 → 左选ji → 左选hua
        // 数字码约束：ji(54) + hua(482)
        // 九键约束仅在数字码级别，hua/gua 同码 482 不可区分
        // 精确拼音过滤由 RIME 的 setInput("ji'482") 实现，客户端只做数字码辅助过滤
        val candidates = listOf("计划", "记挂", "梨花", "客户")
        val comments = listOf("ji hua", "ji gua", "li hua", "ke hu")
        val selectionHistory = listOf(
            T9PinyinMap.SyllableOption("ji", 2),
            T9PinyinMap.SyllableOption("hua", 3)
        )

        val (filtered, _) = filterCandidatesBySelectionHistory(
            candidates, comments, selectionHistory
        )

        // 数字码匹配的都保留
        assertTrue("应保留'计划(ji hua)'", filtered.contains("计划"))
        assertTrue("应保留'记挂(ji gua)'，gua(482)==hua(482)", filtered.contains("记挂"))
        // 数字码不匹配的排除
        assertFalse("应排除'客户(ke hu)'，ke(53)!=ji(54)且hu(48)!=hua(482)",
            filtered.contains("客户"))
    }

    @Test
    fun `filter - selecting only first syllable should not require matching last comment syllable`() {
        // 核心bug验证：只选了第一个音节ji时，不应要求ji的数字码(54)等于comment末音节的数字码
        // 即"计划(ji hua)"不应因"54"!="482"而被过滤掉
        val candidates = listOf("计划", "激化", "即", "及")
        val comments = listOf("ji hua", "ji hua", "ji", "ji")
        val selectionHistory = listOf(T9PinyinMap.SyllableOption("ji", 2))

        val (filtered, filteredComments) = filterCandidatesBySelectionHistory(
            candidates, comments, selectionHistory
        )

        // 选了ji后，组合词(ji hua)和单字(ji)都应该保留
        assertTrue("应保留'计划(ji hua)'", filtered.contains("计划"))
        assertTrue("应保留'激化(ji hua)'", filtered.contains("激化"))
        assertTrue("应保留'即(ji)'", filtered.contains("即"))
    }

    @Test
    fun `filter - selecting k then he keeps prefix-matched candidates like ke-hu`() {
        // 场景5143：左选k → 左选he
        // "空格(kong ge)" 全匹配 → FULL
        // "考核(kao he)" 全匹配 → FULL
        // "客户(ke hu)" k→ke 前缀匹配成功，he≠hu → PREFIX（保留，排在FULL后面）
        val candidates = listOf("空格", "客户", "考核")
        val comments = listOf("kong ge", "ke hu", "kao he")
        val selectionHistory = listOf(
            T9PinyinMap.SyllableOption("k", 1),
            T9PinyinMap.SyllableOption("he", 2)
        )

        val (filtered, _) = filterCandidatesBySelectionHistory(
            candidates, comments, selectionHistory
        )

        // 全匹配排前，前缀匹配排后
        assertTrue("应保留'空格(kong ge)'全匹配", filtered.contains("空格"))
        assertTrue("应保留'考核(kao he)'全匹配", filtered.contains("考核"))
        assertTrue("应保留'客户(ke hu)'前缀匹配(k→ke)", filtered.contains("客户"))
        // 全匹配应在前缀匹配之前
        assertTrue("空格应排在客户前面",
            filtered.indexOf("空格") < filtered.indexOf("客户"))
    }

    // ═══════════════════════════════════════════════════════════
    // 三态状态机
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `state machine - idle to input to selection to idle cycle`() {
        val ctrl = createController()
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        ctrl.onDigitPressed("5")
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("j", 1), ctrl.selectedOption)
        assertEquals("5", ctrl.selectionCandidateDigits)
        ctrl.onDigitPressed("4")
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景3.1：全拼左选li+gua后右选单字"里"（partial commit）
    // 操作：54482 → 左选li → 左选gua → 右选"里"(li, 1字)
    // Bug：buffer变为"482"导致RIME收到"gua'482"产生"瓜瓜"候选
    // 修复：buffer应为"gua"(拼音), selectionHistory移除已消费的"li"
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `scenario 3_1 - partial commit of li leaves gua as pinyin buffer not digits`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 1. 输入54482
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)

        // 2. 左选li
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        assertEquals("li'482", ctrl.bufferString)

        // 3. 左选gua
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        assertEquals("ligua", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 4. 右选"里"(单字, pinyin="li") — partial commit
        rimeCalls.clear()
        val result = ctrl.onRightCandidateSelected("li", 1)
        assertFalse("右选'里'应partial commit, buffer=${ctrl.bufferString}", result)

        // 核心验证：buffer应为拼音"gua"而非数字"482"（String buffer 解析被 T9Buffer 替代）
        assertEquals("buffer应为拼音'gua'而非数字'482'", "gua", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("gua", 3), ctrl.selectedOption)
        assertEquals("482", ctrl.selectionCandidateDigits)

        // partial commit消费了"li"，selectionHistory只保留[gua]
        assertEquals(listOf(T9PinyinMap.SyllableOption("gua", 3)), ctrl.selectionHistory)

        // 左候选区仍显示482的有效拼音
        assertTrue("应包含gua", ctrl.firstOptions.any { it.pinyin == "gua" })
        assertTrue("应包含hua", ctrl.firstOptions.any { it.pinyin == "hua" })

        // RIME输入验证：应为"gua"而非"gua'482"
        ctrl.forceSendToRime()
        assertTrue("RIME应收到'gua', 实际: $rimeCalls",
            rimeCalls.any { it == "gua" })
        assertFalse("RIME不应收到含'482'的输入, 实际: $rimeCalls",
            rimeCalls.any { it.contains("482") })
    }

    @Test
    fun `scenario 3_1 - null comment partial commit also leaves gua as pinyin buffer`() {
        val ctrl = createController()

        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))

        // RIME不返回注释时candidatePinyin=null
        val result = ctrl.onRightCandidateSelected(null, 1)
        assertFalse("null注释右选'里'应partial commit, buffer=${ctrl.bufferString}", result)
        assertEquals("gua", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(listOf(T9PinyinMap.SyllableOption("gua", 3)), ctrl.selectionHistory)
    }

    @Test
    fun `scenario 3_1 - after partial commit, selecting remaining char full commits`() {
        val ctrl = createController()

        // 1-3: 输入54482, 左选li, 左选gua
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))

        // 4: 右选"里"(partial commit)
        val result1 = ctrl.onRightCandidateSelected("li", 1)
        assertFalse(result1)
        assertEquals("gua", ctrl.bufferString)

        // 5: 右选"瓜"(full commit)
        val result2 = ctrl.onRightCandidateSelected("gua", 1)
        assertTrue("右选'瓜'应full commit, buffer=${ctrl.bufferString}", result2)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `scenario 3_1 - backspace after partial commit takes 8 steps`() {
        val ctrl = createController()

        // 1-4: 输入54482, 左选li, 左选gua, 右选"里"(partial commit)
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))
        ctrl.onRightCandidateSelected("li", 1)
        assertEquals("gua", ctrl.bufferString)

        // 回退顺序：【"里"commit, gua选中, 2, 8, 4, li选中, 4, 5】= 8次

        // 1. 撤销"里"commit (RightCommit undo)
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("ligua", ctrl.bufferString)

        // 2. 撤销gua选中 (LeftChoice undo)
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("li'482", ctrl.bufferString)

        // 3. 删除2
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'48", ctrl.bufferString)

        // 4. 删除8
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'4", ctrl.bufferString)

        // 5. 删除4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'", ctrl.bufferString)

        // 6. 撤销li选中 (LeftChoice undo)
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("54", ctrl.bufferString)

        // 7. 删除4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("5", ctrl.bufferString)

        // 8. 删除5
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════
    // Bug：onRightCandidateSelected 不应在服务层 selectCandidate+commit 之前
    // 调用 sendToRime()，否则会破坏 RIME composition 导致上屏空白
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `onRightCandidateSelected full commit must not send to RIME - preserving composition for service selectCandidate`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 54482 → 左选li → 左选gua（预编辑：ligua）
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))

        // 清除之前的 rime 调用记录，只观察 onRightCandidateSelected 期间的调用
        rimeCalls.clear()

        val result = ctrl.onRightCandidateSelected("li gua", 2)
        assertTrue("应完整消费", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)

        // 关键断言：onRightCandidateSelected 期间不应向 RIME 发送任何内容
        // 服务层需要 RIME composition 保持完整，以便调用 selectCandidate + commit
        assertTrue(
            "onRightCandidateSelected 期间不应调用 replaceFullPinyin（会破坏 RIME composition），实际调用: $rimeCalls",
            rimeCalls.isEmpty()
        )
    }

    @Test
    fun `onRightCandidateSelected partial commit must not send to RIME - service calls forceSendToRime after commit`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 544826 → 左选li（预编辑：li'4826）
        for (d in listOf("5", "4", "4", "8", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))

        rimeCalls.clear()

        // 右选"李华"(lihua) — 消费5位数字，剩余1位(6)
        val result = ctrl.onRightCandidateSelected("li hua", 2)
        assertFalse("应部分消费，剩余数字6", result)

        // 关键断言：partial commit 期间也不应向 RIME 发送
        // 服务层会先 rimeEngine.selectCandidate+commit，然后调用 forceSendToRime
        assertTrue(
            "onRightCandidateSelected(partial) 期间不应调用 replaceFullPinyin，实际调用: $rimeCalls",
            rimeCalls.isEmpty()
        )
    }

    @Test
    fun `onRightCandidateSelected digit-segment full commit must not send to RIME`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 54482（无左选，纯数字段模式）
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)

        rimeCalls.clear()

        // 右选"计划"（jihua，消费全部5位数字）
        val result = ctrl.onRightCandidateSelected("ji hua", 2)
        assertTrue("应完整消费", result)
        assertEquals("", ctrl.bufferString)

        assertTrue(
            "digit-segment full commit 期间不应调用 replaceFullPinyin，实际调用: $rimeCalls",
            rimeCalls.isEmpty()
        )
    }

    @Test
    fun `after partial commit forceSendToRime sends remaining preedit to RIME`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 544826 → 左选li（预编辑：li'4826）
        for (d in listOf("5", "4", "4", "8", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))

        rimeCalls.clear()

        // 右选"李华"(lihua) — partial commit，剩余数字6
        val result = ctrl.onRightCandidateSelected("li hua", 2)
        assertFalse("应部分消费", result)

        // onRightCandidateSelected 期间不应调用 replaceFullPinyin
        assertTrue("partial commit 期间不应调用 replaceFullPinyin，实际调用: $rimeCalls", rimeCalls.isEmpty())

        // 服务层调用 forceSendToRime：应发送剩余预编辑
        ctrl.forceSendToRime()

        // 验证：forceSendToRime 应发送剩余的预编辑（6）
        assertTrue(
            "forceSendToRime 应发送剩余预编辑到 RIME，实际调用: $rimeCalls",
            rimeCalls.any { it == "6" }
        )
    }

    @Test
    fun `after full commit controller reset clears state without clearing RIME`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 54482 → 左选li → 左选gua
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))

        rimeCalls.clear()

        // 右选"离卦" — full commit
        val result = ctrl.onRightCandidateSelected("li gua", 2)
        assertTrue("应完整消费", result)
        assertEquals("", ctrl.bufferString)

        // onRightCandidateSelected 期间不应调用 replaceFullPinyin
        assertTrue("full commit 期间不应调用 replaceFullPinyin，实际调用: $rimeCalls", rimeCalls.isEmpty())

        // 服务层 commit 后调用 controller.reset()
        ctrl.reset()

        // reset 不应向 RIME 发送任何内容（RIME 已被 commit 清除）
        // 只需验证控制器状态正确
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // Bug：右侧候选词选词后 backspace 撤销应正确恢复左侧候选区
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `backspace after full right commit returns NOT_CONSUMED when buffer is empty`() {
        val ctrl = createController()

        // 输入 54482 → 左选li → 左选gua
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gua", 3))

        // 右选"离卦" — full commit
        val result = ctrl.onRightCandidateSelected("li gua", 2)
        assertTrue("应完整消费", result)
        assertEquals("", ctrl.bufferString)

        // full commit 后控制器状态：buffer 空，但 undo 栈中可能有 RightCommit
        // backspace 应尝试撤销 RightCommit 或返回 NOT_CONSUMED
        val deleteResult = ctrl.onDeleted()
        // full commit 后 buffer 为空，unassigned 为空，
        // 但 RightCommit 可能在栈顶 → UNDO_COMMIT
        assertTrue(
            "full commit 后 backspace 应返回 UNDO_COMMIT 或 NOT_CONSUMED，实际: $deleteResult",
            deleteResult == T9InputController.DeleteResult.UNDO_COMMIT ||
                deleteResult == T9InputController.DeleteResult.NOT_CONSUMED
        )
    }
}
