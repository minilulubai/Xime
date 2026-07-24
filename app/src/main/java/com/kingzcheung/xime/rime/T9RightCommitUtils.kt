package com.kingzcheung.xime.rime

// ─────────────────────────────────────────────────────────
// 设计文档 6.2 节：三层消费算法 — 纯函数
// ─────────────────────────────────────────────────────────

// ── 公共扩展：候选拼音解析 ──

/** 将候选词拼音注释按空格拆分为音节列表（仅含字母的音节） */
internal fun String?.parseSyllables(): List<String> =
    this?.trim()?.split("\\s+".toRegex())
        ?.filter { it.any { c -> c.isLetter() } } ?: emptyList()

/** 候选词拼音注释中的字母数（不含空格） */
internal fun String?.candidateLetterCount(): Int =
    this?.count { it.isLetter() } ?: 0

/** 候选词拼音注释中仅保留字母（去除空格） */
internal fun String?.candidateLettersOnly(): String =
    this?.filter { it.isLetter() } ?: ""

// ── 消费计算 ──

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
fun computeConsumedDigitsFromPinyin(
    segment: String,
    candidatePinyin: String?,
    allowLongestPrefix: Boolean = false,
): Int {
    if (!candidatePinyin.isNullOrEmpty()) {
        val syllables = candidatePinyin.parseSyllables()
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
                    var matchLen = 0
                    if (remaining.length >= sylCode.length) {
                        // 精确匹配失败。derive rule 场景（digitSegment）使用最长公共前缀，
                        // 避免 RIME 扩展拼音导致 T9 码不匹配（如 "yong"→"9664" vs "966"）。
                        // apostrophe 场景仅 1 字符声母匹配，防止过度消费。
                        if (allowLongestPrefix) {
                            for (len in (sylCode.length - 1) downTo 1) {
                                if (remaining.startsWith(sylCode.take(len))) {
                                    matchLen = len
                                    break
                                }
                            }
                        }
                        if (matchLen == 0 && remaining.startsWith(sylCode.take(1))) {
                            matchLen = 1
                        }
                    } else {
                        // apostrophe 场景仅匹配 1 字符声母，防止过度消费。
                        // 例："guang"(48264) 匹配剩余 "482" 时，仅匹配声母 "4"(1 位)，
                        // 而非最长匹配 "482"(3 位)，否则 "64" 无法与输入序列对应。
                        if (allowLongestPrefix) {
                            // 统一递减方向：最长匹配优先，找到即 break，与上方分支一致。
                            for (len in remaining.length downTo 1) {
                                if (sylCode.startsWith(remaining.take(len))) {
                                    matchLen = len
                                    break
                                }
                            }
                        } else if (remaining.isNotEmpty() && sylCode.startsWith(remaining.take(1))) {
                            matchLen = 1
                        }
                    }
                    if (matchLen > 0) {
                        consumed += matchLen
                        remaining = remaining.drop(matchLen)
                    } else break
                }
            }
            if (consumed > 0) return consumed
        }
        val letterCount = candidatePinyin.candidateLetterCount()
        if (letterCount > 0 && letterCount <= segment.length) return letterCount
    }
    return T9PinyinMap.firstSyllableOptions(segment, maxResults = 1).firstOrNull()?.digitLength ?: 0
}

// ── 首音节跨越 selection 边界调整 ──

/**
 * 当第一音节跨越 selection 边界时，调整 consumedFromUnassigned 值。
 *
 * 第一音节的 digit code 精确匹配 effectiveSequence 前缀但长度超过
 * 第一 selection 的 digit code 时，说明它跨越了 selection 边界，
 * 错误地消费了后续 selection 的 digit code。此时限制只消费第一
 * selection 的 digit code，剩余音节在剩余 effectiveSequence 中重匹配。
 *
 * 典型场景：54482 左选 j→g，右选"机构化(ji gou hua)"
 *   "ji"(54) 精确匹配 "54482" 前缀（"j"+"g" 的拼接 "54"），
 *   但应只消费 "j"(5) 而非 "j"+"g"(54)，否则后续消费错位。
 *
 * @param candidatePinyin 候选词拼音注释
 * @param effectiveSequence selectionDigits 与 unassigned 的拼接
 * @param selectionDigits 已确认 selection 的 digit code 拼接
 * @param unassigned 未分配数字段
 * @param currentConsumedFromUnassigned 当前计算的 consumedFromUnassigned
 * @param firstSelectionPinyin 第一 selection 的拼音
 * @return 调整后的 consumedFromUnassigned
 */
