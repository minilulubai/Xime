package com.kingzcheung.xime.rime

/**
 * 左侧候选区三态状态机
 *
 * 封装 [T9InputController] 中左侧候选区的状态管理逻辑。
 * 严格遵循设计文档第 2.3 节"左侧选择模型"和第 3.2 节"交互状态机"。
 *
 * 设计决策：
 * - 本类为纯逻辑层，不持有 Compose 状态。
 * - [selectionHistory] 记录当前会话中每次左选确认，用于候选词过滤。
 * - [LeftSelection] 封装选中态的完整上下文（文档 2.3 节）。
 */
class T9StateMachine {

    /** 左侧候选区状态 */
    enum class State { IDLE, INPUT, SELECTION }

    /** 
     * 左侧选择模型（设计文档 2.3 节）。
     * 封装 SELECTION 态下当前选中项的完整上下文。
     */
    data class LeftSelection(
        /** 当前选中的拼音/字母 */
        val selectedOption: T9PinyinMap.SyllableOption,
        /** 选中项对应的数字子串（来自 unassigned） */
        val selectionDigits: String,
        /** 选中项之前、已由左侧选择产生的拼音序列 */
        val preSelectedPinyin: String = "",
    ) {
        val digitLength: Int get() = selectedOption.digitLength
        val pinyin: String get() = selectedOption.pinyin
    }

    /** 当前状态 */
    var state: State = State.IDLE
        private set

    /** 当前选择上下文；非 SELECTION 态时为 null */
    val leftSelection: LeftSelection?
        get() {
            val opt = selectedOption
            return if (isSelection && opt != null)
                LeftSelection(opt, selectionCandidateDigits ?: "", confirmedPinyinBeforeSelection)
            else null
        }

    /** 选择态下当前选中的拼音/字母选项 */
    var selectedOption: T9PinyinMap.SyllableOption? = null
        internal set

    /** 选择态下产生当前候选列表的数字段 */
    var selectionCandidateDigits: String? = null
        internal set

    /** 选择态下选中项之前的已确认拼音前缀 */
    var confirmedPinyinBeforeSelection: String = ""
        internal set

    /** 当前输入会话中所有已选择的拼音/字母历史（按选择顺序） */
    private val _selectionHistory: MutableList<T9PinyinMap.SyllableOption> = mutableListOf()
    val selectionHistory: List<T9PinyinMap.SyllableOption> get() = _selectionHistory

    /** 清空选择历史 */
    fun clearSelectionHistory() = _selectionHistory.clear()

    /** 移除最后一个选择历史条目 */
    fun removeLastSelectionHistoryEntry() {
        if (_selectionHistory.isNotEmpty()) _selectionHistory.removeAt(_selectionHistory.lastIndex)
    }

    /** 替换整个选择历史（用于撤销恢复） */
    fun setSelectionHistory(history: List<T9PinyinMap.SyllableOption>) {
        _selectionHistory.clear()
        _selectionHistory.addAll(history)
    }

    /** 进入选择态，记录本次选择到历史 */
    fun enterSelection(option: T9PinyinMap.SyllableOption, candidateDigits: String, confirmedPinyin: String = "") {
        state = State.SELECTION
        selectedOption = option
        selectionCandidateDigits = candidateDigits
        confirmedPinyinBeforeSelection = confirmedPinyin
        _selectionHistory.add(option)
    }

    /** 退出选择态，回到输入态（保留选择历史） */
    fun exitSelection() {
        state = State.INPUT
        selectedOption = null
        selectionCandidateDigits = null
        confirmedPinyinBeforeSelection = ""
    }

    /** 进入空闲态，清空全部状态含选择历史 */
    fun enterIdle() {
        state = State.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        confirmedPinyinBeforeSelection = ""
        _selectionHistory.clear()
    }

    /** 进入输入态（保留选择历史） */
    fun enterInput() {
        state = State.INPUT
        selectedOption = null
        selectionCandidateDigits = null
        confirmedPinyinBeforeSelection = ""
    }

    /** 完全重置，清空选择历史 */
    fun reset() {
        state = State.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        confirmedPinyinBeforeSelection = ""
        _selectionHistory.clear()
    }

    /** 状态快照，用于撤销恢复 */
    data class StateSnapshot(
        val state: State,
        val selectedOption: T9PinyinMap.SyllableOption?,
        val selectionCandidateDigits: String?,
        val confirmedPinyinBeforeSelection: String = "",
        val selectionHistory: List<T9PinyinMap.SyllableOption> = emptyList(),
    )

    /** 获取当前状态快照 */
    fun snapshot(): StateSnapshot = StateSnapshot(
        state, selectedOption, selectionCandidateDigits, confirmedPinyinBeforeSelection,
        _selectionHistory.toList(),
    )

    /** 从快照恢复状态 */
    fun restore(snapshot: StateSnapshot) {
        state = snapshot.state
        selectedOption = snapshot.selectedOption
        selectionCandidateDigits = snapshot.selectionCandidateDigits
        confirmedPinyinBeforeSelection = snapshot.confirmedPinyinBeforeSelection
        _selectionHistory.clear()
        _selectionHistory.addAll(snapshot.selectionHistory)
    }

    /** 从原始值恢复状态（用于快照撤销场景） */
    fun restoreFrom(
        state: State,
        option: T9PinyinMap.SyllableOption?,
        digits: String?,
        confirmedPinyin: String = "",
        history: List<T9PinyinMap.SyllableOption> = emptyList(),
    ) {
        this.state = state
        this.selectedOption = option
        this.selectionCandidateDigits = digits
        this.confirmedPinyinBeforeSelection = confirmedPinyin
        _selectionHistory.clear()
        _selectionHistory.addAll(history)
    }

    /** partial commit 后从 selectionHistory 头部移除已被消费的条目 */
    fun removeConsumedHistoryEntries(consumedPinyin: String) {
        var remaining = consumedPinyin
        while (_selectionHistory.isNotEmpty() && remaining.isNotEmpty()) {
            val first = _selectionHistory.first()
            if (remaining.startsWith(first.pinyin)) {
                remaining = remaining.drop(first.pinyin.length)
                _selectionHistory.removeAt(0)
            } else {
                break
            }
        }
    }

    // 便捷查询
    val isIdle: Boolean get() = state == State.IDLE
    val isInput: Boolean get() = state == State.INPUT
    val isSelection: Boolean get() = state == State.SELECTION
    val hasSelection: Boolean get() = isSelection && selectedOption != null
}
