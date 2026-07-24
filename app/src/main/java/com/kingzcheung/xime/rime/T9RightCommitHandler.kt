package com.kingzcheung.xime.rime

/**
 * 右侧候选词选词处理器。
 *
 * 从 [T9InputController] 中提取所有右侧选词相关的判定与提交逻辑，
 * 通过 [Ctx] 暴露控制器所需的最小可变状态接口，降低控制器的体积与复杂度。
 *
 * 设计文档 §13.1 扩展：原先 onRightCandidateSelected() 的三层消费算法及所有
 * 子方法（handleSelectionLetterBufferCommit / tryShengmuFallback 等）
 * 统一内聚于此。
 *
 * 步骤5 重构：Ctx.inputBuffer 类型从 String 改为 T9Buffer，
 * 消除 String 往返架构。handler 内部直接操作 T9Buffer 结构化模型。
 */
class T9RightCommitHandler {

    /**
     * 控制器可变状态上下文。
     *
     * 仅暴露右侧选词处理过程所需的读写字段与回调，
     * 避免将整个 [T9InputController] 的私有字段全部外泄。
     */
    class Ctx(
        var inputBuffer: T9Buffer,
        var leftColumnLocked: Boolean,
        var separatorConsumedDigits: String?,
        var lastChoiceConsumedDigits: String?,
        val stateMachine: T9StateMachine,
        val undoManager: T9UndoManager,
        val syncState: () -> Unit,
        val updateCandidates: (Boolean) -> Unit,
        val setRimeInput: (String?) -> Unit,
    )

    // ── 公共入口 ──

    /**
     * 右侧候选选词（partial commit），返回 true = 完整消费（full commit）。
     *
     * @param ctx 控制器可变状态上下文
     * @param candidatePinyin RIME 候选词注释（spelling_hints）
     * @param candidateTextLength 候选词文字长度（汉字数）
     */
    fun onRightCandidateSelected(
        ctx: Ctx,
        candidatePinyin: String?,
        candidateTextLength: Int = 0,
    ): Boolean {
        if (ctx.inputBuffer.isEmpty) return true

        // 全简拼无候选 → enterLike 提交
        if (candidatePinyin.isNullOrBlank() && ctx.stateMachine.selectionHistory.isNotEmpty() &&
            ctx.stateMachine.selectionHistory.all { it.digitLength == 1 }
        ) {
            ctx.undoManager.clear()
            clearAndEnterIdle(ctx)
            return true
        }

        val buf = ctx.inputBuffer
        val hasSelections = buf.selections.isNotEmpty()
        val hasUnassigned = buf.unassigned.isNotEmpty()

        // 计算消费（T9Buffer 版本）
        val (_, remainingDigits) = computeRightCommitConsumption(buf, candidatePinyin)

        val prevBuf = buf
        val prevSep = ctx.separatorConsumedDigits
        val prevLC = ctx.lastChoiceConsumedDigits
        val prevLocked = ctx.leftColumnLocked
        val prevOpt = ctx.stateMachine.selectedOption
        val prevDigits = ctx.stateMachine.selectionCandidateDigits
        val prevConf = ctx.stateMachine.confirmedPinyinBeforeSelection
        val prevHist = ctx.stateMachine.selectionHistory.toList()

        ctx.undoManager.push(T9Command.RightCommit(
            prevBuffer = prevBuf,
            prevSeparatorConsumedDigits = prevSep,
            prevLastChoiceConsumedDigits = prevLC,
            prevLeftColumnLocked = prevLocked,
            prevSelectedOption = prevOpt,
            prevSelectionCandidateDigits = prevDigits,
            prevConfirmedPinyin = prevConf,
            prevSelectionHistory = prevHist,
        ))

        val selOptTemp = prevOpt
        val selDigitsTemp = prevDigits
        val selConfTemp = prevConf
        ctx.separatorConsumedDigits = null
        ctx.lastChoiceConsumedDigits = null
        ctx.stateMachine.selectedOption = null
        ctx.stateMachine.selectionCandidateDigits = null
        ctx.stateMachine.confirmedPinyinBeforeSelection = ""

        // 分支判断（基于 T9Buffer 结构）：
        //   apostrophe 模式 → selections 非空 && unassigned 非空
        //   digitSegment 模式 → selections 为空 && unassigned 非空
        //   letterBuffer 模式 → selections 非空 && unassigned 为空
        val isFullCommit = when {
            hasSelections && hasUnassigned ->
                handleApostropheRightCommit(ctx, remainingDigits, candidatePinyin,
                    selOptTemp, selDigitsTemp, selConfTemp)
            hasUnassigned ->
                handleDigitSegmentRightCommit(ctx, candidatePinyin,
                    selOptTemp, selDigitsTemp, selConfTemp)
            else ->
                handleLetterBufferRightCommit(ctx, candidatePinyin, candidateTextLength,
                    selOptTemp, selDigitsTemp, selConfTemp, prevBuf)
        }
        // 记录 commit 后 buffer 的 digitSequence 长度，供撤销时安全检查
        ctx.undoManager.updateTopRightCommitRemaining(ctx.inputBuffer.digitSequence.length)
        // full commit 后 inputBuffer 已清空，undo 栈中的命令（含刚入栈的 RightCommit
        // 及之前的 DigitPressed/LeftChoice）已无意义。若不清空，用户退格会触发
        // popAndUndo 恢复已提交的状态，造成"提交后撤回"的异常行为。
        if (isFullCommit) {
            ctx.undoManager.clear()
        }
        return isFullCommit
    }

    /**
     * 当上层（XimeInputMethodService）需要携带额外的 candidateTextLength 信息时调用。
     * 用于服务层已知候选词字数但 RIME 不返回 comment 的场景（如空格提交全简拼）。
     */
    fun onRightCandidateSelected(
        ctx: Ctx,
        candidatePinyin: String?,
        candidateTextLength: Int,
        isSpaceCommit: Boolean, // unused for now, reserved
    ): Boolean = onRightCandidateSelected(ctx, candidatePinyin, candidateTextLength)

