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
    ): Boolean {
        if (prevSelectedOption != null) {
            ctx.stateMachine.restoreFrom(
                T9StateMachine.State.SELECTION,
                prevSelectedOption,
                prevSelectionCandidateDigits ?: "",
                prevConfirmedPinyin,
                ctx.stateMachine.selectionHistory.toList(),
            )
        } else {
            ctx.stateMachine.enterInput()
        }
        ctx.syncState()
        ctx.updateCandidates(true)
        ctx.setRimeInput(null)
        return false
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

    /**
     * 构造纯数字 T9Buffer（用于 digitSegment 模式的剩余数字）。
     * 保留原始 digitSequence 和 totalDigitsEntered。
     */
    private fun T9Buffer.withRemainingDigits(
        remainingDigits: String,
        originalBuf: T9Buffer,
    ): T9Buffer {
        if (remainingDigits.isEmpty()) return T9Buffer.EMPTY
        // 剩余数字是原始 digitSequence 的后缀
        // consumedCount = digitSequence.length - remainingDigits.length
        return T9Buffer(
            digitSequence = originalBuf.digitSequence,
            selections = emptyList(),
            consumedCount = originalBuf.digitSequence.length - remainingDigits.length,
            totalDigitsEntered = originalBuf.totalDigitsEntered,
        )
    }

    // ── 三层消费算法实现（T9Buffer 版本） ──

    /**
     * 计算右侧候选选词时消费的数字段和剩余段（T9Buffer 版本）。
     *
     * 三层消费算法：
     *   层级1：apostrophe 模式（selections 非空 && unassigned 非空）
     *     从 unassigned 中按字母数消费
     *   层级2：digitSegment 模式（selections 为空 && unassigned 非空）
     *     从 candidatePinyin 逐音节匹配 unassigned 前缀
     *   层级3：letterBuffer 模式（selections 非空 && unassigned 为空）
     *     通过 pinyinToDigitCode 回退转为数字，再按数字段模式处理
     */
    private fun computeRightCommitConsumption(
        buf: T9Buffer,
        candidatePinyin: String?,
    ): Pair<String, String> {
        val hasSelections = buf.selections.isNotEmpty()
        val unassigned = buf.unassigned

        // apostrophe 模式
        if (hasSelections && unassigned.isNotEmpty()) {
            val candidateLetterCount = candidatePinyin?.count { it.isLetter() } ?: 0
            val consumedAfter = candidateLetterCount - buf.selectedPinyin.length
            if (consumedAfter > 0) {
                val remaining = unassigned.drop(consumedAfter)
                return unassigned.take(consumedAfter) to remaining
            }
            return unassigned to unassigned
        }

        // digitSegment 模式
        if (!hasSelections && unassigned.isNotEmpty()) {
            val segment = unassigned
            val consumedCount = computeConsumedDigitsFromPinyin(segment, candidatePinyin)
            return segment.take(consumedCount) to segment.drop(consumedCount)
        }

        // letterBuffer 模式
        val selectedPinyin = buf.selectedPinyin
        val effectiveLetterCount = candidatePinyin?.count { it.isLetter() } ?: 0
        if (effectiveLetterCount > 0 && effectiveLetterCount < selectedPinyin.length) {
            val consumedDig = T9PinyinMap.pinyinToDigitCode(selectedPinyin.take(effectiveLetterCount)) ?: ""
            val remainingDig = T9PinyinMap.pinyinToDigitCode(selectedPinyin.drop(effectiveLetterCount)) ?: ""
            return consumedDig to remainingDig
        }
        if (effectiveLetterCount >= selectedPinyin.length) return "" to ""

        val digits = T9PinyinMap.pinyinToDigitCode(selectedPinyin)
        if (digits != null) {
            val consumedDigitCount = T9PinyinMap.firstSyllableOptions(digits, maxResults = 1)
                .firstOrNull()?.digitLength ?: 0
            if (consumedDigitCount in 1..<digits.length) {
                return digits.take(consumedDigitCount) to digits.drop(consumedDigitCount)
            }
        }
        return "" to ""
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
            val candidateLetterCount = candidatePinyin?.count { it.isLetter() } ?: 0
            val consumedFromNonSelected = minOf(candidateLetterCount, nonSelectedPinyin.length)
            val unconsumedPinyin = nonSelectedPinyin.drop(consumedFromNonSelected)

            // apostrophe 模式下，候选词注释的音节数必须足以覆盖所有已确认选择
            // 以及未分配数字（至少一个额外音节）。若音节不足，即使字母数模型显示
            // remainingDigits 为空，也不能触发 full commit，否则会把未分配数字一并提交。
            // 典型场景：kg'3 + "客观(ke guan)" → ke/k, guan/g，剩余 3 未被覆盖。
            val commentSyllables = candidatePinyin?.trim()?.split("\\s+".toRegex())
                ?.filter { it.any { c -> c.isLetter() } } ?: emptyList()
            val requiredSyllables = buf.selections.size + if (buf.unassigned.isNotEmpty()) 1 else 0
            val canCoverAllBySyllableCount = commentSyllables.size >= requiredSyllables

            if (canCoverAllBySyllableCount &&
                shouldFullCommitInSelection(consumedFromNonSelected, nonSelectedPinyin.length, remainingDigits, candidatePinyin, selectedPinyin)
            ) {
                clearAndEnterIdle(ctx)
                return true
            }
            val consumedNonSelectedPinyin = nonSelectedPinyin.take(consumedFromNonSelected)
            // 移除被消费的前缀选择，保留 unassigned 不变
            ctx.inputBuffer = removeConsumedSelections(ctx, buf, consumedNonSelectedPinyin)
            ctx.leftColumnLocked = false

            // 候选词消费选中项：当 nonSelected 已完全消费、候选词最后一个音节
            // 与当前选中项匹配时，表示该选中项也被候选项消费。
            // 两种匹配模式：
            //   声母匹配：选中项为简拼(digitLength==1)，候选词最后音节声母数字码
            //            与选中项数字码一致（如 g 匹配 guan 的声母 g）
            //   全拼匹配：选中项为全拼(digitLength>1)，候选词最后音节数字码
            //            与选中项数字码完全一致（如 shan 匹配 shan）
            // 例如 kg'3 右选 "客观(ke guan)"：ke 消费 k，guan 的声母 g 消费选中项 g，
            // 仅保留未分配数字 3 并进入 INPUT 态。
            // 例如 yunshan'98726 右选 "云山(yun shan)"：yun 消费 yun，shan 消费选中项 shan，
            // 仅保留未分配数字 98726 并进入 INPUT 态。
            if (unconsumedPinyin.isEmpty() && commentSyllables.isNotEmpty()) {
                val lastSyl = commentSyllables.last()
                val lastSylCode = T9PinyinMap.pinyinToDigitCode(lastSyl)
                val selCode = T9PinyinMap.pinyinToDigitCode(prevSelectedOption.pinyin)
                val selDigits = prevSelectionCandidateDigits ?: ""

                val isShengmuMatch = prevSelectedOption.digitLength == 1 &&
                    lastSylCode != null && selCode != null &&
                    lastSylCode.startsWith(selCode) &&
                    selDigits.startsWith(selCode)

                val isFullPinyinMatch = prevSelectedOption.digitLength > 1 &&
                    lastSylCode != null && selCode != null &&
                    lastSylCode == selCode

                if (isShengmuMatch || isFullPinyinMatch) {
                    ctx.inputBuffer = ctx.inputBuffer.copy(
                        selections = ctx.inputBuffer.selections.dropLast(1),
                    )
                    ctx.stateMachine.clearSelectionHistory()
                    ctx.stateMachine.enterInput()
                    ctx.syncState()
                    ctx.updateCandidates(true)
                    ctx.setRimeInput(ctx.inputBuffer.toBufferString())
                    return false
                }
            }

            return restorePrevState(ctx, prevSelectedOption, prevSelectionCandidateDigits, prevConfirmedPinyin)
        }
        // 非 SELECTION 或 confirmedPinyin 不以 selectedPinyin 结尾：
        // 候选词消费了部分 unassigned 数字，新 buffer 为剩余数字。
        // 与主分支一致地校验音节覆盖：即使 remainingDigits 为空，若候选词注释音节数
        // 不足以覆盖 selections.size + unassigned 数字段，不能触发 full commit，
        // 否则未分配数字会被一并提交（数据丢失）。
        val commentSyllables = candidatePinyin?.trim()?.split("\\s+".toRegex())
            ?.filter { it.any { c -> c.isLetter() } } ?: emptyList()
        val requiredSyllables = buf.selections.size + if (buf.unassigned.isNotEmpty()) 1 else 0
        val canCoverAllBySyllableCount = commentSyllables.size >= requiredSyllables

        if (remainingDigits.isEmpty() && !canCoverAllBySyllableCount) {
            // 音节覆盖不足但字母数模型显示 remaining 为空：保留选中部分为字母形式，
            // 避免直接 full commit 把未分配数字一并提交。
            ctx.inputBuffer = removeConsumedSelections(ctx, buf, buf.selectedPinyin)
            ctx.leftColumnLocked = false
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
            // isFullCommitWithoutBoundaries 误判（场景17 根因：
            // joinToString 拼接残留+新条目 ≠ selectedPinyin）。
            // 与上方 isShengmuMatch/isFullPinyinMatch 分支 line 350-351 一致。
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
        var consumedCount = computeConsumedDigitsFromPinyin(segment, candidatePinyin)
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
        val effectiveLetterCount = candidatePinyin?.count { it.isLetter() } ?: 0
        if (effectiveLetterCount > 0) {
            if (effectiveLetterCount < selectedPinyin.length) {
                if (prevSelectedOption != null) {
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

        val commentSyllables = candidatePinyin?.trim()?.split("\\s+".toRegex())
            ?.filter { it.any { c -> c.isLetter() } } ?: emptyList()
        val hasSyllableBoundaries = commentSyllables.size > 1

        val isFullCommitWithoutBoundaries = !hasSyllableBoundaries &&
            ctx.stateMachine.selectionHistory.isNotEmpty() &&
            ctx.stateMachine.selectionHistory.joinToString("") { it.pinyin } == selectedPinyin &&
            candidateTextLength > 0 &&
            candidateTextLength >= ctx.stateMachine.selectionHistory.size
        val isFullCommit = (hasSyllableBoundaries && candidateTextLength > 0 &&
            candidateTextLength >= commentSyllables.size &&
            (selectedPinyin.dropLast(prevSelectedOption.pinyin.length).isEmpty() ||
                isAllSelectedConsumed(selectedPinyin, commentSyllables, ctx.stateMachine.selectionHistory))) ||
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

    private fun computeSelectionConsumedCount(
        hasSyllableBoundaries: Boolean,
        candidateTextLength: Int,
        commentSyllables: List<String>,
        effectiveLetterCount: Int,
        prevSelectedOption: T9PinyinMap.SyllableOption,
        nonSelectedDigits: String,
        candidatePinyin: String?,
    ): Int {
        if (hasSyllableBoundaries && candidateTextLength > 0 && commentSyllables.size > candidateTextLength) {
            var consumed = 0
            for (i in 0 until minOf(candidateTextLength, commentSyllables.size)) {
                consumed += T9PinyinMap.pinyinToDigitCode(commentSyllables[i])?.length ?: 0
            }
            return consumed
        }

        val candidateNonSelectedLetters = effectiveLetterCount - prevSelectedOption.pinyin.length
        if (candidateNonSelectedLetters in 1..nonSelectedDigits.length) {
            if (hasSyllableBoundaries && candidateNonSelectedLetters >= nonSelectedDigits.length) {
                val nonSelectedSyllables = if (commentSyllables.lastOrNull() == prevSelectedOption.pinyin)
                    commentSyllables.dropLast(1) else commentSyllables
                val totalSyllableDigits = nonSelectedSyllables.sumOf {
                    T9PinyinMap.pinyinToDigitCode(it)?.length ?: 0
                }
                return if (totalSyllableDigits > nonSelectedDigits.length) {
                    computeConsumedDigitsFromPinyin(nonSelectedDigits, candidatePinyin)
                } else {
                    candidateNonSelectedLetters
                }
            }
            return candidateNonSelectedLetters
        }
        return computeConsumedDigitsFromPinyin(nonSelectedDigits, candidatePinyin)
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
            val candidateClean = candidatePinyin?.filter { it.isLetter() } ?: ""
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

        val remainingFromSelected = selDigits.drop(initialCode.length)
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