fun adjustConsumedForSelectionBoundary(
    candidatePinyin: String?,
    effectiveSequence: String,
    selectionDigits: String,
    unassigned: String,
    currentConsumedFromUnassigned: Int,
    firstSelectionPinyin: String,
): Int {
    if (currentConsumedFromUnassigned < 0) return currentConsumedFromUnassigned
    val syls = candidatePinyin.parseSyllables()
    if (syls.isEmpty()) return currentConsumedFromUnassigned

    val firstSylCode = T9PinyinMap.pinyinToDigitCode(syls[0])
    val firstSelCode = T9PinyinMap.pinyinToDigitCode(firstSelectionPinyin)
    if (firstSylCode == null || firstSelCode == null) return currentConsumedFromUnassigned
    if (firstSylCode.length <= firstSelCode.length) return currentConsumedFromUnassigned
    if (!effectiveSequence.startsWith(firstSylCode)) return currentConsumedFromUnassigned

    // 第一音节只消费第一 selection 的 digit code，剩余音节重匹配
    val remainingSyls = syls.drop(1).joinToString(" ")
    val remainingEff = effectiveSequence.drop(firstSelCode.length)
    val restConsumed = computeConsumedDigitsFromPinyin(remainingEff, remainingSyls)
    val newTotalConsumed = firstSelCode.length + restConsumed
    return maxOf(0, newTotalConsumed - selectionDigits.length)
}

/**
 * 首音节完全匹配守卫：当第一音节完全匹配 effectiveSequence 前缀但第二音节
 * 无法匹配剩余 unassigned 时，限制消费仅到第一音节在 unassigned 中的部分。
 *
 * 避免声母前缀过度消费。典型场景：54482 左选 j → 右选"机会 ji hui"
 * "ji"(54) 完全匹配 "54482" 但 "5" 已被 selection 消费，
 * "hui"(484) 前缀匹配 "482" 的 "4" → 过度消费 → 需拦截。
 *
 * @param candidatePinyin 候选词拼音注释
 * @param effectiveSequence selectionDigits 与 unassigned 的拼接
 * @param selectionDigits 已确认 selection 的 digit code 拼接
 * @param unassigned 未分配数字段
 * @param consumedFromUnassigned 当前计算的 consumedFromUnassigned
 * @return 调整后的 consumedFromUnassigned
 */