    // ── 辅助 ──

    private fun enterIdle(ctx: Ctx) {
        ctx.inputBuffer = T9Buffer.EMPTY
        ctx.leftColumnLocked = false
        ctx.stateMachine.enterIdle()
        ctx.syncState()
    }

    private fun clearAndEnterIdle(ctx: Ctx) {
        enterIdle(ctx)
        ctx.updateCandidates(true)
        // 设 null 而非空字符串：服务层需在 selectCandidate+commit 后
        // 通过 forceSendToRime/reset 重新同步，避免 lastRimeInput 与实际 RIME 状态不一致
        ctx.setRimeInput(null)
    }

    private fun restorePrevState(
        ctx: Ctx,
        prevSelectedOption: T9PinyinMap.SyllableOption?,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String = "",
    ): Boolean = commitState(ctx, ctx.inputBuffer, Triple(prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin))

    /**
     * 统一状态提交入口。替代分发的状态同步逻辑，消除状态同步不一致。
     *
     * 所有 handler 出口统一通过此方法进入最终状态，确保：
     * - buffer 与 stateMachine 状态一致
     * - consumedCount 与 selections 同步
     * - UI 刷新与 RIME 输入同步
     *
     * @param ctx 上下文
     * @param buffer 最终状态的 T9Buffer
     * @param newState (selectedOption, selectionCandidateDigits, confirmedPinyin)
     *         为 null 的 selectedOption 表示 INPUT 态，否则为 SELECTION 态
     * @return buffer 是否为空（full commit → true）
     */
    private fun commitState(
        ctx: Ctx,
        buffer: T9Buffer,
        newState: Triple<T9PinyinMap.SyllableOption?, String?, String>,
    ): Boolean {
        ctx.inputBuffer = buffer
        ctx.leftColumnLocked = false
        val (opt, digits, confirmed) = newState
        if (opt != null) {
            ctx.stateMachine.restoreFrom(
                T9StateMachine.State.SELECTION, opt, digits ?: "", confirmed,
                ctx.stateMachine.selectionHistory.toList(),
            )
        } else {
            ctx.stateMachine.enterInput()
        }
        ctx.syncState()
        ctx.updateCandidates(true)
        ctx.setRimeInput(null)
        return buffer.isEmpty
    }

    /**
     * 从 T9Buffer.selections 头部移除被消费的拼音选择。
     *
     * 仅移除 selections，不修改 consumedCount（保持 unassigned 不变）。
     * 同步调用 [T9StateMachine.removeConsumedHistoryEntries] 更新 selectionHistory。
     */
    private fun removeConsumedSelections(
        ctx: Ctx,
        buf: T9Buffer,
        consumedPinyin: String,
    ): T9Buffer {
        if (consumedPinyin.isEmpty()) return buf
        var remaining = consumedPinyin
        var cutIndex = 0
        for (i in buf.selections.indices) {
            if (remaining.isEmpty()) break
            val sel = buf.selections[i]
            if (sel.pinyin.length <= remaining.length && remaining.startsWith(sel.pinyin)) {
                remaining = remaining.drop(sel.pinyin.length)
                cutIndex = i + 1
            } else {
                break
            }
        }
        ctx.stateMachine.removeConsumedHistoryEntries(consumedPinyin)
        return buf.copy(selections = buf.selections.drop(cutIndex))
    }

