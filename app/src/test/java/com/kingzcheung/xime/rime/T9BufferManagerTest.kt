package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * T9Buffer 单元测试。
 *
 * 覆盖 T9Buffer 核心数据模型的基本操作。
 * （遗留的 DigitStream / BufferParts / 字符串函数已在步骤7中删除）
 */
class T9BufferManagerTest {

    // ── T9Buffer 基本操作 ──

    @Test
    fun `buffer addDigit extends digitSequence`() {
        val buf = T9Buffer().addDigit("5").addDigit("4")
        assertEquals("54", buf.digitSequence)
    }

    @Test
    fun `buffer addSelection updates selections and consumedCount`() {
        val buf = T9Buffer("54482").addSelection("ji", 2)
        assertEquals(1, buf.selections.size)
        assertEquals("ji", buf.selections[0].pinyin)
        assertEquals(2, buf.consumedCount)
        assertEquals("482", buf.unassigned)
    }

    @Test
    fun `buffer removeLastDigit shortens sequence`() {
        val buf = T9Buffer("54482").removeLastDigit()
        assertEquals("5448", buf.digitSequence)
    }

    @Test
    fun `buffer toBufferString with selections`() {
        val buf = T9Buffer("54482", selections = listOf(T9Buffer.Selection("ji", 2)), consumedCount = 2)
        assertEquals("ji'482", buf.toBufferString())
    }

    @Test
    fun `buffer toPreeditString with selections`() {
        val buf = T9Buffer("54482", selections = listOf(T9Buffer.Selection("ji", 2)), consumedCount = 2)
        assertEquals("ji'482", buf.toPreeditString())
    }
}