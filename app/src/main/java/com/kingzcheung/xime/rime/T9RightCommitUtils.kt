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
 * 判断候选词各音节"简拼首字母数字码"是否逐位对齐整个 buffer 的数字码。
 *
 * 用于场景19 等"全拼选中项被候选词多音节简拼消费"的边界条件：
 * 候选词的每个音节仅以其首字母对应的数字码消费 1 位，所有音节首字母数字码
 * 拼接后等于 buffer 的数字码 → 候选词完整覆盖 buffer，应触发 full commit。
 *
 * 典型场景（"er 儿"边界条件）：
 *   buffer selectedPinyin="khe" → 数字码 "543"（k=5, h=4, e=3）
 *   候选"卡哈尔"(comment="ka ha er")：ka→k(5)、ha→h(4)、er→e(3)
 *   三音节简拼首字母数字码 = "543" == buffer 数字码 → full commit
 *
 * 此时全拼选中项 `he`(digits 43) 被候选词的 `ha`+`er` 两个简拼音节拆分消费，
 * 既有 [isAllSelectedConsumed] 因 `commentSyllables.size != selectionHistory.size`
 * 失败，[handleConsumedAllNonSelected] 的精确/简拼缩写匹配也因 `er`→"37" 既不
 * 等于 `he`→"43"（精确）也不满足 `he.digitLength==1`（简拼缩写）而失败。
 *
 * 判定条件（同时满足）：
 *   1. 候选音节数 == buffer 数字码位数（每个音节恰消费 1 位）
 *   2. 每个音节首字母数字码 == buffer 对应位数字
 *
 * 这与设计方案 §6.6 "全量提交精确匹配规则" 不冲突：全拼选中项本身仍要求精确匹配，
 * 本函数仅判定"候选词整体简拼对齐 buffer 数字码"这一多音节简拼消费情形，
 * 单音节候选（hasSyllableBoundaries=false）不进入此路径。
 *
 * @param selectedPinyin 当前 buffer 的 selectedPinyin（selections 拼接）
 * @param commentSyllables 候选词拼音注释的音节列表
 * @return true 表示候选词简拼首字母数字码逐位对齐 buffer 数字码
 */
fun isFullCommitByJianpinAlignment(
    selectedPinyin: String,
    commentSyllables: List<String>,
): Boolean {
    if (commentSyllables.isEmpty()) return false
    val bufferDigits = T9PinyinMap.pinyinToDigitCode(selectedPinyin) ?: return false
    if (commentSyllables.size != bufferDigits.length) return false
    return commentSyllables.indices.all { i ->
        val sylCode = T9PinyinMap.pinyinToDigitCode(commentSyllables[i])
        sylCode != null && sylCode.take(1) == bufferDigits[i].toString()
    }
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

/**
 * 解析 RIME 原始候选词列表中与用户选中候选词对应的 index。
 *
 * 场景19 后续修复（上屏错词问题）：
 * T9 模式下，UI 候选词列表经过 [filterCandidatesBySelectionHistory] 过滤/重排序
 * （FULL 匹配在前、PREFIX 在后、NONE 排除），其 index 可能与 RIME 原始候选词
 * index 不对应。服务层 [com.kingzcheung.xime.service.XimeInputMethodService.selectCandidateAsync]
 * 若直接用 UI index 调用 `rimeEngine.selectCandidate(index)`，RIME 会选错词 →
 * `commit()` 返回错误文本 → full commit 上屏错词。
 *
 * 例：selectionHistory=[k, he] 时：
 *   - RIME 原始列表：[考核, 恐吓, 可恨, 课后, 跨行, 卡哈尔, 开盒儿, 看会儿, 看, 可]
 *   - 过滤后列表：[考核, 恐吓, 开盒儿, 课后, 跨行, 卡哈尔, 看会儿, 看, 可]
 *     （可恨被 NONE 排除、开盒儿从 index=6 前移到 index=2）
 *   - 用户右选"开盒儿"(UI index=2) → selectCandidate(2) → RIME 选"可恨"(已排除) → 上屏错词
 *
 * 本函数通过候选词文本在 RIME 原始列表中查找真实 index，确保 RIME 选对词。
 *
 * @param uiIndex UI 显示的候选词 index（过滤/重排序后）
 * @param selectedCandidate 用户选中的候选词文本
 * @param rawCandidates RIME 原始候选词列表（来自 `RimeEngine.getCandidates()`）
 * @return RIME 原始候选词的 index；文本不在原始列表中时回退 [uiIndex]
 */
fun resolveRimeCandidateIndex(
    uiIndex: Int,
    selectedCandidate: String?,
    rawCandidates: List<String>,
): Int {
    if (selectedCandidate == null) return uiIndex
    val rawIdx = rawCandidates.indexOf(selectedCandidate)
    return if (rawIdx >= 0) rawIdx else uiIndex
}
