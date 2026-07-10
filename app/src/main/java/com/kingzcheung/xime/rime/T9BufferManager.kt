package com.kingzcheung.xime.rime

// ─────────────────────────────────────────────────────────
// 设计文档 §2.2：T9Buffer — 结构化输入模型（替代纯字符串 buffer）
//
// 核心设计：
//   - digitSequence: 用户输入的全部数字（如 "54482"）
//   - selections:    已确认的拼音选择，按顺序排列
//   - consumedCount: 已被 LeftSelect / RightCommit 消费的数字总数
//
// 字符串 buffer 变为导出属性 toBufferString()，
// 不再需要 parseBuffer() / digitStreamFrom() 等逆向解析。
//
// 设计文档 §2.2 原 DigitStream 模型已合并到此模型中。
// ─────────────────────────────────────────────────────────

/**
 * T9 输入的完整结构化模型。
 *
 * 解决设计文档 §1.1 的两个根因缺陷：
 *   缺陷 1（Buffer 类型混杂）：digitSequence（数字）与 selections（字母）显式分离
 *   缺陷 2（' 语义过载）：分隔符不再存储，仅在 toBufferString() 中按规则生成
 *
 * 使用示例：
 *   T9Buffer("54482").addSelection("ji", 2) → digits=54482, sel=[ji(2)], consumed=2
 *   .toBufferString() → "ji'482"
 */
data class T9Buffer(
    /** 用户输入的全部数字序列，如 "54482" */
    val digitSequence: String = "",
    /** 已确认的拼音选择，按选择时间排序 */
    val selections: List<Selection> = emptyList(),
    /** 已被 LeftSelect / RightCommit 消费的数字总数 */
    val consumedCount: Int = 0,
    /** 累计输入的数字总数（退格不会减少），用于判断 digitSequence 是否被缩短过 */
    val totalDigitsEntered: Int = digitSequence.length,
) {
    /** 单次拼音选择 */
    data class Selection(
        val pinyin: String,
        val digitLen: Int,
    ) {
        val isAbbreviation: Boolean get() = pinyin.length == 1
    }

    // ── 导出属性 ──

    /** 尚未被消费的数字段 */
    val unassigned: String get() = digitSequence.drop(consumedCount)

    val isFullyConsumed: Boolean get() = consumedCount >= digitSequence.length
    val isEmpty: Boolean get() = digitSequence.isEmpty()

    /** 所有已选拼音拼接（不含分隔符） */
    val selectedPinyin: String get() = selections.joinToString("") { it.pinyin }

    // ── 不可变突变方法 ──

    /** 追加一位数字 */
    fun addDigit(d: String) = copy(
        digitSequence = digitSequence + d,
        totalDigitsEntered = maxOf(totalDigitsEntered, digitSequence.length + 1),
    )

    /** 消费 n 位数字（不关联到具体拼音选择） */
    fun consume(n: Int) = copy(consumedCount = consumedCount + n)

    /** 添加拼音选择并消费对应位数 */
    fun addSelection(pinyin: String, digitLen: Int) = copy(
        selections = selections + Selection(pinyin, digitLen),
        consumedCount = consumedCount + digitLen,
    )

    /** 替换最后一个选择（同位数替换时合并撤销） */
    fun replaceLastSelection(pinyin: String, digitLen: Int): T9Buffer {
        val prev = selections.lastOrNull() ?: return this
        return copy(
            selections = selections.dropLast(1) + Selection(pinyin, digitLen),
            consumedCount = consumedCount - prev.digitLen + digitLen,
        )
    }

    /** 添加选择但不增加消费计数（用于替换分词键已消费的数字） */
    fun addSelectionNoConsume(pinyin: String, digitLen: Int) = copy(
        selections = selections + Selection(pinyin, digitLen),
    )

    /** 撤销最后一个选择并回退消费 */
    fun undoLastSelection(): T9Buffer {
        val prev = selections.lastOrNull() ?: return this
        return copy(
            selections = selections.dropLast(1),
            consumedCount = consumedCount - prev.digitLen,
        )
    }

    /** 清空全部状态 */
    fun clear() = T9Buffer()

    /** 移除最后一位数字（退格删除）。若删除导致消费数超出，则裁剪选择 */
    fun removeLastDigit(): T9Buffer {
        if (digitSequence.isEmpty()) return this
        val newSeq = digitSequence.dropLast(1)
        val newConsumed = consumedCount.coerceAtMost(newSeq.length)
        var acc = 0
        val kept = selections.takeWhile { sel ->
            acc += sel.digitLen
            acc <= newConsumed
        }
        return copy(digitSequence = newSeq, selections = kept, consumedCount = newConsumed)
    }

    // ── 导出字符串（向后兼容） ──

    /**
     * 重建传统 inputBuffer 字符串。
     *
     * 分隔符插入规则：
     *   - 每个选择后，若后续还有数字（已消费或未消费），则加 ' 分隔
     *   - 连续简拼选择间不加 '（通过 toPreeditString 处理 RIME 端的分隔）
     */
    fun toBufferString(): String {
        if (selections.isEmpty() && consumedCount == 0) return digitSequence
        // 无选择时只展示剩余未分配数字（right-commit 已消费的数字不显示）
        if (selections.isEmpty()) return unassigned
        if (isFullyConsumed && selections.sumOf { it.digitLen } == consumedCount) {
            // 仅当 digitSequence 被退格缩短过（有原始数字被删除）时才保留尾随 '
            if (digitSequence.length < totalDigitsEntered) {
                return selectedPinyin + "'"
            }
            return selectedPinyin
        }

        // offset: 被非 selection 方式（right-commit / 分词键）消费的前导数字数
        // selections 在 digitSequence 中的实际起始位置 = offset
        val offset = (consumedCount - selections.sumOf { it.digitLen }).coerceAtLeast(0)
        val sb = StringBuilder()
        var pos = offset
        for (i in selections.indices) {
            val sel = selections[i]
            sb.append(sel.pinyin)
            pos += sel.digitLen
            // 仅当存在未分配数字（pos 已进入 unconsumed 区）时才添加分隔符
            if (pos >= consumedCount && pos < digitSequence.length) {
                val nextIsAbbrev = (i + 1 < selections.size) && selections[i + 1].isAbbreviation
                if (!sel.isAbbreviation || !nextIsAbbrev) {
                    sb.append("'")
                }
            }
        }
        // 未消费数字
        sb.append(unassigned)
        return sb.toString()
    }

    /**
     * 计算发给 RIME 引擎的预编辑字符串。
     *
     * 与 [toBufferString] 的区别：
     *   - 连续简拼选择间加 ' 分隔，避免 RIME 合并为全拼音节（如 "l'i'" vs "li"）
     *   - 全拼选择后不加 '（RIME 自动识别音节边界）
     */
    fun toPreeditString(): String {
        if (selections.isEmpty()) return unassigned

        val sb = StringBuilder()
        for (i in selections.indices) {
            val cur = selections[i]
            if (i > 0) sb.append("'")
            sb.append(cur.pinyin)
        }
        if (!isFullyConsumed) {
            if (selections.isNotEmpty()) sb.append("'")
            sb.append(unassigned)
        }
        return sb.toString()
    }

    companion object {
        /** 空 buffer */
        val EMPTY = T9Buffer()
    }
}

