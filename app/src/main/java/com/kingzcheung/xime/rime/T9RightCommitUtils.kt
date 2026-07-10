package com.kingzcheung.xime.rime

// ─────────────────────────────────────────────────────────
// 设计文档 6.2 节：三层消费算法 — 纯函数
// ─────────────────────────────────────────────────────────

/**
 * 根据候选拼音注释计算数字段中被消费的位数。
 *
 * 优先使用 candidatePinyin 中的字母数（每个字母对应一位数字），
 * 若 candidatePinyin 为空或无字母，则回退到贪婪最长匹配。
 */
fun computeConsumedDigitsFromPinyin(segment: String, candidatePinyin: String?): Int {
    if (!candidatePinyin.isNullOrEmpty()) {
        val letterCount = candidatePinyin.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (letterCount > 0 && letterCount <= segment.length) return letterCount
    }
    return T9PinyinMap.firstSyllableOptions(segment, maxResults = 1).firstOrNull()?.digitLength ?: 0
}

/**
 * 判断 selectionHistory 是否完全覆盖 inputBuffer，且候选词的 comment 音节与选择历史逐位对齐。
 *
 * 当 buffer 完全由 selectionHistory 的拼音拼接构成，且候选词的 comment 音节数字码
 * 与 selectionHistory 的数字码逐位对齐时，表示候选词覆盖了所有已确认选择，
 * 应触发全量提交（full commit）。
 *
 * @param inputBuffer 当前输入缓冲区
 * @param commentSyllables 候选词拼音注释的音节列表
 * @param selectionHistory 左侧已确认的选择历史
 * @return true 表示候选词完全覆盖了所有已确认选择
 */
fun isAllSelectedConsumed(
    inputBuffer: String,
    commentSyllables: List<String>,
    selectionHistory: List<T9PinyinMap.SyllableOption>,
): Boolean {
    if (selectionHistory.isEmpty()) return false
    if (selectionHistory.joinToString("") { it.pinyin } != inputBuffer) return false
    if (commentSyllables.size != selectionHistory.size) return false
    return commentSyllables.indices.all { i ->
        val sylCode = T9PinyinMap.pinyinToDigitCode(commentSyllables[i])
        val selCode = T9PinyinMap.pinyinToDigitCode(selectionHistory[i].pinyin)
        sylCode != null && selCode != null && sylCode == selCode
    }
}

/**
 * 判断在 SELECTION 保护路径中是否应触发 full commit。
 *
 * 当非选中部分已被完全消费、remainingAfterCommit 为空（计算消费声明全部消费），
 * 且候选词注释中包含选中部分的拼音音节（数字码匹配）时，说明候选词覆盖了全部输入，
 * 应触发 full commit 而非 partial commit。
 *
 * @param consumedFromNonSelected 已从非选中部分消费的字母数
 * @param nonSelectedPinyinLength 非选中部分的拼音长度
 * @param remainingAfterCommit computeRightCommitConsumption 返回的剩余数字段
 * @param candidatePinyin 候选词拼音注释
 * @param selectedPinyin 当前选中项的拼音
 * @return true 表示应触发 full commit
 */
fun shouldFullCommitInSelection(
    consumedFromNonSelected: Int,
    nonSelectedPinyinLength: Int,
    remainingAfterCommit: String,
    candidatePinyin: String?,
    selectedPinyin: String,
): Boolean {
    if (consumedFromNonSelected < nonSelectedPinyinLength) return false
    if (remainingAfterCommit.isNotEmpty()) return false
    val commentSyllables = candidatePinyin?.trim()?.split("\\s+".toRegex())
        ?.filter { it.any { c -> c.isLetter() } } ?: emptyList()
    return commentSyllables.any { T9PinyinMap.areDigitCodesMatching(it, selectedPinyin) }
}
