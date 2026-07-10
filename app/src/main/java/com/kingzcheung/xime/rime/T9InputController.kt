package com.kingzcheung.xime.rime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 九键拼音输入控制器。
 *
 * 设计文档 13.1 节模块分工：
 * - DigitStream（原 T9BufferManager）：数字流模型与 buffer 解析
 * - T9StateMachine：三态状态机 + LeftSelection 模型
 * - T9UndoManager：命令模式撤销管理（Phase 3）
 * - T9RimeBridge：RIME 引擎通信桥接
 * - T9RightCommitUtils：右侧选词消费计算
 * - T9PinyinMap：拼音映射（无需改动）
 * - T9DisplayHelper：UI 展示状态构建
 */
class T9InputController(
    onReplaceFullPinyin: (String) -> Unit,
    onQueryRimeComposition: (() -> RimeComposition)? = null,
    onRightCommitUndone: ((Int) -> Unit)? = null,
) {
    companion object {
        const val CLEAR_COMPOSITION_ONLY = T9RimeBridge.CLEAR_COMPOSITION_ONLY
        const val CLEAR_ALL = T9RimeBridge.CLEAR_ALL
    }

    /** 推迟左侧候选区更新到下一帧，避免重组干扰触摸事件命中测试 */
    private val candidateUpdater: android.os.Handler? =
        try { android.os.Handler(android.os.Looper.getMainLooper()) } catch (_: Throwable) { null }

    private val rimeBridge = T9RimeBridge(onReplaceFullPinyin, onQueryRimeComposition, onRightCommitUndone)

    enum class LeftPanelState { IDLE, INPUT, SELECTION }

    enum class DeleteResult {
        DELETED, UNDO_CHOICE, UNDO_COMMIT, NOT_CONSUMED
    }

    /**
     * T9 结构化输入缓冲区。
     *
     * 设计文档 §2.2：替换纯字符串 buffer，分离数字序列与拼音选择。
     * 分隔符 ' 不再存储，仅在 toBufferString() / toPreeditString() 中按规则生成。
     */
    var inputBuffer: T9Buffer by mutableStateOf(T9Buffer.EMPTY)

        internal set

    /** inputBuffer 的字符串表示（供 UI 显示和外部查询）。
     * 分词键确认的拼音已内化到 T9Buffer.selections，由 toBufferString() 自然生成。
     * 此处仅处理 leftColumnLocked 时的尾随 ' 显示（锁定态视觉提示）。 */
    val bufferString: String get() {
        val buf = inputBuffer.toBufferString()
        // 拼音选择完全消费且锁定：补尾随 '（显示锁定状态）
        // 避免与 toBufferString 的退格缩短 ' 重复
        if (leftColumnLocked && inputBuffer.selections.isNotEmpty() &&
            inputBuffer.unassigned.isEmpty() && !buf.endsWith("'")) {
            return buf + "'"
        }
        return buf
    }

    var firstOptions: List<T9PinyinMap.SyllableOption> by mutableStateOf(emptyList())
        private set

    private var lastRimeInput: String?
        get() = rimeBridge.getLastRimeInput()
        set(value) { rimeBridge.setLastRimeInput(value) }

    var leftColumnLocked: Boolean by mutableStateOf(false)
        private set


    var leftPanelState: LeftPanelState by mutableStateOf(LeftPanelState.IDLE)
        private set

    var selectedOption: T9PinyinMap.SyllableOption? by mutableStateOf(null)
        private set

    var selectionCandidateDigits: String? by mutableStateOf(null)
        private set

    /** 当前输入会话中所有已确认的拼音选择历史（按选择顺序），供候选词过滤使用 */
    val selectionHistory: List<T9PinyinMap.SyllableOption> get() = stateMachine.selectionHistory.toList()

    /** 当前左侧选择上下文（设计文档 2.3 节 LeftSelection 模型） */
    val leftSelection: T9StateMachine.LeftSelection? get() = stateMachine.leftSelection

    private val stateMachine = T9StateMachine()

    private fun syncStateFromMachine() {
        leftPanelState = when (stateMachine.state) {
            T9StateMachine.State.IDLE -> LeftPanelState.IDLE
            T9StateMachine.State.INPUT -> LeftPanelState.INPUT
            T9StateMachine.State.SELECTION -> LeftPanelState.SELECTION
        }
        selectedOption = stateMachine.selectedOption
        selectionCandidateDigits = stateMachine.selectionCandidateDigits
    }

    /** 构建命令撤销上下文 */
    private fun undoCtx() = T9Command.Ctx(
        buffer = inputBuffer,
        leftColumnLocked = leftColumnLocked,
        separatorConsumedDigits = separatorConsumedDigits,
        lastChoiceConsumedDigits = lastChoiceConsumedDigits,
        stateMachine = stateMachine,
        onRestored = {
            syncStateFromMachine()
            updateCandidates(force = true)
            rimeBridge.setLastRimeInput(null)
            sendToRime()
        },
    )

    /** 推送命令前的状态快照辅助：记录当前状态以备撤销 */
    private fun pushCommand(cmd: T9Command) {
        undoManager.push(cmd)
    }

    private var cachedDigitSegment: String? = null
    private var cachedFirstOptions: List<T9PinyinMap.SyllableOption> = emptyList()

    private val undoManager = T9UndoManager()

    private var separatorConsumedDigits: String? = null

    private var lastChoiceConsumedDigits: String? = null

    fun reset() = resetState(clearCache = true, clearRime = false)

    fun lastDigitSegment(): String = inputBuffer.unassigned

    fun updateCandidates(force: Boolean = false) {
        val t0 = System.nanoTime()
        if (leftColumnLocked && !force) return

        val bufStr = inputBuffer.toBufferString()
        val hasApostrophe = bufStr.contains("'")
        val selectionDigits = selectionCandidateDigits

        // 分词键锁定：继续显示之前确认的数字段的候选
        if (separatorConsumedDigits != null && hasApostrophe) {
            if (force || cachedDigitSegment != separatorConsumedDigits) {
                cachedDigitSegment = separatorConsumedDigits
                cachedFirstOptions = T9PinyinMap.firstSyllableOptions(separatorConsumedDigits!!, maxResults = 12)
            }
            firstOptions = cachedFirstOptions
            leftColumnLocked = false
            return
        }

        // 使用设计文档 2.5 节 leftCandidates() 纯函数计算候选（T9Buffer 版本）
        val candidates = leftCandidates(inputBuffer, stateMachine.state, selectionDigits)
        val effectiveKey = if (candidates.isNotEmpty()) inputBuffer.unassigned.ifEmpty { selectionDigits ?: "" } else ""

        if (force || cachedDigitSegment != effectiveKey) {
            cachedDigitSegment = effectiveKey
            cachedFirstOptions = candidates
        }
        firstOptions = cachedFirstOptions

        if (!bufStr.endsWith("'")) {
            leftColumnLocked = false
        }
        val elapsed = (System.nanoTime() - t0) / 1_000_000L
        if (elapsed > 2) {
            try { android.util.Log.d("T9InputCtrl", "updateCandidates took ${elapsed}ms buffer='${inputBuffer.toBufferString().take(20)}'") } catch (_: Throwable) { }
        }
    }

    /**
     * 判断当前选中的选项是否应高亮显示。
     */
    fun isSelectedOptionInCurrentCandidates(): Boolean {
        if (leftPanelState != LeftPanelState.SELECTION || selectedOption == null) return false
        val selDigits = selectionCandidateDigits ?: return false
        // 无选择历史 + 有未消费数字 = 纯数字上下文
        if (inputBuffer.selections.isEmpty() && inputBuffer.unassigned.isNotEmpty()) {
            return cachedDigitSegment == selDigits
        }
        if (inputBuffer.unassigned.isNotEmpty()) return false
        return cachedDigitSegment == selDigits
    }

    private fun enterSelection(
        option: T9PinyinMap.SyllableOption,
        candidateDigits: String,
        confirmedPinyin: String = "",
    ) {
        stateMachine.enterSelection(option, candidateDigits, confirmedPinyin)
        syncStateFromMachine()
    }

    private fun enterIdle() {
        stateMachine.enterIdle()
        syncStateFromMachine()
        firstOptions = emptyList()
        leftColumnLocked = false
        if (!undoManager.hasPendingRightCommit()) {
            undoManager.clear()
        }
    }

    fun forceSendToRime() {
        rimeBridge.setLastRimeInput(null)
        sendToRime()
    }

    fun sendToRime() {
        if (inputBuffer.isEmpty) {
            if (lastRimeInput != null) {
                rimeBridge.setLastRimeInput(null)
                val hasPendingRightCommit = undoManager.hasPendingRightCommit()
                rimeBridge.replaceFullPinyin(if (hasPendingRightCommit) CLEAR_COMPOSITION_ONLY else CLEAR_ALL)

            }
            return
        }

        // T9Buffer.toPreeditString() 统一生成预编辑字符串
        // （分词键确认的拼音已在 selections 中，toPreeditString 自然处理分隔符）
        val rimeInput = inputBuffer.toPreeditString()
        if (rimeInput == lastRimeInput) return
        lastRimeInput = rimeInput

        rimeBridge.replaceFullPinyin(rimeInput)
    }

    fun onDigitPressed(digit: String) {
        val t0 = System.nanoTime()
        if (digit == "1") {
            handleSeparatorKey()
            return
        }

        if (stateMachine.isIdle) {
            stateMachine.enterInput()
            syncStateFromMachine()
        }
        // T9Buffer 自动处理字母/数字间的分隔——不需要手动插入 '
        pushCommand(T9Command.DigitPressed(digit))
        inputBuffer = inputBuffer.addDigit(digit)
        candidateUpdater?.post { updateCandidates() } ?: updateCandidates()

        sendToRime()
        val elapsed = (System.nanoTime() - t0) / 1_000_000L
        if (elapsed > 5) {
            try { android.util.Log.d("T9InputCtrl", "onDigitPressed('$digit') took ${elapsed}ms, buffer='${inputBuffer.toBufferString().take(20)}'") } catch (_: Throwable) { }
        }
    }

    /** 分词键（数字 1）：确认当前数字段的最优切分音节。 */
    private fun handleSeparatorKey() {
        val segment = inputBuffer.unassigned
        if (segment.isNotEmpty()) {
            val confirmed = rimeBridge.inferFirstSyllableFromRime(segment)
            pushCommand(T9Command.Separator(
                prevBuffer = inputBuffer,
                prevSeparatorConsumedDigits = separatorConsumedDigits,
                prevSelectionHistory = stateMachine.selectionHistory.toList(),
            ))
            if (confirmed != null) {
                separatorConsumedDigits = segment.take(confirmed.digitLength)
                lastChoiceConsumedDigits = null
                // 确认音节：addSelection 同时记录拼音和消费对应位数
                // （separatorConfirmedPinyin 已内化到 selections，由 toBufferString/toPreeditString 自然生成）
                inputBuffer = inputBuffer.addSelection(confirmed.pinyin, confirmed.digitLength)
            }
            // 无匹配音节：仅记录分词状态（separatorConsumedDigits 保持 null），不修改 inputBuffer
        }
        leftColumnLocked = true
        sendToRime()
    }

    fun onChoiceSelected(option: T9PinyinMap.SyllableOption) {
        // SELECTION 态 + 无未分配数字 → 替换
        if (stateMachine.isSelection && inputBuffer.unassigned.isEmpty()) {
            handleSelectionReplacementChoice(option)
            return
        }

        // 有未分配数字 → 新选择
        handleLeftSelectChoice(option)
    }

    /** 从未分配数字段中选择拼音（统一处理原 apostrophe / digitSegment 两条路径）。 */
    private fun handleLeftSelectChoice(option: T9PinyinMap.SyllableOption) {
        if (option.digitLength > inputBuffer.unassigned.length) return

        val prevBuf = inputBuffer
        val prevSep = separatorConsumedDigits
        val prevLocked = leftColumnLocked
        val prevOpt = stateMachine.selectedOption
        val prevDigits = selectionCandidateDigits
        val prevConf = stateMachine.confirmedPinyinBeforeSelection
        val prevHist = stateMachine.selectionHistory.toList()

        val consumedDigits = if (leftColumnLocked) {
            separatorConsumedDigits ?: inputBuffer.unassigned.take(option.digitLength)
        } else {
            inputBuffer.unassigned.take(option.digitLength)
        }
        val confirmedPinyin = inputBuffer.selectedPinyin

        // 撤销语义标记：
        //   wasFromDigitContext=true  → undo 用 undoLastSelection/copy(dropLast) 移除选择、恢复数字
        //   wasNoConsume=true         → undo 仅移除选择，不递减 consumedCount（addSelectionNoConsume 场景）
        val wasFromDigitContext: Boolean
        val wasNoConsume: Boolean

        if (leftColumnLocked) {
            lastChoiceConsumedDigits = separatorConsumedDigits
            separatorConsumedDigits = null
            if (inputBuffer.selections.isNotEmpty()) {
                // 分词键确认拼音后替换：undo 用 undoLastSelection 移除替换选择、恢复数字
                inputBuffer = inputBuffer.replaceLastSelection(option.pinyin, option.digitLength)
                wasFromDigitContext = true
                wasNoConsume = false
            } else {
                // 分词键未确认拼音后首次选字：undo 仅移除选择，不递减 consumedCount
                inputBuffer = inputBuffer.addSelectionNoConsume(option.pinyin, option.digitLength)
                wasFromDigitContext = true
                wasNoConsume = true
            }
            leftColumnLocked = false
            enterSelection(option, lastChoiceConsumedDigits ?: "", "")
        } else {
            // 从 INPUT 态首次选字：undo 用 undoLastSelection 移除选择、恢复数字
            lastChoiceConsumedDigits = consumedDigits
            separatorConsumedDigits = null
            inputBuffer = inputBuffer.addSelection(option.pinyin, option.digitLength)
            enterSelection(option, consumedDigits, confirmedPinyin)
            wasFromDigitContext = true
            wasNoConsume = false
        }

        pushCommand(T9Command.LeftChoice(
            prevBuffer = prevBuf, prevSeparatorConsumedDigits = prevSep,
            prevLeftColumnLocked = prevLocked, prevSelectedOption = prevOpt,
            prevSelectionCandidateDigits = prevDigits, prevConfirmedPinyin = prevConf,
            prevSelectionHistory = prevHist,
            wasFromDigitContext = wasFromDigitContext,
            wasNoConsume = wasNoConsume,
        ))
        leftColumnLocked = false
        updateCandidates(force = true)
        sendToRime()
    }

    /** SELECTION 替换：筛选层切换拼音/字母选项。 */
    private fun handleSelectionReplacementChoice(option: T9PinyinMap.SyllableOption) {
        if (!stateMachine.isSelection || stateMachine.selectedOption == null) return

        val prevBuf = inputBuffer
        val prevSep = separatorConsumedDigits
        val prevLocked = leftColumnLocked
        val prevOpt = stateMachine.selectedOption
        val prevDigits = selectionCandidateDigits
        val prevConf = stateMachine.confirmedPinyinBeforeSelection
        val prevHist = stateMachine.selectionHistory.toList()

        val candidateDigits = selectionCandidateDigits ?: ""
        if (option.digitLength > candidateDigits.length) return

        val newConsumedDigits = candidateDigits.take(option.digitLength)
        val remaining = candidateDigits.drop(option.digitLength)
        val confirmedPrefix = inputBuffer.selectedPinyin.dropLast(
            (stateMachine.selectedOption?.pinyin?.length ?: 0)
        )

        inputBuffer = if (inputBuffer.selections.isNotEmpty()) {
            inputBuffer.replaceLastSelection(option.pinyin, option.digitLength)
        } else {
            inputBuffer.addSelection(option.pinyin, option.digitLength)
        }

        if (stateMachine.selectionHistory.isNotEmpty()) {
            stateMachine.removeLastSelectionHistoryEntry()
        }
        enterSelection(option, newConsumedDigits, confirmedPrefix)

        // 筛选层替换：继承被替换命令的 prev 字段
        var eBuf: T9Buffer = prevBuf; var eSep = prevSep; var eLocked = prevLocked
        var eOpt = prevOpt; var eDigits = prevDigits; var eConf = prevConf; var eHist = prevHist
        val replaced = undoManager.pop() as? T9Command.LeftChoice
        if (replaced != null) {
            eBuf = replaced.prevBuffer; eSep = replaced.prevSeparatorConsumedDigits
            eLocked = replaced.prevLeftColumnLocked; eOpt = replaced.prevSelectedOption
            eDigits = replaced.prevSelectionCandidateDigits; eConf = replaced.prevConfirmedPinyin
            eHist = replaced.prevSelectionHistory
        }
        pushCommand(T9Command.LeftChoice(
            prevBuffer = eBuf, prevSeparatorConsumedDigits = eSep,
            prevLeftColumnLocked = eLocked, prevSelectedOption = eOpt,
            prevSelectionCandidateDigits = eDigits, prevConfirmedPinyin = eConf,
            prevSelectionHistory = eHist,
        ))
        if (remaining.isNotEmpty()) lastChoiceConsumedDigits = newConsumedDigits
        updateCandidates(force = true)
        sendToRime()
    }

    /** 退格删除。命令模式：pop 栈顶命令 → 执行其 undo()。 */
    fun onDeleted(): DeleteResult {
        // 检查 RightCommit 是否可立即撤销：
        // digitSequence 未变化（length == remainingDigitCount）说明用户未做中间操作，
        // 可安全撤销 RC；digitSequence 缩短（删除了数字）说明用户正在逐步回退，
        // 应先删完剩余数字再通过 step3 撤销 RC。
        val top = undoManager.peek()
        if (top is T9Command.RightCommit &&
            inputBuffer.digitSequence.length == top.remainingDigitCount) {
            val ctx = undoCtx()
            undoManager.popAndUndo(ctx)
            applyUndoCtx(ctx)
            return DeleteResult.UNDO_COMMIT
        }

        // 有未分配数字 → 逐位删除数字（T9Buffer.unassigned 对应旧的 lastDigitSegment）
        if (inputBuffer.unassigned.isNotEmpty()) {
            // 栈顶为 Separator（无 DigitPressed 在其上）→ 撤销分词键
            if (undoManager.isNotEmpty() && undoManager.peek() is T9Command.Separator) {
                val ctx = undoCtx()
                undoManager.popAndUndo(ctx)
                applyUndoCtx(ctx)
                sendToRime()
                return DeleteResult.UNDO_CHOICE
            }
            // 弹出栈顶一个 DigitPressed 命令（逐位退格，不一次性弹出全部）
            if (undoManager.isNotEmpty() && undoManager.peek() is T9Command.DigitPressed) {
                undoManager.pop()
            }
            inputBuffer = inputBuffer.removeLastDigit()
            // 有已确认拼音（selections 非空）时保持列锁定，否则解锁
            if (inputBuffer.selections.isEmpty()) {
                leftColumnLocked = false
            }
            if (inputBuffer.isEmpty) {
                enterIdle()
            } else if (inputBuffer.unassigned.isEmpty() && inputBuffer.consumedCount > 0
                && inputBuffer.selections.isEmpty() && undoManager.hasPendingRightCommit()) {
                // 僵尸 RC 状态：所有未分配数字已删完，但 consumed 部分仍存在。
                // 进入 SELECTION 态显示栈顶 RC 消费的数字段候选，高亮已提交拼音。
                // 多 RC 叠加时，仅取栈顶 RC 消费的部分（如"策"消费"23"），
                // 而非全部 consumed 数字段（如"546946423"含"进行"+"策"两次提交）。
                val topRC = undoManager.peek() as? T9Command.RightCommit
                val prevConsumedCount = topRC?.prevBuffer?.consumedCount ?: 0
                val rcDigitStart = prevConsumedCount.coerceAtMost(inputBuffer.digitSequence.length)
                val rcDigitEnd = inputBuffer.consumedCount.coerceAtMost(inputBuffer.digitSequence.length)
                val consumedDigits = if (rcDigitStart < rcDigitEnd) {
                    inputBuffer.digitSequence.substring(rcDigitStart, rcDigitEnd)
                } else {
                    inputBuffer.digitSequence.take(inputBuffer.consumedCount)
                }
                val options = T9PinyinMap.firstSyllableOptions(consumedDigits)
                val committedOption = options.firstOrNull { it.digitLength == consumedDigits.length }
                    ?: options.firstOrNull()
                if (committedOption != null) {
                    enterSelection(committedOption, consumedDigits)
                }
                updateCandidates(force = true)
            } else {
                updateCandidates()
            }
            sendToRime()
            return DeleteResult.DELETED
        }

        // 无未分配数字 → 执行栈顶命令的 undo
        if (undoManager.isNotEmpty()) {
            val ctx = undoCtx()
            val isSep = top is T9Command.Separator
            val isLC = top is T9Command.LeftChoice
            undoManager.popAndUndo(ctx)
            // undo LeftChoice 后清除 stale DigitPressed，避免后续退格时优先删数字而非撤销 Separator
            if (isLC) {
                undoManager.removeLastDigitPressed()
            }
            applyUndoCtx(ctx)
            val isRC = top is T9Command.RightCommit
            if (!isRC) {
                sendToRime()
            }
            return if (isRC) DeleteResult.UNDO_COMMIT else DeleteResult.UNDO_CHOICE
        }

        // 无命令 → 逐位删字符（字母/符号）
        if (!inputBuffer.isEmpty) {
            inputBuffer = inputBuffer.removeLastDigit()
            leftColumnLocked = false
            if (inputBuffer.isEmpty) enterIdle() else updateCandidates()
            sendToRime()
            return DeleteResult.DELETED
        }

        return DeleteResult.NOT_CONSUMED
    }

    /** 将命令 undo 后的 Ctx 状态同步回控制器字段 */
    private fun applyUndoCtx(ctx: T9Command.Ctx) {
        inputBuffer = ctx.buffer
        leftColumnLocked = ctx.leftColumnLocked
        separatorConsumedDigits = ctx.separatorConsumedDigits
        lastChoiceConsumedDigits = ctx.lastChoiceConsumedDigits
        syncStateFromMachine()
        updateCandidates(force = true)
    }

    private val rightCommitHandler = T9RightCommitHandler()

    fun onRightCandidateSelected(): Boolean = onRightCandidateSelected(null)

    /** 右侧候选选词（partial commit），返回 true = 完整消费。 */
    fun onRightCandidateSelected(candidatePinyin: String?, candidateTextLength: Int = 0): Boolean {
        if (inputBuffer.isEmpty) return true

        // 全简拼无候选 → enterLike 提交（需控制器级别的 resetState）
        // 不清除 RIME：服务层需要 composition 完整以便 selectCandidate + commit
        if (candidatePinyin.isNullOrBlank() && stateMachine.selectionHistory.isNotEmpty() &&
            stateMachine.selectionHistory.all { it.digitLength == 1 }) {
            resetState(clearCache = true, clearRime = false)
            return true
        }

        var ctxRef: T9RightCommitHandler.Ctx? = null
        val ctx = T9RightCommitHandler.Ctx(
            inputBuffer = inputBuffer,
            leftColumnLocked = leftColumnLocked,
            separatorConsumedDigits = separatorConsumedDigits,
            lastChoiceConsumedDigits = lastChoiceConsumedDigits,
            stateMachine = stateMachine,
            undoManager = undoManager,
            syncState = { syncStateFromMachine() },
            updateCandidates = { force ->
                leftColumnLocked = ctxRef!!.leftColumnLocked
                updateCandidates(force)
            },
            setRimeInput = { rimeBridge.setLastRimeInput(it) },
        )
        ctxRef = ctx

        val result = rightCommitHandler.onRightCandidateSelected(ctx, candidatePinyin, candidateTextLength)

        // handler 已直接操作 T9Buffer，直接同步回控制器
        inputBuffer = ctx.inputBuffer
        leftColumnLocked = ctx.leftColumnLocked
        separatorConsumedDigits = ctx.separatorConsumedDigits
        lastChoiceConsumedDigits = ctx.lastChoiceConsumedDigits
        syncStateFromMachine()
        updateCandidates(force = true)
        // 不调用 sendToRime()：服务层需要 RIME composition 保持完整，
        // 以便在 onRightCandidateSelected 返回后调用 rimeEngine.selectCandidate + commit。
        // full commit：服务 commit 后通过 t9ResetSignal 触发 controller.reset()
        // partial commit：服务 commit 后调用 forceSendToRime() 发送剩余数字到 RIME

        return result
    }

    fun clearRimeAndResend() {
        rimeBridge.clearRimeAndResend()
        sendToRime()
    }

    fun clearAll() = resetState(clearCache = false, clearRime = true)

    fun onEnterCommit() = resetState(clearCache = true, clearRime = true)

    private fun resetState(clearCache: Boolean, clearRime: Boolean) {
        inputBuffer = T9Buffer.EMPTY
        firstOptions = emptyList()
        leftColumnLocked = false
        stateMachine.enterIdle()
        syncStateFromMachine()
        undoManager.clear()
        separatorConsumedDigits = null
        lastChoiceConsumedDigits = null
        if (clearCache) {
            rimeBridge.setLastRimeInput(null)
            cachedDigitSegment = null
            cachedFirstOptions = emptyList()
        }
        if (clearRime) {
            rimeBridge.replaceFullPinyin(CLEAR_ALL)
        }
    }
}