// ─────────────────────────────────────────────────────────
// 设计文档 2.2 节：DigitStream — 数字流核心数据模型
// ─────────────────────────────────────────────────────────

/**
 * 右侧提交模型（设计文档 2.4 节）。
 *
 * 描述右侧候选词选择后的消费结果：
 *   consumedDigits: 被消费的数字段
 *   remainingAfterCommit: 未被消费的数字段
 *   committedText: 已上屏的汉字
 *   isPartialCommit: 是否部分消费
 */
data class RightCommit(
    val consumedDigits: String,
    val remainingAfterCommit: String,
    val committedText: String = "",
    val isPartialCommit: Boolean = false,
)

// ─────────────────────────────────────────────────────────
// 设计文档 2.5 节：导出计算（纯函数）
// ─────────────────────────────────────────────────────────

// ── preeditString（T9Buffer 版本） ──

/**
 * 计算预编辑文本（发给 RIME 的字符串），使用 [T9Buffer] 结构化模型。
 *
 * 设计文档 2.5 节。
 */
fun preeditString(buf: T9Buffer): String {
    if (buf.isEmpty) return ""
    return buf.toPreeditString()
}

/**
 * 计算左侧候选区拼音列表（[T9Buffer] 版本）。
 */
fun leftCandidates(
    buf: T9Buffer,
    state: T9StateMachine.State,
    selectionDigits: String?,
): List<T9PinyinMap.SyllableOption> {
    if (state == T9StateMachine.State.IDLE) return emptyList()
    val digits = buf.unassigned.ifEmpty { selectionDigits ?: return emptyList() }
    return T9PinyinMap.firstSyllableOptions(digits)
}

