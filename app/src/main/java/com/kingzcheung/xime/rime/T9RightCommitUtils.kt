package com.kingzcheung.xime.rime

// ─────────────────────────────────────────────────────────
// 设计文档 6.2 节：三层消费算法 — 纯函数
// ─────────────────────────────────────────────────────────

/**
 * 根据候选拼音注释计算数字段中被消费的位数。
 *
 * 逐音节匹配数字码：将 candidatePinyin 按空格拆分为音节，每个音节通过
 * [T9PinyinMap.pinyinToDigitCode] 转为数字码，与剩余数字段前缀匹配。
 * - 完全匹配：音节数字码与剩余段前缀完全一致，消费全部数字码位数
 * - 前缀匹配（声母匹配）：用户仅输入了声母对应的数字，RIME 翻译器补全了韵母，
 *   此时音节数字码的前缀与剩余段匹配，仅消费前缀长度的数字
 *   例：用户输入 9435，候选 "这里(zhe li)"，"li" 数字码 "54"，
 *   剩余 "5" 是 "54" 的前缀（声母 l 对应 digit 5），消费 1 位
 *
 * 若音节匹配失败（pinyinToDigitCode 返回 null 或无前缀匹配），
 * 回退到字母数（每个字母对应一位数字），最终回退到贪婪最长匹配。
 */
fun computeConsumedDigitsFromPinyin(segment: String, candidatePinyin: String?): Int {
    if (!candidatePinyin.isNullOrEmpty()) {
        val syllables = candidatePinyin.trim().split("\\s+".toRegex())
            .filter { it.any { c -> c.isLetter() } }
        if (syllables.isNotEmpty()) {
            var consumed = 0
            var remaining = segment
            for (syl in syllables) {
                val sylCode = T9PinyinMap.pinyinToDigitCode(syl)
                if (sylCode == null) break
                if (remaining.startsWith(sylCode)) {
                    consumed += sylCode.length
                    remaining = remaining.drop(sylCode.length)
                } else {
                    // 前缀匹配：声母场景，用户只输入了音节首字母对应的数字
                    var matchLen = 0
                    for (len in 1..minOf(sylCode.length, remaining.length)) {
                        if (remaining.startsWith(sylCode.take(len))) matchLen = len
                    }
                    if (matchLen > 0) {
                        consumed += matchLen
                        remaining = remaining.drop(matchLen)
                    } else break
                }
            }
            if (consumed > 0) return consumed
        }
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
