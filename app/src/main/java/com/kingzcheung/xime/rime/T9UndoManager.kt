package com.kingzcheung.xime.rime

/**
 * 九键输入撤销管理器（命令模式 — 设计文档 Phase 3）。
 *
 * 每个操作封装为 [T9Command]，自带 undo() 方法，
 * 替代旧版 Snapshot 全量快照 + onDeleted() 优先级迷宫。
 *
 * 设计原则：
 * - 每个命令精确知道如何撤销自己，无需"猜测"
 * - 回退只需 pop().undo()，逻辑极简
 * - 命令存储代价可忽略（< 30 chars / command）
 */

// ─────────────────────────────────────────────────────────
// 命令定义
// ─────────────────────────────────────────────────────────

/** 可撤销的 T9 操作命令 */
sealed class T9Command {

    /**
     * 撤销上下文：undo() 需要的可变状态引用。
     * 由 [T9InputController] 构造并传入。
     */
    class Ctx(
        var buffer: T9Buffer,
        var leftColumnLocked: Boolean,
        var separatorConsumedDigits: String?,
        var lastChoiceConsumedDigits: String?,
        val stateMachine: T9StateMachine,
        val onRestored: () -> Unit,
    )

    /** 执行撤销操作 */
    abstract fun undo(ctx: Ctx)

    // ── 具体命令 ──

    /** 按下数字键 n ∈ {2..9} */
    data class DigitPressed(val digit: String) : T9Command() {
        override fun undo(ctx: Ctx) {
            ctx.buffer = ctx.buffer.removeLastDigit()
            ctx.leftColumnLocked = false
        }
    }

    /** 按下分词键 1 */
    data class Separator(
        val prevBuffer: T9Buffer,
        val prevSeparatorConsumedDigits: String?,
        val prevSelectionHistory: List<T9PinyinMap.SyllableOption> = emptyList(),
    ) : T9Command() {
        override fun undo(ctx: Ctx) {
            ctx.buffer = prevBuffer.copy(
                digitSequence = ctx.buffer.digitSequence,
                totalDigitsEntered = ctx.buffer.totalDigitsEntered,
            )
            ctx.separatorConsumedDigits = prevSeparatorConsumedDigits
            ctx.leftColumnLocked = false
            ctx.stateMachine.enterInput()
            ctx.stateMachine.setSelectionHistory(prevSelectionHistory)
        }
    }

    /** 左侧候选区选择拼音/字母 */
    data class LeftChoice(
        val prevBuffer: T9Buffer,
        val prevSeparatorConsumedDigits: String?,
        val prevLeftColumnLocked: Boolean,
        val prevSelectedOption: T9PinyinMap.SyllableOption?,
        val prevSelectionCandidateDigits: String?,
        val prevConfirmedPinyin: String = "",
        val prevSelectionHistory: List<T9PinyinMap.SyllableOption> = emptyList(),
        /** 所选拼音是否来自数字编码上下文（INPUT 态首次选择）。
         * 为 true 时 undo 移除最后选择、恢复数字；
         * 为 false（SELECTION 态筛选层替换/追加）时恢复 prevBuffer。 */
        val wasFromDigitContext: Boolean = false,
        /** 是否使用 addSelectionNoConsume（分词键确认失败后首次选字）。
         * 为 true 时 undo 仅移除选择，不递减 consumedCount。 */
        val wasNoConsume: Boolean = false,
    ) : T9Command() {
        override fun undo(ctx: Ctx) {
            ctx.buffer = if (wasFromDigitContext) {
                // 从数字上下文选择：移除最后选择，恢复数字（保留用户删除的 digitSequence）
                if (wasNoConsume) {
                    ctx.buffer.copy(selections = ctx.buffer.selections.dropLast(1))
                } else {
                    ctx.buffer.undoLastSelection()
                }
            } else {
                // 筛选层替换/追加：恢复 prevBuffer 的 selections 和 consumedCount
                prevBuffer.copy(
                    digitSequence = ctx.buffer.digitSequence,
                    totalDigitsEntered = ctx.buffer.totalDigitsEntered,
                )
            }
            ctx.separatorConsumedDigits = prevSeparatorConsumedDigits
            ctx.leftColumnLocked = prevLeftColumnLocked
            if (prevSelectedOption != null) {
                ctx.stateMachine.enterSelection(
                    prevSelectedOption,
                    prevSelectionCandidateDigits ?: "",
                    prevConfirmedPinyin,
                )
                if (prevSelectionHistory.isNotEmpty()) {
                    ctx.stateMachine.setSelectionHistory(prevSelectionHistory)
                }
            } else {
                ctx.stateMachine.enterInput()
                ctx.stateMachine.setSelectionHistory(prevSelectionHistory)
            }
        }
    }

