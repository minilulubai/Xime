package com.kingzcheung.xime.rime

/**
 * T9 输入控制器与 RIME 引擎的通信桥接
 *
 * 封装 RIME 通信相关的常量、回调管理和发送缓存。
 * 从 [T9InputController] 中提取，降低控制器复杂度。
 *
 * 设计决策：
 * - sendToRime() 核心逻辑仍留在控制器中，因为其依赖大量控制器状态
 *   （buffer、stateMachine、selectionCandidateDigits 等），
 *   提取为独立模块需要传递过多参数，反而降低可读性。
 *   后续阶段5 可考虑将 sendToRime 逻辑进一步拆分。
 * - 本类仅管理通信常量、回调引用和发送缓存。
 */
class T9RimeBridge(
    private val onReplaceFullPinyin: (String) -> Unit,
    private val onQueryRimeComposition: (() -> RimeComposition)? = null,
    private val onRightCommitUndone: ((Int) -> Unit)? = null,
) {
    companion object {
        /** 仅清除 RIME composition，保留 partial commit 累积文本 */
        const val CLEAR_COMPOSITION_ONLY = "\u0000CLEAR_COMPOSITION_ONLY"

        /** 清除所有内容（composition + partial commit 累积文本） */
        const val CLEAR_ALL = "\u0000CLEAR_ALL"
    }

    /** 上次发送到 RIME 的输入，用于去重 */
    private var lastRimeInput: String? = null

    /** 获取上次发送的输入 */
    fun getLastRimeInput(): String? = lastRimeInput

    /** 设置上次发送的输入 */
    fun setLastRimeInput(input: String?) {
        lastRimeInput = input
    }

    /** 发送全拼音替换到 RIME 引擎 */
    fun replaceFullPinyin(pinyin: String) {
        onReplaceFullPinyin(pinyin)
    }

    /** 查询 RIME 当前 composition */
    fun queryRimeComposition(): RimeComposition? = onQueryRimeComposition?.invoke()

    /** 通知服务层撤销右侧候选选词 */
    fun undoRightCommit(count: Int = 1) {
        onRightCommitUndone?.invoke(count)
    }

    /**
     * 强制重新发送当前 inputBuffer 到 RIME，绕过发送缓存。
     */
    fun forceSendToRime() {
        lastRimeInput = null
    }

    /**
     * 清除 RIME composition 并重新发送当前 buffer。
     * 先撤销一个 partial commit（从 partial commit 列表中移除），
     * 再清 RIME composition，最后送完整数字序列（setInput）。
     */
    fun clearRimeAndResend() {
        onRightCommitUndone?.invoke(1)
        lastRimeInput = null
        onReplaceFullPinyin("")
    }

    /**
     * 优先通过 RIME 候选 comment 反推当前数字段的最优首音节。
     *
     * 取当前 composition 第一个带 comment 的候选，将其 comment（如 "ji gua"）
     * 按空格拆分为音节，取第一个音节并验证其数字编码是否与 [digits] 前缀匹配。
     * 若 RIME 未返回有效 comment 或匹配失败，则回退到本地贪婪最长匹配。
     */
    fun inferFirstSyllableFromRime(digits: String): T9PinyinMap.SyllableOption? {
        val composition = queryRimeComposition() ?: return fallbackFirstSyllable(digits)

        val comment = composition.candidates
            .firstOrNull { it.comment.isNotBlank() }
            ?.comment
            ?.trim()
            ?: return fallbackFirstSyllable(digits)

        val firstPinyin = comment.split(Regex("\\s+"))
            .firstOrNull { it.isNotEmpty() }
            ?: return fallbackFirstSyllable(digits)

        val code = T9PinyinMap.pinyinToDigitCode(firstPinyin)
        if (code != null && digits.startsWith(code)) {
            return T9PinyinMap.SyllableOption(firstPinyin.lowercase(), code.length)
        }
        return fallbackFirstSyllable(digits)
    }

    private fun fallbackFirstSyllable(digits: String): T9PinyinMap.SyllableOption? {
        return T9PinyinMap.firstSyllableOptions(digits, maxResults = 1).firstOrNull()
    }
}