fun adjustForSecondSyllableMismatch(
    candidatePinyin: String?,
    effectiveSequence: String,
    selectionDigits: String,
    unassigned: String,
    consumedFromUnassigned: Int,
): Int {
    if (consumedFromUnassigned <= 0) return consumedFromUnassigned
    val syls = candidatePinyin.parseSyllables()
    if (syls.size <= 1) return consumedFromUnassigned

    val first = T9PinyinMap.pinyinToDigitCode(syls[0])
    val second = T9PinyinMap.pinyinToDigitCode(syls[1])
    if (first == null || second == null) return consumedFromUnassigned
    if (!effectiveSequence.startsWith(first)) return consumedFromUnassigned

    val firstFromUnassign = maxOf(0, first.length - selectionDigits.length)
    if (firstFromUnassign <= 0) return consumedFromUnassigned
    if (unassigned.drop(firstFromUnassign).startsWith(second)) return consumedFromUnassigned

    return firstFromUnassign
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

// ── 三层消费算法核心计算 ──

/**
 * 计算右侧候选选词时消费的数字段和剩余段。
 *
 * 三层消费算法：
 *   层级1：apostrophe 模式（selections 非空 && unassigned 非空）
 *     从 unassigned 中按候选词数字码匹配 digit_sequence 计算消费
 *   层级2：digitSegment 模式（selections 为空 && unassigned 非空）
 *     从 candidatePinyin 逐音节匹配 unassigned 前缀
 *   层级3：letterBuffer 模式（selections 非空 && unassigned 为空）
 *     通过 pinyinToDigitCode 回退转为数字，再按数字段模式处理
 *
 * @param buf 当前 T9Buffer
 * @param candidatePinyin 候选词拼音注释
 * @return Pair<consumedDigits, remainingDigits>
 */
fun computeRightCommitConsumption(
    buf: T9Buffer,
    candidatePinyin: String?,
): Pair<String, String> {
    val hasSelections = buf.selections.isNotEmpty()
    val unassigned = buf.unassigned

    // apostrophe 模式：基于候选词实际数字码匹配 digit_sequence 计算消费
    if (hasSelections && unassigned.isNotEmpty()) {
        val selectionDigits = buf.selections.joinToString("") {
            T9PinyinMap.pinyinToDigitCode(it.pinyin) ?: ""
        }
        val effectiveSequence = selectionDigits + unassigned
        val totalConsumed = computeConsumedDigitsFromPinyin(effectiveSequence, candidatePinyin)
        if (totalConsumed > 0) {
            var consumedFromUnassigned = totalConsumed - selectionDigits.length

            // 首音节完全匹配守卫：当第一个音节完全匹配 effectiveSequence 前缀时，
            // 需要确保第二个音节在剩余 unassigned 中有对应匹配（避免过度消费）。
            if (consumedFromUnassigned > 0) {
                consumedFromUnassigned = adjustForSecondSyllableMismatch(
                    candidatePinyin = candidatePinyin,
                    effectiveSequence = effectiveSequence,
                    selectionDigits = selectionDigits,
                    unassigned = unassigned,
                    consumedFromUnassigned = consumedFromUnassigned,
                )
            }

            // 首音节跨越 selection 边界守卫：当第一音节 digit code 精确匹配
            // effectiveSequence 前缀且长度超过第一 selection 的 digit code 时，
            // 限制只消费第一 selection 的 digit code，剩余音节在剩余序列中重匹配。
            if (buf.selections.isNotEmpty()) {
                consumedFromUnassigned = adjustConsumedForSelectionBoundary(
                    candidatePinyin = candidatePinyin,
                    effectiveSequence = effectiveSequence,
                    selectionDigits = selectionDigits,
                    unassigned = unassigned,
                    currentConsumedFromUnassigned = consumedFromUnassigned,
                    firstSelectionPinyin = buf.selections.first().pinyin,
                )
            }

            if (consumedFromUnassigned in 1..unassigned.length) {
                return unassigned.take(consumedFromUnassigned) to unassigned.drop(consumedFromUnassigned)
            }
            return "" to unassigned
        }
        // fallback：数字码匹配失败，回退到字母数差值
        val candidateLetterCount = candidatePinyin.candidateLetterCount()
        val consumedAfter = (candidateLetterCount - buf.selectedPinyin.length)
            .coerceAtMost(unassigned.length)
        if (consumedAfter > 0) {
            return unassigned.take(consumedAfter) to unassigned.drop(consumedAfter)
        }
        return "" to unassigned
    }

    // digitSegment 模式
    if (!hasSelections && unassigned.isNotEmpty()) {
        val segment = unassigned
        val consumedCount = computeConsumedDigitsFromPinyin(segment, candidatePinyin, allowLongestPrefix = true)
        return segment.take(consumedCount) to segment.drop(consumedCount)
    }

    // letterBuffer 模式
    val selectedPinyin = buf.selectedPinyin
    val effectiveLetterCount = candidatePinyin.candidateLetterCount()
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
    val commentSyllables = candidatePinyin.parseSyllables()
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

// ── T9Buffer 扩展 ──

/**
 * 构造纯数字 T9Buffer（用于 digitSegment 模式的剩余数字）。
 * 保留原始 digitSequence 和 totalDigitsEntered。
 *
 * consumedCount 计算为"原有已消费数 + 本次新消费数"，
 * 即 originalBuf.consumedCount + (originalBuf.unassigned.length - remainingDigits.length)，
 * 语义上等价于 originalBuf.digitSequence.length - remainingDigits.length，
 * 但更清晰地表达了"追加消费"的意图。
 */
fun T9Buffer.withRemainingDigits(
    remainingDigits: String,
    originalBuf: T9Buffer,
): T9Buffer {
    if (remainingDigits.isEmpty()) return T9Buffer.EMPTY
    val newlyConsumed = originalBuf.unassigned.length - remainingDigits.length
    return T9Buffer(
        digitSequence = originalBuf.digitSequence,
        selections = emptyList(),
        consumedCount = originalBuf.consumedCount + newlyConsumed,
        totalDigitsEntered = originalBuf.totalDigitsEntered,
    )
}

// ── 音节覆盖判定 ──

/**
 * 判断候选词音节数是否足以覆盖所有已确认选择及未分配数字段。
 * apostrophe 模式下未分配数字至少需要一个额外音节。
 */
internal fun canCoverAllBySyllableCount(
    commentSyllables: List<String>,
    selectionCount: Int,
    hasUnassigned: Boolean,
): Boolean = commentSyllables.size >= selectionCount + (if (hasUnassigned) 1 else 0)

// ── SELECTION 态消费计算 ──

/**
 * 计算非选中部分被候选词消费的位数（基于音节数溢出或字母数模型）。
 */
fun computeSelectionConsumedCount(
    hasSyllableBoundaries: Boolean,
    candidateTextLength: Int,
    commentSyllables: List<String>,
    effectiveLetterCount: Int,
    prevSelectedOption: T9PinyinMap.SyllableOption,
    nonSelectedDigits: String,
    candidatePinyin: String?,
): Int {
    if (hasSyllableBoundaries && candidateTextLength > 0 && commentSyllables.size > candidateTextLength) {
        return consumeBySyllableOverflow(commentSyllables, candidateTextLength)
    }
    return consumeByLetterOrDigits(
        hasSyllableBoundaries, effectiveLetterCount, prevSelectedOption,
        commentSyllables, nonSelectedDigits, candidatePinyin,
    )
}

/** 音节数溢出：按候选字数对应的音节数字码总长度消费 */
fun consumeBySyllableOverflow(
    commentSyllables: List<String>,
    candidateTextLength: Int,
): Int {
    var consumed = 0
    for (i in 0 until minOf(candidateTextLength, commentSyllables.size)) {
        consumed += T9PinyinMap.pinyinToDigitCode(commentSyllables[i])?.length ?: 0
    }
    return consumed
}

/**
 * 字母数模型消费：以 (有效字母数 - 选中项拼音长度) 为基础，
 * 有音节边界时用音节数字码总长度校验，避免字母数低估消费量。
 */
fun consumeByLetterOrDigits(
    hasSyllableBoundaries: Boolean,
    effectiveLetterCount: Int,
    prevSelectedOption: T9PinyinMap.SyllableOption,
    commentSyllables: List<String>,
    nonSelectedDigits: String,
    candidatePinyin: String?,
): Int {
    val candidateNonSelectedLetters = effectiveLetterCount - prevSelectedOption.pinyin.length
    if (candidateNonSelectedLetters !in 1..nonSelectedDigits.length) {
        return computeConsumedDigitsFromPinyin(nonSelectedDigits, candidatePinyin)
    }
    if (!hasSyllableBoundaries) return candidateNonSelectedLetters

    val nonSelectedSyllables = if (commentSyllables.lastOrNull() == prevSelectedOption.pinyin)
        commentSyllables.dropLast(1) else commentSyllables
    val totalSyllableDigits = nonSelectedSyllables.sumOf {
        T9PinyinMap.pinyinToDigitCode(it)?.length ?: 0
    }
    return if (totalSyllableDigits > candidateNonSelectedLetters) {
        computeConsumedDigitsFromPinyin(nonSelectedDigits, candidatePinyin)
    } else {
        candidateNonSelectedLetters
    }
}
