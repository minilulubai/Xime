package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * T9RimeBridge 单元测试
 *
 * 覆盖 RIME 通信桥接的缓存管理、常量定义和回调机制。
 */
class T9RimeBridgeTest {

    private fun createBridge(
        onReplaceFullPinyin: (String) -> Unit = {},
        onQueryRimeComposition: (() -> RimeComposition)? = null,
        onRightCommitUndone: ((Int) -> Unit)? = null,
    ) = T9RimeBridge(onReplaceFullPinyin, onQueryRimeComposition, onRightCommitUndone)

    // ── 常量 ──

    @Test
    fun `CLEAR_COMPOSITION_ONLY is defined`() {
        assertNotNull(T9RimeBridge.CLEAR_COMPOSITION_ONLY)
        assertTrue(T9RimeBridge.CLEAR_COMPOSITION_ONLY.contains("CLEAR_COMPOSITION_ONLY"))
    }

    @Test
    fun `CLEAR_ALL is defined`() {
        assertNotNull(T9RimeBridge.CLEAR_ALL)
        assertTrue(T9RimeBridge.CLEAR_ALL.contains("CLEAR_ALL"))
    }

    // ── lastRimeInput 缓存 ──

    @Test
    fun `initial lastRimeInput is null`() {
        val bridge = createBridge()
        assertNull(bridge.getLastRimeInput())
    }

    @Test
    fun `setLastRimeInput stores and getLastRimeInput returns`() {
        val bridge = createBridge()
        bridge.setLastRimeInput("54482")
        assertEquals("54482", bridge.getLastRimeInput())
    }

    @Test
    fun `setLastRimeInput null clears cache`() {
        val bridge = createBridge()
        bridge.setLastRimeInput("54482")
        bridge.setLastRimeInput(null)
        assertNull(bridge.getLastRimeInput())
    }

    // ── forceSendToRime ──

    @Test
    fun `forceSendToRime clears lastRimeInput`() {
        val bridge = createBridge()
        bridge.setLastRimeInput("54482")
        bridge.forceSendToRime()
        assertNull(bridge.getLastRimeInput())
    }

    // ── replaceFullPinyin ──

    @Test
    fun `replaceFullPinyin invokes callback`() {
        var received: String? = null
        val bridge = createBridge(onReplaceFullPinyin = { received = it })
        bridge.replaceFullPinyin("54482")
        assertEquals("54482", received)
    }

    // ── queryRimeComposition ──

    @Test
    fun `queryRimeComposition returns null when callback is null`() {
        val bridge = createBridge(onQueryRimeComposition = null)
        assertNull(bridge.queryRimeComposition())
    }

    @Test
    fun `queryRimeComposition invokes callback`() {
        val composition = RimeComposition(
            input = "54482",
            preedit = "li hua",
            committedText = "",
            candidates = arrayOf(RimeCandidate("梨花", "li hua")),
            hasNextPage = false,
            hasPrevPage = false,
            isAsciiMode = false,
        )
        val bridge = createBridge(onQueryRimeComposition = { composition })
        assertEquals(composition, bridge.queryRimeComposition())
    }

    // ── undoRightCommit ──

    @Test
    fun `undoRightCommit invokes callback with count`() {
        var receivedCount: Int? = null
        val bridge = createBridge(onRightCommitUndone = { receivedCount = it })
        bridge.undoRightCommit(3)
        assertEquals(3, receivedCount)
    }

    @Test
    fun `undoRightCommit default count is 1`() {
        var receivedCount: Int? = null
        val bridge = createBridge(onRightCommitUndone = { receivedCount = it })
        bridge.undoRightCommit()
        assertEquals(1, receivedCount)
    }

    @Test
    fun `undoRightCommit does not throw when callback is null`() {
        val bridge = createBridge(onRightCommitUndone = null)
        bridge.undoRightCommit(1) // should not throw
    }

    // ── clearRimeAndResend ──

    @Test
    fun `clearRimeAndResend invokes undo and clears input`() {
        var undoCount: Int? = null
        var replaceInput: String? = null
        val bridge = createBridge(
            onRightCommitUndone = { undoCount = it },
            onReplaceFullPinyin = { replaceInput = it },
        )
        bridge.setLastRimeInput("54482")
        bridge.clearRimeAndResend()
        assertEquals(1, undoCount)
        assertEquals("", replaceInput)
        assertNull(bridge.getLastRimeInput())
    }

    // ── inferFirstSyllableFromRime ──

    @Test
    fun `inferFirstSyllableFromRime returns null when composition is null`() {
        val bridge = createBridge(onQueryRimeComposition = null)
        // Falls back to firstSyllableOptions; with empty digits, returns null
        assertNull(bridge.inferFirstSyllableFromRime(""))
    }

    @Test
    fun `inferFirstSyllableFromRime uses RIME comment to infer syllable`() {
        val composition = RimeComposition(
            input = "54482",
            preedit = "li hua",
            committedText = "",
            candidates = arrayOf(RimeCandidate("梨花", "li hua")),
            hasNextPage = false,
            hasPrevPage = false,
            isAsciiMode = false,
        )
        val bridge = createBridge(onQueryRimeComposition = { composition })
        val result = bridge.inferFirstSyllableFromRime("54482")
        assertNotNull(result)
        assertEquals("li", result!!.pinyin)
        assertEquals(2, result.digitLength)
    }

    @Test
    fun `inferFirstSyllableFromRime falls back when comment does not match digits`() {
        val composition = RimeComposition(
            input = "54482",
            preedit = "li hua",
            committedText = "",
            candidates = arrayOf(RimeCandidate("梨花", "jia hua")), // comment starts with "jia"
            hasNextPage = false,
            hasPrevPage = false,
            isAsciiMode = false,
        )
        val bridge = createBridge(onQueryRimeComposition = { composition })
        // "jia" → digit code "542", doesn't start with "54482", so falls back
        val result = bridge.inferFirstSyllableFromRime("54482")
        // Falls back to local firstSyllableOptions("54482")
        assertNotNull(result)
    }
}