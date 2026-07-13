package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * 右侧候选选词消费计算 单元测试 + 集成测试（bug4 场景）。
 *
 * 覆盖：
 * - computeConsumedDigitsFromPinyin / computeRightCommitConsumption 纯函数
 * - onRightCandidateSelected 在 SELECTION 态下的 full/partial commit 判定
 */
class T9RightCommitHandlerTest {

    // ── 消费计算 纯函数测试 ──

    @Test
    fun `computeConsumedDigitsFromPinyin returns letter count when candidatePinyin has letters`() {
        assertEquals(2, computeConsumedDigitsFromPinyin("54482", "ji"))
        assertEquals(5, computeConsumedDigitsFromPinyin("54482", "ji hua"))
        assertEquals(1, computeConsumedDigitsFromPinyin("54482", "j"))
    }

    @Test
    fun `computeConsumedDigitsFromPinyin falls back to first syllable when candidatePinyin is empty`() {
        val result = computeConsumedDigitsFromPinyin("54482", "")
        assertTrue("Should return first syllable digitLength", result == 1 || result == 2)
    }

    @Test
    fun `computeConsumedDigitsFromPinyin falls back to first syllable when candidatePinyin is null`() {
        val result = computeConsumedDigitsFromPinyin("54482", null)
        assertTrue("Should return first syllable digitLength", result == 1 || result == 2)
    }

    @Test
    fun `computeConsumedDigitsFromPinyin returns 0 for empty segment`() {
        assertEquals(0, computeConsumedDigitsFromPinyin("", null))
    }

    @Test
    fun `computeConsumedDigitsFromPinyin handles partial syllable when letter count exceeds segment length`() {
        // "zhe li" has 5 letters but segment "9435" only has 4 digits.
        // "zhe"(943) fully matches, "li"(54) partially matches remaining "5"(initial l only).
        assertEquals(4, computeConsumedDigitsFromPinyin("9435", "zhe li"))
    }

    @Test
    fun `computeConsumedDigitsFromPinyin partial syllable with remaining digits after match`() {
        // "zhe li"(94354) partially matches "94356": zhe=943, li=54 prefix "5" matches → 4 consumed
        assertEquals(4, computeConsumedDigitsFromPinyin("94356", "zhe li"))
    }

    @Test
    fun `computeConsumedDigitsFromPinyin full syllable match still works`() {
        // Normal case: "ji hua" letters=5, segment "54482" has 5 digits, all fully match
        assertEquals(5, computeConsumedDigitsFromPinyin("54482", "ji hua"))
    }

    // ── 简拼声母匹配集成测试：digitSegment 模式右选候选词应 full commit ──