    private fun handleApostropheRightCommit(
        ctx: Ctx,
        remainingDigits: String,
        candidatePinyin: String?,
        prevSelectedOption: T9PinyinMap.SyllableOption?,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
    ): Boolean {
        val buf = ctx.inputBuffer
        val confirmedPinyin = buf.selectedPinyin

        if (prevSelectedOption != null && confirmedPinyin.endsWith(prevSelectedOption.pinyin)
            && confirmedPinyin.length > prevSelectedOption.pinyin.length
        ) {
            val selectedPinyin = prevSelectedOption.pinyin
            val nonSelectedPinyin = confirmedPinyin.dropLast(selectedPinyin.length)
            val candidateLetterCount = candidatePinyin.candidateLetterCount()
            // 单音节候选词在 multi-selection 上下文中的 cap（对应 C++ CapToFirstSelectionDigitLength）：
            // "jin"(546) 匹配 "54482" 时，字母数=3 → 贪婪消费 nonSelectedPinyin "jg"=2 位，
            // 实际应只消费第一个非选中 selection "j"(1位)。
            val consumedFromNonSelected = if (buf.selections.size > 1) {
                val syllables = candidatePinyin.parseSyllables()
                if (syllables.size <= 1) {
                    minOf(buf.selections[0].pinyin.length, nonSelectedPinyin.length)
                } else {
                    // 多音节候选词在 apostrophe 模式下，音节数可能少于非选中
                    // selections 数。字母数模型在此场景会过度消费（每个字母对应
                    // 一个 selection），应使用音节数对应的 selection 长度之和。
                    // 例：54482 左选 j→g→g→t，"价格(jia ge)" 2音节 vs 3个非选中
                    // selection [j,g,g] → 消费前2个 "jg"=2，保留 "g"。
                    val nonSelectedSelections = buf.selections.dropLast(1)
                    if (syllables.size < nonSelectedSelections.size) {
                        nonSelectedSelections.take(syllables.size)
                            .sumOf { it.pinyin.length }
                            .coerceAtMost(nonSelectedPinyin.length)
                    } else {
                        minOf(candidateLetterCount, nonSelectedPinyin.length)
                    }
                }
            } else {
                minOf(candidateLetterCount, nonSelectedPinyin.length)
            }

            // apostrophe 模式下，候选词注释的音节数必须足以覆盖所有已确认选择
            // 以及未分配数字（至少一个额外音节）。若音节不足，即使字母数模型显示
            // remainingDigits 为空，也不能触发 full commit，否则会把未分配数字一并提交。
            // 典型场景：kg'3 + "客观(ke guan)" → ke/k, guan/g，剩余 3 未被覆盖。
            val commentSyllables = candidatePinyin.parseSyllables()
            val canCoverAll = canCoverAllBySyllableCount(commentSyllables, buf.selections.size, buf.unassigned.isNotEmpty())

            if (canCoverAll &&
                shouldFullCommitInSelection(consumedFromNonSelected, nonSelectedPinyin.length, remainingDigits, candidatePinyin, selectedPinyin)
            ) {
                clearAndEnterIdle(ctx)
                return true
            }
            // 当 canCoverAllBySyllableCount 为 true 且 remainingDigits 非空时，
            // 检查候选词多余音节是否能覆盖剩余未分配数字。
            // 条件：prevSelectedOption 的拼音在 commentSyllables 中（候选词包含选中项），
            //   且多余音节的数字码以剩余数字开头。
            // 例：54482 左选 j→g→hu，右选"价格湖北(jia ge hu bei)"，
            //   "hu" 在 commentSyllables 中，多余音节"bei"(234) 以"2"开头 → full commit。
            // 反例：k'43 右选"开户(kai hu)"，"k" 不在 commentSyllables 中 → 不触发。
            if (canCoverAll && consumedFromNonSelected >= nonSelectedPinyin.length &&
                remainingDigits.isNotEmpty() && commentSyllables.size > buf.selections.size &&
                commentSyllables.contains(prevSelectedOption.pinyin)
            ) {
                val extraSyllables = commentSyllables.drop(buf.selections.size)
                val extraCode = extraSyllables.joinToString("") { T9PinyinMap.pinyinToDigitCode(it) ?: "" }
                if (extraCode.isNotEmpty() && extraCode.startsWith(remainingDigits)) {
                    clearAndEnterIdle(ctx)
                    return true
                }
            }
            val consumedNonSelectedPinyin = nonSelectedPinyin.take(consumedFromNonSelected)
            // 移除被消费的前缀选择，保留 unassigned 不变
            ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedNonSelectedPinyin)
            ctx.leftColumnLocked = false

            // 候选词消费选中项：提取为独立方法 [tryConsumeSelectedBySyllableMatch]
            if (tryConsumeSelectedBySyllableMatch(
                    ctx, buf, remainingDigits, commentSyllables,
                    prevSelectedOption, prevSelectionCandidateDigits,
                )) {
                return false
            }
            return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
        }
        // 非 SELECTION 或 confirmedPinyin 不以 selectedPinyin 结尾：
        // 候选词消费了部分 unassigned 数字，新 buffer 为剩余数字。
        return handleNonSelectionApostropheFallback(
            ctx, buf, remainingDigits, candidatePinyin,
            prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin,
        )
    }

    // ── apostrophe 模式子方法 ──

    /**
     * 候选词消费选中项：当 nonSelected 已完全消费、候选词多余音节与当前选中项匹配时，
     * 消费选中项并进入 INPUT 态（仅保留剩余数字）。
     *
     * 两种匹配模式：
     *   声母匹配：选中项为简拼(digitLength==1)，候选词音节声母数字码与选中项一致
     *   全拼匹配：选中项为全拼(digitLength>1)，候选词音节数字码与选中项完全一致
     *
     * 例如 kg'3 右选"客观(ke guan)"：ke 消费 k，guan 声母 g 消费选中项 g。
     * 例如 yunshan'98726 右选"云山(yun shan)"：yun 消费 yun，shan 消费选中项 shan。
     *
     * @return true 表示已处理（选中项被消费，调用者应 return false）
     */
    private fun tryConsumeSelectedBySyllableMatch(
        ctx: Ctx,
        buf: T9Buffer,
        remainingDigits: String,
        commentSyllables: List<String>,
        prevSelectedOption: T9PinyinMap.SyllableOption,
        prevSelectionCandidateDigits: String?,
    ): Boolean {
        // 候选词消费选中项包含"unconsumedPinyin.isEmpty()"前置条件，
        // 已在调用处保证（nonSelected 已完全消费）。
        if (commentSyllables.isEmpty()) return false
        val nonSelectedSelectionCount = buf.selections.size - 1
        // 仅当候选词有多余音节（音节数 > 非选中 selections 数）时，
        // 音节才可被视为消费选中项。否则该音节已用于非选中部分。
        val hasExtraSyllableForSelected = commentSyllables.size > nonSelectedSelectionCount
        if (!hasExtraSyllableForSelected) return false

        val selCode = T9PinyinMap.pinyinToDigitCode(prevSelectedOption.pinyin)
        val selDigits = prevSelectionCandidateDigits ?: ""
        // 所有多余音节（非选中 selections 用完后剩余的）：
        // 任一音节匹配选中项即消费选中项；否则只消费 unassigned。
        val extraSyllables = commentSyllables.drop(nonSelectedSelectionCount)
        val matchedSyllable = extraSyllables.firstOrNull { syl ->
            val sylCode = T9PinyinMap.pinyinToDigitCode(syl) ?: return@firstOrNull false
            if (prevSelectedOption.digitLength == 1) {
                selCode != null && sylCode.startsWith(selCode) && selDigits.startsWith(selCode)
            } else {
                selCode != null && sylCode == selCode
            }
        }

        if (matchedSyllable != null) {
            ctx.inputBuffer = if (remainingDigits.isEmpty()) {
                T9Buffer.EMPTY
            } else {
                buf.withRemainingDigits(remainingDigits, buf)
            }
            ctx.stateMachine.clearSelectionHistory()
            ctx.stateMachine.enterInput()
            ctx.syncState()
            ctx.updateCandidates(true)
            ctx.setRimeInput(ctx.inputBuffer.toBufferString())
            return true
        }

        // 所有多余音节均不匹配选中项 → 仅消费 unassigned 部分
        val unassignedConsumedDelta = buf.unassigned.length - remainingDigits.length
        if (unassignedConsumedDelta > 0) {
            ctx.inputBuffer = ctx.inputBuffer.copy(
                consumedCount = buf.consumedCount + unassignedConsumedDelta,
            )
        }
        return false
    }

    /**
     * 非 SELECTION 态下的 apostrophe 模式降级处理：
     * 候选词消费了部分 unassigned 数字，新 buffer 为剩余数字。
     *
     * 两种子情形：
     *   1. remainingDigits 为空但音节覆盖不足：保留选中部分为字母形式，
     *      避免 full commit 把未分配数字一并提交。
     *   2. 正常情形：remainingDigits 为剩余数字段，进入 INPUT 态。
     */
    private fun handleNonSelectionApostropheFallback(
        ctx: Ctx,
        buf: T9Buffer,
        remainingDigits: String,
        candidatePinyin: String?,
        prevSelectedOption: T9PinyinMap.SyllableOption?,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
    ): Boolean {
        val commentSyllables = candidatePinyin.parseSyllables()
        val canCoverAll = canCoverAllBySyllableCount(commentSyllables, buf.selections.size, buf.unassigned.isNotEmpty())

        if (remainingDigits.isEmpty() && !canCoverAll) {
            // 音节覆盖不足但字母数模型显示 remaining 为空：保留选中部分为字母形式，
            // 避免直接 full commit 把未分配数字一并提交。
            ctx.inputBuffer = removeConsumedSelections(ctx, buf, buf.selectedPinyin)
            ctx.leftColumnLocked = false
            // 如果所有 selection 都被移除（只有一个 selection 且就是选中项），
            // state 应进入 INPUT 而非 SELECTION，否则后续消费会错误地
            // 受 SELECTION 态保护限制（lockedDigits）。
            if (ctx.inputBuffer.selections.isEmpty()) {
                ctx.stateMachine.clearSelectionHistory()
                ctx.stateMachine.enterInput()
                ctx.syncState()
                ctx.updateCandidates(true)
                ctx.setRimeInput(ctx.inputBuffer.toBufferString())
                return false
            }
            return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
        }

        ctx.inputBuffer = if (remainingDigits.isEmpty()) {
            T9Buffer.EMPTY
        } else {
            buf.withRemainingDigits(remainingDigits, buf)
        }
        ctx.leftColumnLocked = false
        if (ctx.inputBuffer.isEmpty) {
            ctx.stateMachine.enterIdle()
        } else {
            // withRemainingDigits 创建 selections=emptyList()，已消费全部选择。
            // 必须同步清理 selectionHistory，否则残留条目会导致后续左选→右选时
            // isFullCommitWithoutBoundaries 误判（场景17 根因）。
            ctx.stateMachine.clearSelectionHistory()
            ctx.stateMachine.enterInput()
        }
        ctx.syncState()
        ctx.updateCandidates(true)
        ctx.setRimeInput(ctx.inputBuffer.toBufferString())
        return ctx.inputBuffer.isEmpty
    }

    private fun handleDigitSegmentRightCommit(
        ctx: Ctx,
        candidatePinyin: String?,
        prevSelectedOption: T9PinyinMap.SyllableOption?,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
    ): Boolean {
        val buf = ctx.inputBuffer
        val segment = buf.unassigned
        var consumedCount = computeConsumedDigitsFromPinyin(segment, candidatePinyin, allowLongestPrefix = true)
        val lockedDigits = if (prevSelectedOption != null) {
            // prevSelectionCandidateDigits 为 null 时（异常状态）视为全部 segment 被锁定，
            // 走 maxConsumable <= 0 分支恢复状态，避免消费本应被锁定的数字
            prevSelectionCandidateDigits?.length ?: segment.length
        } else 0
        val maxConsumable = segment.length - lockedDigits
        if (maxConsumable <= 0) {
            if (consumedCount > 0) {
                clearAndEnterIdle(ctx)
                return true
            }
            ctx.leftColumnLocked = false
            ctx.stateMachine.restoreFrom(
                if (prevSelectedOption != null) T9StateMachine.State.SELECTION else T9StateMachine.State.INPUT,
                prevSelectedOption,
                prevSelectionCandidateDigits,
                history = ctx.stateMachine.selectionHistory.toList(),
            )
            ctx.syncState()
            ctx.undoManager.pop()
            return false
        }
        if (consumedCount > maxConsumable) consumedCount = maxConsumable
        val remaining = segment.drop(consumedCount)
        // 新 buffer：digitSequence 不变，selections 为空，consumedCount = 已消费数
        ctx.inputBuffer = if (remaining.isEmpty()) {
            T9Buffer.EMPTY
        } else {
            T9Buffer(
                digitSequence = buf.digitSequence,
                selections = emptyList(),
                consumedCount = buf.consumedCount + consumedCount,
                totalDigitsEntered = buf.totalDigitsEntered,
            )
        }
        ctx.leftColumnLocked = false
        if (ctx.inputBuffer.isEmpty) {
            ctx.stateMachine.enterIdle()
            ctx.syncState()
            ctx.updateCandidates(true)
            ctx.setRimeInput(ctx.inputBuffer.toBufferString())
            return true
        }
        return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
    }

    private fun handleLetterBufferRightCommit(
        ctx: Ctx,
        candidatePinyin: String?,
        candidateTextLength: Int,
        prevSelectedOption: T9PinyinMap.SyllableOption?,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
        prevBuf: T9Buffer,
    ): Boolean {
        val buf = ctx.inputBuffer
        val selectedPinyin = buf.selectedPinyin
        val effectiveLetterCount = candidatePinyin.candidateLetterCount()

        // 单音节候选词在 multi-selection 上下文中，防止消费跨越 selection 边界
        if (effectiveLetterCount > 0 && buf.selections.size > 1 && prevSelectedOption != null) {
            val syllables = candidatePinyin.parseSyllables()
            if (syllables.size <= 1) {
                // 无空格的全匹配注释（如 "ligua" == "li"+"gua"）→ 不 cap
                val candidateClean = candidatePinyin.candidateLettersOnly()
                val joinedSelections = buf.selections.joinToString("") { it.pinyin }
                if (candidateClean != joinedSelections) {
                    val consumedLen = buf.selections[0].pinyin.length
                    val consumedPinyin = selectedPinyin.take(consumedLen)
                    // 场景18守卫：候选词拼音是选中项拼音的真前缀时，
                    // 切换选择并保留剩余数字（如 "ti" 是 "tian" 的前缀）。
                    val isCandidateShorterPrefix = candidateClean.length < prevSelectedOption.pinyin.length &&
                        prevSelectedOption.pinyin.startsWith(candidateClean)
                    if (isCandidateShorterPrefix) {
                        val remainingDigits = T9PinyinMap.pinyinToDigitCode(
                            selectedPinyin.drop(consumedLen))
                        if (remainingDigits != null) {
                            ctx.inputBuffer = T9Buffer(
                                digitSequence = remainingDigits,
                                selections = emptyList(),
                                consumedCount = 0,
                                totalDigitsEntered = remainingDigits.length,
                            )
                            ctx.leftColumnLocked = false
                            ctx.stateMachine.clearSelectionHistory()
                            ctx.stateMachine.enterInput()
                            ctx.syncState()
                            ctx.updateCandidates(true)
                            ctx.setRimeInput(ctx.inputBuffer.toBufferString())
                            return false
                        }
                    }
                    ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedPinyin)
                    ctx.leftColumnLocked = false
                    return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
                }
            }
        }

        if (effectiveLetterCount > 0) {
            if (effectiveLetterCount < selectedPinyin.length) {
                if (prevSelectedOption != null) {
                    // 多音节候选词 + 音节数 < selections 数 + EndsWith → HSLBC（音节匹配路径）
                    val commentSyllables = candidatePinyin.parseSyllables()
                    val syllableCount = commentSyllables.size
                    val selectionCount = buf.selections.size
                    if (syllableCount > 1 && syllableCount < selectionCount &&
                        selectedPinyin.endsWith(prevSelectedOption.pinyin)) {
                        return handleSelectionLetterBufferCommit(
                            ctx, candidatePinyin, candidateTextLength, effectiveLetterCount,
                            prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin, prevBuf,
                        )
                    }
                    val consumedPinyin = selectedPinyin.take(effectiveLetterCount)
                    // 场景18：候选词 pinyin 是 prevSelectedOption.pinyin 的真前缀
                    // （如 candidatePinyin="ti", prevSelectedOption.pinyin="tian"）。
                    // removeConsumedSelections 只能移除完整匹配的选择条目，无法处理
                    // "消费选中项部分 pinyin" 的情况——此时 consumedPinyin 比选中项更短，
                    // 不匹配任何完整选择，removeConsumedSelections 什么都不会移除，
                    // 导致 buffer 保持原状、与 RIME 已消费的状态不一致。
                    // 正确语义：相当于用户从选中项切换到更短的拼音选择（同一层切换），
                    // 消费 consumedPinyin 对应数字，剩余转纯数字 buffer。
                    // 与下方 INPUT 态分支逻辑一致；buffer 从字母变为数字时必须清空
                    // selectionHistory（与 tryShengmuFallback / apostrophe 声母匹配分支
                    // 的 clearSelectionHistory() + enterInput() 模式一致）。
                    if (consumedPinyin.length < prevSelectedOption.pinyin.length &&
                        prevSelectedOption.pinyin.startsWith(consumedPinyin)) {
                        val remainingDigits = T9PinyinMap.pinyinToDigitCode(
                            selectedPinyin.drop(effectiveLetterCount))
                        if (remainingDigits != null) {
                            ctx.inputBuffer = T9Buffer(
                                digitSequence = remainingDigits,
                                selections = emptyList(),
                                consumedCount = 0,
                                totalDigitsEntered = remainingDigits.length,
                            )
                            ctx.leftColumnLocked = false
                            ctx.stateMachine.clearSelectionHistory()
                            ctx.stateMachine.enterInput()
                            ctx.syncState()
                            ctx.updateCandidates(true)
                            ctx.setRimeInput(ctx.inputBuffer.toBufferString())
                            return false
                        }
                    }
                    // SELECTION 态：保留未消费部分为字母形式（与 scenario 16 设计一致）
                    ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedPinyin)
                    ctx.leftColumnLocked = false
                    return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
                }
                // INPUT 态：候选词覆盖部分数字 buffer，转为剩余数字
                val remainingDigits = T9PinyinMap.pinyinToDigitCode(selectedPinyin.drop(effectiveLetterCount))
                if (remainingDigits != null) {
                    val consumedPinyin = selectedPinyin.take(effectiveLetterCount)
                    ctx.stateMachine.removeConsumedHistoryEntries(consumedPinyin)
                    ctx.inputBuffer = T9Buffer(
                        digitSequence = remainingDigits,
                        selections = emptyList(),
                        consumedCount = 0,
                        totalDigitsEntered = remainingDigits.length,
                    )
                    ctx.leftColumnLocked = false
                    return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits)
                }
            }
            if (prevSelectedOption != null && selectedPinyin.endsWith(prevSelectedOption.pinyin)) {
                return handleSelectionLetterBufferCommit(
                    ctx, candidatePinyin, candidateTextLength, effectiveLetterCount,
                    prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin, prevBuf,
                )
            }
            clearAndEnterIdle(ctx)
            return true
        }
        return handleDefensiveNoCommentCommit(
            ctx, candidateTextLength,
            prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin, prevBuf,
        )
    }

    // ── SELECTION 态字母 buffer 处理 ──

    private fun handleSelectionLetterBufferCommit(
        ctx: Ctx,
        candidatePinyin: String?,
        candidateTextLength: Int,
        effectiveLetterCount: Int,
        prevSelectedOption: T9PinyinMap.SyllableOption,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
        prevBuf: T9Buffer,
    ): Boolean {
        val buf = ctx.inputBuffer
        val selectedPinyin = buf.selectedPinyin

        val commentSyllables = candidatePinyin.parseSyllables()
        val hasSyllableBoundaries = commentSyllables.size > 1

        val isFullCommitWithoutBoundaries = !hasSyllableBoundaries &&
            ctx.stateMachine.selectionHistory.isNotEmpty() &&
            ctx.stateMachine.selectionHistory.joinToString("") { it.pinyin } == selectedPinyin &&
            candidateTextLength > 0 &&
            candidateTextLength >= ctx.stateMachine.selectionHistory.size
        // 场景19（"er 儿"边界条件）：候选词各音节简拼首字母数字码逐位对齐整个 buffer
        // 数字码时，全拼选中项被多音节简拼拆分消费（如 he(43) 被 ha(4)+er(3) 消费），
        // 应判定 full commit。isAllSelectedConsumed 要求音节数==选择数会在此失效。
        val isJianpinAlignedFullCommit = hasSyllableBoundaries &&
            candidateTextLength > 0 &&
            candidateTextLength >= commentSyllables.size &&
            isFullCommitByJianpinAlignment(selectedPinyin, commentSyllables)
        // 简拼转全拼匹配：当 selection 为简拼(digitLength==1)时，候选词音节数字码
        // 前缀匹配即可；当 selection 为全拼(digitLength>1)时，需要精确匹配。
        // 例：54482 左选 j→g→hu→b，右选"价格湖北(jia ge hu bei)"，
        //   jia(542) 前缀匹配 j(5), ge(43) 前缀匹配 g(4),
        //   hu(48) 精确匹配 hu(48), bei(234) 前缀匹配 b(2) → full commit。
        val isPrefixMatchAllSelected = hasSyllableBoundaries && candidateTextLength > 0 &&
            candidateTextLength >= commentSyllables.size &&
            commentSyllables.size == ctx.stateMachine.selectionHistory.size &&
            commentSyllables.indices.all { i ->
                val sylCode = T9PinyinMap.pinyinToDigitCode(commentSyllables[i])
                val selCode = T9PinyinMap.pinyinToDigitCode(ctx.stateMachine.selectionHistory[i].pinyin)
                val sel = ctx.stateMachine.selectionHistory[i]
                sylCode != null && selCode != null &&
                    if (sel.digitLength == 1) sylCode.startsWith(selCode) else sylCode == selCode
            }
        val isFullCommit = (hasSyllableBoundaries && candidateTextLength > 0 &&
            candidateTextLength >= commentSyllables.size &&
            (selectedPinyin.dropLast(prevSelectedOption.pinyin.length).isEmpty() ||
                isAllSelectedConsumed(selectedPinyin, commentSyllables, ctx.stateMachine.selectionHistory) ||
                isPrefixMatchAllSelected ||
                isJianpinAlignedFullCommit)) ||
            isFullCommitWithoutBoundaries
        if (isFullCommit) {
            clearAndEnterIdle(ctx)
            return true
        }

        val nonSelectedPart = selectedPinyin.dropLast(prevSelectedOption.pinyin.length)
        if (nonSelectedPart.isEmpty()) {
            return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
        }

        // 有音节边界时，使用基于选择历史的消费（每个音节消费一个选择）
        if (hasSyllableBoundaries && candidateTextLength > 0) {
            val lastSyl = commentSyllables.lastOrNull()
            val lastSylInitial = lastSyl?.first()?.toString()
            val optInitial = prevSelectedOption.pinyin.first().toString()
            val lastSylInitialCode = lastSylInitial?.let { T9PinyinMap.pinyinToDigitCode(it) }
            val optInitialCode = T9PinyinMap.pinyinToDigitCode(optInitial)
            val wouldTriggerShengmu = lastSylInitialCode != null && optInitialCode != null &&
                lastSylInitialCode == optInitialCode
            if (!wouldTriggerShengmu) {
                val nonSelectedSyllables = if (commentSyllables.lastOrNull() == prevSelectedOption.pinyin)
                    commentSyllables.dropLast(1) else commentSyllables
                if (nonSelectedSyllables.isNotEmpty()) {
                    val consumedSelections = nonSelectedSyllables.size
                    val nonSelectedHistory = ctx.stateMachine.selectionHistory.dropLast(1)
                    if (consumedSelections >= nonSelectedHistory.size) {
                        // 消费全部非选中部分
                        ctx.inputBuffer = removeConsumedSelections(ctx, buf, nonSelectedPart)
                        ctx.leftColumnLocked = false
                        return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
                    }
                    val consumedPinyin = nonSelectedHistory.take(consumedSelections).joinToString("") { it.pinyin }
                    ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedPinyin)
                    val remainingPinyinStr = nonSelectedPart.drop(consumedPinyin.length)
                    ctx.leftColumnLocked = false
                    return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, remainingPinyinStr)
                }
            }
        }

        val nonSelectedDigits = T9PinyinMap.pinyinToDigitCode(nonSelectedPart) ?: run {
            clearAndEnterIdle(ctx)
            return true
        }

        val consumedCount = computeSelectionConsumedCount(
            hasSyllableBoundaries, candidateTextLength, commentSyllables,
            effectiveLetterCount, prevSelectedOption, nonSelectedDigits, candidatePinyin,
        )

        if (consumedCount >= nonSelectedDigits.length) {
            return handleConsumedAllNonSelected(
                ctx, hasSyllableBoundaries, candidateTextLength, commentSyllables,
                candidatePinyin, prevSelectedOption, prevSelectionCandidateDigits,
                nonSelectedPart, nonSelectedDigits, consumedCount, prevBuf,
            )
        }
        if (consumedCount > 0) {
            return handlePartialConsumedNonSelected(
                ctx, consumedCount, nonSelectedPart, nonSelectedDigits,
                prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin,
            )
        }

        clearAndEnterIdle(ctx)
        return true
    }

    private fun handleConsumedAllNonSelected(
        ctx: Ctx,
        hasSyllableBoundaries: Boolean,
        candidateTextLength: Int,
        commentSyllables: List<String>,
        candidatePinyin: String?,
        prevSelectedOption: T9PinyinMap.SyllableOption,
        prevSelectionCandidateDigits: String?,
        nonSelectedPart: String,
        nonSelectedDigits: String,
        consumedCount: Int,
        prevBuf: T9Buffer,
    ): Boolean {
        val buf = ctx.inputBuffer
        val selectedPinyin = buf.selectedPinyin
        if (hasSyllableBoundaries && candidateTextLength >= commentSyllables.size &&
            commentSyllables.size >= ctx.stateMachine.selectionHistory.size) {
            val candidateClean = candidatePinyin.candidateLettersOnly()
            if (candidateClean.isNotEmpty()) {
                val candidateDigitCode = T9PinyinMap.pinyinToDigitCode(candidateClean)
                val fullBufferDigitCode = T9PinyinMap.pinyinToDigitCode(selectedPinyin)
                if (candidateDigitCode != null && fullBufferDigitCode != null &&
                    candidateDigitCode == fullBufferDigitCode
                ) {
                    clearAndEnterIdle(ctx)
                    return true
                }
                val lastSyl = commentSyllables.lastOrNull()
                if (lastSyl != null) {
                    val lastSylCode = T9PinyinMap.pinyinToDigitCode(lastSyl)
                    val selCode = T9PinyinMap.pinyinToDigitCode(prevSelectedOption.pinyin)
                    val isExactFullMatch = lastSylCode != null && selCode != null && lastSylCode == selCode
                    val isAbbrevFullMatch = prevSelectedOption.digitLength == 1 &&
                        lastSylCode != null && selCode != null &&
                        (lastSylCode.startsWith(selCode) || selCode.startsWith(lastSylCode))
                    if (isExactFullMatch || isAbbrevFullMatch) {
                        clearAndEnterIdle(ctx)
                        return true
                    }
                }
            }
        }

        // 候选词所有音节已被非选中 selections 覆盖时，跳过 shengmu 回退。
        // 例："ji ge"(2音节) + [j,g,hua] → "jg" 匹配 j 和 g(2个) = 2音节全匹配
        //   → 不应额外消费 hua。反例："ke hen"(2音节) + [k,he] → "k" 仅匹配
        //   1个 < 2音节 → 需要 shengmu 回退消费 he 的声母。
        val matchedNonSelectedCount = if (hasSyllableBoundaries) {
            var remaining = nonSelectedPart
            buf.selections.count { sel ->
                if (remaining.isEmpty()) false
                else if (sel.pinyin.length <= remaining.length && remaining.startsWith(sel.pinyin)) {
                    remaining = remaining.drop(sel.pinyin.length)
                    true
                } else false
            }
        } else 0

        // 所有音节已被非选中 selections 覆盖 → 保留剩余选择，进入 SELECTION 态
        if (matchedNonSelectedCount > 0 && matchedNonSelectedCount >= commentSyllables.size) {
            ctx.inputBuffer = removeConsumedSelections(ctx, buf, nonSelectedPart)
            ctx.leftColumnLocked = false
            return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits)
        }

        if (tryShengmuFallback(ctx, hasSyllableBoundaries, commentSyllables, prevSelectedOption,
                prevSelectionCandidateDigits, nonSelectedPart, prevBuf)) {
            return false
        }

        // 消费全部非选中部分，只保留 prevSelectedOption
        ctx.inputBuffer = removeConsumedSelections(ctx, buf, nonSelectedPart)
        ctx.leftColumnLocked = false
        return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits)
    }

    private fun tryShengmuFallback(
        ctx: Ctx,
        hasSyllableBoundaries: Boolean,
        commentSyllables: List<String>,
        prevSelectedOption: T9PinyinMap.SyllableOption,
        prevSelectionCandidateDigits: String?,
        nonSelectedPart: String,
        prevBuf: T9Buffer,
    ): Boolean {
        if (!hasSyllableBoundaries || commentSyllables.isEmpty()) return false
        val buf = ctx.inputBuffer
        val lastSyl = commentSyllables.last()
        val selDigits = prevSelectionCandidateDigits ?: return false
        val lastInitial = lastSyl.first().toString()
        val optInitial = prevSelectedOption.pinyin.first().toString()
        val initialCode = T9PinyinMap.pinyinToDigitCode(lastInitial) ?: return false
        val optInitialCode = T9PinyinMap.pinyinToDigitCode(optInitial) ?: return false
        if (initialCode != optInitialCode || !selDigits.startsWith(initialCode) || selDigits.length <= initialCode.length) {
            return false
        }

        // 候选最后音节可能完整消费选中项前缀（如 "gu"(48) 完全匹配 "gua"(482) 前缀），
        // 不仅限于首字母。优先使用完整数字码长度，回退到声母长度。
        val lastSylFullCode = T9PinyinMap.pinyinToDigitCode(lastSyl)
        val consumedFromSelected = if (lastSylFullCode != null && selDigits.startsWith(lastSylFullCode)) {
            lastSylFullCode.length
        } else {
            initialCode.length
        }
        val remainingFromSelected = selDigits.drop(consumedFromSelected)
        // 消费全部非选中部分 + 移除最后一个选择（prevSelectedOption）
        ctx.inputBuffer = removeConsumedSelections(ctx, buf, nonSelectedPart)
        // removeConsumedSelections 只移除非选中部分的选择，需要额外移除 prevSelectedOption
        if (ctx.inputBuffer.selections.isNotEmpty()) {
            ctx.inputBuffer = ctx.inputBuffer.copy(
                selections = ctx.inputBuffer.selections.dropLast(1),
            )
        }
        // 新 buffer 是剩余数字
        ctx.inputBuffer = T9Buffer(
            digitSequence = remainingFromSelected,
            selections = emptyList(),
            consumedCount = 0,
            totalDigitsEntered = remainingFromSelected.length,
        )
        ctx.leftColumnLocked = false
        // 声母回退完全重置输入上下文（buffer 从字母变为数字，状态从 SELECTION 变为 INPUT），
        // 旧的 selectionHistory 已无意义，必须清除，否则后续左选→右选时
        // selectionHistory 中残留旧条目导致 isFullCommitWithoutBoundaries 误判失败
        ctx.stateMachine.clearSelectionHistory()
        ctx.stateMachine.enterInput()
        ctx.syncState()
        ctx.updateCandidates(true)
        ctx.setRimeInput(ctx.inputBuffer.toBufferString())
        return true
    }

    private fun handlePartialConsumedNonSelected(
        ctx: Ctx,
        consumedCount: Int,
        nonSelectedPart: String,
        nonSelectedDigits: String,
        prevSelectedOption: T9PinyinMap.SyllableOption,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
    ): Boolean {
        val buf = ctx.inputBuffer
        val remainingPinyinStr = if (prevConfirmedPinyin.isNotEmpty() && nonSelectedPart == prevConfirmedPinyin) {
            val totalDigits = T9PinyinMap.pinyinToDigitCode(prevConfirmedPinyin)?.length
                ?: prevConfirmedPinyin.length
            val remainingDigits = totalDigits - consumedCount
            if (remainingDigits <= 0) {
                ""
            } else {
                val sb = StringBuilder()
                var digitsToSkip = remainingDigits
                for (ch in prevConfirmedPinyin.reversed()) {
                    if (digitsToSkip <= 0) break
                    sb.append(ch)
                    digitsToSkip -= T9PinyinMap.pinyinToDigitCode(ch.toString())?.length ?: 1
                }
                sb.reverse().toString()
            }
        } else {
            nonSelectedDigits.drop(consumedCount)
        }
        // 消费前 consumedCount 位数字对应的选择
        val consumedPinyin = nonSelectedPart.take(consumedCount)
        ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedPinyin)
        ctx.leftColumnLocked = false
        return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, remainingPinyinStr)
    }

    private fun handleDefensiveNoCommentCommit(
        ctx: Ctx,
        candidateTextLength: Int,
        prevSelectedOption: T9PinyinMap.SyllableOption?,
        prevSelectionCandidateDigits: String?,
        prevConfirmedPinyin: String,
        prevBuf: T9Buffer,
    ): Boolean {
        val buf = ctx.inputBuffer
        val selectedPinyin = buf.selectedPinyin
        if (prevSelectedOption != null && selectedPinyin.endsWith(prevSelectedOption.pinyin)) {
            if (ctx.stateMachine.selectionHistory.isNotEmpty() &&
                ctx.stateMachine.selectionHistory.joinToString("") { it.pinyin } == selectedPinyin &&
                candidateTextLength > 0 &&
                candidateTextLength >= ctx.stateMachine.selectionHistory.size
            ) {
                clearAndEnterIdle(ctx)
                return true
            }
            val nonSelectedPart = selectedPinyin.dropLast(prevSelectedOption.pinyin.length)
            val selectedDigits = prevSelectionCandidateDigits ?: ""
            if (nonSelectedPart.isNotEmpty() && selectedDigits.isNotEmpty()) {
                val nonSelectedDigits = T9PinyinMap.pinyinToDigitCode(nonSelectedPart)
                if (nonSelectedDigits != null) {
                    val firstSyl = T9PinyinMap.firstSyllableOptions(nonSelectedDigits, maxResults = 1).firstOrNull()
                    if (firstSyl != null && firstSyl.digitLength in 1..nonSelectedDigits.length) {
                        val consumedPinyin = nonSelectedPart.take(firstSyl.digitLength)
                        ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedPinyin)
                        val remainingAfterConsume = nonSelectedDigits.drop(firstSyl.digitLength) + selectedDigits
                        ctx.inputBuffer = if (remainingAfterConsume == selectedDigits) {
                            // 只保留 prevSelectedOption — 但 removeConsumedSelections 已经移除了非选中部分
                            // 此时 ctx.inputBuffer.selections 应该只剩 prevSelectedOption
                            ctx.inputBuffer
                        } else {
                            T9Buffer(
                                digitSequence = remainingAfterConsume,
                                selections = emptyList(),
                                consumedCount = 0,
                                totalDigitsEntered = remainingAfterConsume.length,
                            )
                        }
                        ctx.leftColumnLocked = false
                        return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
                    }
                }
            }
        }

        val digits = T9PinyinMap.pinyinToDigitCode(selectedPinyin)
        if (digits != null) {
            val consumedDigitCount = T9PinyinMap.firstSyllableOptions(digits, maxResults = 1)
                .firstOrNull()?.digitLength ?: 0
            if (consumedDigitCount in 1..<digits.length) {
                ctx.inputBuffer = T9Buffer(
                    digitSequence = digits.drop(consumedDigitCount),
                    selections = emptyList(),
                    consumedCount = 0,
                    totalDigitsEntered = digits.drop(consumedDigitCount).length,
                )
                ctx.leftColumnLocked = false
                return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
            }
        }

        clearAndEnterIdle(ctx)
        return true
    }
}