/**
 * 候选词与选择历史的匹配级别。
 *
 * - [FULL]:     候选词的拼音注释匹配了 selectionHistory 中的全部音节
 * - [PREFIX]:   候选词的拼音注释仅匹配了 selectionHistory 的前缀部分
 * - [NONE]:     不匹配
 */
enum class MatchLevel { FULL, PREFIX, NONE }

/**
 * 根据左侧选择历史过滤候选词，分两层返回：全匹配 + 前缀匹配。
 *
 * 全匹配组（匹配所有 selectionHistory）排在前面，
 * 前缀匹配组（仅匹配 selectionHistory 的某个前缀）排在后面，
 * 确保精确匹配优先，同时不丢失单字候选。
 *
 * 例：selectionHistory=[jiu, jian] 时，
 *   "就见(jiu jian)" 为 FULL 匹配，
 *   "九(jiu)" 为 PREFIX 匹配（仅匹配了 jiu）。
 */
fun filterCandidatesBySelectionHistory(
    candidates: List<String>,
    comments: List<String>,
    selectionHistory: List<T9PinyinMap.SyllableOption>,
): Pair<List<String>, List<String>> {
    if (selectionHistory.isEmpty()) return candidates to comments

    // 预计算 selectionHistory 的数字码，避免对每个候选词重复计算
    val selCodes = selectionHistory.map { T9PinyinMap.pinyinToDigitCode(it.pinyin) }

    val fullTexts = mutableListOf<String>()
    val fullComments = mutableListOf<String>()
    val prefixTexts = mutableListOf<String>()
    val prefixComments = mutableListOf<String>()

    for (i in candidates.indices) {
        val comment = comments.getOrElse(i) { "" }
        when (matchCandidateComment(comment, selectionHistory, selCodes)) {
            MatchLevel.FULL -> {
                fullTexts.add(candidates[i])
                fullComments.add(comment)
            }
            MatchLevel.PREFIX -> {
                prefixTexts.add(candidates[i])
                prefixComments.add(comment)
            }
            MatchLevel.NONE -> { /* 排除 */ }
        }
    }

    val resultTexts = fullTexts + prefixTexts
    val resultComments = fullComments + prefixComments
    if (resultTexts.isEmpty()) return candidates to comments
    return resultTexts to resultComments
}

/**
 * 判断候选词的拼音注释与选择历史的匹配级别。
 *
 * 逐音节对齐校验：每个选择匹配到 comment 中对应位置的音节。
 * 全拼要求数字码完全相等（允许 he 匹配 ge，因为同码 43），
 * 简拼只求数字码前缀匹配。
 *
 * - FULL:   comment 中每个音节都能匹配 selectionHistory 中的对应选择，
 *           且所有 selectionHistory 都被匹配
 * - PREFIX: 仅匹配了 selectionHistory 的某个前缀（comment 音节数少于
 *           selectionHistory 的匹配数，但已匹配部分全部正确）
 * - NONE:   存在匹配失败的 selectionHistory 条目
 *
 * @param selCodes selectionHistory 对应的数字码列表（由调用方预计算复用）
 */
private fun matchCandidateComment(
    comment: String,
    selectionHistory: List<T9PinyinMap.SyllableOption>,
    selCodes: List<String?>,
): MatchLevel {
    val syllables = comment.trim()
        .split("[\\s']+".toRegex())
        .filter { it.any { c -> c.isLetter() } }
    if (syllables.isEmpty()) return MatchLevel.FULL

    var syllableIdx = 0
    var matchedCount = 0
    for (idx in selectionHistory.indices) {
        val sel = selectionHistory[idx]
        val selCode = selCodes[idx] ?: return MatchLevel.FULL
        var matched = false
        while (syllableIdx < syllables.size) {
            val sylCode = T9PinyinMap.pinyinToDigitCode(syllables[syllableIdx])
                ?: run { syllableIdx++; continue }
            if (sylCode.startsWith(selCode)) {
                if (sel.pinyin.length > 1 && sylCode != selCode) return MatchLevel.NONE
                syllableIdx++
                matched = true
                break
            }
            syllableIdx++
        }
        if (matched) {
            matchedCount++
        } else {
            // selectionHistory 未全部匹配：前面有成功匹配 → PREFIX，否则 → NONE
            return if (matchedCount > 0) MatchLevel.PREFIX else MatchLevel.NONE
        }
    }
    return MatchLevel.FULL
}