    @Test
    fun `digitSegment mode - right candidate with partial syllable should full commit when all digits consumed`() {
        val ctrl = createController()
        // Input 9435 without any left selection
        for (d in listOf("9", "4", "3", "5")) ctrl.onDigitPressed(d)
        assertEquals("9435", ctrl.bufferString)
        // Right-select "这里" (comment "zhe li")
        // "zhe" consumes 943, "li" partially matches digit 5 (initial "l" only)
        // All 4 digits consumed → full commit
        val result = ctrl.onRightCandidateSelected("zhe li", 2)
        assertTrue("Should be full commit — all 4 digits consumed by zhe li", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    @Test
    fun `digitSegment mode - right candidate with partial syllable partial commit when digits remain`() {
        val ctrl = createController()
        // Input 94356 without any left selection
        for (d in listOf("9", "4", "3", "5", "6")) ctrl.onDigitPressed(d)
        assertEquals("94356", ctrl.bufferString)
        // Right-select "这里" (comment "zhe li")
        // "zhe" consumes 943, "li" partially matches digit 5 (initial "l" only)
        // 4 digits consumed, remaining "6" → partial commit
        val result = ctrl.onRightCandidateSelected("zhe li", 2)
        assertFalse("Should be partial commit — digit 6 remains after zhe li", result)
        assertEquals("6", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
    }

    // ── bug4 集成测试：SELECTION 态下纯字母 buffer 右选 ──

    @Test
    fun `bug4 - pure letter buffer in selection state should not full commit when comment covers whole buffer`() {
        val ctrl = createController()
        // 步骤1-4: 输入 23744 → 右选"策" → 左选"pi" → 左选"h"
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        assertEquals("744", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        assertEquals("pi'4", ctrl.bufferString)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("h", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // 步骤5: 右选"皮"（RIME comment="pi h"，字母数=3=buffer长度）
        // 修复前：effectiveLetterCount(3) >= inputBuffer.length(3) → full commit → 策皮上屏
        // 修复后：candidateTextLength=1（"皮"1字）< commentSyllableCount=2 → partial commit
        val result = ctrl.onRightCandidateSelected("pi h", 1)
        assertFalse("Should be partial commit — selected 'h' must be preserved", result)
        assertEquals("h", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("h", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)
        assertPinyins(ctrl, "g", "h", "i")
    }

    @Test
    fun `bug4 - pure letter buffer with no-space comment also partial commit`() {
        // RIME comment 可能是 "pih"（无空格），字母数同样=3
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.bufferString)

        val result = ctrl.onRightCandidateSelected("pih", 1)
        assertFalse("Should be partial commit", result)
        assertEquals("h", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
    }

    @Test
    fun `bug4 - pure letter buffer with short comment still partial commit`() {
        // RIME comment 是精确的 "pi"（雾凇九键风格），字母数=2<3，走已有分支
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.bufferString)

        val result = ctrl.onRightCandidateSelected("pi")
        assertFalse("Should be partial commit", result)
        assertEquals("h", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
    }

    @Test
    fun `bug4 - multi-syllable pure letter buffer in selection state partial commit`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gu", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("b", 1))
        assertEquals("ligub", ctrl.bufferString)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)

        val result = ctrl.onRightCandidateSelected("li gu b", 2)
        assertFalse("Should be partial commit — selected 'b' must be preserved", result)
        assertEquals("b", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
    }

    @Test
    fun `bug4 - full commit still works when candidate covers entire buffer without selection`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        val result = ctrl.onRightCandidateSelected("ji hua")
        assertTrue("Full commit when candidate covers entire input", result)
        assertEquals("", ctrl.bufferString)
    }

    @Test
    fun `bug4 - partial commit without selection when candidate covers part of buffer`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        val result = ctrl.onRightCandidateSelected("ji")
        assertFalse("Partial commit when candidate covers part of input", result)
        assertEquals("482", ctrl.bufferString)
    }

    @Test
    fun `bug4 - null candidatePinyin in selection state should partial commit by consuming first syllable of non-selected part`() {
        // 用户报告的场景6：输入 23744 → 右选"策" → 左选 pi → 左选 h → 右选"皮"
        // 但在 Xime 的 T9 方案中"皮"的 comment 可能为空（无拼音注释），
        // candidatePinyin=null 时应防御性地仅消费非选中部分的首音节。
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("h", 1), ctrl.selectedOption)

        // candidatePinyin=null 模拟 RIME 未产生 comment
        val result = ctrl.onRightCandidateSelected(null)
        assertFalse("Should be partial commit when candidatePinyin=null in SELECTION", result)
        assertEquals("h", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("h", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)
        assertPinyins(ctrl, "g", "h", "i")
    }

    // ── 场景14：简拼混合输入，SELECTION 态下右选候选词不应全量提交 ──
    //
    // 操作流程（来自 .trae/docs/异常输入流程.md 场景14）：
    // 1. 输入 5143 → buffer="j'43", leftColumnLocked
    // 2. 左选 k → buffer="k'43", unlocked
    // 3. 左选 g → buffer="kg'3", SELECTION(g, "4")
    // 4. 左选 d → buffer="kgd", SELECTION(d, "3")
    // 5. 右选"控股 kong gu"(candidatePinyin="kong gu", textLength=2) →
    //    预期：partial commit, buffer="3", SELECTION(d, "3") 保留, 左侧候选区显示 [d,e,f]
    //    修复前：isFullCommit=true → buffer="", IDLE态, 丢失d筛选机会

    @Test
    fun `scenario 14 - right candidate in selection state preserves non-selected part digits`() {
        val ctrl = createController()

        // 步骤1: 输入 5143 → buffer="j'43"
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k → buffer="k'43"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 g → buffer="kg'3", SELECTION(g, "4")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)

        // 步骤4: 左选 d → buffer="kgd", SELECTION(d, "3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("kgd", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("d", 1), ctrl.selectedOption)
        assertEquals("3", ctrl.selectionCandidateDigits)

        // 步骤5: 右选"控股 kong gu"(comment="kong gu", textLength=2)
        // 修复前：candidateTextLength(2) >= commentSyllables.size(2) → isFullCommit=true → buffer=""
        // 修复后：SELECTION 态下非选中部分非空 → isFullCommit=false → partial commit
        val result = ctrl.onRightCandidateSelected("kong gu", 2)
        assertFalse("Should be partial commit — selected 'd'(digit 3) must be preserved", result)
        assertEquals("d", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("d", 1), ctrl.selectedOption)
        assertEquals("3", ctrl.selectionCandidateDigits)
        // 左侧候选区应显示 digit 3 对应的拼音 [e, d, f]（"e" 是精确拼音匹配，"d"/"f" 是字母回退）
        assertPinyins(ctrl, "e", "d", "f")
        // 问题二修复：右选后左侧候选区中已选项 d 的选中态 UI 标记必须保留
        assertTrue(
            "scenario 14: selected 'd' should remain highlighted after right candidate commit",
            ctrl.isSelectedOptionInCurrentCandidates()
        )
    }

    @Test
    fun `scenario 14 - right candidate pinyin shorter than non-selected part still partial commit`() {
        // 变体：候选词拼音仅覆盖部分非选中部分
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("kgd", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 右选"看"(comment="k", textLength=1) → 消费 "k"(5)
        // 与 scenario 16 设计一致：partial commit 后保留未消费部分为字母形式
        val result = ctrl.onRightCandidateSelected("k", 1)
        assertFalse("Should be partial commit", result)
        // "k"被消费移除，剩余 "g"(非选中) + "d"(选中项) = "gd"
        assertEquals("gd", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("d", 1), ctrl.selectedOption)
        assertEquals("3", ctrl.selectionCandidateDigits)
    }

    @Test
    fun `scenario 14 - right candidate full commit when buffer equals selected option only`() {
        // 验证：当 buffer 完全由选中部分构成时，isFullCommit 仍应正常工作
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gu", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("b", 1))
        ctrl.onRightCandidateSelected("li")
        ctrl.onRightCandidateSelected("gu")
        // 与 scenario 16 step 8 设计一致：partial commit 后保留选中项字母形式
        assertEquals("b", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)

        // 右选"并"(comment="bing", textLength=1) → buffer="b"=选中部分 → full commit
        val result = ctrl.onRightCandidateSelected("bing", 1)
        assertTrue("Should be full commit when buffer equals selected option only", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ── 场景15：全拼输入，混合左侧候选区选择简拼与右侧候选词混合筛选定词 ──
    //
    // 操作流程（来自 .trae/docs/异常输入流程.md 场景15）：
    // 1. 输入 54482 → buffer="j'4482", INPUT
    // 2. 左选 j → buffer="j'4482", SELECTION(j, "5")
    // 3. 左选 g → buffer="jg'482", SELECTION(g, "4")
    // 4. 左选 hu → buffer="jghu'2", SELECTION(hu, "48")
    // 5. 右选"价格 jia ge"(candidatePinyin="jia ge", textLength=2) →
    //    预期：partial commit, buffer="hu'2", SELECTION(hu, "48") 保留
    //    修复前：apostrophe 分支消费了整个 confirmedPinyin("jghu")+"2" → buffer="", IDLE态
    //    修复后：SELECTION 态下仅消费非选中部分"jg"→ 保留"hu'2"

    @Test
    fun `scenario 15 - right candidate in selection state with apostrophe buffer preserves selected option`() {
        val ctrl = createController()

        // 步骤1: 输入 54482
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")

        // 步骤2: 左选 j → buffer="j'4482", SELECTION(j, "5")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))
        assertEquals("j'4482", ctrl.bufferString)

        // 步骤3: 左选 g → buffer="jg'482", SELECTION(g, "4")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("jg'482", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)

        // 步骤4: 左选 hu → buffer="jghu'2", SELECTION(hu, "48")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("hu", 2))
        assertEquals("jghu'2", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("hu", 2), ctrl.selectedOption)
        assertEquals("48", ctrl.selectionCandidateDigits)

        // 步骤5: 右选"价格 jia ge"(candidatePinyin="jia ge", textLength=2)
        // 修复前：apostrophe 分支消费了整个 confirmedPinyin"jghu"(4)+"2"(1) → buffer="", IDLE
        // 修复后：SELECTION 态下仅消费非选中部分"jg"(2), 保留"hu'2"
        val result = ctrl.onRightCandidateSelected("jia ge", 2)
        assertFalse("Should be partial commit — selected 'hu'(digits 48) must be preserved", result)
        assertEquals("hu'2", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("hu", 2), ctrl.selectedOption)
        assertEquals("48", ctrl.selectionCandidateDigits)
        // 左侧候选区应显示 digit 2 对应的拼音 [a, b, c]
        assertPinyins(ctrl, "a", "b", "c")
    }

    @Test
    fun `scenario 15 - after selecting c then right candidate preserves c highlight`() {
        val ctrl = createController()

        // 输入 54482
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")

        // 左选 j → g → hu → c（对应场景15操作步骤6）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))  // j'4482
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))  // jg'482
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("hu", 2)) // jghu'2
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("c", 1))  // jghuc, SELECTION(c,"2")
        assertEquals("jghuc", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("c", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)

        // 右选"价格 jia ge"
        val result = ctrl.onRightCandidateSelected("jia ge", 2)
        assertFalse("Should be partial commit — selected 'c'(digit 2) must be preserved", result)
        // 右选后应保留 hu + c：hu 是已确认拼音，c 是选中项
        // 纯字母 buffer 不带分隔符，由 sendToRime 为 RIME 重建 "hu'c"
        assertEquals("huc", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("c", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)
        // 左侧候选区应显示 digit 2 对应的拼音 [a, b, c]
        assertPinyins(ctrl, "a", "b", "c")
        // 问题二修复：右选后左侧候选区中已选项 c 的选中态 UI 标记必须保留
        assertTrue(
            "scenario 15: selected 'c' should remain highlighted after right candidate commit",
            ctrl.isSelectedOptionInCurrentCandidates()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4右选修复：验证右选覆盖全部选择+剩余时触发full commit
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 g → buffer="kg'3", SELECTION(g, "4")
    //   4. 右选"空格的"(comment="kong ge de", textLength=3)
    //      预期：full commit → buffer清空, IDLE态
    //      旧行为：SELECTION保护只消费"k"，buffer变"g'3"，需再次点击
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4 rc - right candidate full commit covers selected option and remaining digits`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 g → buffer="kg'3", SELECTION(g, "4")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // 步骤4: 右选"空格的"(comment="kong ge de", textLength=3)
        // 候选覆盖了 nonSelected("k") + selected("g") + remaining("3")
        val result = ctrl.onRightCandidateSelected("kong ge de", 3)
        assertTrue("Should be full commit — 'kong ge de' covers k+g+3", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ── 场景16：全拼输入，左侧候选区选择变为纯简拼，右侧候选词混合筛选定词 ──
    //
    // 操作流程（来自 .trae/docs/异常输入流程.md 场景16）：
    // 1. 输入 54482 → buffer="j'4482", INPUT
    // 2. 左选 j → buffer="j'4482", SELECTION(j, "5")
    // 3. 左选 g → buffer="jg'482", SELECTION(g, "4")
    // 4. 左选 g → buffer="jgg'82", SELECTION(g, "4")
    // 5. 左选 t → buffer="jggt'2", SELECTION(t, "8")
    // 6. 左选 b → buffer="jggtb", SELECTION(b, "2")
    // 7. 右选"价格 jia ge"(candidatePinyin="jia ge", textLength=2) →
    //    预期：partial commit, buffer="482"(g=4,t=8,b=2), SELECTION(b,"2")保留
    //    修复前：consumedCount=4(过估)→"all consumed"→buffer="2"(仅选中部分), 丢失"gt"(48)
    //    修复后：consumedCount=2(正确)→partial consumption→buffer="48"+"2"="482"

    @Test
    fun `scenario 16 - pure letter buffer with single-char selections and right candidate preserves remaining letters`() {
        val ctrl = createController()

        // 步骤1: 输入 54482
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")

        // 步骤2-6: 逐步左选 j, g, g, t, b
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))  // j'4482
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))  // jg'482
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))  // jgg'82
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("t", 1))  // jggt'2
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("b", 1))  // jggtb

        // 验证步骤6后的状态
        assertEquals("jggtb", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)

        // 步骤7: 右选"价格 jia ge"(candidatePinyin="jia ge", textLength=2)
        // 预期：partial commit — "jia ge"消费j+g(2个确认字母), 剩余g+t+b作为独立简拼字母保留
        val result = ctrl.onRightCandidateSelected("jia ge", 2)
        assertFalse("Should be partial commit — 'jia ge' consumes j+g, leaving g+t+b", result)
        assertEquals("gtb", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)
        // 左侧候选区应显示 digit 2 对应的拼音 [a, b, c]
        assertPinyins(ctrl, "a", "b", "c")
        // 问题二修复：右选后左侧候选区中已选项 b 的选中态 UI 标记必须保留
        assertTrue(
            "scenario 16: selected 'b' should remain highlighted after right candidate commit",
            ctrl.isSelectedOptionInCurrentCandidates()
        )
    }

    @Test
    fun `scenario 16 step 8 - second right candidate after gt b preserves b`() {
        val ctrl = createController()

        // 步骤1: 输入 54482
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")

        // 步骤2-6: 逐步左选 j, g, g, t, b
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("t", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("b", 1))
        assertEquals("jggtb", ctrl.bufferString)

        // 步骤7: 右选"价格 jia ge"
        val step7Result = ctrl.onRightCandidateSelected("jia ge", 2)
        assertFalse("Step 7 should be partial commit", step7Result)
        assertEquals("gtb", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)

        // 步骤8: 右选"共同 gong tong"(candidatePinyin="gong tong", textLength=2)
        // 预期：partial commit — "gong tong"消费"gt"(g=4,t=8→"48"), 保留"b"(2)
        val step8Result = ctrl.onRightCandidateSelected("gong tong", 2)
        assertFalse("Step 8 should be partial commit — 'gong tong' consumes 'gt', leaving 'b'", step8Result)
        assertEquals("b", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("b", 1), ctrl.selectedOption)
        assertEquals("2", ctrl.selectionCandidateDigits)
        assertPinyins(ctrl, "a", "b", "c")
        assertTrue(
            "scenario 16 step 8: selected 'b' should remain highlighted",
            ctrl.isSelectedOptionInCurrentCandidates()
        )
    }

    // ── 场景4.1：简拼和全拼混合输入后，通过候选词列表完成完整上屏 ──
    //
    // 操作流程（来自 .trae/docs/回退测试.md 场景4.1）：
    // 1. 输入序列：5143，预编辑文本：j'ge，左侧候选区：【j，k，l】(简拼5对应的拼音字母映射)
    // 2. 点击左侧候选区：k，预编辑文本更新为：k' he，左侧候选区更新为：【ge，he，g，h，i】（输入序列：43的有效拼音和字母组合）
    // 3. 继续点击左侧候选区中："ge"，预编辑文本更新为：k' ge，左侧候选区进入选择态，候选词列表显示为【看个，开个，空格，凯歌，，，，】；
    // 4. 此时：点击："空格"，预编辑文本："空格ge"，候选词列表更新：【隔，个，哥，，，】
    //    预期结果：系统判定输入序列5143已被完整消费，"空格"一词直接上屏，预编辑文本、候选词列表/内页、左侧候选区全部清空并进入空闲态
    //    实际结果（修复前）：预编辑文本"空格ge"，"ge"残留
    //
    // 根因：buffer="kge"（T9编码"543"）与候选项"kong ge"（T9编码"566443"）的数字编码不匹配，
    // 因为"k"是"kong"的声母，声母扩展导致T9编码变化。letter SELECTION分支的全量检查仅用数字编码
    // 比较，无法处理声母扩展，退化为partial commit。修复后在数字编码比较之外增加音节级匹配检查。

    @Test
    fun `scenario 4-1 - mixed shorthand and full pinyin input full commit via right candidate`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")

        // 步骤2: 左选 k → buffer="k'43", SELECTION(k, "5")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 ge → buffer="kge", SELECTION(ge, "43")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ge", 2))
        assertEquals("kge", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("ge", 2), ctrl.selectedOption)
        assertEquals("43", ctrl.selectionCandidateDigits)

        // 步骤4: 右选"空格 kong ge"(candidatePinyin="kong ge", textLength=2)
        // 修复前：数字编码"543"!="566443"→退化为partial commit→buffer="43"
        // 修复后：音节级匹配("ge"=="ge")→full commit→buffer=""
        val result = ctrl.onRightCandidateSelected("kong ge", 2)
        assertTrue("Should be full commit — 'kong ge' covers entire input 5143", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull("No option should be selected after full commit", ctrl.selectedOption)
        assertNull("No candidate digits after full commit", ctrl.selectionCandidateDigits)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.2：验证候选词声母回退匹配（Tier 2）
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 he → buffer="khe", SELECTION(he, "43")
    //   4. 右选"可恨"(comment="ke hen", textLength=2)
    //      预期：声母回退 → partial commit
    //      "ke"消费nonSelected"k"(digit5), "hen"声母"h"消费选中项"4"(digit from "43")
    //      buffer="h'3", SELECTION(h, "4")
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario42 - right commit ke hen triggers shengmu fallback to h with remaining 3`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 he → buffer="khe", SELECTION(he, "43")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("he", 2))
        assertEquals("khe", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("he", 2), ctrl.selectedOption)
        assertEquals("43", ctrl.selectionCandidateDigits)

        // 步骤4: 右选"可恨"(comment="ke hen", textLength=2)
        // "hen"不精确匹配"he"，但声母"h"匹配 → 声母回退
        val result = ctrl.onRightCandidateSelected("ke hen", 2)
        assertFalse("Should be partial commit — hen only matches initial h of he", result)
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull("声母回退后选中项应为null（进入INPUT态）", ctrl.selectedOption)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4变体：声母回退后左选→右选完整流程
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 ge → buffer="kge", SELECTION(ge, "43")
    //   4. 右选"课改"(comment="ke gai", textLength=2) → 声母回退 → buffer="3", INPUT
    //   5. 左选 f → buffer="f", SELECTION(f, "3")
    //   6. 右选"非"(comment="fei", textLength=1) → full commit → buffer="", IDLE
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4 variant - shengmu fallback then left select f then right commit fei full commit`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 ge → buffer="kge", SELECTION(ge, "43")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ge", 2))
        assertEquals("kge", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 步骤4: 右选"课改"(ke gai) → 声母回退 → buffer="3", INPUT
        val step4Result = ctrl.onRightCandidateSelected("ke gai", 2)
        assertFalse("ke gai should be shengmu fallback (partial)", step4Result)
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // 步骤5: 左选 f (digit 3)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("f", 1))
        assertEquals("f", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("f", 1), ctrl.selectedOption)
        assertEquals("3", ctrl.selectionCandidateDigits)

        // 步骤6: 右选"非"(fei) → full commit
        val step6Result = ctrl.onRightCandidateSelected("fei", 1)
        assertTrue("fei should be full commit on buffer f", step6Result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4全简拼无候选：验证空格/右选直接上屏预编辑文本
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 i（简拼）→ buffer="ki'3", SELECTION(i, "4")
    //   4. 调用 onRightCandidateSelected(null) → 模拟RIME 0候选时的空格/右选
    //      预期：全简拼连通 → 直接上屏预编辑文本（等同换行键）, buffer清空, IDLE
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4 all abbrev - right commit with null candidate triggers enter like commit`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k（简拼）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 i（简拼）→ buffer="ki'3", SELECTION(i, "4")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("i", 1))
        assertEquals("ki'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("i", 1), ctrl.selectedOption)

        // 步骤4: 模拟空格/右选（candidatePinyin=null），应触发 enter-like 提交
        val result = ctrl.onRightCandidateSelected(null)
        assertTrue("全简拼无候选时应直接上屏预编辑文本", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.4：简拼+全拼混合输入后右选"客观"→应partial commit保留剩余数字
    //
    // 操作流程：
    //   1. 输入 5 → 按分词键(1) → 4 → 3 → buffer="j'43"
    //   2. 左选 k → buffer="k'43", SELECTION(k, "5")
    //   3. 左选 g（简拼）→ buffer="kg'3", SELECTION(g, "4")
    //   4. 右选"客观"(comment="ke guan", textLength=2)
    //      预期：partial commit（非full commit），
    //      "ke"消费nonSelected"k"(digit 5)，"guan"声母"g"匹配选中项"g"(digit 4)
    //      剩余数字"3"应保留 → buffer="3", INPUT（不保持SELECTION）
    //
    // 注意与场景4.2的区别：
    //   场景4.2第三步左选"he"（全拼），"hen"声母"h"匹配"he"首字母
    //   场景4.4第三步左选"g"（简拼），"guan"声母"g"匹配"g"首字母
    //   两者都应触发声母级消费，但场景4.4走apostrophe路径（有unassigned"3"），
    //   场景4.2走letterBuffer路径（unassigned为空）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario 44 - right commit ke guan on shorthand g should partial commit with remaining 3`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k（简拼）
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 g（简拼）→ buffer="kg'3", SELECTION(g, "4")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)
        assertEquals("4", ctrl.selectionCandidateDigits)

        // 步骤4: 右选"客观"(comment="ke guan", textLength=2)
        // "ke"匹配nonSelected"k"，"guan"的声母"g"匹配选中项"g"(digit 4)
        // 应完全消费两个选择，剩余数字"3"
        val result = ctrl.onRightCandidateSelected("ke guan", 2)
        assertFalse("Should be partial commit — remaining '3' unchanged", result)
        assertEquals("剩余数字3应保留", "3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull("声母匹配消费后选中项应为null（进入INPUT态）", ctrl.selectedOption)
        assertNull("声母匹配消费后selectionCandidateDigits应为null", ctrl.selectionCandidateDigits)
        assertTrue("声母匹配消费后选择历史应清空", ctrl.selectionHistory.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.5：简拼+右选"客观"后左选d/f，再右选/空格应full commit
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario45 - after ke guan partial commit selecting d then duo full commits`() {
        val ctrl = createController()

        // 步骤1-3: 5143 → k → g
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 步骤4: 右选"客观"(ke guan) → partial commit, buffer="3", INPUT
        val step4Result = ctrl.onRightCandidateSelected("ke guan", 2)
        assertFalse("ke guan should be partial commit", step4Result)
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)

        // 步骤5: 左选 d
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("d", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("d", 1), ctrl.selectedOption)

        // 步骤6: 右选"多"(duo) → full commit
        val step6Result = ctrl.onRightCandidateSelected("duo", 1)
        assertTrue("duo should full commit on selected d", step6Result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
    }

    @Test
    fun `scenario45 - after ke guan partial commit selecting f then fei full commits`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        ctrl.onRightCandidateSelected("ke guan", 2)

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("f", 1))
        assertEquals("f", ctrl.bufferString)

        val result = ctrl.onRightCandidateSelected("fei", 1)
        assertTrue("fei should full commit on selected f", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4.5 backspace回退：左选k+g，右选"客观"(ke guan)后左选d，8步回退
    //
    // 回退顺序：undo d → undo 客观RC → delete 3 → undo g →
    //           delete 4 → undo k → undo separator → delete 5
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario45 - backspace after ke guan partial commit with d selection restores correctly`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 g → buffer="kg'3"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 步骤4: 右选"客观"(comment="ke guan", textLength=2) → 声母回退
        val result = ctrl.onRightCandidateSelected("ke guan", 2)
        assertFalse("ke guan should be partial commit", result)
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)

        // 步骤5: 左选 d
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("d", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // ── backspace 回退（8步）──

        // bs1: undo d → buffer="3", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs2: undo "客观" RightCommit → 恢复为 kg'3（remainingDigitCount=3，可安全撤销）
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.onDeleted())
        assertEquals("kg'3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("g", 1), ctrl.selectedOption)

        // bs3: delete digit 3 → buffer="kg'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("kg'", ctrl.bufferString)

        // bs4: undo g → buffer="k'4", SELECTION
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("k'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)

        // bs5: delete digit 4 → buffer="k'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("k'", ctrl.bufferString)

        // bs6: undo k → buffer="5", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("5", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs7: undo separator → buffer="5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("5", ctrl.bufferString)

        // bs8: delete digit 5 → buffer=""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)

        // 无更多操作
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.onDeleted())
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景4变体：左选k+he，右选"跨行"(kua hang)后左选d，backspace回退
    //
    // 回退顺序（8步）：undo d → undo 跨行RC → undo he → delete 3 →
    //           delete 4 → undo k → undo separator → delete 5
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario4variant - backspace after kua hang partial commit with d selection restores correctly`() {
        val ctrl = createController()

        // 步骤1: 输入 5143
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 he → buffer="khe"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("he", 2))
        assertEquals("khe", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 步骤4: 右选"跨行"(comment="kua hang", textLength=2) → 声母回退
        val result = ctrl.onRightCandidateSelected("kua hang", 2)
        assertFalse("kua hang should be partial commit", result)
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)

        // 步骤5: 左选 d
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("d", 1))
        assertEquals("d", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // ── backspace 回退（8步）──

        // bs1: undo d → buffer="3", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs2: undo "跨行" RightCommit → 恢复为 khe（remainingDigitCount=1，可安全撤销）
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.onDeleted())
        assertEquals("khe", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("he", 2), ctrl.selectedOption)
        assertEquals("43", ctrl.selectionCandidateDigits)

        // bs3: undo he → buffer="k'43"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("k'43", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("k", 1), ctrl.selectedOption)

        // bs4: delete digit 3 → buffer="k'4"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("k'4", ctrl.bufferString)

        // bs5: delete digit 4 → buffer="k'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("k'", ctrl.bufferString)

        // bs6: undo k → buffer="5", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("5", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs7: undo separator → buffer="5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("5", ctrl.bufferString)

        // bs8: delete digit 5 → buffer=""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)

        // 无更多操作
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.onDeleted())
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景6 backspace回退：输入23744 → 右选"策" → 左选pi → 左选h → 8步回退
    //
    // digitSegment模式RC后，回退需先删除所有unassigned数字才能撤销RC。
    // 修复前bug：(1)9步而非8步 (2)bs4提前触发RC撤销导致跳到ceshi
    //
    // 回退顺序：undo h → delete 4 → undo pi → delete 4 → delete 7 →
    //           undo 策RC → delete 3 → delete 2
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario6 - backspace after ce partial commit with pi and h selection restores correctly`() {
        val ctrl = createController()

        // 步骤1: 输入 23744
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        assertEquals("23744", ctrl.bufferString)

        // 步骤2: 右选"策"(comment="ce") → digitSegment模式partial commit
        val result = ctrl.onRightCandidateSelected("ce")
        assertFalse("ce should be partial commit", result)
        assertEquals("744", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // 步骤3: 左选 pi(2) → 从unassigned "744"中选择
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        assertEquals("pi'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("pi", 2), ctrl.selectedOption)

        // 步骤4: 左选 h(1) → 从unassigned "4"中选择
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("h", 1), ctrl.selectedOption)

        // ── backspace 回退（8步）──

        // bs1: undo h → buffer="pi'4", SELECTION(pi)
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("pi'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("pi", 2), ctrl.selectedOption)

        // bs2: delete 4 → buffer="pi", SELECTION(pi)
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("pi", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("pi", 2), ctrl.selectedOption)

        // bs3: undo pi → buffer="74", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("74", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs4: delete 4（倒数第二个4）→ buffer="7", INPUT
        // 修复前：提前触发RC撤销，buffer跳到"ceshi"或"23744"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("7", ctrl.bufferString)

        // bs5: delete 7 → zombie状态：digitSequence="23", consumedCount=2
        // 僵尸RC处理：进入SELECTION态，高亮已提交拼音ce，左侧候选区显示[ce,a,b,c...]
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("ce", 2), ctrl.selectedOption)
        assertTrue("bs5 left candidates should contain ce", ctrl.firstOptions.any { it.pinyin == "ce" })

        // bs6: undo 策RC → buffer="23", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.onDeleted())
        assertEquals("23", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs7: delete 3 → buffer="2"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("2", ctrl.bufferString)

        // bs8: delete 2 → buffer=""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)

        // 无更多操作
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.onDeleted())
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景8 backspace回退：多RC叠加的僵尸状态
    //
    // 输入546946423744 → 右选"进行"(7位) → 右选"策"(2位) → 左选pi → 左选h
    // 回退时bs5删除最后一个未分配数字后，应进入SELECTION态显示栈顶RC("策")的候选[ce,...]
    // 而非全部consumed数字的候选[jin,lin,...]
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario8 - zombie state with stacked RCs shows top RC candidates`() {
        val ctrl = createController()

        // 步骤1: 输入 546946423744
        for (d in listOf("5", "4", "6", "9", "4", "6", "4", "2", "3", "7", "4", "4"))
            ctrl.onDigitPressed(d)
        assertEquals("546946423744", ctrl.bufferString)

        // 步骤2: 右选"进行"(comment="jin xing") → digitSegment模式partial commit, 消费7位
        val result1 = ctrl.onRightCandidateSelected("jin xing", 2)
        assertFalse("进行 should be partial commit", result1)
        assertEquals("23744", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // 步骤3: 右选"策"(comment="ce") → digitSegment模式partial commit, 消费2位
        val result2 = ctrl.onRightCandidateSelected("ce", 1)
        assertFalse("策 should be partial commit", result2)
        assertEquals("744", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // 步骤4: 左选 pi(2) → 从unassigned "744"中选择
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        assertEquals("pi'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // 步骤5: 左选 h(1) → 从unassigned "4"中选择
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("h", 1), ctrl.selectedOption)

        // ── backspace 回退 ──

        // bs1: undo h
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("pi'4", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)

        // bs2: delete 4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("pi", ctrl.bufferString)

        // bs3: undo pi
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.onDeleted())
        assertEquals("74", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs4: delete 4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("7", ctrl.bufferString)

        // bs5: delete 7 → 僵尸RC状态（多RC叠加）
        // 应进入SELECTION态显示栈顶RC("策")消费的"23"的候选[ce,a,b,c]
        // 而非全部consumed数字段"546946423"的候选[jin,lin,...]
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("ce", 2), ctrl.selectedOption)
        assertTrue("bs5 should show ce from top RC", ctrl.firstOptions.any { it.pinyin == "ce" })
        assertFalse("bs5 should NOT show jin from earlier RC", ctrl.firstOptions.any { it.pinyin == "jin" })

        // bs6: undo 策RC → buffer="23", INPUT
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.onDeleted())
        assertEquals("23", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // bs7: delete 3 → buffer="2"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("2", ctrl.bufferString)

        // bs8: delete 2 → 僵尸RC状态（"进行"RC，7位consumed）
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted())
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        // 栈顶RC现在是"进行"，其消费的数字段前缀应以"546"开头
        assertTrue("bs8 should show jin from 进行 RC", ctrl.firstOptions.any { it.pinyin == "jin" })

        // bs9: undo 进行RC → buffer恢复
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.onDeleted())
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // 继续删除剩余数字
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 6
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 9
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 6
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 4
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.onDeleted()) // 5
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ═══════════════════════════════════════════════════════════════
    // 全拼左选后右选候选词：apostrophe 路径全拼匹配消费选中项
    //
    // 操作流程：
    //   1. 输入 986742698726 → 左选 yun(3) → 左选 shan(4)
    //      buffer="yunshan'98726", SELECTION(shan, "7426")
    //   2. 右选"云山"(comment="yun shan", textLength=2)
    //      预期：partial commit — yun+shan 全部消费，保留未分配数字 98726
    //      修复前：只消费 yun(986)，shan 保留 → buffer="shan'98726"
    //      修复后：yun+shan 全部消费 → buffer="98726", INPUT 态
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `full pinyin left select then right select - apostrophe mode consumes selected option`() {
        val ctrl = createController()

        // 步骤1: 输入 986742698726
        for (d in listOf("9", "8", "6", "7", "4", "2", "6", "9", "8", "7", "2", "6"))
            ctrl.onDigitPressed(d)

        // 步骤2: 左选 yun(3)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("yun", 3))
        assertEquals("yun'742698726", ctrl.bufferString)

        // 步骤3: 左选 shan(4)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("shan", 4))
        assertEquals("yunshan'98726", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("shan", 4), ctrl.selectedOption)
        assertEquals("7426", ctrl.selectionCandidateDigits)

        // 步骤4: 右选"云山"(comment="yun shan", textLength=2)
        // 修复前：只消费 yun → buffer="shan'98726"，SELECTION 态
        // 修复后：yun+shan 全部消费 → buffer="98726"，INPUT 态
        val result = ctrl.onRightCandidateSelected("yun shan", 2)
        assertFalse("Should be partial commit — unassigned digits 98726 remain", result)
        assertEquals("98726", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull("Selected option should be null after consuming all selections", ctrl.selectedOption)
        assertTrue("Selection history should be cleared", ctrl.selectionHistory.isEmpty())
    }

    @Test
    fun `full pinyin left select then right select - with single selection also works`() {
        // 变体：仅左选一个全拼，右选候选词覆盖该选中项
        val ctrl = createController()
        for (d in listOf("9", "8", "6", "7", "4", "2", "6"))
            ctrl.onDigitPressed(d)

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("yun", 3))
        assertEquals("yun'7426", ctrl.bufferString)

        // 右选"云"(comment="yun", textLength=1) — digitSegment 模式，无 apostrophe
        // 这是 digitSegment 模式，不走 apostrophe 路径，已有逻辑处理
        val result = ctrl.onRightCandidateSelected("yun", 1)
        assertFalse("Should be partial commit — unassigned digits 7426 remain", result)
        assertEquals("7426", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
    }

    @Test
    fun `full pinyin left select then right select - shengmu match still works after refactor`() {
        // 回归验证：声母匹配（digitLength==1）仍然工作
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.bufferString)

        val result = ctrl.onRightCandidateSelected("ke guan", 2)
        assertFalse("ke guan should be partial commit (shengmu match)", result)
        assertEquals("3", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景17：全拼输入，左选拼音→右选候选词(partial)→左选拼音→右选候选词
    //       第二次右选应 full commit 但因 selectionHistory 残留误判为 partial
    //
    // 操作流程（来自 .trae/docs/T9测试/异常输入流程.md 场景17，行81-94）：
    //   1. 输入 826 + 分词键(1) + 8426 → buffer="tan'8426"
    //      （T9PinyinMap 中 826=tao/tan, 8426=tian/tiao）
    //   2. 左选 tao → 替换 tan → buffer="tao'8426"
    //   3. 右选"饕"(comment="tao", textLength=1) → partial commit, buffer="8426"
    //   4. 左选 tian → buffer="tian", SELECTION(tian, "8426")
    //   5. 右选"天"(comment="tian", textLength=1) → 应 full commit, buffer="", IDLE
    //
    // 修复前根因：
    //   步骤3 的 apostrophe else 分支调用 withRemainingDigits 创建 selections=emptyList()
    //   但 enterInput() 不清理 selectionHistory，残留 [tao]；
    //   步骤4 左选 tian 后 selectionHistory=[tao,tian]；
    //   步骤5 isFullCommitWithoutBoundaries 检查 joinToString="taotian"≠"tian" → 判定失败
    //   → 误为 partial commit，预编辑文本变成"饕天tian"而非上屏"饕天"。
    //
    // 修复后：
    //   步骤3 清理 selectionHistory，步骤5 正确判定为 full commit。
    //
    // 注意：多音节候选词如"提案"(comment="ti an")走 hasSyllableBoundaries 路径，
    //       不依赖 selectionHistory，因此不受此 bug 影响（与场景描述一致）。
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `scenario 17 - partial commit then left select then right select should full commit`() {
        val ctrl = createController()

        // 步骤1: 输入 826 + 分词键(1) + 8426 → buffer="tan'8426"
        for (d in listOf("8", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onDigitPressed("1") // 分词键：自动确认首音节 tan（firstSyllableOptions("826") 首项）
        for (d in listOf("8", "4", "2", "6")) ctrl.onDigitPressed(d)
        assertEquals("tan'8426", ctrl.bufferString)

        // 步骤2: 左选 tao → 替换 tan → buffer="tao'8426"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tao", 3))
        assertEquals("tao'8426", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("tao", 3), ctrl.selectedOption)

        // 步骤3: 右选"饕"(comment="tao", textLength=1) → partial commit
        //   候选词"饕"的拼音"tao"恰好匹配已选拼音"tao"，消费选中项，保留未分配数字"8426"
        val result1 = ctrl.onRightCandidateSelected("tao", 1)
        assertFalse("Step 3: Should be partial commit — unassigned digits 8426 remain", result1)
        assertEquals("8426", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        // 关键不变式：partial commit 消费了全部 selections 后，selectionHistory 必须同步清理
        assertTrue(
            "Step 3: selectionHistory must be cleared after partial commit consumed all selections",
            ctrl.selectionHistory.isEmpty()
        )

        // 步骤4: 左选 tian → buffer="tian", SELECTION(tian, "8426")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tian", 4))
        assertEquals("tian", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("tian", 4), ctrl.selectedOption)
        assertEquals("8426", ctrl.selectionCandidateDigits)

        // 步骤5: 右选"天"(comment="tian", textLength=1) → 应 full commit
        //   修复前：selectionHistory=[tao,tian] → joinToString="taotian"≠"tian" → 误判 partial
        //   修复后：selectionHistory=[tian] → joinToString="tian"=="tian" → 正确判定 full commit
        val result2 = ctrl.onRightCandidateSelected("tian", 1)
        assertTrue("Step 5: Should be full commit — '天' consumes all remaining input", result2)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    @Test
    fun `scenario 17 - multi-syllable candidate ti an also full commits after fix`() {
        // 场景17 步骤6变体：步骤4后右选"提案"(comment="ti an", textLength=2)
        // 多音节候选词走 hasSyllableBoundaries 路径，不受 selectionHistory 残留影响，
        // 但修复后此路径同样应正确 full commit。
        val ctrl = createController()

        for (d in listOf("8", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onDigitPressed("1")
        for (d in listOf("8", "4", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tao", 3))
        assertEquals("tao'8426", ctrl.bufferString)

        // 步骤3: 右选"饕" → partial commit
        ctrl.onRightCandidateSelected("tao", 1)
        assertEquals("8426", ctrl.bufferString)

        // 步骤4: 左选 tian
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tian", 4))
        assertEquals("tian", ctrl.bufferString)

        // 步骤5变体: 右选"提案"(comment="ti an", textLength=2) → full commit
        val result = ctrl.onRightCandidateSelected("ti an", 2)
        assertTrue("Should be full commit — '提案' consumes all remaining input", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
    }

    // ── 场景18：右选候选词 pinyin 是选中项 pinyin 的真前缀，应切换选择并消费 ──
    //
    // 操作流程（来自 .trae/docs/T9测试/异常输入流程.md 场景18，行99-110）：
    // 1. 输入 826 8426；预编辑 "tan tiao"
    // 2. 左选 tao → "tao'8426"
    // 3. 右选"饕"(tao) → partial commit → "8426"
    // 4. 左选 tian → "tian"（SELECTION 态，tian 高亮）
    // 5. 右选"惕"(comment="ti") → 候选词 pinyin "ti" 是选中项 "tian" 的真前缀
    //
    // 期望：相当于用户从 tian 切换到 ti，消费 ti(数字84)，剩余 26(an) 转纯数字 buffer
    // 根因（修复前）：SELECTION 态分支调用 removeConsumedSelections("ti")，但 "ti" 不是
    //   selections=[tian] 中任何选择的完整 pinyin，无法移除 → buffer 保持 [tian] 不变，
    //   与 RIME 已消费"ti"的状态不一致 → 左侧候选区不刷新，tian 仍显示选中态

    @Test
    fun `scenario 18 - right candidate pinyin shorter than selected option switches selection and leaves remaining digits`() {
        val ctrl = createController()

        // 步骤1: 输入 826 + 分词键 + 8426
        for (d in listOf("8", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onDigitPressed("1")
        for (d in listOf("8", "4", "2", "6")) ctrl.onDigitPressed(d)
        assertEquals("tan'8426", ctrl.bufferString)

        // 步骤2: 左选 tao
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tao", 3))
        assertEquals("tao'8426", ctrl.bufferString)

        // 步骤3: 右选"饕"(tao) → partial commit，selectionHistory 被清空
        ctrl.onRightCandidateSelected("tao", 1)
        assertEquals("8426", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertTrue(ctrl.selectionHistory.isEmpty())

        // 步骤4: 左选 tian → SELECTION(tian, "8426")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tian", 4))
        assertEquals("tian", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("tian", 4), ctrl.selectedOption)

        // 步骤5: 右选"惕"(comment="ti", textLength=1)
        // 候选词 pinyin "ti" 是 prevSelectedOption.pinyin "tian" 的真前缀
        // 期望：切换选择 tian→ti，消费 ti(数字84)，剩余 26(an) 转纯数字 buffer
        val result = ctrl.onRightCandidateSelected("ti", 1)
        assertFalse("Step 5: Should be partial commit — 'an' digits remain", result)
        assertEquals("26", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)
        assertNull("Step 5: selectedOption must be null after switching to digit buffer", ctrl.selectedOption)
        assertTrue(
            "Step 5: selectionHistory must be cleared after switching to digit buffer",
            ctrl.selectionHistory.isEmpty()
        )
    }

    @Test
    fun `scenario 18 - after switching to digit buffer left candidates refresh to an options`() {
        val ctrl = createController()

        // 前置步骤同上
        for (d in listOf("8", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onDigitPressed("1")
        for (d in listOf("8", "4", "2", "6")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tao", 3))
        ctrl.onRightCandidateSelected("tao", 1)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("tian", 4))
        assertEquals("tian", ctrl.bufferString)

        // 步骤5: 右选"惕"(ti)
        ctrl.onRightCandidateSelected("ti", 1)
        assertEquals("26", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.INPUT, ctrl.leftPanelState)

        // 步骤6: 左侧候选区应刷新为数字 26 对应的拼音候选（an/ao/bo...）
        // 修复前：左侧候选区仍为旧的 [tian, tiao, ti, t, u, v]，tian 仍显示选中态
        // 与场景18描述的预期左侧候选区【an，ao，bo，a,b,c】一致
        assertPinyins(ctrl, "an", "ao", "bo", "a", "b", "c")
    }

    // ── 场景19：简拼对齐的全拼选中项被候选词多音节简拼消费，应 full commit ──
    //
    // 操作流程（来自 .trae/docs/T9测试/异常输入流程.md 场景19，行121-138）：
    // 1. 输入 5143（5 + 分词键 + 43）→ j'ge（j 由分词键确认，43 由 RIME 推断为 ge）
    // 2. 左选 k → k'43（替换 j 为 k）
    // 3. 左选 he → khe（SELECTION(he)，he 高亮）
    // 4. 右选"卡哈尔"(comment="ka ha er", textLength=3) → 候选 3 音节 ka/ha/er 的
    //    简拼首字母数字码 = 5/4/3 = "543" = buffer 数字码
    //
    // 期望：full commit，buffer 清空，进入 IDLE
    // 根因（修复前）：isAllSelectedConsumed 要求 commentSyllables.size == selectionHistory.size
    //   （3 != 2 失败），且候选最后音节 "er" 既不精确匹配选中项 "he"（"37"!="43"），
    //   也不满足简拼缩写匹配（he.digitLength=2，非简拼）→ 误判 partial commit，
    //   仅消费非选中部分 "k"，保留 "he"，预编辑文本残留 "卡哈尔he"。
    //   "er 儿" 是边界条件：he(digits 43) 被 ka/ha/er 三音节简拼(5/4/3)完整消费。

    @Test
    fun `scenario 19 - jianpin-aligned candidate ka ha er full commits consuming entire buffer`() {
        val ctrl = createController()

        // 步骤1: 输入 5143（5 + 分词键 + 43）
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.bufferString)

        // 步骤2: 左选 k → 替换分词键确认的 j
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.bufferString)

        // 步骤3: 左选 he → SELECTION(he)，buffer="khe"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("he", 2))
        assertEquals("khe", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.SELECTION, ctrl.leftPanelState)
        assertEquals(T9PinyinMap.SyllableOption("he", 2), ctrl.selectedOption)

        // 步骤4: 右选"卡哈尔"(comment="ka ha er", textLength=3)
        // ka→k(5) ha→h(4) er→e(3)，三音节简拼首字母数字码 "543" == buffer 数字码
        val result = ctrl.onRightCandidateSelected("ka ha er", 3)
        assertTrue("Should be full commit — ka/ha/er 简拼对齐 543 消费全部输入", result)
        assertEquals("", ctrl.bufferString)
        assertEquals(T9InputController.LeftPanelState.IDLE, ctrl.leftPanelState)
        assertNull(ctrl.selectedOption)
        assertTrue(ctrl.selectionHistory.isEmpty())
    }

    @Test
    fun `scenario 19 - isFullCommitByJianpinAlignment pure function detects digit-by-digit alignment`() {
        // 纯函数：候选词各音节简拼首字母数字码逐位等于 buffer 数字码
        assertTrue(isFullCommitByJianpinAlignment("khe", listOf("ka", "ha", "er")))
        // er 单独也能对齐 e(3)
        assertTrue(isFullCommitByJianpinAlignment("e", listOf("er")))
        // 简拼缩写候选（ke→k, bei→b, er→e）对齐 "kbe"→"523"
        assertTrue(isFullCommitByJianpinAlignment("kbe", listOf("ke", "bei", "er")))
        // 音节数 ≠ 数字位数 → 不触发（交由既有路径）
        assertFalse(isFullCommitByJianpinAlignment("khe", listOf("kao", "he")))
        assertFalse(isFullCommitByJianpinAlignment("khe", listOf("ke", "hen")))
        assertFalse(isFullCommitByJianpinAlignment("khe", listOf("kan")))
        // 某音节首字母数字码不匹配
        assertFalse(isFullCommitByJianpinAlignment("khe", listOf("ka", "ha", "san")))
        // 空入参
        assertFalse(isFullCommitByJianpinAlignment("khe", emptyList()))
        assertFalse(isFullCommitByJianpinAlignment("", listOf("ka")))
    }

    // ── 场景19 后续：候选词 index 错位修复（上屏错词问题）──
    //
    // 问题：filterCandidatesBySelectionHistory 将候选词分为 FULL/PREFIX 两组重排序，
    // 导致 UI 列表 index 与 RIME 原始候选词 index 不对应。
    // selectCandidateAsync 用 UI index 调用 rimeEngine.selectCandidate(index)，
    // RIME 选错词 → commit() 返回错误文本 → full commit 上屏错词。
    // 例：selectionHistory=[k, he] 时，RIME 原始列表 [考核, 恐吓, 可恨, 课后, 跨行,
    // 卡哈尔, 开盒儿, 看会儿, 看, 可]，过滤后 [考核, 恐吓, 开盒儿, 课后, 跨行,
    // 卡哈尔, 看会儿, 看, 可]（可恨被排除、开盒儿从 index=6 前移到 index=2）。
    // 用户右选"开盒儿"(UI index=2) → selectCandidate(2) → RIME 选"可恨"(已被排除) →
    // 甚至可能选到"恐吓" → 上屏"恐吓"而非"开盒儿"。
    //
    // 修复：resolveRimeCandidateIndex 通过候选词文本查找 RIME 原始候选词 index。

    @Test
    fun `resolveRimeCandidateIndex returns raw index when candidate text found in raw list`() {
        // RIME 原始候选词列表
        val rawCandidates = listOf("考核", "恐吓", "可恨", "课后", "跨行", "卡哈尔", "开盒儿", "看会儿", "看", "可")
        // 用户选"开盒儿"（UI index=2，因过滤重排序），但 RIME 原始 index=6
        val result = resolveRimeCandidateIndex(
            uiIndex = 2,
            selectedCandidate = "开盒儿",
            rawCandidates = rawCandidates,
        )
        assertEquals("应返回 RIME 原始 index=6", 6, result)
    }

    @Test
    fun `resolveRimeCandidateIndex returns first match when duplicate candidate texts exist`() {
        val rawCandidates = listOf("看", "可", "看", "可")
        // 重复文本"看"在 index=0 和 index=2，应返回第一个（index=0）
        val result = resolveRimeCandidateIndex(
            uiIndex = 1,
            selectedCandidate = "看",
            rawCandidates = rawCandidates,
        )
        assertEquals(0, result)
    }

    @Test
    fun `resolveRimeCandidateIndex falls back to uiIndex when candidate text not in raw list`() {
        // 候选词文本不在 RIME 原始列表中（如 UI 层补充的候选词），回退 uiIndex
        val rawCandidates = listOf("考核", "恐吓", "可恨")
        val result = resolveRimeCandidateIndex(
            uiIndex = 5,
            selectedCandidate = "自定义词",
            rawCandidates = rawCandidates,
        )
        assertEquals("找不到时回退 uiIndex", 5, result)
    }

    @Test
    fun `resolveRimeCandidateIndex falls back to uiIndex when selectedCandidate is null`() {
        val rawCandidates = listOf("考核", "恐吓")
        val result = resolveRimeCandidateIndex(
            uiIndex = 1,
            selectedCandidate = null,
            rawCandidates = rawCandidates,
        )
        assertEquals("selectedCandidate 为 null 时回退 uiIndex", 1, result)
    }

    @Test
    fun `resolveRimeCandidateIndex falls back to uiIndex when rawCandidates is empty`() {
        val result = resolveRimeCandidateIndex(
            uiIndex = 3,
            selectedCandidate = "开盒儿",
            rawCandidates = emptyList(),
        )
        assertEquals("rawCandidates 为空时回退 uiIndex", 3, result)
    }

    @Test
    fun `resolveRimeCandidateIndex scenario 19 - ka ha er full commit selects correct raw index`() {
        // 场景19 完整模拟：selectionHistory=[k, he]
        // RIME 原始列表（基于 preedit "k'he"）：
        //   [考核(kao he), 恐吓(kong he), 可恨(ke hen), 课后(ke hou), 跨行(kua hang),
        //    卡哈尔(ka ha er), 开盒儿(kai he er), 看会儿(kan hu er), 看(kan), 可(ke)]
        // filterCandidatesBySelectionHistory 后（FULL 在前，PREFIX 在后，NONE 排除）：
        //   FULL: 考核, 恐吓, 开盒儿（kai→k, he→he, er 无对应选择但所有 selection 已匹配）
        //   PREFIX: 课后, 跨行, 卡哈尔, 看会儿, 看, 可
        //   NONE(排除): 可恨
        //   过滤后: [考核, 恐吓, 开盒儿, 课后, 跨行, 卡哈尔, 看会儿, 看, 可]
        val rawCandidates = listOf("考核", "恐吓", "可恨", "课后", "跨行", "卡哈尔", "开盒儿", "看会儿", "看", "可")
        // 用户右选"卡哈尔"（过滤后 UI index=5），RIME 原始 index=5 → 一致（巧合）
        assertEquals(5, resolveRimeCandidateIndex(5, "卡哈尔", rawCandidates))
        // 用户右选"开盒儿"（过滤后 UI index=2），RIME 原始 index=6 → 修复错位
        assertEquals(6, resolveRimeCandidateIndex(2, "开盒儿", rawCandidates))
        // 用户右选"看会儿"（过滤后 UI index=6），RIME 原始 index=7 → 修复错位
        assertEquals(7, resolveRimeCandidateIndex(6, "看会儿", rawCandidates))
    }

    // ── 辅助方法 ──

    private fun createController(): T9InputController {
        return T9InputController(onReplaceFullPinyin = { /* no-op */ })
    }

    private fun assertPinyins(controller: T9InputController, vararg expected: String) {
        assertEquals(expected.toList(), controller.firstOptions.map { it.pinyin })
    }
}