    /** 右侧候选词选择汉字。
     *  prevBuffer 为 T9Buffer，undo 时恢复其 selections/consumedCount，
     *  但保留当前 digitSequence（right commit 后可能已有新数字输入）。
     *  remainingDigitCount 记录 commit 后 buffer.digitSequence.length，
     *  用于撤销时判断当前 buffer 是否可安全恢复（digitSequence 未增长 = 无新增数字）。 */
    data class RightCommit(
        val prevBuffer: T9Buffer,
        val prevSeparatorConsumedDigits: String?,
        val prevLastChoiceConsumedDigits: String?,
        val prevLeftColumnLocked: Boolean,
        val prevSelectedOption: T9PinyinMap.SyllableOption?,
        val prevSelectionCandidateDigits: String?,
        val prevConfirmedPinyin: String = "",
        val prevSelectionHistory: List<T9PinyinMap.SyllableOption> = emptyList(),
        val remainingDigitCount: Int = 0,
    ) : T9Command() {
        override fun undo(ctx: Ctx) {
            ctx.separatorConsumedDigits = prevSeparatorConsumedDigits
            ctx.lastChoiceConsumedDigits = prevLastChoiceConsumedDigits
            ctx.leftColumnLocked = prevLeftColumnLocked
            if (prevSelectedOption != null) {
                ctx.stateMachine.enterSelection(
                    prevSelectedOption,
                    prevSelectionCandidateDigits ?: "",
                    prevConfirmedPinyin,
                )
                if (prevSelectionHistory.isNotEmpty()) {
                    ctx.stateMachine.setSelectionHistory(prevSelectionHistory)
                }
            } else {
                ctx.stateMachine.enterInput()
                ctx.stateMachine.setSelectionHistory(prevSelectionHistory)
            }
            // 恢复 prevBuffer 的 selections/consumedCount，
            // 但保留当前 digitSequence（right commit 后可能已有新数字输入）。
            //
            // digitSegment 模式：RC 保持原始 digitSequence（仅增加 consumedCount），
            //   回退删除缩短了 digitSequence，undo 时应保留缩短后的序列。
            //   判定条件：consumedCount > 0 且当前序列是 prevBuffer 序列的前缀。
            // letterBuffer 模式：RC 创建了全新 T9Buffer（声母回退），
            //   digitSequence 完全不同，undo 时应恢复 prevBuffer 的原始序列。
            val restoredDigitSeq = when {
                ctx.buffer.consumedCount > 0 &&
                    prevBuffer.digitSequence.startsWith(ctx.buffer.digitSequence) &&
                    ctx.buffer.digitSequence.length < prevBuffer.digitSequence.length ->
                    ctx.buffer.digitSequence  // digitSegment: 保留缩短后的序列
                ctx.buffer.digitSequence.length < prevBuffer.digitSequence.length ->
                    prevBuffer.digitSequence  // letterBuffer: 恢复原始完整序列
                else ->
                    ctx.buffer.digitSequence  // 同长度：无需恢复
            }
            ctx.buffer = prevBuffer.copy(
                digitSequence = restoredDigitSeq,
                totalDigitsEntered = maxOf(prevBuffer.totalDigitsEntered, ctx.buffer.totalDigitsEntered),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 撤销管理器
// ─────────────────────────────────────────────────────────

class T9UndoManager {

    private val commands = mutableListOf<T9Command>()

    val size: Int get() = commands.size
    fun isEmpty(): Boolean = commands.isEmpty()
    fun isNotEmpty(): Boolean = commands.isNotEmpty()

    /** 查看栈顶命令 */
    fun peek(): T9Command? = commands.lastOrNull()

    /** 压入命令 */
    fun push(cmd: T9Command) {
        commands.add(cmd)
    }

    /** 弹出栈顶命令并执行 undo() */
    fun popAndUndo(ctx: T9Command.Ctx) {
        if (commands.isEmpty()) return
        val cmd = commands.removeAt(commands.lastIndex)
        cmd.undo(ctx)
    }

    /** 清空命令栈 */
    fun clear() {
        commands.clear()
    }

    /** 弹出栈顶命令（不执行 undo，用于滚动 back 场景） */
    fun pop(): T9Command? =
        if (commands.isNotEmpty()) commands.removeAt(commands.lastIndex) else null

    /** 是否存在 RightCommit 类型的命令 */
    fun hasPendingRightCommit(): Boolean = commands.any { it is T9Command.RightCommit }

    /** 栈顶是否为 RightCommit */
    val topIsRightCommit: Boolean get() = commands.lastOrNull() is T9Command.RightCommit

    /** 移除栈中最后一个 DigitPressed 命令（不限位置）。用于 undo LeftChoice 后清除 stale 标记。 */
    fun removeLastDigitPressed() {
        val idx = commands.indexOfLast { it is T9Command.DigitPressed }
        if (idx >= 0) {
            commands.removeAt(idx)
        }
    }

    /** 更新栈顶 RightCommit 的 remainingDigitCount（commit 后 buffer.digitSequence.length） */
    fun updateTopRightCommitRemaining(count: Int) {
        val top = commands.lastOrNull()
        if (top is T9Command.RightCommit) {
            commands[commands.lastIndex] = top.copy(remainingDigitCount = count)
        }
    }
}
